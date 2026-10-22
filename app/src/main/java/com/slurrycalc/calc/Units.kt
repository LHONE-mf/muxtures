package com.slurrycalc.calc

/**
 * Правило единиц (этап 1d, SPEC п.0.5):
 *
 *  - ВНУТРИ слоя calc и БД: массы — **мг (Long)**, вязкость — **Па·с (Double)**,
 *    плотность — кг/м³ (Double), длина — м (Double), температура — °C.
 *  - В UI: граммы, мПа·с, г/см³, мм, об/мин.
 *  - Перевод величин выполняется ТОЛЬКО на границе UI (здесь + viewmodel-адаптеры).
 *    Функции calc-слоя принимают/возвращают единицы calc; существующие g-API
 *    (RecipeCalc/ActualComposition) сохранены как boundary-обёртки и делегируют в mg-API.
 */
object Units {

    // ---- масса: мг (Long) <-> г (Double, UI) ----
    fun gToMg(g: Double): Long = Math.round(g * 1000.0)
    fun mgToG(mg: Long): Double = mg / 1000.0
    fun kgToMg(kg: Double): Long = Math.round(kg * 1_000_000.0)
    fun mgToKg(mg: Long): Double = mg / 1_000_000.0

    // ---- вязкость: Па·с (calc/БД) <-> мПа·с (UI) ----
    fun pasToMPas(pas: Double): Double = pas * 1000.0
    fun mPasToPas(mp: Double): Double = mp / 1000.0

    // ---- плотность: кг/м³ (calc/БД) <-> г/см³ (UI) ----
    fun gcm3ToKgM3(gcm3: Double): Double = gcm3 * 1000.0
    fun kgm3ToGcm3(kgm3: Double): Double = kgm3 / 1000.0

    // ---- длина: м (calc/БД) <-> мм (UI) ----
    fun mToMm(m: Double): Double = m * 1000.0
    fun mmToM(mm: Double): Double = mm / 1000.0
}
