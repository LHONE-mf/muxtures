package com.slurrycalc.calc

/**
 * Соглашение об единицах calc-слоя (задаётся полностью на этапе 1d):
 * массы — мг (Long), вязкость — Па·с. Здесь — чистые конвертеры для границы UI.
 */
object UnitUtils {
    fun gToMg(g: Double): Long = Math.round(g * 1000.0)
    fun mgToG(mg: Long): Double = mg / 1000.0
    fun pasToMPas(pas: Double): Double = pas * 1000.0
    fun mPasToPas(mp: Double): Double = mp / 1000.0
}
