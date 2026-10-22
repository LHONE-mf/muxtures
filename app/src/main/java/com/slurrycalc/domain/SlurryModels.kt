package com.slurrycalc.domain

/**
 * Компонент твёрдой фазы суспензии (активная масса, проводник, связующее в сухом виде и т.п.)
 * @param name   название компонента
 * @param density истинная плотность, г/см³
 * @param fraction массовая доля компонента в СУХОМ веществе (0..1)
 * @param materialId ссылка на запись базы материалов (null — ручной ввод)
 * @param role роль материала (для подсказок); null — неизвестно
 */
data class SolidComponent(
    val name: String,
    val density: Double,
    val fraction: Double,
    val materialId: Long? = null,
    val role: MaterialRole? = null,
)

/**
 * Растворитель / дисперсионная среда (NMP, вода и т.д.)
 * Единицы UI-границы: г/см³ и мПа·с (в calc-слое — кг/м³ и Па·с, перевод на границе).
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

/** Модель оценки вязкости для интерфейса (маппится на calc.ViscosityModel в SlurryEngine) */
enum class ViscosityModel { NONE, MO, MARON_PIERIS, KRIEGER_DOUGHERTY }

/** Параметры моделей оценки вязкости (диапазон сдвига ~1–1000 с⁻¹) */
object ViscosityModels {
    const val MARON_K_DEFAULT = 2.5
    const val KD_K = 2.5
    const val KD_PHI_MAX = 0.71
    const val MO_A = 2.5
    const val MO_PHI_MAX = 0.71
}

/**
 * Входные данные расчёта партии суспензии (единицы UI: г, мл, г/см³, мПа·с).
 * Задается либо масса мокрой суспензии, либо масса сухого вещества, либо объём партии.
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
    val maronK: Double = ViscosityModels.MARON_K_DEFAULT,
)

/** Результаты расчёта партии (единицы UI: г, мл, г/см³, мПа·с) */
data class SlurryResult(
    val dryMassG: Double,
    val solventMassG: Double,
    val wetMassG: Double,
    val componentMassesG: Map<String, Double>,
    val dryDensityGcm3: Double,      // истинная плотность сухого вещества
    val slurryDensityGcm3: Double,   // плотность готовой суспензии
    val volumeMl: Double,            // объём партии
    val solidVolumeFraction: Double, // φ — объёмная доля твёрдой фазы
    val relativeViscosity: Double?,  // η_rel = η/η_р-ля
    val viscosityEstimateMPas: Double?, // оценка вязкости, мПа·с
    val warnings: List<String>,
)
