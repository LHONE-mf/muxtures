package com.slurrycalc.calc

/**
 * Расчёт рецептуры по SPEC (раздел 5.1). Чистый Kotlin, без зависимостей от Android.
 *
 * Модель: часть связующего вносится в виде заранее приготовленного раствора binder в растворителе.
 *  - dryMassG          — суммарная масса сухого вещества, г
 *  - fractions         — массовые доли компонентов в СУХОМ веществе (нормализуются к 1)
 *  - binderIndex       — индекс компонента-связующего
 *  - solutionBinderPct — % связующего в растворе (например 8 => 8 % PVDF в NMP)
 *  - scTarget          — целевой сухой остаток суспензии, доля 0..1
 *  - assays            — содержание активного вещества каждого компонента (1 = 100 %)
 *  - moistures         — влажность каждого компонента, доля 0..1
 */
data class RecipeInput(
    val dryMassG: Double,
    val fractions: List<Double>,
    val binderIndex: Int,
    val solutionBinderPct: Double = 0.0,   // 0 = раствор связующего не используем
    val scTarget: Double,
    val assays: List<Double> = emptyList(),
    val moistures: List<Double> = emptyList(),
)

data class RecipeResult(
    val componentMassesG: List<Double>,     // навески компонентов «как есть» (с assay/влажностью)
    val binderSolutionMassG: Double,        // масса раствора связующего
    val binderSolventInSolutionG: Double,   // растворитель, пришедший ВНУТРИ раствора
    val extraSolventG: Double,              // добавочный растворитель
    val totalSolventG: Double,              // всего растворителя
    val totalMassG: Double,                 // масса всей суспензии
    val warnings: List<String> = emptyList(),
)

object RecipeCalc {

    fun calcAssay(i: Int, input: RecipeInput): Double =
        if (i < input.assays.size) input.assays[i] else 1.0

    fun calcMoisture(i: Int, input: RecipeInput): Double =
        if (i < input.moistures.size) input.moistures[i] else 0.0

    fun calculate(input: RecipeInput): Result<RecipeResult> {
        val warnings = mutableListOf<String>()
        if (input.dryMassG <= 0) return Result.failure(IllegalArgumentException("m_dry должен быть > 0"))
        if (input.fractions.isEmpty()) return Result.failure(IllegalArgumentException("Нет компонентов"))
        if (input.scTarget <= 0 || input.scTarget >= 1)
            return Result.failure(IllegalArgumentException("SC должен быть между 0 и 100 %"))
        if (input.binderIndex !in input.fractions.indices)
            return Result.failure(IllegalArgumentException("Не указан компонент-связующее"))

        val sumFrac = input.fractions.sum()
        if (sumFrac <= 0) return Result.failure(IllegalArgumentException("Сумма долей равна нулю"))
        if (kotlin.math.abs(sumFrac - 1.0) > 0.005) {
            warnings.add(String.format(java.util.Locale.US,
                "Сумма долей = %.2f %%, нормализованы к 100 %%", sumFrac * 100))
        }
        val frac = input.fractions.map { it / sumFrac }

        // Сухая масса каждого компонента с учётом assay; влага считается от массы «как есть»,
        // т.е. m_i = dry_i / (assay_i * (1 - w_i)) — стандартная формула влажной навески.
        val n = frac.size
        val dryMass = DoubleArray(n)
        val wetMass = DoubleArray(n)
        var effectiveDry = 0.0
        for (i in 0 until n) {
            val a = calcAssay(i, input)
            val w = calcMoisture(i, input)
            if (a <= 0 || a > 1) return Result.failure(IllegalArgumentException("assay вне (0..1]: $a"))
            if (w < 0 || w >= 1) return Result.failure(IllegalArgumentException("влажность вне [0..1): $w"))
            dryMass[i] = input.dryMassG * frac[i]
            wetMass[i] = dryMass[i] / (a * (1.0 - w))
            effectiveDry += dryMass[i]
        }

        val totalWet = effectiveDry / input.scTarget
        var totalSolvent = totalWet - effectiveDry
        var binderSolution = 0.0
        var solventInSolution = 0.0

        if (input.solutionBinderPct > 0.0) {
            val c = input.solutionBinderPct / 100.0
            if (c >= 1.0) return Result.failure(IllegalArgumentException("Концентрация раствора связующего должна быть < 100 %"))
            val binderDry = dryMass[input.binderIndex]
            binderSolution = binderDry / c
            solventInSolution = binderSolution - binderDry
            // связующее берём из раствора, а не из сухих банок: его навеска обнуляется
            wetMass[input.binderIndex] = 0.0
            // если раствор несёт больше растворителя, чем нужно — SC будет выше целевого
            if (solventInSolution > totalSolvent) warnings.add(
                "Раствор связующего вносит больше растворителя, чем требуется для SC — сухой остаток будет выше целевого")
            totalSolvent -= solventInSolution
            if (totalSolvent < 0) totalSolvent = 0.0
        }

        return Result.success(RecipeResult(
            componentMassesG = wetMass.toList(),
            binderSolutionMassG = binderSolution,
            binderSolventInSolutionG = solventInSolution,
            extraSolventG = totalSolvent,
            totalSolventG = totalSolvent + solventInSolution,
            totalMassG = totalWet,
            warnings = warnings,
        ))
    }
}
