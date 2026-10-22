package com.slurrycalc.calc

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN/парольные хэши (этап 1f). Чистый Kotlin — тестируется на JVM.
 * PBKDF2-HMAC-SHA256, 120 000 итераций, соль 16 байт (SecureRandom).
 * Формат хранения: "pbkdf2$iter$saltB64$hashB64".
 */
object PinHash {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): String {
        require(pin.isNotEmpty()) { "PIN не может быть пустым" }
        val dk = derive(pin, salt, iterations)
        return "pbkdf2$$iterations\$${b64(salt)}\$${b64(dk)}"
    }

    /** Проверка с постоянным сравнением через MessageDigest.isEqual (не break по символам) */
    fun verify(pin: String, stored: String): Boolean {
        return try {
            val parts = stored.split("$")
            if (parts.size != 4 || parts[0] != "pbkdf2") return false
            val iter = parts[1].toInt()
            val salt = un64(parts[2])
            val expected = un64(parts[3])
            val actual = derive(pin, salt, iter)
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
    }

    /** Валидность PIN: только цифры, 4..8 символов */
    fun isValidPin(pin: String): Boolean =
        pin.length in 4..8 && pin.all { it in '0'..'9' }

    // ===== Код восстановления администратора (A3) =====
    // 5 групп по 5 символов из алфавита без похожих знаков (I/O/0/1 убраны).
    // Ёмкость: 32^25 ≈ 2^125 бит — грубая сила при лимите попыток нереальна.
    val RECOVERY_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val RECOVERY_GROUPS = 5
    const val RECOVERY_GROUP_LEN = 5

    /** Сгенерировать одноразовый код вида XXXXX-XXXXX-XXXXX-XXXXX-XXXXX */
    fun generateRecoveryCode(random: java.util.Random = SecureRandom()): String {
        val sb = StringBuilder()
        repeat(RECOVERY_GROUPS) { g ->
            if (g > 0) sb.append('-')
            repeat(RECOVERY_GROUP_LEN) { sb.append(RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)]) }
        }
        return sb.toString()
    }

    /** Нормализация ввода: убрать всё, кроме букв/цифр, привести к верхнему регистру */
    fun normalizeRecoveryInput(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }

    /** Хэш кода восстановления — тот же PBKDF2, соль своя; сам код не хранится */
    fun hashRecovery(code: String): String = hash(normalizeRecoveryInput(code))

    fun verifyRecovery(rawInput: String, stored: String): Boolean =
        verify(normalizeRecoveryInput(rawInput), stored)

    /** Формат: ровно 5 групп по 5 символов нашего алфавита (дефисы опциональны) */
    fun isValidRecoveryCode(code: String): Boolean {
        val compact = normalizeRecoveryInput(code)
        return compact.length == RECOVERY_GROUPS * RECOVERY_GROUP_LEN &&
            compact.all { RECOVERY_ALPHABET.indexOf(it) >= 0 }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    private fun b64(b: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun un64(s: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(s)
}
