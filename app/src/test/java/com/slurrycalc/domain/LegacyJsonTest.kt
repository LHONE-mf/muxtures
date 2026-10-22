package com.slurrycalc.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты формата legacy-SharedPreferences (этап 1e).
 * LegacyJson воспроизводит формат старых JournalStore/MaterialsDb/RegimeStore:
 * ключи "entries"/"customs", UI-единицы (г, г/см³, мПа·с), round-trip без потерь.
 */
class LegacyJsonTest {

    @Test fun `journal entry round trip keeps all fields`() {
        val e = JournalEntry(
            id = 42L,
            timestampMs = 1_700_000_000_000L,
            title = "NMC622 партия #7",
            plannedDryContentPct = 50.0,
            plannedWetMassG = 2000.0,
            recipeSummary = "96/2/2 NMC/SuperP/PVDF",
            componentNames = listOf("NMC 622", "Super P", "PVDF"),
            componentDensities = listOf(4.7, 1.9, 1.78),
            plannedComponentMassesG = listOf(920.0, 19.2, 19.2),
            plannedSolventName = "NMP",
            plannedSolventDensity = 1.03,
            plannedSolventMassG = 1041.6,
            mixingRegimeId = "planetary-high",
            mixingDurationMin = 45.0,
            mixingNotes = "T<35C",
            steps = listOf(
                MixingStep(
                    id = 1L, action = "Растворение PVDF", tool = MixToolType.PLANETARY,
                    disperserRpm = 1500, bladeRpm = 500, durationMin = 20.0,
                    tempC = 25.0, vacuum = false, comment = "до прозрачности",
                ),
            ),
            actualComponentMassesG = listOf(921.3, 19.1, 19.25),
            actualSolventMassG = 1040.0,
            resultViscosityMPas = 3500.0,
            resultViscosityMpaTime = "шпиндель 3, 20 об/мин",
            resultTemperatureC = 24.5,
            grindLevel = "единичные агломераты",
            grindMaxParticleUm = 25.0,
            measuredDensityGcm3 = 1.66,
            comment = "однородная",
            verdict = "OK",
            isTask = false,
            stepStatuses = listOf("выполнено"),
        )
        val back = LegacyJson.journalEntryFromJson(LegacyJson.journalEntryToJson(e))
        assertEquals(e.id, back.id)
        assertEquals(e.title, back.title)
        assertEquals(e.plannedDryContentPct, back.plannedDryContentPct, 1e-9)
        assertEquals(e.plannedWetMassG, back.plannedWetMassG, 1e-9)
        assertEquals(e.componentNames, back.componentNames)
        assertEquals(e.plannedComponentMassesG.size, back.plannedComponentMassesG.size)
        for (i in e.plannedComponentMassesG.indices)
            assertEquals(e.plannedComponentMassesG[i], back.plannedComponentMassesG[i], 1e-9)
        assertEquals(e.actualComponentMassesG, back.actualComponentMassesG)
        assertEquals(e.actualSolventMassG, back.actualSolventMassG)
        assertEquals(e.resultViscosityMPas!!, back.resultViscosityMPas!!, 1e-9)
        assertEquals(e.measuredDensityGcm3!!, back.measuredDensityGcm3!!, 1e-9)
        assertEquals(1, back.steps.size)
        assertEquals("Растворение PVDF", back.steps[0].action)
        assertEquals(1500, back.steps[0].disperserRpm)
        assertEquals(500, back.steps[0].bladeRpm)
        assertEquals(e.verdict, back.verdict)
        assertEquals(e.stepStatuses, back.stepStatuses)
    }

    @Test fun `null actual mass survives round trip`() {
        val e = JournalEntry(
            id = 7L, timestampMs = 0L, title = "без факта",
            plannedDryContentPct = 45.0, plannedWetMassG = 500.0,
            recipeSummary = "", componentNames = listOf("LFP", "PVDF"),
            componentDensities = listOf(3.6, 1.78),
            plannedComponentMassesG = listOf(200.0, 4.5),
            plannedSolventName = "NMP", plannedSolventDensity = 1.03,
            plannedSolventMassG = 295.5, mixingRegimeId = "manual",
            mixingDurationMin = null, mixingNotes = "",
            actualComponentMassesG = listOf(null, 4.5), actualSolventMassG = null,
        )
        val back = LegacyJson.journalEntryFromJson(LegacyJson.journalEntryToJson(e))
        assertNull(back.actualComponentMassesG[0])
        assertEquals(4.5, back.actualComponentMassesG[1]!!, 1e-9)
        assertNull(back.actualSolventMassG)
    }

    @Test fun `material round trip keeps optional parameters`() {
        val m = MaterialEntry(
            id = 100L, name = "Своя добавка", role = MaterialRole.ADDITIVE, density = 2.3,
            tapDensity = 0.4, particleSizeD50Um = 8.0, betAreaM2g = 400.0,
            moisturePpm = 120, dryingTempC = 120.0, capacityMahG = null,
            conductivityScm = null, viscosityMPas = null, note = "для теста", custom = true,
        )
        val back = LegacyJson.materialFromJson(LegacyJson.materialToJson(m))
        assertEquals(m.name, back.name)
        assertEquals(m.role, back.role)
        assertEquals(m.density, back.density, 1e-9)
        assertEquals(m.tapDensity!!, back.tapDensity!!, 1e-9)
        assertEquals(m.particleSizeD50Um!!, back.particleSizeD50Um!!, 1e-9)
        assertEquals(m.moisturePpm, back.moisturePpm)
        assertNull(back.capacityMahG)
        assertEquals(m.note, back.note)
        assertEquals(true, back.custom)
    }

    @Test fun `regime round trip keeps hints`() {
        val r = CustomRegime(
            id = "my-regime", name = "Мой режим", tool = MixToolType.VACUUM,
            disperserRpmHint = 800, bladeRpmHint = 200, timeMinHint = 30.0,
            tempLimitC = 40.0, description = "медленно + вакуум",
        )
        val back = LegacyJson.regimeFromJson(LegacyJson.regimeToJson(r))
        assertEquals(r.id, back.id)
        assertEquals(r.name, back.name)
        assertEquals(r.tool, back.tool)
        assertEquals(r.disperserRpmHint, back.disperserRpmHint)
        assertEquals(r.tempLimitC!!, back.tempLimitC!!, 1e-9)
        assertEquals(r.description, back.description)
    }

    @Test fun `keys match legacy store constants`() {
        // старые хранилища писали массив под этими ключами — не сломать миграцию
        assertEquals("entries", LegacyJson.KEY_ENTRIES)
        assertEquals("customs", LegacyJson.KEY_CUSTOMS)
    }
}
