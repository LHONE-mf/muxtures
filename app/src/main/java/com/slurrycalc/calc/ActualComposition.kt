package com.slurrycalc.calc

/**
 * Фактический состав замеса из реально загруженных масс (SPEC 5.2).
 * Все массы в граммах; доли — массовые доли в сухом веществе.
 */
data class PlannedComponent(
    val name: String,
    val plannedDryMassG: Double,
    val plannedFraction: Double,   // плановая доля в сухом, 0..1
)

data class ActualDeviation(
    val name: String,
    val plannedG: Double,
    val actualG: Double,
    val absDevG: Double,           // факт − план, г
    val relDevPct: Double,         // отклонение от плановой навески, %
    val actualFractionPct: Double, // фактическая доля в сухом, %
    val outOfTolerance: Boolean,   // |relDev| > tolerancePct
)

data class ActualCompositionResult(
    val totalDryActualG: Double,
    val deviations: List<ActualDeviation>,
    val scActualPct: Double?,      // фактический сухой остаток, если задана масса растворителя
)

object ActualComposition {

    fun calculate(
        planned: List<PlannedComponent>,
        actualDryMassesG: List<Double>,
        tolerancePct: Double = 1.0,
        solventActualG: Double? = null,
    ): Result<ActualCompositionResult> {
        if (planned.isEmpty()) return Result.failure(IllegalArgumentException("Нет компонентов"))
        if (planned.size != actualDryMassesG.size)
            return Result.failure(IllegalArgumentException("Число фактических масс != числа компонентов"))
        if (actualDryMassesG.any { it < 0 })
            return Result.failure(IllegalArgumentException("Отрицательная фактическая масса"))

        val totalActual = actualDryMassesG.sum()
        if (totalActual <= 0) return Result.failure(IllegalArgumentException("Суммарная фактическая масса сухого = 0"))

        val devs = planned.indices.map { i ->
            val p = planned[i]
            val a = actualDryMassesG[i]
            val rel = if (p.plannedDryMassG > 0) (a - p.plannedDryMassG) / p.plannedDryMassG * 100.0 else Double.NaN
            ActualDeviation(
                name = p.name,
                plannedG = p.plannedDryMassG,
                actualG = a,
                absDevG = a - p.plannedDryMassG,
                relDevPct = rel,
                actualFractionPct = a / totalActual * 100.0,
                outOfTolerance = !rel.isNaN() && kotlin.math.abs(rel) > tolerancePct,
            )
        }

        val sc = solventActualG?.let { s ->
            if (s < 0) return Result.failure(IllegalArgumentException("Отрицательная масса растворителя"))
            val total = totalActual + s
            if (total <= 0) null else totalActual / total * 100.0
        }

        return Result.success(ActualCompositionResult(totalActual, devs, sc))
    }
}
