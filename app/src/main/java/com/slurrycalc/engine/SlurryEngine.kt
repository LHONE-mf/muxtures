package com.slurrycalc.engine

/**
 * Компонент твёрдой фазы суспензии (активная масса, проводник, связующее в сухом виде и т.п.)
 * @param name   название компонента
 * @param density истинская плотность, г/см³
 * @param fraction массовая доля компонента в СУХОМ веществе (0..1)
 */
data class SolidComponent(
    val name: String,
    val density: Double,
    val fraction: Double,
)

/**
 * Растворитель / дисперсионная среда (NMP, вода и т.д.)
 * @param viscosityDynamic динамическая вязкость чистого растворителя, мПа·с (сПз) при заданной T
 */
data class Solvent(
    val name: String,
    val density: Double,          // г/см³
    val viscosityDynamic: Double, // мПа·с
)

/** Пресеты растворителей */
object Solvents {
    val NMP = Solvent("NMP (25 °C)", density = 1.028, viscosityDynamic = 1.87)
    val WATER = Solvent("Вода (25 °C)", density = 0.997, viscosityDynamic = 0.89)

    val all = listOf(NMP, WATER)
}

/** Пресеты типичных компонентов твёрдой фазы (плотности, г/см³) */
object Materials {
    data class Preset(val name: String, val density: Double)

    val cathodePresets = listOf(
        Preset("LCO", 5.0),
        Preset("NMC 532", 4.76),
        Preset("NMC 622", 4.67),
        Preset("NMC 811", 4.45),
        Preset("LFP", 3.6),
        Preset("LMO", 4.28),
        Preset("NCA", 4.37),
        Preset("PVDF (сухое)", 1.77),
        Preset("Super-P / сажа", 1.95),
        Preset("Графит", 2.26),
        Preset("Al2O3 покрытие", 3.95),
    )

    val anodePresets = listOf(
        Preset("Графит", 2.26),
        Preset("Si (нанокремний)", 2.33),
        Preset("Li4Ti5O12", 3.5),
        Preset("CMC", 1.6),
        Preset("SBR (сухое)", 1.05),
        Preset("Super-P / сажа", 1.95),
        Preset("Al2O3 покрытие", 3.95),
    )

    /** Типовой катодный состав: 96 % активная масса, 2 % Super-P, 2 % PVDF */
    fun typicalCathode(activeName: String = "NMC 622", activeDensity: Double = 4.67) = listOf(
        SolidComponent(activeName, activeDensity, 0.96),
        SolidComponent("Super-P", 1.95, 0.02),
        SolidComponent("PVDF", 1.77, 0.02),
    )

    /** Типовой анодный состав: 95 % графит, 2 % Super-P, 1.5 % CMC, 1.5 % SBR */
    fun typicalAnode() = listOf(
        SolidComponent("Графит", 2.26, 0.95),
        SolidComponent("Super-P", 1.95, 0.02),
        SolidComponent("CMC", 1.6, 0.015),
        SolidComponent("SBR", 1.05, 0.015),
    )
}

enum class ViscosityModel { NONE, MARON_PIERIS, MO, KRIEGER_DOUGHERTY }

/**
 * Входные данные расчёта партии суспензии.
 *
 * Задается либо общая масса мокрой суспензии (totalWetMassG), либо масса сухого вещества
 * (totalDryMassG), либо объём партии (targetVolumeMl). Что заполнено (> 0), то и используется.
 */
data class SlurryInput(
    val solids: List<SolidComponent>,
    val solvent: Solvent,
    /** целевая массовая доля сухого вещества в суспензии, 0..1 */
    val dryContentMassFraction: Double,
    val totalWetMassG: Double = 0.0,
    val totalDryMassG: Double = 0.0,
    val targetVolumeMl: Double = 0.0,
    val viscosityModel: ViscosityModel = ViscosityModel.MO,
    /** параметр Марона–Пириса k (обычно 2.5 для сферических частиц) */
    val maronK: Double = 2.5,
)

/** Результаты расчёта партии */
data class SlurryResult(
    val dryMassG: Double,
    val solventMassG: Double,
    val wetMassG: Double,
    val componentMassesG: Map<String, Double>,
    val dryDensityGcm3: Double,      // истинская плотность сухого вещества
    val slurryDensityGcm3: Double,   // плотность готовой суспензии
    val volumeMl: Double,            // объём партии
    val solidVolumeFraction: Double, // φ — объёмная доля твёрдой фазы
    val relativeViscosity: Double?,  // η_rel = η/η_р-ля
    val viscosityEstimateMPas: Double?, // оценка вязкости, мПа·с
    val warnings: List<String>,
)

class SlurryEngine {

    /** Нормализует доли сухих компонентов; возвращает предупреждение если сумма != ~1 */
    fun normalizedSolids(solids: List<SolidComponent>): Pair<List<SolidComponent>, String?> {
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

    fun calculate(input: SlurryInput): Result<SlurryResult> {
        val warnings = mutableListOf<String>()
        val (solids, normWarning) = normalizedSolids(input.solids)
        normWarning?.let { warnings.add(it) }

        if (solids.isEmpty()) return Result.failure(IllegalArgumentException("Нет компонентов твёрдой фазы"))
        if (input.dryContentMassFraction <= 0 || input.dryContentMassFraction >= 1)
            return Result.failure(IllegalArgumentException("Сухой остаток должен быть между 0 и 100 %"))

        // Истинская плотность сухого вещества (правило обратных долей)
        val dryDensity = 1.0 / solids.sumOf { it.fraction / it.density }

        // Определяем массы по тому, что задал пользователь
        var dryMass = input.totalDryMassG
        var wetMass = input.totalWetMassG

        val solventMassFraction = 1.0 - input.dryContentMassFraction

        if (wetMass > 0) {
            dryMass = wetMass * input.dryContentMassFraction
        } else if (dryMass > 0) {
            wetMass = dryMass / input.dryContentMassFraction
        } else if (input.targetVolumeMl > 0) {
            // V = wet * (f_dc/ρ_true + (1-f_dc)/ρ_solv) → wet = V / (...)
            val specificVol = input.dryContentMassFraction / dryDensity +
                    solventMassFraction / input.solvent.density
            wetMass = input.targetVolumeMl / specificVol
            dryMass = wetMass * input.dryContentMassFraction
        } else {
            return Result.failure(IllegalArgumentException("Укажите массу суспензии, массу сухого вещества или объём партии"))
        }

        val solventMass = wetMass - dryMass

        // Массы отдельных компонентов
        val componentMasses = solids.associate { it.name to dryMass * it.fraction }

        // Объёмная доля твёрдой фазы
        val solidVolume = dryMass / dryDensity
        val solventVolume = solventMass / input.solvent.density
        val totalVolume = solidVolume + solventVolume
        val phi = solidVolume / totalVolume
        val slurryDensity = wetMass / totalVolume

        // Оценка вязкости
        val phiMax = 0.74
        val (relEta, modelWarn) = when (input.viscosityModel) {
            ViscosityModel.NONE -> null to null
            ViscosityModel.MARON_PIERIS ->
                if (phi >= 1.0 / input.maronK) null to "φ слишком велик для модели Марона–Пириса"
                else Math.pow(1.0 - input.maronK * phi, -2.0) to null
            ViscosityModel.KRIEGER_DOUGHERTY ->
                if (phi >= phiMax) null to "φ ≥ φmax (0.74) — модель Кригера–Догерти неприменима"
                else Math.pow(1.0 - phi / phiMax, -input.maronK) to null
            ViscosityModel.MO ->
                if (phi >= phiMax) null to "φ ≥ φmax (0.74) — модель Муни неприменима"
                else Math.exp(2.5 * phi / (1.0 - phi / phiMax)) to null
        }
        modelWarn?.let { warnings.add(it) }

        val eta = relEta?.let { input.solvent.viscosityDynamic * it }

        if (phi > 0.65) warnings.add(
            "Высокая объёмная доля твёрдой фазы (φ = " +
                String.format(java.util.Locale.US, "%.0f", phi * 100) +
                " %) — суспензия может быть трудноразливаемой"
        )

        return Result.success(
            SlurryResult(
                dryMassG = dryMass,
                solventMassG = solventMass,
                wetMassG = wetMass,
                componentMassesG = componentMasses,
                dryDensityGcm3 = dryDensity,
                slurryDensityGcm3 = slurryDensity,
                volumeMl = totalVolume,
                solidVolumeFraction = phi,
                relativeViscosity = relEta,
                viscosityEstimateMPas = eta,
                warnings = warnings,
            )
        )
    }
}
