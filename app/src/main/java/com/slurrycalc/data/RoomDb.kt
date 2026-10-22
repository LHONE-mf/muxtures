package com.slurrycalc.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room-база (этап 1e, SPEC п.0.5 «правило единиц»):
 *  - массы хранятся в **мг (Long)**, вязкость — в **Па·с (Double)**, плотность — в **кг/м³ (Double)**;
 *  - перевод г ↔ мг и мПа·с ↔ Па·с выполняется ТОЛЬКО на границе UI (data/Mappers).
 *
 * Старые SharedPreferences (journal.db / materials.db / regimes.db) НЕ удаляются:
 * при первом открытии БД выполняется автоматическая миграция + резервная копия JSON
 * (см. MigrationEngine и LegacyBackup).
 */

// ============================ Entity ============================

@Entity(tableName = "journal")
data class JournalRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ts: Long,                       // метка времени создания, мс
    val title: String,
    // ---- план ----
    val dryContentPct: Double,          // % (безразмерная величина, единицы не конвертируем)
    @ColumnInfo(name = "wet_mass_mg") val wetMassMg: Long,
    val recipeSummary: String,
    /** сериализованный массив [{name, density_kgm3, planned_mg}] */
    val componentsJson: String,
    val solventName: String,
    @ColumnInfo(name = "solvent_density_kgm3") val solventDensityKgm3: Double,
    @ColumnInfo(name = "solvent_planned_mg") val solventPlannedMg: Long,
    // ---- смешивание ----
    val regimeId: String,
    @ColumnInfo(name = "mix_dur_min") val mixDurMin: Double?,
    val mixNotes: String,
    /** сериализованный массив этапов MixingStep */
    val stepsJson: String,
    // ---- факт (мг) ----
    @ColumnInfo(name = "actual_masses_json") val actualMassesJson: String,
    @ColumnInfo(name = "actual_solvent_mg") val actualSolventMg: Long?,
    // ---- результаты ----
    @ColumnInfo(name = "visc_pas") val viscPas: Double?,        // Па·с!
    val viscCond: String,
    val tempC: Double?,
    val grindLevel: String,
    @ColumnInfo(name = "grind_um") val grindUm: Double?,
    @ColumnInfo(name = "meas_density_kgm3") val measDensityKgm3: Double?,
    val comment: String,
    val verdict: String,
    val isTask: Boolean,
    val stepStatusesJson: String,
    // ---- 1f: защита журнала ----
    /** запись создана миграцией/исполнителем; удаление запрещено для batcher-роли */
    val immutable: Boolean = false,
)

@Entity(tableName = "materials", indices = [Index("name")])
data class MaterialRow(
    @PrimaryKey val id: Long,           // >= 2000 — импортированные из prefs; >= 1_000_000 — новые
    val name: String,
    val role: String,                   // имя MaterialRole
    @ColumnInfo(name = "density_kgm3") val densityKgm3: Double,
    @ColumnInfo(name = "tap_kgm3") val tapDensityKgm3: Double?,
    @ColumnInfo(name = "d50_um") val d50Um: Double?,
    @ColumnInfo(name = "bet_m2g") val betM2g: Double?,
    val ph: Double?,
    val moisturePpm: Int?,
    val dryingTempC: Double?,
    @ColumnInfo(name = "cap_mah_g") val capacityMahG: Double?,
    @ColumnInfo(name = "cond_s_cm") val conductivityScm: Double?,
    @ColumnInfo(name = "visc_pas") val viscosityPas: Double?,     // Па·с!
    val note: String,
    val custom: Boolean,
)

@Entity(tableName = "regimes", indices = [Index("name")])
data class RegimeRow(
    @PrimaryKey val id: String,         // legacy "custom_N" или новый "regime_<ts>"
    val name: String,
    val tool: String,                   // имя MixToolType
    @ColumnInfo(name = "dispr_rpm") val disperserRpmHint: Int?,
    @ColumnInfo(name = "blade_rpm") val bladeRpmHint: Int?,
    @ColumnInfo(name = "time_min") val timeMinHint: Double?,
    @ColumnInfo(name = "temp_limit_c") val tempLimitC: Double?,
    val description: String,
)

/** Событие аудита (этап 1f). Append-only: DELETE/UPDATE в DAO отсутствуют намеренно. */
@Entity(tableName = "audit_log", indices = [Index("ts")])
data class AuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ts: Long,
    val actor: String,                  // имя пользователя + роль; system — служебные события
    val action: String,                 // LOGIN, LOGOUT, BATCH_CREATE, BATCH_UPDATE, MATERIAL_ADD, USER_ADD, DENIED ...
    val details: String,
)

/** Пользователь с PIN (этап 1f). Хэш PBKDF2+соль, см. calc.PinHash */
@Entity(tableName = "users", indices = [Index("name")])
data class UserRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val role: String,                   // имя UserRole
    val pinHash: String,                // "pbkdf2$iter$salt$hash"
    val createdAt: Long,
    val lastLoginAt: Long? = null,
    val failedAttempts: Int = 0,        // защита от перебора PIN
    val lockedUntil: Long? = null,      // мс эпохи; до этого момента вход запрещён
    val active: Boolean = true,         // деактивированный профиль не удаляется (аудит!)
)

/** Служебный флаг: миграция выполнена */
@Entity(tableName = "meta")
data class MetaRow(
    @PrimaryKey val key: String,
    val value: String,
)

// ============================ DAO ============================

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal ORDER BY ts DESC")
    fun all(): List<JournalRow>

    @Query("SELECT * FROM journal WHERE id = :id")
    fun byId(id: Long): JournalRow?

    @Query("INSERT INTO journal (ts, title, dryContentPct, wet_mass_mg, recipeSummary, componentsJson, solventName, solvent_density_kgm3, solvent_planned_mg, regimeId, mix_dur_min, mixNotes, stepsJson, actual_masses_json, actual_solvent_mg, visc_pas, viscCond, tempC, grindLevel, grind_um, meas_density_kgm3, comment, verdict, isTask, stepStatusesJson, immutable) VALUES (:ts, :title, :dryContentPct, :wetMassMg, :recipeSummary, :componentsJson, :solventName, :solventDensityKgm3, :solventPlannedMg, :regimeId, :mixDurMin, :mixNotes, :stepsJson, :actualMassesJson, :actualSolventMg, :viscPas, :viscCond, :tempC, :grindLevel, :grindUm, :measDensityKgm3, :comment, :verdict, :isTask, :stepStatusesJson, :immutable)")
    fun insertRaw(
        ts: Long, title: String, dryContentPct: Double, wetMassMg: Long, recipeSummary: String,
        componentsJson: String, solventName: String, solventDensityKgm3: Double, solventPlannedMg: Long,
        regimeId: String, mixDurMin: Double?, mixNotes: String, stepsJson: String,
        actualMassesJson: String, actualSolventMg: Long?, viscPas: Double?, viscCond: String,
        tempC: Double?, grindLevel: String, grindUm: Double?, measDensityKgm3: Double?,
        comment: String, verdict: String, isTask: Boolean, stepStatusesJson: String, immutable: Boolean,
    ): Long

    @androidx.room.Insert
    fun insert(row: JournalRow): Long

    @androidx.room.Update
    fun update(row: JournalRow)

    /**
     * Физическое удаление записей журнала ЗАПРЕЩЕНО (SPEC п.1.6, этап 1f).
     * Разрешено только удаление mutable-черновиков (immutable = 0), созданных
     * текущей сессией расчёта. Импортированные и подписанные записи не удаляются:
     * исправление = новая запись с причиной.
     */
    @Query("DELETE FROM journal WHERE id = :id AND immutable = 0")
    fun deleteMutableById(id: Long): Int

    /** Пометить запись неизменяемой (при сохранении замеса/задания) */
    @Query("UPDATE journal SET immutable = 1 WHERE id = :id")
    fun markImmutable(id: Long): Int

    @Query("SELECT COUNT(*) FROM journal WHERE id = :id AND immutable = 1")
    fun isImmutable(id: Long): Int
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials ORDER BY name COLLATE NOCASE")
    fun all(): List<MaterialRow>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun insert(row: MaterialRow)

    @Query("DELETE FROM materials WHERE id = :id AND custom = 1")
    fun deleteCustom(id: Long): Int
}

@Dao
interface RegimeDao {
    @Query("SELECT * FROM regimes ORDER BY rowid DESC")
    fun all(): List<RegimeRow>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun insert(row: RegimeRow)

    @Query("DELETE FROM regimes WHERE id = :id")
    fun delete(id: String): Int
}

/**
 * DAO аудита. ПРИНЦИПИАЛЬНО append-only: нет @Update/@Delete —
 * удалить или подправить событие невозможно даже из кода приложения (этап 1f).
 */
@Dao
interface AuditDao {
    @androidx.room.Insert
    fun insert(row: AuditRow): Long

    @Query("SELECT * FROM audit_log ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): List<AuditRow>

    @Query("SELECT COUNT(*) FROM audit_log")
    fun count(): Int
}

/** Пользователи (этап 1f) */
@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE active = 1 ORDER BY name COLLATE NOCASE")
    fun activeAll(): List<UserRow>

    @Query("SELECT * FROM users ORDER BY name COLLATE NOCASE")
    fun all(): List<UserRow>

    @Query("SELECT * FROM users WHERE id = :id")
    fun byId(id: Long): UserRow?

    @Query("SELECT COUNT(*) FROM users")
    fun count(): Int

    @androidx.room.Insert
    fun insert(row: UserRow): Long

    @androidx.room.Update
    fun update(row: UserRow)
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE key = :key")
    fun get(key: String): String?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun put(row: MetaRow)

    @Query("DELETE FROM meta WHERE key = :key")
    fun deleteKey(key: String): Int
}

// ============================ Database ============================

/**
 * ВАЖНО: аннотация @Database обязательна — именно она заставляет KSP-процессор
 * Room сгенерировать класс AppDatabase_Impl. Без неё генерация молча не
 * выполняется, и на устройстве при первом открытии БД падает с
 * "Cannot find implementation ... AppDatabase_Impl does not exist".
 */
// ВАЖНО: миграция v2→v3 регистрируется только через Builder.addMigrations(V2ToV3).
// Аннотация @Migration (из androidx.room — она для Room Multiplatform KSP) здесь НЕ применима:
// в Android-Room она не обрабатывается и ломала KSP ([MissingType] 'V2ToV3').
val V2ToV3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // users — новая таблица (этап 1f)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `users` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `role` TEXT NOT NULL, `pinHash` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `lastLoginAt` INTEGER, `failedAttempts` INTEGER NOT NULL, " +
                "`lockedUntil` INTEGER, `active` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_users_name` ON `users` (`name`)")
        // audit_log — индекс по ts (таблица уже существует из v2; колонки совпадают)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_ts` ON `audit_log` (`ts`)")
        // journal.immutable уже есть в v2; на всякий случай приводим к значению по умолчанию
        db.execSQL("UPDATE journal SET immutable = 1 WHERE immutable IS NULL")
    }
}

@Database(
    entities = [JournalRow::class, MaterialRow::class, RegimeRow::class, AuditRow::class, MetaRow::class, UserRow::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
    abstract fun materialDao(): MaterialDao
    abstract fun regimeDao(): RegimeDao
    abstract fun auditDao(): AuditDao
    abstract fun userDao(): UserDao
    abstract fun metaDao(): MetaDao

    companion object {
        const val DB_NAME = "slurrycalc.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, DB_NAME).also { instance = it }
            }

        fun openForTest(context: Context, file: java.io.File): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()

        internal fun build(appContext: Context, name: String): AppDatabase {
            // allowMainThreadQueries — временная мера для синхронного UI-слоя; БД маленькая,
            // все запросы < 1 мс. Инициализация/миграция при этом выполняется на ФОНОВОЙ нити
            // (SlurryApp), главный поток блокируется только короткими чтениями после StartupGate.
            return Room.databaseBuilder(appContext, AppDatabase::class.java, name)
                .allowMainThreadQueries()
                // этап 1f: миграции v2→v3 явные; destructive — только как последний страховочный
                // вариант для несовместимых будущих схем (данные журнала при этом теряются,
                // поэтому до 1f включали fallback — оставляем, но добавляем addMigrations).
                .addMigrations(V2ToV3)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
