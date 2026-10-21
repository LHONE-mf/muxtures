package com.slurrycalc.calc

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Реометрия: степенная модель (Power Law / Оствальда–де Валя) и её подгонка,
 * а также конвертация тонкого помола (Hegman ↔ мкм). Чистый Kotlin.
 *
 * Единицы SPEC-соглашения: вязкость η в Па·с, скорость сдвига γ̇ в с⁻¹.
 */
object Rheology {

    /** η = K · γ̇^(n−1). Возвращает η [Па·с]; K [Па·с^n], n — индекс течения. */
    fun powerLawEta(k: Double, n: Double, shearRate: Double): Double {
        require(shearRate > 0) { "Скорость сдвига должна быть > 0" }
        require(k > 0) { "K должен быть > 0" }
        return k * shearRate.pow(n - 1.0)
    }

    data class FitResult(val k: Double, val n: Double, val r2: Double, val points: Int)

    /**
     * МНК-подгонка в логарифмах: ln η = ln K + (n−1)·ln γ̇.
     * Точки с non-finite или <=0 значениями отбрасываются; нужно минимум 2 точки.
     */
    fun fitPowerLaw(shearRates: List<Double>, viscositiesPas: List<Double>): Result<FitResult> {
        if (shearRates.size != viscositiesPas.size)
            return Result.failure(IllegalArgumentException("Массивы γ̇ и η разной длины"))
        val pts = shearRates.zip(viscositiesPas).filter { (g, e) ->
            g.isFinite() && e.isFinite() && g > 0 && e > 0
        }
        if (pts.size < 2) return Result.failure(IllegalArgumentException("Нужно минимум 2 валидные точки"))

        val xs = pts.map { ln(it.first) }
        val ys = pts.map { ln(it.second) }
        val m = xs.size
        val sx = xs.sum(); val sy = ys.sum()
        val sxx = xs.sumOf { it * it }; val sxy = xs.indices.sumOf { xs[it] * ys[it] }
        val denom = m * sxx - sx * sx
        if (abs(denom) < 1e-12) return Result.failure(IllegalArgumentException("Вырожденный набор точек (одинаковые γ̇)"))
        val slope = (m * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / m
        val n = slope + 1.0
        val k = exp(intercept)

        // R² в логарифмическом пространстве
        val yMean = sy / m
        val ssTot = ys.sumOf { (it - yMean) * (it - yMean) }
        val ssRes = xs.indices.sumOf { i ->
            val pred = intercept + slope * xs[i]
            (ys[i] - pred) * (ys[i] - pred)
        }
        val r2 = if (ssTot > 0) 1.0 - ssRes / ssTot else 1.0

        return Result.success(FitResult(k = k, n = n, r2 = r2, points = m))
    }

    /** Конверсия тонкого помола: Hegman gauge 0..8 ↔ размер частиц ~0..100 мкм (линейная шкала). */
    fun hegmanToMicron(hegman: Double): Double {
        require(hegman in 0.0..8.0) { "Hegman вне диапазона 0..8" }
        return (8.0 - hegman) * 12.5
    }

    fun micronToHegman(microns: Double): Double {
        require(microns in 0.0..100.0) { "Размер вне диапазона 0..100 мкм" }
        return 8.0 - microns / 12.5
    }
}
