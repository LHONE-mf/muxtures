package com.slurrycalc.domain

import com.slurrycalc.calc.BatchInput
import com.slurrycalc.calc.BatchResult
import com.slurrycalc.calc.DryComponent
import com.slurrycalc.calc.Medium
import com.slurrycalc.calc.SlurryCalc
import com.slurrycalc.calc.Units
// ViscosityModel calc-слоя используется по полному имени: в domain есть UI-enum с тем же именем

/**
 * Граница UI↔calc (правило единиц этапа 1d): принимает единицы интерфейса
 * (г, г/см³, мПа·с) и модели отображения, конвертирует в mg-единицы calc-слоя
 * (мг Long, кг/м³, Па·с), вызывает [SlurryCalc] и возвращает результат в UI-единицах.
 *
 * Модели данных UI-слоя (SolidComponent, Solvent, SlurryInput, SlurryResult,
 * ViscosityModel, Solvents, Materials) — в файле SlurryModels.kt этого же пакета.
 */

class SlurryEngine {

    /** Маппинг UI-модели вязкости на модель calc-слоя */
    private fun toCalcModel(m: ViscosityModel): com.slurrycalc.calc.ViscosityModel = when (m) {
        ViscosityModel.NONE -> com.slurrycalc.calc.ViscosityModel.NONE
        ViscosityModel.MO -> com.slurrycalc.calc.ViscosityModel.MO
        ViscosityModel.MARON_PIERIS -> com.slurrycalc.calc.ViscosityModel.MARON_PIERIS
        ViscosityModel.KRIEGER_DOUGHERTY -> com.slurrycalc.calc.ViscosityModel.KRIEGER_DOUGHERTY
    }

    fun normalizedSolids(solids: List<SolidComponent>): Pair<List<SolidComponent>, String?> {
        val (norm, warning) = SlurryCalc.normalizedSolids(
            solids.map { DryComponent(it.name, Units.gcm3ToKgM3(it.density), it.fraction) }
        )
        return norm.map { SolidComponent(it.name, Units.kgm3ToGcm3(it.densityKgM3), it.fraction) } to warning
    }

    fun calculate(input: SlurryInput): Result<SlurryResult> {
        val calcInput = BatchInput(
            solids = input.solids.map { DryComponent(it.name, Units.gcm3ToKgM3(it.density), it.fraction) },
            solvent = Medium(input.solvent.name, Units.gcm3ToKgM3(input.solvent.density),
                             Units.mPasToPas(input.solvent.viscosityDynamic)),
            dryContentMassFraction = input.dryContentMassFraction,
            totalWetMassMg = Units.gToMg(input.totalWetMassG),
            totalDryMassMg = Units.gToMg(input.totalDryMassG),
            targetVolumeMl = input.targetVolumeMl,
            viscosityModel = toCalcModel(input.viscosityModel),
            maronK = input.maronK,
        )
        return SlurryCalc.calculate(calcInput).map { r -> toUi(r) }
    }

    /** Перевод результата из mg/Pa·s/кг·м⁻³ единиц calc обратно в UI-единицы — только здесь */
    private fun toUi(r: BatchResult) = SlurryResult(
        dryMassG = Units.mgToG(r.dryMassMg),
        solventMassG = Units.mgToG(r.solventMassMg),
        wetMassG = Units.mgToG(r.wetMassMg),
        componentMassesG = r.componentMassesMg.mapValues { Units.mgToG(it.value) },
        dryDensityGcm3 = Units.kgm3ToGcm3(r.dryDensityKgM3),
        slurryDensityGcm3 = Units.kgm3ToGcm3(r.slurryDensityKgM3),
        volumeMl = r.volumeMl,
        solidVolumeFraction = r.solidVolumeFraction,
        relativeViscosity = r.relativeViscosity,
        viscosityEstimateMPas = r.viscosityEstimatePas?.let { Units.pasToMPas(it) },
        warnings = r.warnings,
    )
}
