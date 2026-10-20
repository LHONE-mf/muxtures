package com.slurrycalc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.slurrycalc.engine.Materials
import com.slurrycalc.engine.SlurryEngine
import com.slurrycalc.engine.SlurryResult
import com.slurrycalc.engine.Solvent
import com.slurrycalc.engine.SolidComponent
import com.slurrycalc.engine.Solvents
import com.slurrycalc.engine.ViscosityModel
import kotlin.math.abs

class SlurryViewModel : ViewModel() {

    private val engine = SlurryEngine()

    val solids = mutableStateListOf<SolidComponent>().apply { addAll(Materials.typicalCathode()) }

    var solventName by mutableStateOf("NMP (25 °C)")
        private set
    var solventDensity by mutableDoubleStateOf(1.028)
        private set
    var solventViscosity by mutableDoubleStateOf(1.87)
        private set

    /** Сухой остаток, % */
    var dryContentPct by mutableDoubleStateOf(50.0)
        private set

    /** 0 — масса суспензии; 1 — масса сухого вещества; 2 — объём партии */
    var batchMode by mutableIntStateOf(0)
        private set

    /** Значение партии: г (режимы 0,1) или мл (режим 2) */
    var batchValue by mutableDoubleStateOf(2000.0)
        private set

    var viscosityModelIndex by mutableIntStateOf(ViscosityModel.MO.ordinal)
        private set

    var result by mutableStateOf<SlurryResult?>(null)
        private set

    var error by mutableStateOf("")
        private set

    init {
        recalculate()
    }

    fun addComponent() {
        solids.add(SolidComponent("Новый компонент", 3.0, 0.0))
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
            name = name ?: c.name,
            density = density ?: c.density,
            fraction = (fractionPct?.let { it / 100.0 } ?: c.fraction),
        )
        recalculate()
    }

    fun loadPreset(cathode: Boolean, activeName: String = "NMC 622", activeDensity: Double = 4.67) {
        solids.clear()
        solids.addAll(if (cathode) Materials.typicalCathode(activeName, activeDensity) else Materials.typicalAnode())
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

    fun setDryContent(pct: Double) {
        dryContentPct = pct.coerceIn(1.0, 99.0)
        recalculate()
    }

    fun setBatchMode(mode: Int) {
        batchMode = mode
        recalculate()
    }

    fun setBatchValue(v: Double) {
        batchValue = v.coerceAtLeast(0.0)
        recalculate()
    }

    fun setViscosityModel(index: Int) {
        viscosityModelIndex = index.coerceIn(0, ViscosityModel.values().size - 1)
        recalculate()
    }

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

    val fractionSumPct: Double
        get() = solids.sumOf { it.fraction } * 100.0

    val fractionsOk: Boolean
        get() = abs(fractionSumPct - 100.0) < 0.5
}
