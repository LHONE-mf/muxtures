package com.slurrycalc.calc

import kotlin.math.exp
import kotlin.math.pow

/**
 * Расчёт партии суспензии (модель объёмов смешения + оценки вязкости дисперсной фазы).
 * Чистый Kotlin. Единицы calc-слоя: массы — мг (Long), вязкость — Па·с, плотность — кг/м³.
 * (Перенесено из domain/SlurryEngine на этап 1d; перевод г↔мг и мПа·с↔Па·с — на границе UI.)
 */

enum class ViscosityModel { NONE, MO, MARON_PIERIS, KRIEGER_DOUGHERTY }

object ViscosityParams {
    /** Марон–Пирис: ηrel = (1 − kφ)^−2, разумна при kφ < 0.85 */
    const val MARON_K_DEFAULT = 2.5
    /** Кригер–Догерти: ηrel = (1 − φ/φm)^−k, k = 2.5, φm ≈ 0.61–0.74 */
    const val KD_K = 2.5
    const val KD_PHI_MAX = 0.71
    /** Муни: ηrel = exp(2.5·φ/(1 − φ/φm)) */
    const val MO_A = 2.5
    const val MO_PHI_MAX = 0.71
}

data class DryComponent(val name: String, val densityKgM3: Double, val fraction: Double)
data class Medium(val name: String, val densityKgM3: Double, val viscosityPas: Double)

data class BatchInput(
    val solids: List<DryComponent>,
    val solvent: Medium,
    /** целевая массовая доля сухого вещества, 0..1 */
    val dryContentMassFraction: Double,
    /** что задано (> 0), то и используется: масса мокрой суспензии / сухого / объём мл */
    val totalWetMassMg: Long = 0L,
    val totalDryMassMg: Long = 0L,
    val targetVolumeMl: Double = 0.0,
    val viscosityModel: ViscosityModel = ViscosityModel.MO,
    val maronK: Double = ViscosityParams.MARON_K_DEFAULT,
)

data class BatchResult(
    val dryMassMg: Long,
    val solventMassMg: Long,
    val wetMassMg: Long,
    val componentMassesMg: Map<String, Long>,
    /** истинная плотность сухого вещества, кг/м³ */
    val dryDensityKgM3: Double,
    /** плотность готовой суспензии, кг/м³ */
    val slurryDensityKgM3: Double,
    val volumeMl: Double,
    val solidVolumeFraction: Double,
    val relativeViscosity: Double?,
    /** оценка вязкости, Па·с */
    val viscosityEstimatePas: Double?,
    val warnings: List<String>,
)

object SlurryCalc {

    fun normalizedSolids(solids: List<DryComponent>): Pair<List<DryComponent>, String?> {
        val sum = solids.sumOf { it.fraction }
        return if (sum <= 0.0) {
            solids to "Сумма долей сухих компонентов равна нулю"
        } else if (kotlin.math.abs(sum - 1.0) > 0.005) {
            solids.map { it.copy(fraction = it.fraction / sum) } to
                "Сумма долей = " + String.format(java.util.Locale.US, "%.1f", sum * 100) +
                " %, доли нормализованы к 100 %"
        } else {
            solids to null
        }
    }

    fun calculate(input: BatchInput): Result<BatchResult> {
        val warnings = mutableListOf<String>()
        val (solids, normWarning) = normalizedSolids(input.solids)
        normWarning?.let { warnings.add(it) }

        if (solids.isEmpty()) return Result.failure(IllegalArgumentException("Нет компонентов твёрдой фазы"))
        if (input.dryContentMassFraction <= 0 || input.dryContentMassFraction >= 1)
            return Result.failure(IllegalArgumentException("Сухой остаток должен быть между 0 и 100 %"))

        // Истинная плотность сухого вещества (правило обратных долей), кг/м³
        val dryDensity = 1.0 / solids.sumOf { it.fraction / it.densityKgM3 }

        var dryMassMg = input.totalDryMassMg
        var wetMassMg = input.totalWetMassMg
        val solventMassFraction = 1.0 - input.dryContentMassFraction

        when {
            wetMassMg > 0 -> dryMassMg = Math.round(wetMassMg * input.dryContentMassFraction)
            dryMassMg > 0 -> wetMassMg = Math.round(dryMassMg / input.dryContentMassFraction)
            input.targetVolumeMl > 0 -> {
                // удельный объём 1 мг суспензии = f_dc/ρ_true + (1−f_dc)/ρ_solv [мл/мг]
                val specificVol = input.dryContentMassFraction / (dryDensity / 1000.0) +
                        solventMassFraction / (input.solvent.densityKgM3 / 1000.0)
                val wetG = input.targetVolumeMl / specificVol
                wetMassMg = Units.gToMg(wetG)
                dryMassMg = Math.round(wetMassMg * input.dryContentMassFraction)
            }
            else -> return Result.failure(IllegalArgumentException("Укажите массу суспензии, массу сухого вещества или объём партии"))
        }

        val solventMassMg = wetMassMg - dryMassMg

        val componentMasses = solids.associate { it.name to Math.round(dryMassMg * it.fraction) }

        // Объёмная доля твёрдой фазы (кг/м³ → г/мл делением на 1000)
        val solidVolumeMl = dryMassMg / (dryDensity / 1000.0) * 1e-3
        val solventVolumeMl = solventMassMg / (input.solvent.densityKgM3 / 1000.0) * 1e-3
        val totalVolumeMl = solidVolumeMl + solventVolumeMl
        val phi = solidVolumeMl / totalVolumeMl
        val slurryDensity = wetMassMg * 1e-3 / totalVolumeMl * 1000.0 // кг/м³

        // Оценка вязкости (мПа·с внутри расчёта → Па·с в результате)
        val phiMaxMo = ViscosityParams.MO_PHI_MAX
        val phiMaxKd = ViscosityParams.KD_PHI_MAX
        val (relEta, modelWarn) = when (input.viscosityModel) {
            ViscosityModel.NONE -> null to null
            ViscosityModel.MO ->
                if (phi >= phiMaxMo) null to "φ ≥ ${"%.0f".format(phiMaxMo * 100)} % — модель Муни неприменима (запредельная упаковка)"
                else exp(ViscosityParams.MO_A * phi / (1.0 - phi / phiMaxMo)) to null
            ViscosityModel.MARON_PIERIS -> {
                val kPhi = input.maronK * phi
                val w = if (kPhi >= 0.85) "Марон–Пирис за пределами применимости (kφ ≥ 0.85) — оценка занижена/некорректна" else null
                if (kPhi >= 1.0) null to w
                else (1.0 - kPhi).pow(-2.0) to w
            }
            ViscosityModel.KRIEGER_DOUGHERTY ->
                if (phi >= phiMaxKd) null to "φ ≥ ${"%.0f".format(phiMaxKd * 100)} % — модель Кригера–Догерти неприменима"
                else (1.0 - phi / phiMaxKd).pow(-ViscosityParams.KD_K) to null
        }
        modelWarn?.let { warnings.add(it) }

        val etaMPas = relEta?.let { (input.solvent.viscosityPas * 1000.0) * it }
        val etaPas = relEta?.let { input.solvent.viscosityPas * it }
        if (etaMPas != null && etaMPas > 5000) warnings.add(
            "Оценка η ≈ " + String.format(java.util.Locale.US, "%.0f", etaMPas) +
                " мПа·с — выше типовой наноспособности (~1000–5000 мПа·с); снижайте СО или меняйте режим диспергирования"
        )
        if (phi > 0.65) warnings.add(
            "Высокая объёмная доля твёрдой фазы (φ = " +
                String.format(java.util.Locale.US, "%.0f", phi * 100) +
                " %) — суспензия может быть трудноразливаемой"
        )

        return Result.success(BatchResult(
            dryMassMg = dryMassMg,
            solventMassMg = solventMassMg,
            wetMassMg = wetMassMg,
            componentMassesMg = componentMasses,
            dryDensityKgM3 = dryDensity,
            slurryDensityKgM3 = slurryDensity,
            volumeMl = totalVolumeMl,
            solidVolumeFraction = phi,
            relativeViscosity = relEta,
            viscosityEstimatePas = etaPas,
            warnings = warnings,
        ))
    }
}
