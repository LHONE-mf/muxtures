package com.slurrycalc.engine

/**
 * Режимы диспергирования/смешивания. Типовой порядок для катодной суспензии:
 * pre-mix сухих (AM+CB) → растворение связующего → wet-mix → финишный перетир → дегазация.
 */
data class MixingRegime(
    val id: String,
    val name: String,
    /** Planetary / Magnetic / Three-roll / Ultrasonic / Vacuum / Manual */
    val method: String,
    /** ориентировочные параметры инструмента (текст-подсказка) */
    val tipSpeedHint: String,
    private val timeMinFirst: Int,
    private val timeMinLast: Int,
    /** ограничение температуры суспензии при обработке, °C (null — не нормируется) */
    val tempLimitC: Double?,
    val description: String,
    /** 1 = предсмесь, 2 = связующее, 3 = основная масса, 4 = финиш */
    val stageOrder: Int,
) {
    val timeHint: String get() = "$timeMinFirst–$timeMinLast мин"
}

object MixingRegimes {

    val ALL: List<MixingRegime> = listOf(
        MixingRegime(
            "manual_beaker", "Ручное (стакан + лопатка)", "Manual",
            "вручную, ~1 м/с", 15, 30, null,
            "Для пробников <100 г. Однородность низкая, агломераты сохраняются.", 3,
        ),
        MixingRegime(
            "magnetic", "Магнитная мешалка", "Magnetic",
            "500–1500 об/мин", 20, 60, null,
            "Хороша для растворения PVDF и разбавленных составов; для высокого φ слаба.", 2,
        ),
        MixingRegime(
            "planetary_low", "Планетарная (низкая скорость)", "Planetary",
            "~10 м/с (≈1200 об/мин, чаша 250 мл)", 10, 20, 60.0,
            "Pre-mix сухих CB+AM или мягкий wet-mix малых партий.", 1,
        ),
        MixingRegime(
            "planetary_std", "Планетарная (стандарт)", "Planetary",
            "~20 м/с (≈2500 об/мин)", 20, 40, 60.0,
            "Базовый режим lab-диспергирования суспензий 0.2–2 л.", 3,
        ),
        MixingRegime(
            "planetary_high", "Планетарная (высокая скорость)", "Planetary",
            "~30 м/с (≈4000 об/мин)", 30, 60, 50.0,
            "Интенсивное диспергирование; греется — контролируйте T°, возможен гель PVDF.", 3,
        ),
        MixingRegime(
            "three_roll", "Трёхвальцовая мельница", "Three-roll",
            "зазор 5–10 мкм, 2–3 прохода", 5, 15, 45.0,
            "Финишный перетир после предсмеса; лучший деагломератор для φ > 35 %.", 4,
        ),
        MixingRegime(
            "ultrasonic", "УЗ-зонд (sonication)", "Ultrasonic",
            "20 кГц, импульсный режим", 5, 20, 40.0,
            "Гомогенизация малых объёмов; следить за перегревом и деградацией связующего.", 3,
        ),
        MixingRegime(
            "degassing", "Дегазация (вакуум)", "Vacuum",
            "< −0.08 МПа при помешивании", 5, 15, null,
            "Удаление пузырей перед нанесением; финальный шаг любого режима.", 4,
        ),
    )

    fun byId(id: String): MixingRegime? = ALL.firstOrNull { it.id == id }

    /** Рекомендуемая последовательность стадий для выбранного основного режима */
    fun recommendedSequence(mainId: String): List<String> = when (mainId) {
        "manual_beaker" -> listOf("manual_beaker", "degassing")
        "magnetic" -> listOf("magnetic", "degassing")
        "planetary_low" -> listOf("planetary_low", "planetary_std", "degassing")
        "planetary_std" -> listOf("planetary_low", "planetary_std", "degassing")
        "planetary_high" -> listOf("planetary_low", "planetary_std", "planetary_high", "degassing")
        "three_roll" -> listOf("planetary_std", "three_roll", "degassing")
        "ultrasonic" -> listOf("planetary_std", "ultrasonic", "degassing")
        else -> listOf(mainId)
    }
}

/**
 * Пересчёт замеса по ФАКТИЧЕСКИМ взвешенным массам.
 * Реальные массы всегда отличаются от расчётных — здесь считаем «как получилось»:
 * фактический СО, доли компонентов в сухом, ρ сухого, φ и объём (из измеренной ρ, если есть).
 */
object BatchMath {

    data class ActualComponent(val name: String, val density: Double, val massG: Double)

    data class ActualBatch(
        val dryMassG: Double,
        val solventMassG: Double,
        val wetMassG: Double,
        /** фактический сухой остаток, % */
        val dryContentPct: Double,
        /** фактические доли компонентов в сухом веществе, % */
        val componentPctInDry: Map<String, Double>,
        /** истинская ρ сухого вещества (правило обратных долей), г/см³ */
        val dryDensityGcm3: Double,
        /** расчётная ρ суспензии (идеальное смешение), г/см³ */
        val calcDensityGcm3: Double,
        /** измеренная ρ пасты, если указана, г/см³ */
        val measuredDensityGcm3: Double?,
        /** объём: m/ρ_изм (если ρ измерена), иначе расчётный */
        val volumeMl: Double,
        val volumeSource: String,
        /** φ — объёмная доля твёрдой фазы */
        val solidVolumeFraction: Double,
        val warnings: List<String>,
    )

    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    fun fromActualMasses(
        components: List<ActualComponent>,
        solventMassG: Double,
        solventDensity: Double,
        measuredDensityGcm3: Double? = null,
    ): Result<ActualBatch> {
        val warnings = mutableListOf<String>()
        val comps = components.filter { it.massG > 0 }
        if (comps.isEmpty())
            return Result.failure(IllegalArgumentException("Укажите хотя бы одну фактическую массу компонента"))
        if (solventMassG <= 0)
            return Result.failure(IllegalArgumentException("Укажите фактическую массу растворителя"))
        if (solventDensity <= 0)
            return Result.failure(IllegalArgumentException("Некорректная плотность растворителя"))
        if (comps.any { it.density <= 0 })
            return Result.failure(IllegalArgumentException("Для всех компонентов должна быть задана плотность"))

        val dryMass = comps.sumOf { it.massG }
        val wetMass = dryMass + solventMassG
        val dcPct = dryMass / wetMass * 100.0

        val dryVolume = comps.sumOf { it.massG / it.density }
        val totalVolume = dryVolume + solventMassG / solventDensity
        val dryDensity = dryMass / dryVolume
        val phi = dryVolume / totalVolume
        val calcDensity = wetMass / totalVolume

        val compPct = comps.associate { it.name to it.massG / dryMass * 100.0 }

        val volume: Double
        val source: String
        if (measuredDensityGcm3 != null && measuredDensityGcm3 > 0) {
            volume = wetMass / measuredDensityGcm3
            source = "измеренная ρ"
            val diff = (measuredDensityGcm3 - calcDensity) / calcDensity * 100.0
            if (kotlin.math.abs(diff) > 3) warnings.add(
                "Измеренная ρ=${f3(measuredDensityGcm3)} отличается от расчётной ${f3(calcDensity)} на " +
                        "${f1(diff)} % — вероятны захват воздуха (ρ ниже) либо " +
                        "неполная смачиваемость / ошибка ввода масс."
            )
        } else {
            volume = totalVolume
            source = "расчётная ρ"
        }

        if (phi > 0.65) warnings.add("Высокая φ = ${f1(phi * 100)} % — возможна трудноразливаемость.")

        return Result.success(
            ActualBatch(
                dryMassG = dryMass,
                solventMassG = solventMassG,
                wetMassG = wetMass,
                dryContentPct = dcPct,
                componentPctInDry = compPct,
                dryDensityGcm3 = dryDensity,
                calcDensityGcm3 = calcDensity,
                measuredDensityGcm3 = measuredDensityGcm3,
                volumeMl = volume,
                volumeSource = source,
                solidVolumeFraction = phi,
                warnings = warnings,
            )
        )
    }
}
