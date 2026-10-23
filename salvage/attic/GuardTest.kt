package com.slurrycalc.data

import androidx.room.Room
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.slurrycalc.domain.Capability
import com.slurrycalc.domain.UserRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A2 (восстановлен в B0.1): Guard — отказ в праве пишет событие DENIED в append-only аудит,
 * разрешение — не пишет. Проверка на реальной in-memory БД Room (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GuardTest {

    private lateinit var db: AppDatabase

    @Before fun setup() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    private fun user(role: UserRole) = AuthRepository.User(
        id = 1L, name = "Тест", role = role, createdAt = 0L, lastLoginAt = null,
        failedAttempts = 0, lockedUntil = null, active = true,
    )

    @Test fun `отказ пишет DENIED в аудит с текстом причины`() {
        val res = Guard.check(db, user(UserRole.OBSERVER), Capability.CREATE_BATCH)
        assertEquals(false, res.allowed)
        val rows = Audit.recent(db, 50)
        val denied = rows.firstOrNull { it.action == "DENIED" }
        assertNotNull("событие DENIED записано", denied)
        assertTrue("в причине упомянут запрет", denied!!.details.contains("не имеет права"))
        assertTrue("актор указан", denied.actor.contains("Тест"))
    }

    @Test fun `разрешение ничего не пишет`() {
        val before = db.auditDao().allOrdered().size
        val res = Guard.check(db, user(UserRole.TECHNOLOG), Capability.CREATE_BATCH)
        assertEquals(true, res.allowed)
        assertEquals(before, db.auditDao().allOrdered().size)
    }

    @Test fun `гость получает отказ и событие с актором-гостем`() {
        val res = Guard.check(db, null, Capability.SIGN_OFF)
        assertEquals(false, res.allowed)
        val last = db.auditDao().allOrdered().last()
        assertEquals("DENIED", last.action)
        assertEquals("гость", last.actor)
    }

    @Test fun `цепочка аудита остаётся валидной после серии отказов`() {
        repeat(3) { Guard.check(db, user(UserRole.OBSERVER), Capability.MANAGE_USERS) }
        assertNull(Audit.verifyChain(db))
    }
}
