package com.slurrycalc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slurrycalc.data.AuthRepository
import com.slurrycalc.domain.UserRole

/**
 * Экран входа (этап 1f). Первый запуск — форма создания первого пользователя
 * (обязательно Администратор, PIN >= 6 цифр, проверка в data-слое AuthRepository).
 */
@Composable
fun LoginScreen(vm: SlurryViewModel) {
    val hasUsers = vm.auth.hasUsers()
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.ADMIN) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableStateOf<Long?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (hasUsers) "Вход" else "Создание первого пользователя",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (hasUsers) "Выберите профиль и введите PIN."
                else "Первый пользователь обязан быть Администратором. PIN — не короче 6 цифр.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasUsers) {
                // список активных профилей — выбор тапом, затем PIN
                val users = vm.auth.activeUsers()
                if (users.isNotEmpty()) {
                    Text("Профиль", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        users.forEach { u ->
                            FilterChip(
                                selected = name == u.name,
                                onClick = { name = u.name; error = null },
                                label = { Text(u.name, maxLines = 1) },
                            )
                        }
                    }
                }
                LabeledField(pin, { pin = it.filter { c -> c in '0'..'9' }.take(8) }, "PIN", "4–8 цифр")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && pin.length >= 4,
                    onClick = {
                        when (val res = vm.login(name, pin)) {
                            is AuthRepository.LoginResult.Success -> { /* смена currentUser перестроит UI */ }
                            is AuthRepository.LoginResult.WrongPin -> {
                                error = "Неверный PIN. Осталось попыток: ${res.attemptsLeft}"
                                pin = ""
                            }
                            is AuthRepository.LoginResult.Locked -> {
                                error = "Вход заблокирован на минуту (5 неверных PIN)."
                                lockedUntil = res.untilMs
                            }
                            AuthRepository.LoginResult.NotFound -> error = "Профиль не найден или деактивирован."
                        }
                    },
                ) { Text("Войти") }
            } else {
                LabeledField(name, { name = it }, "Имя", "*")
                ChipFlow(UserRole.entries.map { it.label }, role.label) { sel ->
                    role = UserRole.entries.first { it.label == sel }
                }
                LabeledField(pin, { pin = it.filter { c -> c in '0'..'9' }.take(8) }, "PIN", "цифры, 4–8")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && pin.length >= 4,
                    onClick = {
                        vm.addUser(name, pin, role)
                            .onSuccess { /*currentUser установлен — UI перестроится*/ }
                            .onFailure { error = it.message }
                    },
                ) { Text("Создать") }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            lockedUntil?.let {
                val left = ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
                Text("Блокировка ещё ~${left} с.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Меню текущего пользователя: вход/выход, смена PIN, панель администратора, аудит */
@Composable
fun UserMenuSheet(
    vm: SlurryViewModel,
    onDismiss: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenAudit: () -> Unit,
) {
    var changePinOpen by remember { mutableStateOf(false) }
    val user = vm.currentUser
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (user != null) "${user.name} · ${user.role.label}" else "Пользователь") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (user == null) {
                    Text("Вы работаете без профиля: права ограничены (только просмотр). Войдите, чтобы выполнять операции.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        vm.logout(); onDismiss()
                    }) { Text("Перейти ко входу") }
                } else {
                    Text("Права: ${com.slurrycalc.domain.RolePermissions.capsOf(user.role).size} из " +
                        "${com.slurrycalc.domain.Capability.entries.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { changePinOpen = true }) { Text("Сменить PIN") }
                    if (vm.can(com.slurrycalc.domain.Capability.VIEW_AUDIT)) {
                        OutlinedButton(onClick = { onOpenAudit(); onDismiss() }) { Text("Журнал аудита") }
                    }
                    if (vm.can(com.slurrycalc.domain.Capability.MANAGE_USERS)) {
                        OutlinedButton(onClick = { onOpenAdmin(); onDismiss() }) { Text("Пользователи и роли") }
                    }
                    OutlinedButton(onClick = { vm.logout(); onDismiss() }) { Text("Выйти") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
    if (changePinOpen && user != null) {
        ChangePinDialog(vm, user.id) { changePinOpen = false }
    }
}

@Composable
private fun ChangePinDialog(vm: SlurryViewModel, userId: Long, onDone: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var neu by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Смена PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(old, { old = it.filter { c -> c in '0'..'9' }.take(8) }, "Текущий PIN", "цифры")
                LabeledField(neu, { neu = it.filter { c -> c in '0'..'9' }.take(8) }, "Новый PIN", "4–8 цифр")
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.changePin(userId, old, neu)
                    .onSuccess { err = null; onDone() }
                    .onFailure { err = it.message }
            }) { Text("Сменить") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } },
    )
}

/** Панель администратора: пользователи, роли, сброс PIN, деактивация (без физического удаления) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: SlurryViewModel) {
    val admin = vm.currentUser
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Список пользователей (${vm.auth.users().size})") {
            Text(
                "Удаление профилей запрещено — только деактивация: история аудита ссылается на id.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            vm.auth.users().forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(u.name + if (!u.active) " (деактивирован)" else "", fontWeight = FontWeight.Medium)
                        Text(
                            u.role.label +
                                (u.lastLoginAt?.let { " · вход ${java.text.DateFormat.getTimeInstance().format(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (admin != null && u.id != admin.id) {
                        TextButton(onClick = { vm.resetPin(admin.id, u.id, "") }) { Text("сброс PIN") }
                        if (u.active) {
                            TextButton(onClick = { vm.deactivateUser(admin.id, u.id) }) {
                                Text("деактивировать", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        AddUserCard(vm)
        Text(
            "Все действия здесь пишутся в неизменяемый журнал аудита.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun AddUserCard(vm: SlurryViewModel) {
    SectionCard("Добавить пользователя") {
        var name by remember(vm) { mutableStateOf("") }
        var pin by remember(vm) { mutableStateOf("") }
        var role by remember(vm) { mutableStateOf(UserRole.OPERATOR) }
        var msg by remember(vm) { mutableStateOf<String?>(null) }
        LabeledField(name, { name = it }, "Имя", "*")
        Spacer(Modifier.height(6.dp))
        ChipFlow(UserRole.entries.map { it.label }, role.label) { sel ->
            role = UserRole.entries.first { it.label == sel }
        }
        Spacer(Modifier.height(6.dp))
        LabeledField(pin, { pin = it.filter { c -> c in '0'..'9' }.take(8) }, "PIN", "4–8 цифр; для администратора ≥6")
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            vm.addUser(name, pin, role)
                .onSuccess { msg = "Создан: ${it.name} (${it.role.label})"; name = ""; pin = "" }
                .onFailure { msg = it.message }
        }) { Text("Создать") }
        msg?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Экран журнала аудита (append-only): кто, когда, что сделал; отказы тоже видны */
@Composable
fun AuditScreen(vm: SlurryViewModel) {
    val rows = vm.auditLog(300)
    SectionCard("Журнал аудита (последние ${rows.size})") {
        Text(
            "Неизменяемый: события только добавляются, удаление/правка невозможны на уровне DAO.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        if (rows.isEmpty()) Text("Событий пока нет.")
        LazyColumn(modifier = Modifier.height(480.dp)) {
            items(rows, key = { it.id }) { r ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.ts))} · ${r.actor}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("${r.action}: ${r.details}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
