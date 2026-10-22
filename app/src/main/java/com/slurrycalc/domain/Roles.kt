package com.slurrycalc.domain

/**
 * Этап 1f (SPEC п.1 «Роли и вход», чек-лист раздела 8):
 * роли, права проверяются в слое domain — UI только отражает запрет,
 * репозиторий при каждом изменении сверяет текущую роль с Capability.
 */
enum class UserRole(val label: String) {
    ADMIN("Администратор"),
    TECHNOLOG("Технолог"),
    OPERATOR("Оператор"),
    QC("ОТК/QC"),
    OBSERVER("Наблюдатель"),
}

/** Права (гранулярно; «скрытие кнопок» — не защита, проверка здесь и в data-слое) */
enum class Capability {
    CALCULATE,            // пользоваться калькулятором
    MANAGE_MATERIALS,     // добавлять/удалять материалы
    MANAGE_REGIMES,       // добавлять/удалять режимы смешивания
    CREATE_BATCH,         // сохранять новый замес в журнал
    EDIT_ACTUALS,         // вносить фактические массы/этапы в существующую запись
    SIGN_OFF,             // вердикт ОТК (OK / доработка / брак)
    VIEW_AUDIT,           // просмотр журнала аудита
    MANAGE_USERS,         // пользователи, PIN, сброс
    PURGE_LEGACY,         // удаление legacy-данных (этап 1e)
}

object RolePermissions {
    private val map: Map<UserRole, Set<Capability>> = mapOf(
        UserRole.ADMIN to Capability.entries.toSet(),
        UserRole.TECHNOLOG to setOf(
            Capability.CALCULATE, Capability.MANAGE_MATERIALS, Capability.MANAGE_REGIMES,
            Capability.CREATE_BATCH, Capability.EDIT_ACTUALS, Capability.VIEW_AUDIT,
        ),
        UserRole.OPERATOR to setOf(
            Capability.CALCULATE, Capability.CREATE_BATCH, Capability.EDIT_ACTUALS,
        ),
        UserRole.QC to setOf(
            Capability.SIGN_OFF, Capability.VIEW_AUDIT,
        ),
        UserRole.OBSERVER to setOf(
            Capability.VIEW_AUDIT,
        ),
    )

    fun can(role: UserRole?, cap: Capability): Boolean =
        role != null && map[role]?.contains(cap) == true

    fun capsOf(role: UserRole): Set<Capability> = map[role].orEmpty()
}

/** Результат проверки права — для сообщения пользователю и записи в аудит */
data class PermissionCheck(val allowed: Boolean, val reason: String) {
    companion object {
        fun denied(role: UserRole?, cap: Capability) = PermissionCheck(
            allowed = false,
            reason = deniedReason(role, cap),
        )

        /** Текст отказа — используется и в UI, и в записи аудита */
        fun deniedReason(role: UserRole?, cap: Capability): String =
            "Роль «${role?.label ?: "не выбран"}» не имеет права «${capLabel(cap)}»"

        val OK = PermissionCheck(true, "")

        fun capLabel(c: Capability): String = when (c) {
            Capability.CALCULATE -> "расчёт"
            Capability.MANAGE_MATERIALS -> "управление материалами"
            Capability.MANAGE_REGIMES -> "управление режимами"
            Capability.CREATE_BATCH -> "создание замеса"
            Capability.EDIT_ACTUALS -> "внесение фактических данных"
            Capability.SIGN_OFF -> "признание годности (ОТК)"
            Capability.VIEW_AUDIT -> "просмотр аудита"
            Capability.MANAGE_USERS -> "управление пользователями"
            Capability.PURGE_LEGACY -> "удаление старых данных"
        }
    }
}
