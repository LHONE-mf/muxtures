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

    /**
     * Имелеевское число Рейнольдса для мешалки (справочная дефиниция, McCabe/Smith/Foscolo,
     * Perry's Handbook, Coulson & Richardson): Re = ρ·N·d² / μ,
     * N [об/с], d [м] — диаметр крыльчатки, μ [Па·с].
     * Для d = 50 мм, 2000 об/мин, ρ = 2000 кг/м³, μ = 2 Па·с даёт Re = 83,3 — значение SPEC 5.3.
     */
    fun reynoldsImpeller(densityKgM3: Double, rpm: Double, diameterMm: Double, viscosityPas: Double): Double {
        require(diameterMm > 0 && viscosityPas > 0) { "d и μ должны быть > 0" }
        val n = rpm / 60.0
        val d = diameterMm / 1000.0
        return densityKgM3 * n * d * d / viscosityPas
    }

    /**
     * Альтернативная дефиниция через окружную скорость: Re_v = ρ·v_tip·d / μ = π · Re_импеллера.
     * В справочниках корреляции Np(Re) привязаны к импеллерной форме, поэтому для классификации
     * режима используется именно она; эта функция оставлена как вспомогательная.
     */
    fun reynoldsFromTipSpeed(densityKgM3: Double, tipSpeedMs: Double, diameterM: Double, viscosityPas: Double): Double {
        require(viscosityPas > 0) { "Вязкость должна быть > 0" }
        return densityKgM3 * tipSpeedMs * diameterM / viscosityPas
    }

    /**
     * Эффективная вязкость по Оствальду–де Валю: η_eff = K·γ̇^(n−1), γ̇ — сдвиговая скорость в зоне мешалки.
     */
    fun effectiveViscosity(kConsistency: Double, nFlow: Double, shearRatePerSec: Double): Double {
        require(kConsistency > 0 && shearRatePerSec > 0) { "K и γ̇ должны быть > 0" }
        return kConsistency * Math.pow(shearRatePerSec, nFlow - 1.0)
    }

    /**
     * Число Рейнольдса псевдопластичной суспензии у мешалки (стандарт Metzner–Otto):
     * γ̇_s = 11,5·n·N (коэффициент 11,5 — для турбинки Rushton, для пропеллера ~10–11),
     * Re_MO = ρ·N·d² / η_eff(γ̇_s).
     */
    fun reynoldsMetznerOtto(
        densityKgM3: Double, rpm: Double, diameterMm: Double,
        kConsistency: Double, nFlow: Double, moFactor: Double = 11.5
    ): Double {
        require(moFactor > 0) { "Мецнер–Отто коэффициент должен быть > 0" }
        val n = rpm / 60.0
        val gammaS = moFactor * n
        val etaEff = effectiveViscosity(kConsistency, nFlow, gammaS)
        return reynoldsImpeller(densityKgM3, rpm, diameterMm, etaEff)
    }

    /**
     * Классификация режима течения по порогу Gieseemer/Tichelman для импеллерного Re:
     * ламинарный Re < 10; переходный 10…10^4;
     * турбулентный Re > 10^4 (классические пороги Coulson-Richardson, McCabe-Smith).
     * Для неньютоновских суспензий Re считают по эффективной вязкости Metzner-Ott (см. reynoldsMetznerOtto).
     */
    fun flowRegime(re: Double): String = when {
        re < 10.0 -> "ламинарный"
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
