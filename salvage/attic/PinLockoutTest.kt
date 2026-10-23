package com.slurrycalc.data

import android.content.Context
import androidx.room.Room
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
 * A3 (восстановлен в B0.1): блокировка PIN хранится в БД (UserRow.failedAttempts/lockedUntil),
 * 5 неверных попыток -> блок на 60 с; счётчик переживает «перезапуск» (тот же файл БД).
 * Плюс лимит попыток ввода кода восстановления и его запись в аудит (B0.2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PinLockoutTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var auth: AuthRepository

    @Before fun setup() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        auth = AuthRepository(db)
        val r = auth.addUser("Админ", "123456", UserRole.ADMIN)
        assertTrue(r.isSuccess)
    }

    @After fun teardown() = db.close()

    @Test fun `5 неверных PIN подряд дают блокировку`() {
        repeat(4) { i ->
            val res = auth.login("Админ", "0000$((i % 9))")
            assertTrue("попытка $i — WrongPin", res is AuthRepository.LoginResult.WrongPin)
            assertEquals("остаток попыток", 4 - i, (res as AuthRepository.LoginResult.WrongPin).attemptsLeft)
        }
        val fifth = auth.login("Админ", "999999")
        assertTrue("5-я попытка — Locked", fifth is AuthRepository.LoginResult.Locked)
        // верный PIN во время блокировки не пускает
        val ok = auth.login("Админ", "123456")
        assertTrue(ok is AuthRepository.LoginResult.Locked)
    }

    @Test fun `блокировка живёт в БД и переживает перезапуск приложения`() {
        repeat(5) { auth.login("Админ", "000000") }
        // «перезапуск»: новый объект репозитория поверх той же БД
        val auth2 = AuthRepository(db)
        val res = auth2.login("Админ", "123456")
        assertTrue("после перезапуска всё ещё Locked", res is AuthRepository.LoginResult.Locked)
        val until = (res as AuthRepository.LoginResult.Locked).untilMs
        val now = System.currentTimeMillis()
        assertTrue("блок примерно на 60 секунд", until in (now + 30_000L)..(now + 70_000L))
    }

    @Test fun `неудачные попытки пишутся в аудит`() {
        repeat(5) { auth.login("Админ", "000000") }
        val all = Audit.recent(db, 100).filter { it.action != "USER_ADD" }
        // каждая из 5 неудач пишется как LOGIN_FAIL; событие блокировки — при входе ПОСЛЕ блока
        assertEquals("5 событий LOGIN_FAIL", 5, all.count { it.action == "LOGIN_FAIL" })
        assertTrue("после блокировки верный PIN даёт Locked и пишет LOGIN_LOCKED",
            auth.login("Админ", "123456") is AuthRepository.LoginResult.Locked)
        assertTrue(Audit.recent(db, 100).any { it.action == "LOGIN_LOCKED" })
    }

    @Test fun `верный вход сбрасывает счётчик попыток`() {
        auth.login("Админ", "000000")
        auth.login("Админ", "000000")
        val row = db.userDao().all().first()
        assertEquals(2, row.failedAttempts)
        assertTrue(auth.login("Админ", "123456") is AuthRepository.LoginResult.Success)
        assertEquals(0, db.userDao().byId(row.id)!!.failedAttempts)
        assertNull(db.userDao().byId(row.id)!!.lockedUntil)
    }

    // ===== B0.2: попытки кода восстановления ограничены тем же лимитом =====

    @Test fun `5 неверных кодов восстановления блокируют админа и пишут RECOVERY_FAIL`() {
        val admin = auth.activeUsers().first()
        auth.setRecoveryCode(admin) // код не важен — проверяем лимит на НЕВЕРНЫХ вводах
        repeat(4) { i ->
            val r = auth.resetAdminPinWithRecovery("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE$i".take(29), "654321")
            assertTrue("WrongCode ${r}", r is AuthRepository.ResetPinResult.WrongCode)
        }
        val fifth = auth.resetAdminPinWithRecovery("ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ", "654321")
        assertTrue("после 5-й — Locked", fifth is AuthRepository.ResetPinResult.Locked)
        val fails = Audit.recent(db, 200).count { it.action == "RECOVERY_FAIL" }
        assertEquals(5, fails)
        // даже верный код во время блокировки не принимается
        val locked = auth.resetAdminPinWithRecovery("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", "654321")
        assertTrue(locked is AuthRepository.ResetPinResult.Locked)
    }

    @Test fun `верный код восстановления сбрасывает PIN и выдаёт новый одноразовый код`() {
        val admin = auth.activeUsers().first()
        val codeRes = auth.setRecoveryCode(admin)
        assertTrue(codeRes.isSuccess)
        val code = codeRes.getOrThrow()
        assertNotNull(PinHashFix.verifyTrue(code))
        val r = auth.resetAdminPinWithRecovery(code, "654321")
        assertTrue("Done", r is AuthRepository.ResetPinResult.Done)
        val newCode = (r as AuthRepository.ResetPinResult.Done).newRecoveryCode
        assertTrue("новый код отличается", newCode != code)
        // старый код погашен
        val reuse = auth.resetAdminPinWithRecovery(code, "111111")
        assertTrue(reuse is AuthRepository.ResetPinResult.WrongCode)
        // новый PIN работает, счётчик обнулён
        assertTrue(auth.login("Админ", "654321") is AuthRepository.LoginResult.Success)
    }
}

/** Мини-помощник: код проходит собственную валидацию и верификацию хэша */
private object PinHashFix {
    fun verifyTrue(code: String): Boolean =
        com.slurrycalc.calc.PinHash.isValidRecoveryCode(code) &&
        com.slurrycalc.calc.PinHash.verifyRecovery(code, com.slurrycalc.calc.PinHash.hashRecovery(code))
}
