package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC 5.5 — конвертация Hegman ↔ мкм и обратимость */
class SpecTest55 {

    @Test fun `known points`() {
        assertEquals(5.6, Rheology.micronToHegman(30.0), 1e-9)
        assertEquals(0.0, Rheology.micronToHegman(100.0), 1e-9)
        assertEquals(8.0, Rheology.micronToHegman(0.0), 1e-9)
        assertEquals(30.0, Rheology.hegmanToMicron(5.6), 1e-9)
        assertEquals(100.0, Rheology.hegmanToMicron(0.0), 1e-9)
        assertEquals(0.0, Rheology.hegmanToMicron(8.0), 1e-9)
    }

    @Test fun `roundtrip`() {
        for (h in listOf(0.0, 1.3, 2.5, 4.0, 5.6, 6.8, 8.0)) {
            assertEquals(h, Rheology.micronToHegman(Rheology.hegmanToMicron(h)), 1e-9)
        }
        for (d in listOf(0.0, 12.5, 25.0, 50.0, 75.0, 100.0)) {
            assertEquals(d, Rheology.hegmanToMicron(Rheology.micronToHegman(d)), 1e-9)
        }
    }
}
