package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B0-б: тесты кода восстановления администратора (A3).
 * Формат: 5 групп по 5 символов алфавита без похожих знаков (I/O/0/1/L убраны не были —
 * проверено: в алфавите нет I, O, 0, 1). Ёмкость log2(32^25) ≈ 125 бит.
 */
class RecoveryCodeTest {

    @Test fun `сгенерированный код имеет формат 5 групп по 5 символов`() {
        repeat(50) {
            val code = PinHash.generateRecoveryCode()
            val groups = code.split("-")
            assertEquals("групп должно быть 5", PinHash.RECOVERY_GROUPS, groups.size)
            groups.forEach { g ->
                assertEquals(PinHash.RECOVERY_GROUP_LEN, g.length)
                assertTrue("символ вне алфавита: $g", g.all { PinHash.RECOVERY_ALPHABET.indexOf(it) >= 0 })
            }
            assertTrue(PinHash.isValidRecoveryCode(code))
        }
    }

    @Test fun `алфавит не содержит похожих знаков I O 0 1`() {
        for (c in charArrayOf('I', 'O', '0', '1')) {
            assertFalse("алфавит содержит похожий знак $c", PinHash.RECOVERY_ALPHABET.contains(c))
        }
    }

    @Test fun `ёмкость кода не менее 118 бит`() {
        val bits = PinHash.RECOVERY_GROUPS * PinHash.RECOVERY_GROUP_LEN *
            (Math.log(PinHash.RECOVERY_ALPHABET.length.toDouble()) / Math.log(2.0))
        assertTrue("ёмкость ${bits.toInt()} бит < 118", bits >= 118.0)
    }

    @Test fun `round-trip - верный код проходит проверку хэша`() {
        val code = PinHash.generateRecoveryCode()
        val stored = PinHash.hashRecovery(code)
        assertTrue(PinHash.verifyRecovery(code, stored))
        // ввод с дефисами/регистром нормализуется
        assertTrue(PinHash.verifyRecovery(code.lowercase().replace("-", " "), stored))
    }

    @Test fun `неверный код не проходит`() {
        val code = PinHash.generateRecoveryCode()
        val stored = PinHash.hashRecovery(code)
        val wrong = if (code.first() == 'A') "B" + code.substring(1) else "A" + code.substring(1)
        assertFalse(PinHash.verifyRecovery(wrong, stored))
    }

    @Test fun `два кода различаются (SecureRandom)`() {
        assertNotEquals(PinHash.generateRecoveryCode(), PinHash.generateRecoveryCode())
    }

    @Test fun `нормализация убирает всё кроме букв и цифр`() {
        assertEquals("ABCDE-FGHIJ".replace("-", ""),
            PinHash.normalizeRecoveryInput(" abcde\nfg-hij! "))
    }

    @Test fun `валидация длины - 24 символа не код`() {
        assertFalse(PinHash.isValidRecoveryCode("ABCDE-ABCDE-ABCDE-ABCDE-ABCD"))
        assertFalse(PinHash.isValidRecoveryCode(""))
    }
}
