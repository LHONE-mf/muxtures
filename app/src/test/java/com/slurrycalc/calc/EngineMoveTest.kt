package com.slurrycalc.calc

import com.slurrycalc.engine.Materials
import com.slurrycalc.engine.SlurryEngine
import com.slurrycalc.engine.SlurryInput
import com.slurrycalc.engine.Solvents
import com.slurrycalc.engine.ViscosityModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Регрессия: переносимый в calc движок даёт те же числа, что и эталон v1.x */
class EngineMoveTest {

    @Test fun `reference cathode batch`() {
        val e = SlurryEngine()
        val r = e.calculate(SlurryInput(
            solids = Materials.typicalCathode(),
            solvent = Solvents.NMP,
            dryContentMassFraction = 0.50,
            totalDryMassG = 2000.0,
            viscosityModel = ViscosityModel.MO,
        )).getOrThrow()
        // Эталон получен ручным расчётом для пресета NMC 622 (ρ=4.67):
        // rho_dry = 1/(0.96/4.67 + 0.02/1.95 + 0.02/1.77) = 4.402895
        assertEquals(4.402895, r.dryDensityGcm3, 1e-5)
        // phi = (1/rho_dry)/(1/rho_dry + 1/1.028) = 0.189287
        assertEquals(0.189287, r.solidVolumeFraction, 1e-5)
        // rho_slurry = 1/(0.5/rho_dry + 0.5/rho_nmp) = 1.666827; m_wet = 4000 г => V = 2399.77 мл
        assertEquals(1.666827, r.slurryDensityGcm3, 1e-5)
        assertEquals(2399.77, r.volumeMl, 0.05)
        // Mo: eta_rel = exp(2.5*phi/(1-phi/0.71)) = 1.90645 -> *1.87 мПа*с = 3.5651
        assertEquals(3.5651, r.viscosityEstimateMPas!!, 1e-3)
        assertEquals(1920.0, r.componentMassesG.getValue("NMC 622"), 1e-9) // 0.96 * 2000 г сухого
    }

    @Test fun `volume mode roundtrip`() {
        val e = SlurryEngine()
        val base = SlurryInput(
            solids = Materials.typicalCathode(), solvent = Solvents.NMP,
            dryContentMassFraction = 0.50, totalDryMassG = 2000.0,
        )
        val v = e.calculate(base).getOrThrow().volumeMl
        val back = e.calculate(base.copy(totalDryMassG = 0.0, targetVolumeMl = v)).getOrThrow()
        assertEquals(2000.0, back.dryMassG, 1e-6)
    }
}
