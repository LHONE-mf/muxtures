package com.slurrycalc.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.slurrycalc.domain.JournalEntry
import com.slurrycalc.domain.UserRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A4 (восстановлен в B0.1): обновление записи журнала требует ОБЯЗАТЕЛЬНУЮ причину;
 * в аудит попадает diff «было → стало» + причина. Пустая/пробельная причина = отказ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UpdateJournalGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var journal: JournalRepository
    private val technologist = AuthRepository.User(
        id = 1L, name = "Технолог", role = UserRole.TECHNOLOG, createdAt = 0L,
        lastLoginAt = null, failedAttempts = 0, lockedUntil = null, active = true,
    )

    @Before fun setup() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        journal = JournalRepository(db)
    }

    @After fun teardown() = db.close()

    private fun entry(verdict: String = "") = JournalEntry(
        id = 0L, timestampMs = 0L, title = "NMC622 #1",
        plannedDryContentPct = 50.0, plannedWetMassG = 2000.0,
        recipeSummary = "96/2/2", componentNames = listOf("NMC 622"),
        componentDensities = listOf(4.7), plannedComponentMassesG = listOf(960.0),
        plannedSolventName = "NMP", plannedSolventDensity = 1.03, plannedSolventMassG = 1000.0,
        mixingRegimeId = "planetary", mixingDurationMin = 45.0, mixingNotes = "",
        verdict = verdict,
    )

    @Test fun `пустая причина — обновление отклонено`() {
        val saved = journal.add(entry(), technologist)!!
        val updated = saved.copy(comment = "доввели NMP")
        assertFalse(journal.update(updated, technologist, reason = ""))
        assertFalse(journal.update(updated, technologist, reason = "   "))
        // запись не изменилась
        assertEquals("", journal.byId(saved.id)!!.comment)
        assertTrue(Audit.recent(db, 50).any { it.action == "UPDATE_DENIED" })
    }

    @Test fun `с указанием причины — обновление проходит и пишется diff было-стало`() {
        val saved = journal.add(entry(), technologist)!!
        val updated = saved.copy(comment = "доввели NMP 40 г", measuredDensityGcm3 = 1.67)
        assertTrue(journal.update(updated, technologist, reason = "фактическая плотность ниже плана"))
        val fresh = journal.byId(saved.id)!!
        assertEquals("доввели NMP 40 г", fresh.comment)
        assertEquals(1.67, fresh.measuredDensityGcm3!!, 1e-9)
        val upd = Audit.recent(db, 50).first { it.action == "BATCH_UPDATE" }
        assertNotNull(upd)
        assertTrue("причина в аудите", upd.details.contains("причина: фактическая плотность ниже плана"))
        assertTrue("diff полей в аудите", upd.details.contains("комментарий изменён"))
        assertTrue("id затронутой записи", upd.details.contains("id=${saved.id}"))
    }

    @Test fun `смена вердикта без права SIGN_OFF — отказ с DENIED`() {
        val operator = technologist.copy(id = 2L, name = "Оператор", role = UserRole.OPERATOR)
        val saved = journal.add(entry(), operator)!!
        val toQc = saved.copy(verdict = "OK")
        assertFalse(journal.update(toQc, operator, reason = "замес готов"))
        assertEquals("", journal.byId(saved.id)!!.verdict)
        assertTrue(Audit.recent(db, 50).any { it.action == "DENIED" && it.details.contains("признание годности") })
    }

    @Test fun `вердикт меняет QC, diff показывает было-стало`() {
        val qc = technologist.copy(id = 3L, name = "ОТК", role = UserRole.QC)
        val saved = journal.add(entry(), technologist)!!
        assertTrue(journal.update(saved.copy(verdict = "OK"), qc, reason = "вязкость в допуске"))
        val upd = Audit.recent(db, 50).first { it.action == "BATCH_UPDATE" }
        assertTrue("вердикт было→стало", upd.details.contains("вердикт:  → OK"))
    }

    @Test fun `цепочка аудита остаётся валидной после обновлений`() {
        val saved = journal.add(entry(), technologist)!!
        journal.update(saved.copy(comment = "x"), technologist, reason = "правка факта")
        journal.update(saved.copy(comment = "y"), technologist, reason = "вторая правка")
        assertNull(Audit.verifyChain(db))
    }
}
