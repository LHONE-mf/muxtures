package com.slurrycalc.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.slurrycalc.data.AuthRepository
import com.slurrycalc.data.AuditRow
import com.slurrycalc.data.DataHolder
import com.slurrycalc.domain.Capability
import com.slurrycalc.domain.PermissionCheck
import com.slurrycalc.domain.UserRole
import com.slurrycalc.domain.BatchMath
import com.slurrycalc.domain.BuiltinRegimes
import com.slurrycalc.domain.JournalEntry
import com.slurrycalc.domain.MaterialEntry
import com.slurrycalc.domain.MaterialRole
import com.slurrycalc.domain.MixToolType
import com.slurrycalc.domain.MixingSequences
import com.slurrycalc.domain.MixingStep
import com.slurrycalc.domain.SlurryEngine
import com.slurrycalc.domain.SlurryResult
import com.slurrycalc.domain.Solvent
import com.slurrycalc.domain.SolidComponent
import com.slurrycalc.domain.Solvents
import com.slurrycalc.domain.ViscosityModel
import kotlin.math.abs

/** Внешность приложения: тема и размер шрифта (настройки пользователя) */
enum class AppThemeMode(val label: String) { SYSTEM("Системная"), LIGHT("Светлая"), DARK("Тёмная") }

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var themeMode: AppThemeMode
        get() = AppThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: AppThemeMode.SYSTEM
        set(v) = prefs.edit().putString("theme", v.name).apply()
    /** множитель размера шрифта 0.8–1.6 */
    var fontScale: Float
        get() = prefs.getFloat("fontScale", 1.0f)
        set(v) = prefs.edit().putFloat("fontScale", v.coerceIn(0.8f, 1.6f)).apply()
}

class SlurryViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = SlurryEngine()
    // этап 1e: хранилища переведены на Room. Инициализация выполняется фоновой нитью
    // приложения (SlurryApp -> DataHolder.init). Здесь — синхронная страховка только для
    // instrumented-тестов, где VM может быть создана без инициализации Application.
    // В UI доступ к репозиториям происходит после StartupGate (см. MainActivity).
    init { if (!DataHolder.ready()) DataHolder.init(app) }
    val materialsDb get() = DataHolder.materials
    val journalStore get() = DataHolder.journal
    val regimeStore get() = DataHolder.regimes
    val settings = Settings(app)

    // ---------- этап 1f: пользователи / роли / аудит ----------
    val auth get() = DataHolder.auth
    var currentUser by mutableStateOf<AuthRepository.User?>(DataHolder.currentUser)
        private set
    /** сообщение об отказе права (показывается в UI и исчезает после действия) */
    var permissionMessage by mutableStateOf<String?>(null)
        private set

    fun can(cap: Capability): Boolean {
        val check = DataHolder.guard(cap)
        if (!check.allowed) permissionMessage = check.reason
        return check.allowed
    }

    fun clearPermissionMessage() { permissionMessage = null }

    fun addUser(name: String, pin: String, role: UserRole): Result<AuthRepository.User> =
        auth.addUser(name, pin, role).also { r ->
            if (r.isSuccess && currentUser == null) {
                // первый пользователь сразу становится текущим — иначе некому управлять
                val u = r.getOrNull()
                if (u != null) { currentUser = u; DataHolder.setCurrentUser(u) }
            }
        }

    fun changePin(userId: Long, oldPin: String, newPin: String): Result<Unit> =
        auth.changePin(userId, oldPin, newPin)

    /** Сброс PIN пользователю администратором (панель управления) */
    fun resetPin(adminId: Long, userId: Long, newPin: String): Result<Unit> =
        auth.resetPinByAdmin(adminId, userId, newPin)

    fun deactivateUser(adminId: Long, userId: Long): Result<Unit> =
        auth.deactivateUser(adminId, userId)

    fun login(name: String, pin: String): AuthRepository.LoginResult {
        val res = auth.login(name, pin)
        if (res is AuthRepository.LoginResult.Success) {
            currentUser = res.user
            DataHolder.setCurrentUser(res.user)
        }
        return res
    }

    fun logout() {
        currentUser?.let { auth.logout(it) }
        currentUser = null
        DataHolder.setCurrentUser(null)
    }

    fun auditLog(limit: Int = 200): List<AuditRow> = DataHolder.auditRecent(limit)

    // ---------- состав ----------
    val solids = mutableStateListOf<SolidComponent>()

    var solventName by mutableStateOf(Solvents.NMP.name); private set
    var solventDensity by mutableDoubleStateOf(Solvents.NMP.density); private set
    var solventViscosity by mutableDoubleStateOf(Solvents.NMP.viscosityDynamic); private set

    /** Сухой остаток, % */
    var dryContentPct by mutableDoubleStateOf(50.0); private set

    /** 0 — масса суспензии; 1 — масса сухого; 2 — объём */
    var batchMode by mutableIntStateOf(0); private set
    var batchValue by mutableDoubleStateOf(2000.0); private set

    var viscosityModelIndex by mutableIntStateOf(ViscosityModel.MO.ordinal); private set

    /** выбранный основной режим смешивания (имя из встроенных или своих) */
    var mixingRegimeName by mutableStateOf(BuiltinRegimes.ALL["planetary_std"]!!.name); private set

    /** пошаговая карта замеса (редактируется в калькуляторе и в журнале) */
    val steps = mutableStateListOf<MixingStep>()
    private var stepId = 1L

    var result by mutableStateOf<SlurryResult?>(null); private set
    var error by mutableStateOf(""); private set

    /** Упрощённый режим («исполнитель»): калькулятор скрыт, только задания и внесение данных */
    var simpleMode by mutableStateOf(false)
        private set

    fun toggleSimpleMode(v: Boolean) { simpleMode = v }

    val journal = mutableStateListOf<JournalEntry>().apply { addAll(journalStore.all()) }

    init {
        loadPreset(cathode = true)
    }

    // ---------- компоненты ----------

    fun addComponent() {
        solids.add(SolidComponent("Новый компонент", 3.0, 0.0))
        recalculate()
    }

    /** добавить компонент, выбранный из базы материалов */
    fun addFromCatalog(entry: MaterialEntry) {
        if (entry.role == MaterialRole.SOLVENT) {
            setSolvent(Solvent(entry.name, entry.density, entry.viscosityMPas ?: 1.0))
            return
        }
        if (solids.any { it.name == entry.name }) return
        solids.add(SolidComponent(entry.name, entry.density, 0.0, entry.id, entry.role))
        recalculate()
    }

    fun removeComponent(index: Int) {
        if (index in solids.indices) {
            solids.removeAt(index)
            recalculate()
        }
    }

    fun updateComponent(index: Int, name: String? = null, density: Double? = null, fractionPct: Double? = null) {
        if (index !in solids.indices) return
        val c = solids[index]
        solids[index] = c.copy(
            name = name?.ifBlank { c.name } ?: c.name,
            density = density ?: c.density,
            fraction = (fractionPct?.let { it / 100.0 } ?: c.fraction),
        )
        recalculate()
    }

    /** переназначить материал строке состава (из выпадающего списка) */
    fun assignMaterial(index: Int, entry: MaterialEntry) {
        if (index !in solids.indices) return
        solids[index] = solids[index].copy(
            name = entry.name,
            density = entry.density,
            materialId = entry.id,
            role = entry.role,
        )
        if (entry.role == MaterialRole.SOLVENT) setSolvent(Solvent(entry.name, entry.density, entry.viscosityMPas ?: 1.0))
        recalculate()
    }

    fun loadPreset(cathode: Boolean, activeName: String = "NMC 622", activeDensity: Double = 4.67) {
        solids.clear()
        val list = if (cathode) com.slurrycalc.domain.Materials.typicalCathode(activeName, activeDensity)
                   else com.slurrycalc.domain.Materials.typicalAnode()
        // подвязываем id из каталога, где совпадает имя
        solids.addAll(list.map { c ->
            val m = materialsDb.all().firstOrNull { it.name == c.name }
            c.copy(materialId = m?.id, role = m?.role)
        })
        setSolvent(if (cathode) Solvents.NMP else Solvents.WATER)
        dryContentPct = if (cathode) 50.0 else 45.0
        recalculate()
    }

    fun setSolvent(s: Solvent) {
        solventName = s.name
        solventDensity = s.density
        solventViscosity = s.viscosityDynamic
        recalculate()
    }

    fun setDryContent(pct: Double) { dryContentPct = pct.coerceIn(1.0, 99.0); recalculate() }
    fun selectBatchMode(mode: Int) { batchMode = mode; recalculate() }
    fun updateBatchValue(v: Double) { batchValue = v.coerceAtLeast(0.0); recalculate() }
    fun setViscosityModel(index: Int) {
        viscosityModelIndex = index.coerceIn(0, ViscosityModel.values().size - 1)
        recalculate()
    }
    fun setMixingRegime(name: String) { mixingRegimeName = name }

    // ---------- этапы смешивания ----------

    private fun newStepId(): Long = System.currentTimeMillis() * 1000 + (stepId++)

    fun addStep(step: MixingStep = MixingStep(id = 0, action = "Новый этап")) {
        steps.add(step.copy(id = newStepId()))
    }

    fun removeStep(index: Int) { if (index in steps.indices) steps.removeAt(index) }

    fun moveStep(index: Int, delta: Int) {
        val to = index + delta
        if (index in steps.indices && to in steps.indices) {
            val s = steps.removeAt(index)
            steps.add(to, s)
        }
    }

    fun updateStep(index: Int, patch: (MixingStep) -> MixingStep) {
        if (index in steps.indices) steps[index] = patch(steps[index])
    }

    /** загрузить типовую последовательность этапов под текущий состав/растворитель */
    fun loadSequenceTemplate() {
        val cathode = solventName.contains("NMP", true)
        val list = MixingSequences.template(cathode, solventName)
        steps.clear()
        list.forEach { steps.add(it.copy(id = newStepId())) }
    }

    /** быстрый пресет: подставить параметры выбранного режима в этап */
    fun applyRegimeToStep(stepIndex: Int, regimeName: String) {
        if (stepIndex !in steps.indices) return
        val custom = regimeStore.byName(regimeName)
        val builtin = BuiltinRegimes.info(regimeName)
        steps[stepIndex] = steps[stepIndex].copy(
            tool = custom?.tool ?: builtin?.tool ?: steps[stepIndex].tool,
            disperserRpm = custom?.disperserRpmHint ?: steps[stepIndex].disperserRpm,
            bladeRpm = custom?.bladeRpmHint ?: steps[stepIndex].bladeRpm,
            durationMin = custom?.timeMinHint ?: steps[stepIndex].durationMin,
            tempC = custom?.tempLimitC ?: steps[stepIndex].tempC,
            vacuum = (custom?.tool ?: builtin?.tool) == MixToolType.VACUUM || steps[stepIndex].vacuum,
        )
    }

    /** Добавить режим смешивания вручную (право MANAGE_REGIMES проверяется в data-слое) */
    fun addCustomRegime(
        name: String, tool: MixToolType, dispr: Int?, blader: Int?,
        timeMin: Double?, tempLimit: Double?, description: String,
    ): Boolean =
        regimeStore.add(name, tool, dispr, blader, timeMin, tempLimit, description, currentUser) != null

    fun saveStepAsRegime(step: MixingStep): Boolean {
        val name = step.action.ifBlank { "Режим ${regimeStore.customList().size + 1}" }
        return regimeStore.add(
            name = name, tool = step.tool,
            dispr = step.disperserRpm, blader = step.bladeRpm,
            timeMin = step.durationMin, tempLimit = step.tempC,
            description = step.comment, actor = currentUser,
        ) != null
    }

    fun recalculate() {
        error = ""
        val input = com.slurrycalc.domain.SlurryInput(
            solids = solids.toList(),
            solvent = Solvent(solventName, solventDensity, solventViscosity),
            dryContentMassFraction = dryContentPct / 100.0,
            totalWetMassG = if (batchMode == 0) batchValue else 0.0,
            totalDryMassG = if (batchMode == 1) batchValue else 0.0,
            targetVolumeMl = if (batchMode == 2) batchValue else 0.0,
            viscosityModel = ViscosityModel.values()[viscosityModelIndex],
        )
        engine.calculate(input)
            .onSuccess { result = it }
            .onFailure {
                result = null
                error = it.message ?: "Ошибка расчёта"
            }
    }

    val fractionSumPct: Double get() = solids.sumOf { it.fraction } * 100.0
    val fractionsOk: Boolean get() = abs(fractionSumPct - 100.0) < 0.5

    // ---------- журнал ----------

    /** Сохранить текущий РАСЧЁТ как план замеса в журнале. Возвращает id записи. */
    fun savePlanToJournal(title: String): Long? {
        val r = result ?: return null
        val recipe = solids.joinToString(" / ") { "${it.name} ${fmt1(it.fraction * 100)}" } +
                " @ $solventName, СО ${fmt1(dryContentPct)} %"
        val stepsTime = steps.sumOf { it.durationMin ?: 0.0 }.takeIf { it > 0 }
        val e = JournalEntry(
            id = 0,
            timestampMs = System.currentTimeMillis(),
            title = title.ifBlank { "Замес ${journal.size + 1}" },
            plannedDryContentPct = dryContentPct,
            plannedWetMassG = r.wetMassG,
            recipeSummary = recipe,
            componentNames = solids.map { it.name },
            componentDensities = solids.map { it.density },
            plannedComponentMassesG = solids.map { r.componentMassesG[it.name] ?: 0.0 },
            plannedSolventName = solventName,
            plannedSolventDensity = solventDensity,
            plannedSolventMassG = r.solventMassG,
            mixingRegimeId = mixingRegimeName,
            mixingDurationMin = stepsTime,
            mixingNotes = "",
            steps = steps.toList(),
        )
        if (!can(Capability.CREATE_BATCH)) return null
        val saved = journalStore.add(e, currentUser) ?: return null
        journal.add(0, saved)
        return saved.id
    }

    /**
     * Обновить запись журнала (факт/результаты). Права EDIT_ACTUALS/SIGN_OFF проверяются
     * в data-слое (Repositories.JournalRepository.update); при отказе причина — в permissionMessage.
     */
    fun updateJournalEntry(e: JournalEntry, reason: String = ""): Boolean {
        // предварительная проверка для сообщения в UI; реальная защита — в data-слое
        val old = journal.firstOrNull { it.id == e.id }
        val verdictChanged = old != null && old.verdict != e.verdict
        if (!can(if (verdictChanged) Capability.SIGN_OFF else Capability.EDIT_ACTUALS)) return false
        if (!journalStore.update(e, currentUser, reason)) return false
        val idx = journal.indexOfFirst { it.id == e.id }
        if (idx >= 0) journal[idx] = e
        return true
    }

    /**
     * Этап 1f: удаление записей журнала запрещено (SPEC п.1.6 «ничего не удаляется
     * физически»). Метод всегда возвращает false; исправление — новая запись с причиной.
     */
    fun deleteJournalEntry(id: Long): Boolean {
        permissionMessage = "Журнал неизменяем: записи не удаляются. Исправление — новая запись с причиной."
        DataHolder.audit("DELETE_BLOCKED", "UI-попытка удаления id=$id")
        return false
    }

    /** загрузить план из журнала обратно в калькулятор */
    fun loadFromJournal(e: JournalEntry) {
        solids.clear()
        e.componentNames.forEachIndexed { i, n ->
            solids.add(
                SolidComponent(
                    name = n,
                    density = e.componentDensities.getOrElse(i) { 3.0 },
                    fraction = (e.plannedComponentMassesG.getOrElse(i) { 0.0 })
                        .let { if (e.plannedWetMassG > 0 && e.plannedDryContentPct > 0)
                            it / (e.plannedWetMassG * e.plannedDryContentPct / 100.0) else 0.0 } * 100.0 / 100.0,
                )
            )
        }
        setSolvent(Solvent(e.plannedSolventName, e.plannedSolventDensity, solventViscosity))
        dryContentPct = e.plannedDryContentPct
        batchMode = 0
        batchValue = e.plannedWetMassG
        if (e.mixingRegimeId.isNotBlank()) mixingRegimeName = e.mixingRegimeId
        steps.clear()
        e.steps.forEach { steps.add(it.copy(id = newStepId())) }
        recalculate()
    }

    fun actualFor(e: JournalEntry): BatchMath.ActualBatch? = e.computeActual()

    /** Принять входящее задание: добавить запись (isTask=true) в начало журнала */
    fun importTask(e: JournalEntry): Boolean {
        if (!can(Capability.CREATE_BATCH)) return false
        val saved = journalStore.add(e.copy(id = 0, isTask = true), currentUser) ?: return false
        journal.add(0, saved)
        return true
    }

    /** Отметить статус этапа i в записи журнала («выполнено»/«отклонение»/«пропущено», повторное нажатие снимает) */
    fun toggleStepStatus(e: JournalEntry, index: Int, key: String) {
        val list = ArrayList(e.stepStatuses)
        while (list.size <= index) list.add("")
        list[index] = if (list[index] == key) "" else key
        updateJournalEntry(e.copy(stepStatuses = list))
    }

    fun regimeName(nameOrId: String): String =
        BuiltinRegimes.info(nameOrId)?.name ?: nameOrId

    /** список режимов для выбора: свои + встроенные */
    fun regimeNames(): List<String> = regimeStore.allNames()

    fun setThemeMode(mode: AppThemeMode) { settings.themeMode = mode }
    fun setFontScale(scale: Float) { settings.fontScale = scale }

    private fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
