package com.slurrycalc.data

import android.content.Context
import com.slurrycalc.domain.CustomRegime
import com.slurrycalc.domain.JournalEntry
import com.slurrycalc.domain.MaterialEntry
import com.slurrycalc.domain.MaterialRole
import com.slurrycalc.domain.MixToolType

/**
 * Репозитории поверх Room. API намеренно совпадает со старыми SharedPreferences-хранилищами
 * (MaterialsDb / JournalStore / RegimeStore) — единицы domain (г, мПа·с, г/см³), перевод внутрь
 * БД (мг, Па·с, кг/м³) делает Mappers. Все методы синхронные: вызываются с фоновой нити
 * (DataInitializer) либо в allowMainThread для простоты текущего UI.
 */

class JournalRepository(private val db: AppDatabase) {

    fun all(): List<JournalEntry> = db.journalDao().all().map(Mappers::fromRow)

    /** добавить запись; id/ts проставляются здесь */
    fun add(e: JournalEntry): JournalEntry {
        val row = Mappers.toRow(e).copy(
            id = 0L,
            ts = if (e.timestampMs > 0) e.timestampMs else System.currentTimeMillis(),
        )
        val id = db.journalDao().insert(row)
        return e.copy(id = id, timestampMs = row.ts)
    }

    fun update(e: JournalEntry) = db.journalDao().update(Mappers.toRow(e))

    /**
     * Удаление записи журнала. До этапа 1f доступно всем; флаг immutable уже пишется
     * (миграция помечает импортированные записи) — правила запрета подключим в 1f.
     */
    fun remove(id: Long): Boolean = db.journalDao().deleteById(id) > 0

    fun byId(id: Long): JournalEntry? = db.journalDao().byId(id)?.let(Mappers::fromRow)
}

class MaterialsRepository(private val db: AppDatabase) {

    private var nextId = 1_000_000L

    init {
        val maxExisting = db.materialDao().all().maxOfOrNull { it.id } ?: 0L
        if (maxExisting >= nextId) nextId = maxExisting + 1
    }

    /** встроенный каталог (domain.Catalog) + пользовательские из Room */
    fun all(): List<MaterialEntry> {
        val customs = db.materialDao().all().filter { it.custom }.map(Mappers::fromRow)
        return customs + com.slurrycalc.domain.Catalog.DEFAULTS
    }

    fun byRole(role: MaterialRole): List<MaterialEntry> =
        all().filter { it.role == role }.sortedBy { it.name.lowercase() }

    fun add(name: String, role: MaterialRole, density: Double, extras: Map<String, Double?> = emptyMap(),
            moisturePpm: Int? = null, note: String = ""): MaterialEntry {
        val e = MaterialEntry(
            id = nextId++,
            name = name.trim().ifEmpty { "Материал" },
            role = role,
            density = density.coerceIn(0.01, 20.0),
            tapDensity = extras["tap"],
            particleSizeD50Um = extras["d50"],
            betAreaM2g = extras["bet"],
            ph = extras["ph"],
            moisturePpm = moisturePpm,
            dryingTempC = extras["dryT"],
            capacityMahG = extras["cap"],
            conductivityScm = extras["cond"],
            viscosityMPas = extras["visc"],
            note = note.trim(),
            custom = true,
        )
        db.materialDao().insert(Mappers.toRow(e))
        return e
    }

    fun remove(id: Long): Boolean = db.materialDao().deleteCustom(id) > 0
}

class RegimeRepository(private val db: AppDatabase) {

    private var seq = 0L

    fun customList(): List<CustomRegime> = db.regimeDao().all().map(Mappers::fromRow)

    /** свои + встроенные имена (как старый RegimeStore.allNames) */
    fun allNames(): List<String> =
        customList().map { it.name } + com.slurrycalc.domain.BuiltinRegimes.ALL.values.map { it.name }

    fun add(name: String, tool: MixToolType, dispr: Int?, blader: Int?, timeMin: Double?,
            tempLimit: Double?, description: String): CustomRegime {
        val r = CustomRegime(
            id = "regime_${System.currentTimeMillis()}_${seq++}",
            name = name.trim().ifEmpty { "Мой режим" },
            tool = tool, disperserRpmHint = dispr, bladeRpmHint = blader,
            timeMinHint = timeMin, tempLimitC = tempLimit, description = description.trim(),
        )
        db.regimeDao().insert(Mappers.toRow(r))
        return r
    }

    fun remove(id: String): Boolean = db.regimeDao().delete(id) > 0

    fun byName(name: String): CustomRegime? = customList().firstOrNull { it.name == name }
}

/**
 * Инициализация данных при старте приложения (в фоновой нити):
 *  1. открыть Room;
 *  2. миграция SharedPreferences → Room + backup (MigrationEngine);
 *  3. отдать репозитории ViewModel.
 */
object DataHolder {

    @Volatile
    private var db: AppDatabase? = null

    lateinit var journal: JournalRepository
        private set
    lateinit var materials: MaterialsRepository
        private set
    lateinit var regimes: RegimeRepository
        private set

    /** результат последней миграции (для отладки/экрана «О программе») */
    var lastMigrationReport: MigrationEngine.Report? = null
        private set

    /** был ли хотя бы раз выполнен перенос из prefs в Room (в т.ч. раньше) */
    fun wasMigrated(db: AppDatabase): Boolean =
        db.metaDao().get(MigrationEngine.FLAG_MIGRATED) != null

    fun init(context: Context) {
        if (db != null) return
        synchronized(this) {
            if (db != null) return
            val database = AppDatabase.get(context)
            // миграция выполняется ДО выдачи репозиториев
            lastMigrationReport = MigrationEngine.migrateIfNeeded(context, database)
            db = database
            journal = JournalRepository(database)
            materials = MaterialsRepository(database)
            regimes = RegimeRepository(database)
        }
    }

    /**
     * Явное удаление старых SharedPreferences после подтверждения пользователя
     * (этап 1e). Разрешено только если миграция уже выполнялась; иначе данные
     * prefs ещё не перенесены и удалять их нельзя.
     */
    fun purgeLegacyPrefs(context: Context): MigrationEngine.PurgeReport? {
        val database = db ?: return null
        if (!wasMigrated(database)) return null
        val report = MigrationEngine.purgeLegacyPrefs(context)
        try {
            database.metaDao().put(
                MetaRow("prefs_purged_at", "${System.currentTimeMillis()}"),
            )
        } catch (_: Exception) { }
        return report
    }

    fun ready(): Boolean = db != null

    /** только для тестов: подменить базу */
    internal fun useDatabaseForTest(database: AppDatabase) {
        db = database
        journal = JournalRepository(database)
        materials = MaterialsRepository(database)
        regimes = RegimeRepository(database)
    }
}
