package com.slurrycalc.calc

import kotlin.math.PI

/**
 * Физика перемешивания (SPEC 5.3). Чистый Kotlin, СИ-единицы:
 * v_tip [м/с], Re [-], γ̇ [с⁻¹], E_уд [кДж/кг].
 */
object MixingPhysics {

    /** Окружная скорость диспергатора: v_tip = π·d·n; d [м], rpm [об/мин] → [м/с] */
    fun tipSpeed(diameterMm: Double, rpm: Double): Double {
        require(diameterMm > 0 && rpm >= 0) { "d и rpm должны быть > 0" }
        return PI * (diameterMm / 1000.0) * (rpm / 60.0)
    }

    /** Число Рейнольдса для диска/ротора: Re = ρ·v_tip·d / μ (ρ [кг/м³], μ [Па·с]) */
    fun reynolds(densityKgM3: Double, tipSpeedMs: Double, diameterM: Double, viscosityPas: Double): Double {
        require(viscosityPas > 0) { "Вязкость должна быть > 0" }
        return densityKgM3 * tipSpeedMs * diameterM / viscosityPas
    }

    /** Режим по Re: ламинарный < 10, переходный 10…10^4, турбулентный > 10^4 */
    fun flowRegime(re: Double): String = when {
        re < 10 -> "ламинарный"
        re <= 1e4 -> "переходный"
        else -> "турбулентный"
    }

    /** Средний градиент скорости в зазоре ротор–статор: γ̇ ≈ π·d_rotor·n / δ */
    fun shearRateGap(rotorDiameterMm: Double, gapMm: Double, rpm: Double): Double {
        require(rotorDiameterMm > 0 && gapMm > 0 && rpm >= 0) { "d_rotor, δ, rpm должны быть > 0" }
        return PI * (rotorDiameterMm / 1000.0) * (rpm / 60.0) / (gapMm / 1000.0)
    }

    /** Масштабирование по постоянной v_tip: n2 = n1·d1/d2 (об/мин) */
    fun scaleRpmConstantTipSpeed(rpm1: Double, d1Mm: Double, d2Mm: Double): Double {
        require(d1Mm > 0 && d2Mm > 0) { "Диаметры должны быть > 0" }
        return rpm1 * d1Mm / d2Mm
    }

    /** Удельная подводимая энергия: E = P·t/m, возвращает кДж/кг (P [Вт], t [мин], m [кг]) */
    fun specificEnergyKJPerKg(powerW: Double, timeMin: Double, massKg: Double): Double {
        require(massKg > 0) { "Масса должна быть > 0" }
        return powerW * timeMin * 60.0 / 1000.0 / massKg
    }
}
