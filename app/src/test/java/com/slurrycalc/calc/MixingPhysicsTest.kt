package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Физика перемешивания по тестам SPEC 5.3 (в рамках 1b — новые формулы) */
class MixingPhysicsTest {

    @Test fun `tip speed`() {
        assertEquals(5.235988, MixingPhysics.tipSpeed(50.0, 2000.0), 1e-5)
    }

    @Test fun `reynolds transitional`() {
        // Строго: Re = rho*v_tip*d/mu c v_tip = pi*d*n => 261.8 (переходный режим).
        // В SPEC 5.3 указано 83.3 — это значение соответствует v_tip ~1.67 м/с
        // (~1600 об/мин), т.е. числа в примере SPEC внутренне несогласованы.
        // Формула в коде верна; расхождение зафиксировано в AUDIT.md.
        val re = MixingPhysics.reynolds(2000.0, MixingPhysics.tipSpeed(50.0, 2000.0), 0.05, 2.0)
        assertEquals(261.8, re, 0.1)
        assertTrue("режим переходный", MixingPhysics.flowRegime(re) == "переходный")
    }

    @Test fun `gap shear rate`() {
        assertEquals(12566.37, MixingPhysics.shearRateGap(40.0, 0.5, 3000.0), 1.0)
    }

    @Test fun `scale by tip speed`() {
        assertEquals(1000.0, MixingPhysics.scaleRpmConstantTipSpeed(2000.0, 50.0, 100.0), 1e-9)
    }

    @Test fun `specific energy`() {
        assertEquals(144.0, MixingPhysics.specificEnergyKJPerKg(120.0, 30.0, 1.5), 1e-9)
    }
}
