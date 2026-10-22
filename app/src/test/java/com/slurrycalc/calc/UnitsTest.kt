package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Тесты правила единиц этапа 1d: мг(Long) <-> г, Па·с <-> мПа·с, кг/м³ <-> г/см³ */
class UnitsTest {

    @Test fun massRoundTrip() {
        assertEquals(2_000_000L, Units.gToMg(2000.0))
        assertEquals(2000.0, Units.mgToG(2_000_000L), 1e-9)
        // точность 1 мг при типичных весах 0.1 г
        assertEquals(150L, Units.gToMg(0.1496)) // округление до мг
        assertEquals(1500L, Units.gToMg(1.5))
    }

    @Test fun viscosityRoundTrip() {
        assertEquals(1.87 / 1000.0, Units.mPasToPas(1.87), 1e-12)
        assertEquals(1.87, Units.pasToMPas(0.00187), 1e-9)
        // 3.5 mPa·s (эталонный катод) = 0.0035 Pa·s
        assertEquals(0.0035, Units.mPasToPas(3.5), 1e-12)
    }

    @Test fun densityRoundTrip() {
        assertEquals(4670.0, Units.gcm3ToKgM3(4.67), 1e-9)
        assertEquals(4.67, Units.kgm3ToGcm3(4670.0), 1e-12)
    }

    @Test fun batchResultUsesCalcUnits() {
        // Эталон: катод NMC622, 2 кг суспензии, СО 50 % — в calc-единицах массы мг(Long)
        val input = BatchInput(
            solids = listOf(
                DryComponent("NMC 622", Units.gcm3ToKgM3(4.67), 0.96),
                DryComponent("Super-P", Units.gcm3ToKgM3(1.95), 0.02),
                DryComponent("PVDF", Units.gcm3ToKgM3(1.77), 0.02),
            ),
            solvent = Medium("NMP", Units.gcm3ToKgM3(1.028), Units.mPasToPas(1.87)),
            dryContentMassFraction = 0.5,
            totalWetMassMg = Units.gToMg(2000.0),
        )
        val r = SlurryCalc.calculate(input).getOrThrow()
        assertEquals(1_000_000L, r.dryMassMg)      // 1000 г = 1 000 000 мг
        assertEquals(1_000_000L, r.solventMassMg)
        assertEquals(960_000L, r.componentMassesMg["NMC 622"])
        assertEquals(4402.9, r.dryDensityKgM3, 1.0)   // 4.403 г/см³ (проверено независимым расчётом)
        assertEquals(1666.8, r.slurryDensityKgM3, 1.0) // ~1.67 г/см³
        assertEquals(0.00187, input.solvent.viscosityPas, 1e-12) // Па·с внутри слоя
    }
}
