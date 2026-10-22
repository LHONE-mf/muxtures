package com.slurrycalc.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

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

/** Событие аудита (таблица заполняется на этапе 1f) */
@Entity(tableName = "audit_log")
data class AuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ts: Long,
    val actor: String,                  // роль: master / batcher / system
    val action: String,                 // LOGIN, BATCH_CREATE, BATCH_UPDATE, MIGRATION ...
    val details: String,
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

    @Query("DELETE FROM journal WHERE id = :id")
    fun deleteById(id: Long): Int
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

@Dao
interface AuditDao {
    @androidx.room.Insert
    fun insert(row: AuditRow)

    @Query("SELECT * FROM audit_log ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): List<AuditRow>
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE key = :key")
    fun get(key: String): String?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun put(row: MetaRow)
}

// ============================ Database ============================

abstract class AppDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
    abstract fun materialDao(): MaterialDao
    abstract fun regimeDao(): RegimeDao
    abstract fun auditDao(): AuditDao
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
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
