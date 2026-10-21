package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Физика перемешивания по тестам SPEC 5.3 (в рамках 1b — новые формулы) */
class MixingPhysicsTest {

    @Test fun `tip speed`() {
        assertEquals(5.235988, MixingPhysics.tipSpeed(50.0, 2000.0), 1e-5)
    }

    @Test fun `reynolds impeller matches SPEC 5 point 3`() {
        // Справочная дефиниция для мешалки (McCabe/Coulson&Perry): Re = rho*N*d^2/mu.
        // d = 50 мм, 2000 об/мин, rho = 2000 кг/м3, mu = 2 Па·с => Re = 83,33 — ровно как в SPEC 5.3.
        val re = MixingPhysics.reynoldsImpeller(2000.0, 2000.0, 50.0, 2.0)
        assertEquals(83.3333, re, 1e-3)
        assertTrue("режим переходный", MixingPhysics.flowRegime(re) == "переходный")
    }

    @Test fun `tip-speed reynolds is pi times impeller reynolds`() {
        // Реальная причина расхождения в исходном тексте SPEC: там Re считался по v_tip
        // (rho*v_tip*d/mu = pi*rho*N*d^2/mu), что дало бы 261,8 при тех же данных.
        val reTip = MixingPhysics.reynoldsFromTipSpeed(2000.0, MixingPhysics.tipSpeed(50.0, 2000.0), 0.05, 2.0)
        val reImp = MixingPhysics.reynoldsImpeller(2000.0, 2000.0, 50.0, 2.0)
        assertEquals(Math.PI, reTip / reImp, 1e-9)
        assertEquals(261.7994, reTip, 1e-3)
    }

    @Test fun `metzner otto reynolds for power-law slurry`() {
        // Псевдопластичная суспензия K=1.5 Па·с^n, n=0.7, Rushton d=50 мм, 2000 об/мин:
        // gamma_s = 11.5*N = 383.33 с^-1, eta_eff = K*gamma_s^(n-1) = 0.2518 Па·с,
        // Re_MO = rho*N*d^2/eta_eff = 661.96 -> переходный режим (Re < 150 — не ламинарный!).
        // Это ключевая физическая иллюстрация: псевдопластик разжижается в зоне мешалки,
        // и его Re на 2 порядка выше, чем по «низкосдвиговой» вязкости.
        val etaEff = MixingPhysics.effectiveViscosity(1.5, 0.7, 11.5 * 2000.0 / 60.0)
        assertEquals(0.25178, etaEff, 1e-4)
        val re = MixingPhysics.reynoldsMetznerOtto(2000.0, 2000.0, 50.0, 1.5, 0.7)
        assertEquals(661.96, re, 0.05)
        assertTrue("переходный", MixingPhysics.flowRegime(re) == "переходный")
    }

    @Test fun `flow regime boundaries classic`() {
        // Классические пороги для импеллерного Re: ламинарный <10, переходный 10..10^4, >10^4 турбулентный.
        assertEquals("ламинарный", MixingPhysics.flowRegime(9.9))
        assertEquals("переходный", MixingPhysics.flowRegime(10.0))
        assertEquals("переходный", MixingPhysics.flowRegime(83.33))   // значение SPEC 5.3
        assertEquals("переходный", MixingPhysics.flowRegime(661.96))  // Re Metzner-Ott псевдопластика
        assertEquals("турбулентный", MixingPhysics.flowRegime(10000.1))
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
