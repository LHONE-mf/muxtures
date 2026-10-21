package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 5.1 — рецептура катода с раствором PVDF 8 % в NMP */
class SpecTest51 {

    private fun recipe() = RecipeInput(
        dryMassG = 1000.0,
        fractions = listOf(0.96, 0.02, 0.02),   // NMC / Super P / PVDF
        binderIndex = 2,
        solutionBinderPct = 8.0,                // раствор 8 % PVDF в NMP
        scTarget = 0.65,
    )

    @Test fun `component masses`() {
        val r = RecipeCalc.calculate(recipe()).getOrThrow()
        assertEquals("NMC", 960.0, r.componentMassesG[0], 1e-6)
        assertEquals("Super P", 20.0, r.componentMassesG[1], 1e-6)
        assertEquals("PVDF сухой (вносится раствором)", 0.0, r.componentMassesG[2], 1e-6)
    }

    @Test fun `binder solution and its nmp`() {
        val r = RecipeCalc.calculate(recipe()).getOrThrow()
        assertEquals("масса раствора PVDF", 250.0, r.binderSolutionMassG, 1e-6)
        assertEquals("NMP внутри раствора", 230.0, r.binderSolventInSolutionG, 1e-6)
    }

    @Test fun `totals`() {
        val r = RecipeCalc.calculate(recipe()).getOrThrow()
        assertEquals("m_total", 1538.4615384615386, r.totalMassG, 1e-6)
        assertEquals("растворитель всего", 538.4615384615386, r.totalSolventG, 1e-6)
        assertEquals("добавочный NMP", 308.4615384615385, r.extraSolventG, 1e-6)
    }

    @Test fun `mass balance closes`() {
        val r = RecipeCalc.calculate(recipe()).getOrThrow()
        val sum = r.componentMassesG.sum() + r.binderSolutionMassG + r.extraSolventG
        assertEquals("Σ навесок == m_total", r.totalMassG, sum, 1e-9)
        assertTrue("без предупреждений", r.warnings.isEmpty())
    }

    @Test fun `assay and moisture adjust weigh amounts`() {
        // влажность NMC 0,5 % => навеска больше сухой массы
        val inp = recipe().copy(
            moistures = listOf(0.005, 0.0, 0.0),
            assays = listOf(0.99, 1.0, 1.0),
            solutionBinderPct = 0.0,
        )
        val r = RecipeCalc.calculate(inp).getOrThrow()
        assertEquals(960.0 / (0.99 * 0.995), r.componentMassesG[0], 1e-6)
    }
}
