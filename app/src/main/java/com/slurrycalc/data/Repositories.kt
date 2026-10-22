package com.slurrycalc.data

import android.content.Context
import com.slurrycalc.domain.CustomRegime
import com.slurrycalc.domain.JournalEntry
import com.slurrycalc.domain.MaterialEntry
import com.slurrycalc.domain.MaterialRole
import com.slurrycalc.domain.Capability
import com.slurrycalc.domain.MixToolType
import com.slurrycalc.domain.RolePermissions

/**
 * Репозитории поверх Room. API намеренно совпадает со старыми SharedPreferences-хранилищами
 * (MaterialsDb / JournalStore / RegimeStore) — единицы domain (г, мПа·с, г/см³), перевод внутрь
 * БД (мг, Па·с, кг/м³) делает Mappers. Все методы синхронные: вызываются с фоновой нити
 * (DataInitializer) либо в allowMainThread для простоты текущего UI.
 */

class JournalRepository(private val db: AppDatabase) {

    fun all(): List<JournalEntry> = db.journalDao().all().map(Mappers::fromRow)

    /** Добавить запись. Требует права CREATE_BATCH (этап 1f: проверка в data-слое). */
    fun add(e: JournalEntry, actor: AuthRepository.User? = null): JournalEntry? {
        Guard.check(db, actor, Capability.CREATE_BATCH).takeIf { !it.allowed }?.let { return null }
        val row = Mappers.toRow(e).copy(
            id = 0L,
            ts = if (e.timestampMs > 0) e.timestampMs else System.currentTimeMillis(),
            immutable = true,   // этап 1f: записи журнала не удаляются
        )
        val id = db.journalDao().insert(row)
        Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
            "BATCH_CREATE", "замес «${e.title}», id=$id")
        return e.copy(id = id, timestampMs = row.ts)
    }

    /**
     * Обновление фактических данных/результатов РАЗРЕШЕНО (запись остаётся в журнале,
     * изменения аудятся старое→новое). Смена вердикта — подпись ОТК, требует SIGN_OFF.
     * Возвращает false, если право отсутствует (отказ уже записан в аудит).
     */
    fun update(e: JournalEntry, actor: AuthRepository.User? = null, reason: String = ""): Boolean {
        // A4 (SPEC раздел 10 шаг A.6): причина изменения обязательна — замена физическому
        // запрету правок. Пустая/пробельная причина = отказ, событие пишется в аудит.
        if (reason.isBlank()) {
            Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
                "UPDATE_DENIED", "id=${e.id}: попытка изменения без указания причины")
            return false
        }
        val old = db.journalDao().byId(e.id)
        val verdictChanged = old != null && old.verdict != e.verdict
        val cap = if (verdictChanged) Capability.SIGN_OFF else Capability.EDIT_ACTUALS
        if (!Guard.check(db, actor, cap).allowed) return false
        val newRow = Mappers.toRow(e).copy(immutable = true)
        db.journalDao().update(newRow)
        val diff = buildString {
            if (old != null) {
                if (old.verdict != newRow.verdict) append("вердикт: ${old.verdict} → ${newRow.verdict}; ")
                if (old.actualMassesJson != newRow.actualMassesJson) append("факт.массы изменены; ")
                if (old.viscPas != newRow.viscPas) append("вязкость изменена; ")
                if (old.measDensityKgm3 != newRow.measDensityKgm3) append("плотность измерена/изменена; ")
                if (old.comment != newRow.comment) append("комментарий изменён; ")
            }
            if (reason.isNotBlank()) append("причина: $reason")
        }.ifEmpty { "обновление записи id=${e.id}" }
        Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
            "BATCH_UPDATE", "id=${e.id}: $diff")
        return true
    }

    /**
     * Этап 1f: физическое удаление записей журнала ЗАПРЕЩЕНО (SPEC п.1.6).
     * Метод сохранён для совместимости API, всегда возвращает false и пишет событие.
     * Исправление ошибки — новая запись с причиной.
     */
    fun remove(id: Long, actor: AuthRepository.User? = null): Boolean {
        Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
            "DELETE_BLOCKED", "попытка удаления записи id=$id — журнал неизменяем")
        return false
    }

    fun byId(id: Long): JournalEntry? = db.journalDao().byId(id)?.let(Mappers::fromRow)
    fun isImmutable(id: Long): Boolean = db.journalDao().isImmutable(id) > 0
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
            moisturePpm: Int? = null, note: String = "",
            actor: AuthRepository.User? = null): MaterialEntry? {
        // этап 1f: право проверяется в data-слое (не только скрытием кнопок)
        if (!Guard.check(db, actor, Capability.MANAGE_MATERIALS).allowed) return null
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
        Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
            "MATERIAL_ADD", "${e.name}, ${e.role}, ρ=${e.density}")
        return e
    }

    fun remove(id: Long, actor: AuthRepository.User? = null): Boolean {
        if (!Guard.check(db, actor, Capability.MANAGE_MATERIALS).allowed) return false
        val ok = db.materialDao().deleteCustom(id) > 0
        if (ok) Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
            "MATERIAL_DELETE", "id=$id")
        return ok
    }
}

class RegimeRepository(private val db: AppDatabase) {

    private var seq = 0L

    fun customList(): List<CustomRegime> = db.regimeDao().all().map(Mappers::fromRow)

    /** свои + встроенные имена (как старый RegimeStore.allNames) */
    fun allNames(): List<String> =
        customList().map { it.name } + com.slurrycalc.domain.BuiltinRegimes.ALL.values.map { it.name }

    fun add(name: String, tool: MixToolType, dispr: Int?, blader: Int?, timeMin: Double?,
            tempLimit: Double?, description: String,
            actor: AuthRepository.User? = null): CustomRegime? {
        if (!Guard.check(db, actor, Capability.MANAGE_REGIMES).allowed) return null
        val r = CustomRegime(
            id = "regime_${System.currentTimeMillis()}_${seq++}",
            name = name.trim().ifEmpty { "Мой режим" },
            tool = tool, disperserRpmHint = dispr, bladeRpmHint = blader,
            timeMinHint = timeMin, tempLimitC = tempLimit, description = description.trim(),
        )
        db.regimeDao().insert(Mappers.toRow(r))
        Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
            "REGIME_ADD", r.name)
        return r
    }

    fun remove(id: String, actor: AuthRepository.User? = null): Boolean {
        if (!Guard.check(db, actor, Capability.MANAGE_REGIMES).allowed) return false
        val ok = db.regimeDao().delete(id) > 0
        if (ok) Audit.record(db, actor?.let { "${it.name} (${it.role.label})" } ?: "система",
            "REGIME_DELETE", id)
        return ok
    }

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
    lateinit var auth: AuthRepository
        private set

    /** последняя вошедшая в приложение учётная запись (null — выбор пользователя не сделан) */
    @Volatile
    var currentUser: AuthRepository.User? = null
        private set

    fun setCurrentUser(user: AuthRepository.User?) { currentUser = user }

    /** Проверка права текущей роли; при отказе — событие DENIED в аудите */
    fun guard(cap: Capability): com.slurrycalc.domain.PermissionCheck {
        val database = db
        return if (database == null) com.slurrycalc.domain.PermissionCheck(false, "БД не готова")
        else Guard.check(database, currentUser, cap)
    }

    /** Запись события аудита от имени текущего пользователя */
    fun audit(action: String, details: String) {
        val database = db ?: return
        Audit.record(database, currentUser?.let { "${it.name} (${it.role.label})" } ?: "система", action, details)
    }

    fun auditRecent(limit: Int = 200): List<AuditRow> =
        db?.let { Audit.recent(it, limit) } ?: emptyList()

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
            auth = AuthRepository(database)
            Audit.record(database, "system", "APP_START", "приложение запущено, БД v${database.openHelper.readableDatabase.version}")
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
        // этап 1f: удаление legacy-данных — только роль с правом PURGE_LEGACY (администратор)
        if (!Guard.check(database, currentUser, Capability.PURGE_LEGACY).allowed) return null
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
        auth = AuthRepository(database)
    }
}
