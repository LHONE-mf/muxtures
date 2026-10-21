package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 5.2 — фактический состав из реальных навесок */
class SpecTest52 {

    private val planned = listOf(
        PlannedComponent("NMC", 960.0, 0.96),
        PlannedComponent("Super P", 20.0, 0.02),
        PlannedComponent("PVDF", 20.0, 0.02),
    )
    private val actual = listOf(961.2, 19.6, 20.1)

    @Test fun `total dry actual`() {
        val r = ActualComposition.calculate(planned, actual).getOrThrow()
        assertEquals(1000.9, r.totalDryActualG, 1e-9)
    }

    @Test fun `absolute deviations`() {
        val d = ActualComposition.calculate(planned, actual).getOrThrow().deviations
        assertEquals(1.2, d[0].absDevG, 1e-9)
        assertEquals(-0.4, d[1].absDevG, 1e-9)
        assertEquals(0.1, d[2].absDevG, 1e-9)
    }

    @Test fun `relative deviations pct`() {
        val d = ActualComposition.calculate(planned, actual).getOrThrow().deviations
        assertEquals(0.125, d[0].relDevPct, 1e-9)
        assertEquals(-2.0, d[1].relDevPct, 1e-9)
        assertEquals(0.5, d[2].relDevPct, 1e-9)
    }

    @Test fun `actual fractions in dry`() {
        val d = ActualComposition.calculate(planned, actual).getOrThrow().deviations
        assertEquals(96.03, d[0].actualFractionPct, 0.01)
        assertEquals(1.96, d[1].actualFractionPct, 0.01)
        assertEquals(2.01, d[2].actualFractionPct, 0.01)
    }

    @Test fun `tolerance check`() {
        val d = ActualComposition.calculate(planned, actual, tolerancePct = 1.0).getOrThrow().deviations
        assertFalse("NMC в допуске", d[0].outOfTolerance)
        assertTrue("Super P вне допуска ±1 %", d[1].outOfTolerance)
        assertFalse("PVDF в допуске", d[2].outOfTolerance)
    }

    @Test fun `actual SC from solvent mass`() {
        val r = ActualComposition.calculate(planned, actual, solventActualG = 538.4615384615386).getOrThrow()
        assertEquals(1000.9 / (1000.9 + 538.4615384615386) * 100.0, r.scActualPct!!, 1e-9)
    }
}
