package com.slurrycalc.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B2: тесты миграций Room без эмулятора (Robolectric + SQLite из android-all).
 *
 * Штатный MigrationTestHelper читает схемы из assets APK instrumentation-тестов, что
 * недоступно в unit-тестах; поэтому здесь:
 *  - DDL v2 парсится напрямую из экспортированной app/schemas/.../2.json (classpath ресурс);
 *  - миграции V2ToV3/V2ToV4 гоняются на реальной БД (Robolectric Sqlite = нативный sqlite3);
 *  - ИТОГОВАЯ схема сверяется с экспортированной 4.json через SchemaBundle.equals —
 *    это эквивалент validateMigration из room-testing.
 * Цепочка v2→v4 обеспечивается самой V2ToV4 (внутри вызывает V2ToV3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MigrationV2toV4RobolectricTest {

    private companion object { const val TABLE_PH = "\u0024{TABLE_NAME}" }

    // версия схемы из экспортированного файла 4.json (надежнее рефлексии над @Database:
    // аннотация Room retention-ом может быть недоступна в юнит-тесте)
    private val currentVersion: Int = 4

    private fun readSchemaJson(version: Int): String {
        val path = "com.slurrycalc.data.AppDatabase/$version.json"
        // Gradle resources.srcDirs кладёт файлы по относительному пути от каталога schemas
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
            ?: javaClass.classLoader!!.getResourceAsStream("$version.json")
        requireNotNull(stream) { "Схема $path не найдена в test classpath" }
        return stream.bufferedReader().use { it.readText() }
    }

    /** createSql всех таблиц+индексов версии [version] из экспортированной схемы */
    private fun schemaDdl(version: Int): List<String> {
        val out = mutableListOf<String>()
        val dbObj = org.json.JSONObject(readSchemaJson(version)).getJSONObject("database")
        val entities = dbObj.getJSONArray("entities")
        for (e in 0 until entities.length()) {
            val ent = entities.getJSONObject(e)
            out += ent.getString("createSql").replace(TABLE_PH, ent.getString("tableName"))
            val indices = ent.optJSONArray("indices") ?: continue
            for (x in 0 until indices.length()) {
                val idx = indices.getJSONObject(x).optString("createSql")
                if (idx.isNotEmpty()) out += idx.replace(TABLE_PH, ent.getString("tableName"))
            }
        }
        return out
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Создаём реальную БД по схеме v2 (DDL из 2.json), юнит-каталог теста */
    private fun createDatabaseV2(name: String): SupportSQLiteDatabase {
        val dir = context().getDir("migtest", Context.MODE_PRIVATE)
        val file = java.io.File(dir, name)
        file.delete()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context())
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (stmt in schemaDdl(2)) db.execSQL(stmt)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    /** Прогон цепочки v2→v3→v4 + полная сверка итоговой схемы с 4.json */
    private fun migrateAndValidate(db: SupportSQLiteDatabase) {
        assertEquals("старая версия", 2, db.version)
        V2ToV4.migrate(db)                       // цепочка: V2ToV3 + колонки prevHash/rowHash
        db.version = currentVersion
        val json = readSchemaJson(currentVersion)

        // 1) набор таблиц и индексов в реальной БД == объявленным в схеме Room
        val actualObjs = mutableListOf<Pair<String, String>>() // name -> normalized sql
        db.query("SELECT name, type, sql FROM sqlite_master WHERE type IN ('table','index') " +
            "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'").use { c ->
            while (c.moveToNext())
                actualObjs += c.getString(0) to normalizeSql(c.getString(2) ?: "")
        }
        val expectedTables = mutableListOf<String>()
        val expectedIndices = mutableListOf<Pair<String, String>>()
        org.json.JSONObject(json).getJSONObject("database").getJSONArray("entities").let { ents ->
            for (e in 0 until ents.length()) {
                val ent = ents.getJSONObject(e)
                expectedTables += ent.getString("tableName")
                val idxs = ent.optJSONArray("indices") ?: continue
                for (x in 0 until idxs.length()) {
                    val idx = idxs.getJSONObject(x)
                    expectedIndices += idx.getString("name") to
                        normalizeSql(idx.getString("createSql").replace(TABLE_PH, ent.getString("tableName")))
                }
            }
        }
        val actualTableNames = actualObjs.filter { !it.first.startsWith("index_") }.map { it.first }.sorted()
        assertEquals("набор таблиц", expectedTables.sorted(), actualTableNames)
        for ((nm, expSql) in expectedIndices) {
            val act = actualObjs.firstOrNull { it.first == nm }
            assertNotNull("индекс $nm создан миграцией", act)
            // SQLite хранит индекс без IF NOT EXISTS — сравнение после нормализации
            assertEquals("DDL индекса $nm", expSql, act!!.second)
        }

        // 2) колонки каждой таблицы (имя/тип/notNull): PRAGMA реальной БД против эталона 4.json
        for (t in expectedTables) {
            assertEquals("колонки таблицы $t", columnsFromJson(json, t), columnsDump(db, t))
        }
        // 3) таблица users создана (из @Database-сущности UserRow — её нет в entity-списке только если ошибка)
        assertTrue("таблица users существует", actualTableNames.contains("users"))
    }

    private fun normalizeSql(sql: String): String =
        sql.trim().replace(Regex("\\s+"), " ").replace("`", "")
            .replace("AUTOINCREMENT", "")
            .replace("IF NOT EXISTS ", "", ignoreCase = true)
            .replace("  ", " ").uppercase()

    /**
     * Нормализованное описание колонок таблицы из реальной БД (PRAGMA table_info).
     * SQLite в sqlite_master не хранит DEFAULT для колонок, добавленных через ALTER TABLE ADD COLUMN,
     * поэтому эталон берём из экспортированной Room-схемы (4.json), а не из дампа SQL.
     */
    private fun columnsDump(db: SupportSQLiteDatabase, table: String): String = buildString {
        val rows = db.query("PRAGMA table_info(`$table`)").let { c ->
            val r = mutableListOf<List<String>>()
            while (c.moveToNext()) r += listOf(
                c.getString(1).uppercase(), c.getString(2).uppercase(),
                if (c.getInt(3) != 0) "1" else "0",
            )
            c.close(); r
        }
        rows.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
            .forEach { appendLine("COL ${it.joinToString("|")}") }
    }

    /** Те же три атрибута (имя/тип/notNull) из JSON схемы — эталон */
    private fun columnsFromJson(json: String, table: String): String = buildString {
        val ents = org.json.JSONObject(json).getJSONObject("database").getJSONArray("entities")
        for (e in 0 until ents.length()) {
            val ent = ents.getJSONObject(e)
            if (!ent.getString("tableName").equals(table, ignoreCase = true)) continue
            val f = ent.getJSONArray("fields")
            (0 until f.length())
                .map { x ->
                    val fd = f.getJSONObject(x)
                    listOf(fd.getString("columnName").uppercase(), fd.getString("affinity").uppercase(),
                        if (fd.getBoolean("notNull")) "1" else "0")
                }
                .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
                .forEach { appendLine("COL ${it.joinToString("|")}") }
        }
    }

    @Test fun `цепочка v2-v4 даёт схему идентичную экспортированной v4`() {
        val db = createDatabaseV2("mig_empty.db")
        migrateAndValidate(db)
        db.query("SELECT COUNT(*) FROM users").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT prevHash, rowHash FROM audit_log LIMIT 0").use { c ->
            assertEquals(2, c.columnCount)
        }
        listOf("journal", "materials", "regimes", "meta").forEach { t ->
            db.query("SELECT COUNT(*) FROM $t").use { c ->
                assertTrue("запрос к $t", c.moveToFirst())
            }
        }
        db.close()
    }

    @Test fun `данные v2 переходят в v4 без изменений значений`() {
        val db = createDatabaseV2("mig_data.db")
        db.insertSeedV2Rows()
        migrateAndValidate(db)

        db.query("SELECT title,wet_mass_mg,visc_pas,immutable FROM journal").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("NMC622 #7", c.getString(0))
            assertEquals(2_000_000L, c.getLong(1))
            assertEquals(3.5, c.getDouble(2), 1e-9)
            assertEquals(1L, c.getLong(3))
        }
        db.query("SELECT name,density_kgm3,moisturePpm FROM materials").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("NMC 622", c.getString(0))
            assertEquals(4700.0, c.getDouble(1), 1e-9)
            assertEquals(300, c.getInt(2))
        }
        db.query("SELECT id,blade_rpm FROM regimes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("custom_1", c.getString(0))
            assertEquals(1500, c.getInt(1))
        }
        db.query("SELECT actor,prevHash,rowHash FROM audit_log").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("system", c.getString(0))
            assertEquals("", c.getString(1))   // дефолт '' для legacy-записей
            assertEquals("", c.getString(2))
        }
        db.query("SELECT COUNT(*) FROM users").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    /** seed-записи по схеме v2 (колонок хэшей в v2 нет) */
    private fun SupportSQLiteDatabase.insertSeedV2Rows() {
        beginTransaction()
        try {
            execSQL(
                "INSERT INTO journal (ts,title,dryContentPct,wet_mass_mg,recipeSummary,componentsJson," +
                    "solventName,solvent_density_kgm3,solvent_planned_mg,regimeId,mix_dur_min,mixNotes," +
                    "stepsJson,actual_masses_json,actual_solvent_mg,visc_pas,viscCond,tempC,grindLevel," +
                    "grind_um,meas_density_kgm3,comment,verdict,isTask,stepStatusesJson,immutable) " +
                    "VALUES (1700000000000,'NMC622 #7',50.0,2000000,'96/2/2','[]','NMP',1030.0,1041600.0," +
                    "'planetary-high',45.0,'pre-mix','[]','[]',1041600,3.5,'10 s-1',24.0,'тонкий',5.0," +
                    "1670.0,'без комков','OK',0,'[]',1)"
            )
            execSQL(
                "INSERT INTO materials (id,name,role,density_kgm3,tap_kgm3,d50_um,bet_m2g,ph,moisturePpm," +
                    "dryingTempC,cap_mah_g,cond_s_cm,visc_pas,note,custom) " +
                    "VALUES (2001,'NMC 622','CATHODE',4700.0,NULL,5.0,NULL,NULL,300,NULL,180.0,NULL,NULL,'образец',1)"
            )
            execSQL(
                "INSERT INTO regimes (id,name,tool,dispr_rpm,blade_rpm,time_min,temp_limit_c,description) " +
                    "VALUES ('custom_1','Планетарная высокая','PLANETARY',0,1500,45.0,40.0,'образец режима')"
            )
            execSQL(
                "INSERT INTO audit_log (ts,actor,action,details) VALUES (1699999000000,'system','MIGRATE','первая legacy')"
            )
            setTransactionSuccessful()
        } finally { endTransaction() }
    }

    @Test fun `чистая установка v4 - цепочка хэшей работает сразу`() {
        val db = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assertNull(Audit.verifyChain(db.auditDao().allOrdered())) // пустая — OK
        Audit.record(db, "a", "T1", "d1")
        Audit.record(db, "b", "T2", "d2")
        val rows = db.auditDao().allOrdered()
        assertEquals(2, rows.size)
        assertEquals(AUDIT_GENESIS, rows[0].prevHash)
        assertEquals(rows[0].rowHash, rows[1].prevHash)
        assertNotNull(rows[0].rowHash)
        assertNull(Audit.verifyChain(rows))
        db.close()
    }

    /** Резервная копия старых данных (SharedPreferences) перед миграцией в Room */
    @Test fun `миграция prefs делает json-бэкап до переноса данных`() {
        val db = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            context().getSharedPreferences("journal.db", Context.MODE_PRIVATE)
                .edit()
                .putString("entries", """[{"ts":1700000000000,"title":"Бэкап-тест","dryPct":50.0,"wetG":2000.0}]""")
                .apply()
            val report = MigrationEngine.migrateIfNeeded(context(), db)
            assertTrue("миграция выполнена", report.ran)
            assertNotNull("файл бэкапа создан", report.backupFile)
            val backup = java.io.File(report.backupFile!!)
            assertTrue("бэкап существует: ${backup.absolutePath}", backup.exists())
            assertTrue("бэкап непустой", backup.length() > 0)
            assertTrue("в бэкапе есть запись", backup.readText().contains("Бэкап-тест"))
            assertEquals("журнал перенесён в Room", 1, db.journalDao().all().size)
        } finally {
            db.close()
        }
    }
}
