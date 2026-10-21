package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** SPEC 5.4 — подгонка степенной модели по синтетическим данным η = 20·γ̇^(-0.5) */
class SpecTest54 {

    private fun synth(): Pair<List<Double>, List<Double>> {
        val gammas = ArrayList<Double>()
        val etas = ArrayList<Double>()
        var g = 1.0
        while (g <= 100.0 + 1e-9) {
            gammas.add(g); etas.add(Rheology.powerLawEta(20.0, 0.5, g)); g *= 1.10
        }
        return gammas to etas
    }

    @Test fun `fit recovers K and n within 1 pct`() {
        val (gammas, etas) = synth()
        val fit = Rheology.fitPowerLaw(gammas, etas).getOrThrow()
        assertEquals(0.5, fit.n, 0.01 * 0.5)
        assertEquals(20.0, fit.k, 0.01 * 20.0)
        assertTrue("R² близок к 1", fit.r2 > 0.9999)
    }

    @Test fun `eta at 10 per second`() {
        val eta = Rheology.powerLawEta(k = 20.0, n = 0.5, shearRate = 10.0)
        assertEquals(6.324555320336759, eta, 1e-6)
        assertEquals(6.32, eta, 0.01)
    }

    @Test fun `rejects degenerate input`() {
        assertTrue(Rheology.fitPowerLaw(listOf(1.0), listOf(20.0)).isFailure)
        assertTrue(Rheology.fitPowerLaw(listOf(1.0, 1.0), listOf(20.0, 10.0)).isFailure)
    }
}
