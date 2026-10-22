package com.slurrycalc.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slurrycalc.domain.MaterialRole
import com.slurrycalc.domain.MixToolType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Тест миграции SharedPreferences → Room (этап 1e).
 *
 * Схема: в prefs пишется эталонный legacy-JSON (формат старого JournalStore/MaterialsDb/
 * RegimeStore), затем MigrationEngine.migrateIfNeeded переносит его во временную Room-базу.
 * Проверяются: количество записей, конверсия единиц (г→мг, мПа·с→Па·с, г/см³→кг/м³),
 * round-trip через domain-маппер, наличие backup-файла, идемпотентность и то,
 * что старые prefs НЕ удалены. В конце тест убирает свои legacy-данные из prefs.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrateJournalWithUnitsAndBackup() {
        val journalPrefs = ctx.getSharedPreferences("journal.db", Context.MODE_PRIVATE)
        val materialPrefs = ctx.getSharedPreferences("materials.db", Context.MODE_PRIVATE)
        val regimePrefs = ctx.getSharedPreferences("regimes.db", Context.MODE_PRIVATE)

        // ---- legacy-данные (единицы UI: г, г/см³, мПа·с) ----
        val entry = JSONObject(
            """
            {
              "id": 7, "ts": 1700000000000, "title": "NMC622 2kg тест",
              "pdc": 50.0, "pwet": 2000.0, "recipe": "NMC 622 96 / SP 2 / PVDF 2 @ NMP",
              "names": ["NMC 622","Super P","PVDF"],
              "dens": [4.67, 1.95, 1.77],
              "pmass": [960.0, 20.0, 20.0],
              "solvName": "NMP (25 °C)", "solvDens": 1.028, "solvMass": 1000.0,
              "regime": "planetary_high", "dur": 30.0, "mixNotes": "порциями",
              "steps": [
                {"action":"Растворение PVDF","tool":"MAGNETIC","dispr":null,"blader":400,
                 "dur":60.0,"temp":45.0,"cmt":"тонкой струйкой"},
                {"action":"Дегазация","tool":"VACUUM","dispr":null,"blader":200,
                 "dur":10.0,"vac":true,"cmt":""}
              ],
              "amass": [961.5, null, 19.8],
              "asolv": 998.0,
              "visc": 3500.0, "viscCond": "300 s-1", "temp": 24.5,
              "grind": "нет комков", "grindUm": 12.0, "measDens": 1.665,
              "comment": "тест", "verdict": "OK",
              "isTask": true, "stepStatuses": ["done","skip"]
            }
            """.trimIndent()
        )
        journalPrefs.edit().putString("entries", JSONArray().put(entry).toString()).commit()

        val mat = JSONObject(
            """{"id":5,"name":"Мой материал","role":"ADDITIVE","density":3.5,"tap":1.2,
               "d50":2.5,"note":"заметка"}"""
        )
        materialPrefs.edit().putString("entries", JSONArray().put(mat).toString()).commit()

        val reg = JSONObject(
            """{"id":"custom_3","name":"Мой режим","tool":"PLANETARY","dispr":1500,
               "blader":400,"time":20.0,"temp":45.0,"desc":"описание"}"""
        )
        regimePrefs.edit().putString("customs", JSONArray().put(reg).toString()).commit()

        // ---- миграция во временную базу ----
        val db = MigrationEngine.openTestDb(ctx)
        val report = MigrationEngine.migrateIfNeeded(ctx, db)

        assertTrue("миграция должна выполниться", report.ran)
        assertEquals(1, report.journalCount)
        assertEquals(1, report.materialCount)
        assertEquals(1, report.regimeCount)
        assertTrue("ошибок миграции быть не должно: ${report.errors}", report.errors.isEmpty())
        assertNotNull("backup-файл должен быть создан", report.backupFile)
        assertTrue(java.io.File(report.backupFile!!).exists())

        // ---- единицы в БД ----
        val row = db.journalDao().all().single()
        assertEquals(2_000_000L, row.wetMassMg)                 // 2000 г → мг
        assertEquals(1_000_000L, row.solventPlannedMg)          // 1000 г → мг
        assertEquals(1028.0, row.solventDensityKgm3, 1e-9)      // г/см³ → кг/м³
        assertEquals(3.5, row.viscPas!!, 1e-12)                 // 3500 мПа·с → Па·с
        assertEquals(1665.0, row.measDensityKgm3!!, 1e-9)       // г/см³ → кг/м³
        assertEquals(998_000L, row.actualSolventMg!!)
        assertTrue(row.isTask)
        assertTrue(row.immutable)

        val amass = JSONArray(row.actualMassesJson)
        assertEquals(961_500L, amass.getLong(0))
        assertTrue(amass.isNull(1))
        assertEquals(19_800L, amass.getLong(2))

        val comps = JSONArray(row.componentsJson)
        assertEquals(4670.0, comps.getJSONObject(0).getDouble("density_kgm3"), 1e-9)
        assertEquals(960_000L, comps.getJSONObject(0).getLong("planned_mg"))

        val steps = Mappers.parseStepsForTest(row.stepsJson)
        assertEquals(2, steps.size)
        assertEquals(MixToolType.MAGNETIC, steps[0].tool)
        assertEquals(400, steps[0].bladeRpm)
        assertEquals(45.0, steps[0].tempC!!, 1e-9)
        assertTrue(steps[1].vacuum)

        // ---- round-trip в domain (обратно в UI-единицы) ----
        val e = Mappers.fromRow(row)
        assertEquals("NMC622 2kg тест", e.title)
        assertEquals(2000.0, e.plannedWetMassG, 1e-9)
        assertEquals(1.028, e.plannedSolventDensity, 1e-9)
        assertEquals(3500.0, e.resultViscosityMPas!!, 1e-6)
        assertEquals(1.665, e.measuredDensityGcm3!!, 1e-9)
        assertEquals(961.5, e.actualComponentMassesG[0]!!, 1e-9)
        assertEquals(null, e.actualComponentMassesG[1])
        assertEquals(19.8, e.actualComponentMassesG[2]!!, 1e-9)
        assertEquals(listOf("done", "skip"), e.stepStatuses)
        assertEquals(4.67, e.componentDensities[0], 1e-9)

        // ---- материалы ----
        val mrow = db.materialDao().all().single()
        assertEquals(3500.0, mrow.densityKgm3, 1e-9)
        assertEquals(2005L, mrow.id)                            // legacy-id смещён на +2000
        val me = Mappers.fromRow(mrow)
        assertEquals(MaterialRole.ADDITIVE, me.role)
        assertEquals("Мой материал", me.name)
        assertEquals(3.5, me.density, 1e-9)

        // ---- режимы ----
        val rrow = db.regimeDao().all().single()
        assertEquals("legacy_custom_3", rrow.id)
        assertEquals(1500, rrow.disperserRpmHint)
        val cr = Mappers.fromRow(rrow)
        assertEquals(MixToolType.PLANETARY, cr.tool)

        // ---- идемпотентность ----
        val report2 = MigrationEngine.migrateIfNeeded(ctx, db)
        assertTrue("повторная миграция не выполняется", !report2.ran)
        assertEquals(1, db.journalDao().all().size)

        // ---- старые prefs НЕ удалены ----
        assertNotNull(journalPrefs.getString("entries", null))
        assertNotNull(materialPrefs.getString("entries", null))
        assertNotNull(regimePrefs.getString("customs", null))

        // очистка за собой: иначе боевая миграция подхватит тестовые данные
        journalPrefs.edit().remove("entries").commit()
        materialPrefs.edit().remove("customs").commit() // no-op guard
        materialPrefs.edit().remove("entries").commit()
        regimePrefs.edit().remove("customs").commit()
        db.close()
    }
}
