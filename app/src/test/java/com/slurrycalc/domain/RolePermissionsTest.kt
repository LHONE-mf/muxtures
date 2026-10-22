package com.slurrycalc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 1f, матрица прав «каждая роль × каждое право» (A2).
 * Ожидаемый результат для каждой пары задан ЯВНО таблицей ниже —
 * тест не сверяется с самой RolePermissions косвенно, а проверяет по спеке.
 */
class RolePermissionsTest {

    /** Полная матрица: строка — роль, столбцы — права; true/false из ТЗ-интерпретации. */
    private val expected: Map<UserRole, Map<Capability, Boolean>> = mapOf(
        UserRole.ADMIN to Capability.entries.associateWith { true },
        UserRole.TECHNOLOG to mapOf(
            Capability.CALCULATE to true,
            Capability.MANAGE_MATERIALS to true,
            Capability.MANAGE_REGIMES to true,
            Capability.CREATE_BATCH to true,
            Capability.EDIT_ACTUALS to true,
            Capability.SIGN_OFF to false,        // вердикт — только ОТК/админ
            Capability.VIEW_AUDIT to true,
            Capability.MANAGE_USERS to false,    // пользователи — только админ
            Capability.PURGE_LEGACY to false,    // удаление legacy — только админ
        ),
        UserRole.OPERATOR to mapOf(
            Capability.CALCULATE to true,
            Capability.MANAGE_MATERIALS to false,
            Capability.MANAGE_REGIMES to false,
            Capability.CREATE_BATCH to true,
            Capability.EDIT_ACTUALS to true,
            Capability.SIGN_OFF to false,
            Capability.VIEW_AUDIT to false,
            Capability.MANAGE_USERS to false,
            Capability.PURGE_LEGACY to false,
        ),
        UserRole.QC to mapOf(
            Capability.CALCULATE to false,       // ОТК не меняет рецептуры
            Capability.MANAGE_MATERIALS to false,
            Capability.MANAGE_REGIMES to false,
            Capability.CREATE_BATCH to false,
            Capability.EDIT_ACTUALS to false,
            Capability.SIGN_OFF to true,
            Capability.VIEW_AUDIT to true,
            Capability.MANAGE_USERS to false,
            Capability.PURGE_LEGACY to false,
        ),
        UserRole.OBSERVER to mapOf(
            Capability.CALCULATE to false,
            Capability.MANAGE_MATERIALS to false,
            Capability.MANAGE_REGIMES to false,
            Capability.CREATE_BATCH to false,
            Capability.EDIT_ACTUALS to false,
            Capability.SIGN_OFF to false,
            Capability.VIEW_AUDIT to true,       // единственное право наблюдателя
            Capability.MANAGE_USERS to false,
            Capability.PURGE_LEGACY to false,
        ),
    )

    @Test fun `полная матрица роль x право`() {
        for ((role, caps) in expected) {
            assertEquals("матрица должна покрывать все права для $role",
                Capability.entries.size, caps.size)
            for ((cap, want) in caps) {
                assertEquals("$role / $cap", want, RolePermissions.can(role, cap))
            }
        }
    }

    @Test fun `null роль не имеет никаких прав`() {
        for (cap in Capability.entries) {
            assertFalse("null роль не должна иметь $cap", RolePermissions.can(null, cap))
        }
    }

    @Test fun `capsOf согласован с can`() {
        for (role in UserRole.entries) {
            val set = RolePermissions.capsOf(role)
            for (cap in Capability.entries) {
                assertEquals("$role / $cap", set.contains(cap), RolePermissions.can(role, cap))
            }
        }
    }

    @Test fun `отказ содержит понятную причину с названием роли и права`() {
        val d = PermissionCheck.denied(UserRole.OPERATOR, Capability.SIGN_OFF)
        assertFalse(d.allowed)
        assertTrue("причина: ${d.reason}", d.reason.contains("Оператор"))
        assertTrue("причина: ${d.reason}", d.reason.contains("признание годности"))
    }
}
