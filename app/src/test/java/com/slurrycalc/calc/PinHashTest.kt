package com.slurrycalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Этап 1f, тесты PinHash (SPEC раздел 5 / чек-лист A2) */
class PinHashTest {

    @Test fun `round-trip - верный PIN проходит проверку`() {
        val h = PinHash.hash("12345")
        assertTrue(PinHash.verify("12345", h))
    }

    @Test fun `неверный PIN не проходит`() {
        val h = PinHash.hash("12345")
        assertFalse(PinHash.verify("12346", h))
        assertFalse(PinHash.verify("1234", h))
        assertFalse(PinHash.verify("", h))
    }

    @Test fun `хэш содержит формат pbkdf2 iter salt hash`() {
        val h = PinHash.hash("9876")
        val parts = h.split("$")
        assertEquals(4, parts.size)
        assertEquals("pbkdf2", parts[0])
        assertEquals(120_000, parts[1].toInt())
        // соль 16 байт -> base64url без padding = 22 символа
        assertEquals(22, parts[2].length)
        // ключ 256 бит -> 43 символа
        assertEquals(43, parts[3].length)
    }

    @Test fun `соль случайна - два хэша одного PIN различаются`() {
        assertNotEquals(PinHash.hash("1234"), PinHash.hash("1234"))
    }

    @Test fun `повреждённый хэш - verify возвращает false без исключения`() {
        val h = PinHash.hash("12345")
        val brokenFormat = "not-a-valid-hash"
        assertFalse(PinHash.verify("12345", brokenFormat))
        assertFalse(PinHash.verify("12345", ""))
        // подмена payload при правильном формате
        val parts = h.split("$")
        val tampered = "${parts[0]}$${parts[1]}$${parts[2]}\$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertFalse(PinHash.verify("12345", tampered))
        // неизвестный алгоритм
        val unknownAlgo = "bcrypt\$10\$salt\$hash"
        assertFalse(PinHash.verify("12345", unknownAlgo))
    }

    @Test fun `формат PIN 4-8 только цифры`() {
        assertTrue(PinHash.isValidPin("1234"))
        assertTrue(PinHash.isValidPin("12345678"))
        assertFalse(PinHash.isValidPin("123"))          // короче 4
        assertFalse(PinHash.isValidPin("123456789"))    // длиннее 8
        assertFalse(PinHash.isValidPin("12a4"))         // не цифры
        assertFalse(PinHash.isValidPin(""))             // пусто
        assertFalse(PinHash.isValidPin("12 34"))        // пробел
    }
}
