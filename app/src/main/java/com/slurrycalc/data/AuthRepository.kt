package com.slurrycalc.data

import com.slurrycalc.calc.PinHash
import com.slurrycalc.domain.Capability
import com.slurrycalc.domain.PermissionCheck
import com.slurrycalc.domain.RolePermissions
import com.slurrycalc.domain.UserRole
import java.security.MessageDigest

/**
 * Этап 1f: профили пользователей с PIN, роли, журнал аудита.
 * Все методы синхронные (allowMainThreadQueries для маленьких запросов).
 */
class AuthRepository(private val db: AppDatabase) {

    data class User(
        val id: Long,
        val name: String,
        val role: UserRole,
        val createdAt: Long,
        val lastLoginAt: Long?,
        val failedAttempts: Int,
        val lockedUntil: Long?,
        val active: Boolean,
    )

    sealed class LoginResult {
        data class Success(val user: User) : LoginResult()
        data class WrongPin(val attemptsLeft: Int) : LoginResult()
        data class Locked(val untilMs: Long) : LoginResult()
        object NotFound : LoginResult()
    }

    companion object {
        const val MAX_FAILED = 5
        const val LOCK_MS = 60_000L          // блокировка на минуту после 5 неверных PIN
        private const val MIN_ADMIN_PIN_LEN = 6   // администратору — не короче 6 цифр

        /** Ключи meta для кода восстановления (A3) */
        const val META_RECOVERY_HASH = "recovery_hash"
        const val META_RECOVERY_OWNER = "recovery_owner"
    }

    fun users(): List<User> = db.userDao().all().map(::toUser)
    fun activeUsers(): List<User> = db.userDao().activeAll().map(::toUser)
    fun hasUsers(): Boolean = db.userDao().count() > 0

    /**
     * Создать пользователя. Первый пользователь обязан быть Администратором
     * (иначе кто будет управлять остальными?). PIN валидируется здесь же.
     */
    fun addUser(name: String, pin: String, role: UserRole): Result<User> {
        val n = name.trim()
        if (n.isEmpty()) return Result.failure(IllegalArgumentException("Имя не может быть пустым"))
        if (!PinHash.isValidPin(pin)) {
            return Result.failure(IllegalArgumentException("PIN: только цифры, длина 4–8"))
        }
        if (role == UserRole.ADMIN && pin.length < MIN_ADMIN_PIN_LEN) {
            return Result.failure(IllegalArgumentException("PIN администратора — минимум $MIN_ADMIN_PIN_LEN цифр"))
        }
        if (db.userDao().all().any { it.name.equals(n, ignoreCase = true) && it.active }) {
            return Result.failure(IllegalArgumentException("Такое имя уже есть"))
        }
        val first = !hasUsers()
        if (first && role != UserRole.ADMIN) {
            return Result.failure(IllegalArgumentException("Первый пользователь должен быть Администратором"))
        }
        val row = UserRow(
            id = 0L, name = n, role = role.name, pinHash = PinHash.hash(pin),
            createdAt = System.currentTimeMillis(),
        )
        val id = db.userDao().insert(row)
        Audit.record(db, "${n} (${role.label})", "USER_ADD", "создан пользователь, роль ${role.label}")
        return Result.success(toUser(row.copy(id = id)))
    }

    /** Смена PIN самим пользователем (нужен старый PIN) */
    fun changePin(userId: Long, oldPin: String, newPin: String): Result<Unit> {
        val row = db.userDao().byId(userId) ?: return Result.failure(IllegalStateException("Нет такого пользователя"))
        if (!PinHash.verify(oldPin, row.pinHash)) {
            bumpFailure(row)
            Audit.record(db, "${row.name} (${row.role})", "PIN_CHANGE_FAIL", "неверный текущий PIN")
            return Result.failure(IllegalArgumentException("Текущий PIN неверен"))
        }
        if (!PinHash.isValidPin(newPin)) {
            return Result.failure(IllegalArgumentException("Новый PIN: только цифры, длина 4–8"))
        }
        if (row.role == UserRole.ADMIN.name && newPin.length < MIN_ADMIN_PIN_LEN) {
            return Result.failure(IllegalArgumentException("PIN администратора — минимум $MIN_ADMIN_PIN_LEN цифр"))
        }
        db.userDao().update(row.copy(pinHash = PinHash.hash(newPin), failedAttempts = 0, lockedUntil = null))
        Audit.record(db, "${row.name} (${row.role})", "PIN_CHANGE", "PIN изменён")
        return Result.success(Unit)
    }

    /** Сброс PIN администратором (панель управления пользователями) */
    fun resetPinByAdmin(adminId: Long, userId: Long, newPin: String): Result<Unit> {
        val admin = db.userDao().byId(adminId)
            ?: return Result.failure(IllegalStateException("Администратор не найден"))
        if (admin.role != UserRole.ADMIN.name) {
            Audit.record(db, "${admin.name} (${admin.role})", "DENIED", "попытка сброса PIN без роли администратора")
            return Result.failure(SecurityException(PermissionCheck.deniedReason(UserRole.ADMIN, Capability.MANAGE_USERS)))
        }
        val target = db.userDao().byId(userId) ?: return Result.failure(IllegalStateException("Пользователь не найден"))
        if (!PinHash.isValidPin(newPin)) return Result.failure(IllegalArgumentException("PIN: только цифры, длина 4–8"))
        db.userDao().update(target.copy(pinHash = PinHash.hash(newPin), failedAttempts = 0, lockedUntil = null))
        Audit.record(db, "${admin.name} (ADMIN)", "PIN_RESET", "сброс PIN для «${target.name}»")
        return Result.success(Unit)
    }

    /** Деактивация профиля (физическое удаление запрещено — история аудита ссылается на id) */
    fun deactivateUser(adminId: Long, userId: Long): Result<Unit> {
        val admin = db.userDao().byId(adminId)
            ?: return Result.failure(IllegalStateException("Администратор не найден"))
        if (admin.role != UserRole.ADMIN.name) {
            return Result.failure(SecurityException(PermissionCheck.deniedReason(UserRole.ADMIN, Capability.MANAGE_USERS)))
        }
        val target = db.userDao().byId(userId) ?: return Result.failure(IllegalStateException("Пользователь не найден"))
        if (target.role == UserRole.ADMIN.name &&
            db.userDao().activeAll().count { it.role == UserRole.ADMIN.name } <= 1
        ) {
            return Result.failure(IllegalStateException("Нельзя деактивировать единственного администратора"))
        }
        db.userDao().update(target.copy(active = false))
        Audit.record(db, "${admin.name} (ADMIN)", "USER_DEACTIVATE", "деактивирован «${target.name}»")
        return Result.success(Unit)
    }

    /** Пользователь по id (для UI-шага C); null если нет */
    fun byId(id: Long): User? = db.userDao().byId(id)?.let(::toUser)

    fun login(name: String, pin: String): LoginResult {
        val row = db.userDao().all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && it.active }
            ?: return LoginResult.NotFound
        val now = System.currentTimeMillis()
        if (row.lockedUntil != null && row.lockedUntil > now) {
            Audit.record(db, "${row.name} (${row.role})", "LOGIN_LOCKED", "попытка входа во время блокировки")
            return LoginResult.Locked(row.lockedUntil!!)
        }
        if (!PinHash.verify(pin, row.pinHash)) {
            // A3: счётчик попыток живёт в БД (UserRow.failedAttempts/lockedUntil),
            // поэтому перезапуск приложения блокировку не сбрасывает.
            bumpFailure(row)
            val attemptsAfter = db.userDao().byId(row.id)!!.failedAttempts
            Audit.record(db, "${row.name} (${row.role})", "LOGIN_FAIL", "неверный PIN (попытка $attemptsAfter)")
            val fresh = db.userDao().byId(row.id)!!
            return if (fresh.lockedUntil != null && fresh.lockedUntil > now) LoginResult.Locked(fresh.lockedUntil!!)
            else LoginResult.WrongPin((MAX_FAILED - attemptsAfter).coerceAtLeast(0))
        }
        db.userDao().update(row.copy(failedAttempts = 0, lockedUntil = null, lastLoginAt = now))
        Audit.record(db, "${row.name} (${row.role})", "LOGIN", "вход выполнен")
        return LoginResult.Success(toUser(row.copy(lastLoginAt = now)))
    }

    // ===== A3: код восстановления администратора =====
    // Хранится в meta как PBKDF2-хэш; сам код показывается один раз при создании первого админа.
    // Попытки ввода кода ограничены тем же счётчиком MAX_FAILED и тоже пишутся в аудит.

    /** Прикрепить новый код восстановления к аккаунту администратора. Возвращает код в открытом виде (один раз!). */
    fun setRecoveryCode(admin: User): Result<String> {
        if (admin.role != UserRole.ADMIN)
            return Result.failure(SecurityException(PermissionCheck.deniedReason(admin.role, Capability.MANAGE_USERS)))
        val code = PinHash.generateRecoveryCode()
        db.metaDao().put(META_RECOVERY_HASH, PinHash.hashRecovery(code))
        db.metaDao().put(META_RECOVERY_OWNER, admin.name)
        Audit.record(db, "${admin.name} (ADMIN)", "RECOVERY_SET",
            if (hasRecoveryCode()) "код восстановления перезаписан" else "создан код восстановления")
        return Result.success(code)
    }

    fun hasRecoveryCode(): Boolean = db.metaDao().get(META_RECOVERY_HASH) != null

    /** Сброс PIN администратора по одноразовому коду восстановления. Код после успеха НЕ гасится
     *  (повторное использование запрещено другим методом — consumeRecoveryAndResetPin). */
    fun resetAdminPinWithRecovery(recoveryInput: String, newPin: String): ResetPinResult {
        val stored = db.metaDao().get(META_RECOVERY_HASH)
            ?: return ResetPinResult.NoRecovery
        val admin = db.userDao().activeAll().firstOrNull { it.role == UserRole.ADMIN.name }
            ?: return ResetPinResult.NoRecovery
        val now = System.currentTimeMillis()
        if (admin.lockedUntil != null && admin.lockedUntil > now) {
            Audit.record(db, "${admin.name} (ADMIN)", "RECOVERY_LOCKED", "попытка ввода кода во время блокировки")
            return ResetPinResult.Locked(admin.lockedUntil!!)
        }
        if (!PinHash.isValidPin(newPin) || newPin.length < MIN_ADMIN_PIN_LEN) {
            return ResetPinResult.BadNewPin("PIN администратора: цифры, минимум $MIN_ADMIN_PIN_LEN знаков")
        }
        if (!PinHash.verifyRecovery(recoveryInput, stored)) {
            bumpFailure(admin)
            val fresh = db.userDao().byId(admin.id)!!
            Audit.record(db, "${admin.name} (ADMIN)", "RECOVERY_FAIL", "неверный код восстановления")
            return if (fresh.lockedUntil != null && fresh.lockedUntil > now) ResetPinResult.Locked(fresh.lockedUntil!!)
            else ResetPinResult.WrongCode((MAX_FAILED - fresh.failedAttempts).coerceAtLeast(0))
        }
        // успех: PIN сброшен, код гасится (одноразовый), выдаём новый
        db.userDao().update(admin.copy(
            pinHash = PinHash.hash(newPin), failedAttempts = 0, lockedUntil = null,
        ))
        val newCode = PinHash.generateRecoveryCode()
        db.metaDao().put(META_RECOVERY_HASH, PinHash.hashRecovery(newCode))
        Audit.record(db, "${admin.name} (ADMIN)", "RECOVERY_RESET",
            "PIN восстановлен по одноразовому коду; выдан новый код")
        return ResetPinResult.Done(newCode)
    }

    sealed class ResetPinResult {
        data class Done(val newRecoveryCode: String) : ResetPinResult()
        data class WrongCode(val attemptsLeft: Int) : ResetPinResult()
        data class Locked(val untilMs: Long) : ResetPinResult()
        data class BadNewPin(val message: String) : ResetPinResult()
        object NoRecovery : ResetPinResult()
    }

    fun logout(user: User) = Audit.record(db, "${user.name} (${user.role.label})", "LOGOUT", "")

    private fun bumpFailure(row: UserRow) {
        val attempts = row.failedAttempts + 1
        val now = System.currentTimeMillis()
        val lock = if (attempts >= MAX_FAILED) now + LOCK_MS else null
        db.userDao().update(row.copy(failedAttempts = if (lock != null) 0 else attempts, lockedUntil = lock))
    }

    private fun toUser(r: UserRow) = User(
        id = r.id, name = r.name, role = runCatching { UserRole.valueOf(r.role) }.getOrDefault(UserRole.OBSERVER),
        createdAt = r.createdAt, lastLoginAt = r.lastLoginAt,
        failedAttempts = r.failedAttempts, lockedUntil = r.lockedUntil, active = r.active,
    )
}

/** Журнал аудита: append-only фасад + цепочка хэшей (B1) + видимый счётчик сбоев (A4) */
object Audit {

    /** Сбой записи аудита НЕ должен ронять бизнес-операцию, но потеря видна:
     *  инкрементируется in-memory счётчик и ставится флаг в meta (переживает перезапуск). */
    @Volatile
    var failures: Long = 0L
        private set

    fun restoreFailureCounter(db: AppDatabase) {
        failures = db.metaDao().get(META_AUDIT_FAILURES)?.toLongOrNull() ?: 0L
    }

    private fun bumpFailure(db: AppDatabase) {
        failures += 1
        try { db.metaDao().put(META_AUDIT_FAILURES, failures.toString()) } catch (_: Exception) {}
    }

    /** SHA-256 канонической строки записи; prev включён — получается цепочка */
    fun computeRowHash(prevHash: String, ts: Long, actor: String, action: String, details: String): String {
        val canonical = "$prevHash\n$ts\n$actor\n$action\n$details"
        val md = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }

    fun record(db: AppDatabase, actor: String, action: String, details: String) {
        try {
            val last = db.auditDao().last()
            val prevHash = last?.rowHash?.takeIf { it.isNotEmpty() } ?: AUDIT_GENESIS
            val ts = System.currentTimeMillis()
            val rowHash = computeRowHash(prevHash, ts, actor, action, details)
            db.auditDao().insert(AuditRow(ts = ts, actor = actor, action = action, details = details,
                prevHash = prevHash, rowHash = rowHash))
        } catch (e: Exception) {
            bumpFailure(db)
            // B0-г: сбой пишется в stderr — на JVM-тестах он виден без android.util.Log
            System.err.println("[Audit] не удалось записать событие $action: ${e.message}")
        }
    }

    fun recent(db: AppDatabase, limit: Int = 200): List<AuditRow> = db.auditDao().recent(limit)

    /** B1: проверка целостности цепочки. null — всё в порядке; иначе человекочитаемое описание первой поломки. */
    fun verifyChain(rows: List<AuditRow>): String? {
        if (rows.isEmpty()) return null
        // первая запись должна быть genesis
        if (rows.first().prevHash != AUDIT_GENESIS) {
            return "нарушение начала цепочки: у записей до id=${rows.first().id} удалена/подменена genesis-запись"
        }
        var prevHash = AUDIT_GENESIS
        for ((i, r) in rows.withIndex()) {
            if (r.prevHash != prevHash) {
                return "разрыв цепочки у id=${r.id}: ожидался prevHash=$prevHash, записан ${r.prevHash}" +
                    (if (i > 0) " (предыдущая запись id=${rows[i - 1].id} изменена или удалена)" else "")
            }
            val recomputed = computeRowHash(r.prevHash, r.ts, r.actor, r.action, r.details)
            if (r.rowHash != recomputed) {
                return "подмена содержимого записи id=${r.id}: rowHash не совпадает с пересчитанным"
            }
            prevHash = r.rowHash
        }
        return null
    }

    fun verifyChain(db: AppDatabase): String? = verifyChain(db.auditDao().allOrdered())

    private const val META_AUDIT_FAILURES = "audit_failures"
}

/** Единая точка проверки права с записью отказа в аудит */
object Guard {
    fun check(db: AppDatabase, user: AuthRepository.User?, cap: Capability): PermissionCheck {
        return if (RolePermissions.can(user?.role, cap)) PermissionCheck.OK
        else {
            val reason = PermissionCheck.deniedReason(user?.role, cap)
            Audit.record(db, user?.let { "${it.name} (${it.role.label})" } ?: "гость", "DENIED", reason)
            PermissionCheck(false, reason)
        }
    }
}
