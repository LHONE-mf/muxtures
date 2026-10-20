package com.slurrycalc.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.slurrycalc.engine.BatchMath
import com.slurrycalc.engine.JournalEntry
import com.slurrycalc.engine.JournalStore
import com.slurrycalc.engine.MaterialEntry
import com.slurrycalc.engine.MaterialRole
import com.slurrycalc.engine.MaterialsDb
import com.slurrycalc.engine.MixingRegimes
import com.slurrycalc.engine.SlurryEngine
import com.slurrycalc.engine.SlurryResult
import com.slurrycalc.engine.Solvent
import com.slurrycalc.engine.SolidComponent
import com.slurrycalc.engine.Solvents
import com.slurrycalc.engine.ViscosityModel
import kotlin.math.abs

class SlurryViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = SlurryEngine()
    val materialsDb = MaterialsDb(app)
    val journalStore = JournalStore(app)

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

    /** выбранный режим смешивания (id из MixingRegimes.ALL) */
    var mixingRegimeId by mutableStateOf("planetary_std"); private set

    var result by mutableStateOf<SlurryResult?>(null); private set
    var error by mutableStateOf(""); private set

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
        val list = if (cathode) com.slurrycalc.engine.Materials.typicalCathode(activeName, activeDensity)
                   else com.slurrycalc.engine.Materials.typicalAnode()
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
    fun setMixingRegime(id: String) { mixingRegimeId = id }

    fun recalculate() {
        error = ""
        val input = com.slurrycalc.engine.SlurryInput(
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
            mixingRegimeId = mixingRegimeId,
            mixingDurationMin = null,
            mixingNotes = "",
        )
        val saved = journalStore.add(e)
        journal.add(0, saved)
        return saved.id
    }

    /** Обновить запись журнала (факт/результаты). */
    fun updateJournalEntry(e: JournalEntry) {
        journalStore.update(e)
        val idx = journal.indexOfFirst { it.id == e.id }
        if (idx >= 0) journal[idx] = e
    }

    fun deleteJournalEntry(id: Long) {
        journalStore.remove(id)
        journal.removeAll { it.id == id }
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
        mixingRegimeId = e.mixingRegimeId.ifBlank { mixingRegimeId }
        recalculate()
    }

    fun actualFor(e: JournalEntry): BatchMath.ActualBatch? = e.computeActual()

    fun regimeName(id: String): String = MixingRegimes.byId(id)?.name ?: id

    private fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
