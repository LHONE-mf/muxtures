package com.slurrycalc.data

import com.slurrycalc.calc.PinHash
import com.slurrycalc.domain.Capability
import com.slurrycalc.domain.PermissionCheck
import com.slurrycalc.domain.RolePermissions
import com.slurrycalc.domain.UserRole

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
            return Result.failure(SecurityException(RolePermissions.deniedReason(UserRole.ADMIN, Capability.MANAGE_USERS)))
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
            return Result.failure(SecurityException(RolePermissions.deniedReason(UserRole.ADMIN, Capability.MANAGE_USERS)))
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

    fun login(name: String, pin: String): LoginResult {
        val row = db.userDao().all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && it.active }
            ?: return LoginResult.NotFound
        val now = System.currentTimeMillis()
        if (row.lockedUntil != null && row.lockedUntil > now) {
            Audit.record(db, "${row.name} (${row.role})", "LOGIN_LOCKED", "попытка входа во время блокировки")
            return LoginResult.Locked(row.lockedUntil!!)
        }
        if (!PinHash.verify(pin, row.pinHash)) {
            val attempts = row.failedAttempts + 1
            val lock = if (attempts >= MAX_FAILED) now + LOCK_MS else null
            db.userDao().update(row.copy(failedAttempts = if (lock != null) 0 else attempts, lockedUntil = lock))
            Audit.record(db, "${row.name} (${row.role})", "LOGIN_FAIL", "неверный PIN (попытка $attempts)")
            return if (lock != null) LoginResult.Locked(lock)
            else LoginResult.WrongPin(MAX_FAILED - attempts)
        }
        db.userDao().update(row.copy(failedAttempts = 0, lockedUntil = null, lastLoginAt = now))
        Audit.record(db, "${row.name} (${row.role})", "LOGIN", "вход выполнен")
        return LoginResult.Success(toUser(row.copy(lastLoginAt = now)))
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

/** Журнал аудита: append-only фасад */
object Audit {
    fun record(db: AppDatabase, actor: String, action: String, details: String) {
        try {
            db.auditDao().insert(AuditRow(ts = System.currentTimeMillis(), actor = actor, action = action, details = details))
        } catch (_: Exception) {
            // аудит не должен ронять бизнес-операцию; но молча терять тоже плохо — пишем в logcat
            android.util.Log.e("Audit", "не удалось записать событие $action: $details")
        }
    }

    fun recent(db: AppDatabase, limit: Int = 200): List<AuditRow> = db.auditDao().recent(limit)
}

/** Единая точка проверки права с записью отказа в аудит */
object Guard {
    fun check(db: AppDatabase, user: AuthRepository.User?, cap: Capability): PermissionCheck {
        return if (RolePermissions.can(user?.role, cap)) PermissionCheck.OK
        else {
            val reason = RolePermissions.deniedReason(user?.role, cap)
            Audit.record(db, user?.let { "${it.name} (${it.role.label})" } ?: "гость", "DENIED", reason)
            PermissionCheck(false, reason)
        }
    }
}
