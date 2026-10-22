package com.slurrycalc.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.slurrycalc.domain.BatchMath
import com.slurrycalc.domain.BuiltinRegimes
import com.slurrycalc.domain.JournalEntry
import com.slurrycalc.domain.MaterialEntry
import com.slurrycalc.domain.MaterialRole
import com.slurrycalc.domain.MixToolType
import com.slurrycalc.domain.MixingStep
import com.slurrycalc.domain.SolidComponent
import com.slurrycalc.domain.Solvent
import com.slurrycalc.domain.Solvents
import com.slurrycalc.ui.TaskQr
import com.slurrycalc.domain.TaskTransfer
import com.slurrycalc.domain.ViscosityModel
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    /** текст входящего задания (JSON / base64 / ссылка) — показываем диалог подтверждения */
    private var pendingTask by mutableStateOf<String?>(null)

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) readTaskFromUri(uri) }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> if (uri != null) writeExportedText(uri) }

    private var pendingExport: Pair<String, String>? = null  // fileName to text

    /**
     * Нейтрализуем системный масштаб шрифта Oxygen OS / ColorOS: он по-разному применяется к
     * OutlinedTextField (label/hint), TextButton и обычному Text — из-за чего надписи «съезжают»
     * и обрезаются. Приложение остаётся читаемым благодаря собственному ползунку размера шрифта.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        val cfg = Configuration(newBase.resources.configuration)
        cfg.fontScale = 1.0f
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        try {
            setContent {
                // Сплэш-гейт: ждём завершения фоновой инициализации БД (миграция prefs->Room),
                // чтобы ни один запрос к Room/репозиториям не случился на главном потоке до готовности.
                var ready by remember { mutableStateOf(com.slurrycalc.data.DataHolder.ready()) }
                var err by remember { mutableStateOf(com.slurrycalc.StartupState.error) }
                LaunchedEffect(Unit) {
                    while (!com.slurrycalc.data.DataHolder.ready() && com.slurrycalc.StartupState.error == null) {
                        delay(50)
                    }
                    ready = true
                    err = com.slurrycalc.StartupState.error
                }
                val errNow = err
                if (errNow != null) {
                    StartupErrorScreen(errNow)
                } else if (!ready || !com.slurrycalc.data.DataHolder.ready()) {
                    SplashScreen()
                } else {
                    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
                    // показываем stacktrace предыдущего падения (если был crash.log)
                    val crash = remember {
                        try {
                            val f = java.io.File(app.filesDir, "crash.log")
                            if (f.exists()) { val s = f.readText(); f.delete(); s.take(4000) } else null
                        } catch (_: Throwable) { null }
                    }
                    if (crash != null) {
                        StartupErrorScreen("Прошлое завершение приложения было аварийным:\n$crash")
                    } else {
                        MainApp(app)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("SlurryCalc", "UI init failed", e)
            showFatalError(e)
        }
    }

    @androidx.compose.runtime.Composable
    private fun MainApp(app: android.app.Application) {
        val vm: SlurryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
            key = "slurryVm",
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return SlurryViewModel(app) as T
                }
            },
        )
        var themeMode by androidx.compose.runtime.remember { mutableStateOf(vm.settings.themeMode) }
        var fontScale by androidx.compose.runtime.remember { mutableStateOf(vm.settings.fontScale) }
        SlurryTheme(mode = themeMode, fontScale = fontScale) {
            SlurryScreen(
                vm, onTheme = { themeMode = it }, onFontScale = { fontScale = it },
                onShareText = ::shareText,
                onPickTaskFile = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                onExportFile = { name, text ->
                    pendingExport = name to text
                    exportLauncher.launch(name)
                },
                taskIncoming = pendingTask,
                onTaskConfirmed = { raw ->
                    try {
                        vm.importTask(TaskTransfer.parseIncoming(raw))
                        pendingTask = null
                        tabRequestJournal = true
                    } catch (ex: Exception) {
                        pendingTaskError = ex.message ?: "Не удалось прочитать задание"
                    }
                },
                onTaskDismiss = { pendingTask = null; pendingTaskError = "" },
                taskError = pendingTaskError,
                openJournal = tabRequestJournal,
                onJournalOpened = { tabRequestJournal = false },
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun SplashScreen() {
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            ) {
                androidx.compose.material3.CircularProgressIndicator()
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                androidx.compose.material3.Text("Инициализация базы данных…")
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun StartupErrorScreen(message: String) {
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                androidx.compose.material3.Text(
                    "Не удалось подготовить базу данных",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.Button(onClick = { recreate() }) { Text("Перезапустить") }
            }
        }
    }

    private var pendingTaskError by mutableStateOf("")
    private var tabRequestJournal by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val payload = data.getQueryParameter("d") ?: data.fragment?.removePrefix("d=")
                if (payload != null) {
                    pendingTask = payload
                } else if (data.scheme == "file" || data.scheme == "content") {
                    readTaskFromUri(data)
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                when {
                    text != null -> pendingTask = text
                    uri != null -> readTaskFromUri(uri)
                }
            }
        }
    }

    private fun readTaskFromUri(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: return
            pendingTask = raw
        } catch (e: Exception) {
            pendingTask = null
            pendingTaskError = "Не удалось открыть файл: ${e.message}"
        }
    }

    private fun writeExportedText(uri: Uri) {
        val (name, text) = pendingExport ?: return
        pendingExport = null
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) { }
    }

    private fun shareText(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Передать задание"))
    }

    private fun showFatalError(e: Throwable) {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        setContentView(
            android.widget.HorizontalScrollView(this).apply {
                addView(
                    android.widget.TextView(this@MainActivity).apply {
                        text = "SlurryCalc: ошибка запуска\n\n$sw"
                        setTextIsSelectable(true)
                        setPadding(32, 32, 32, 32)
                    }
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SlurryCalc", "Uncaught exception", throwable)
            try {
                runOnUiThread { showFatalError(throwable) }
                Thread.sleep(60_000)
            } catch (_: Exception) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}

private fun parseD(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun parseI(s: String): Int? = parseD(s)?.toInt()
private fun fmt(v: Double, digits: Int = 2): String =
    String.format(java.util.Locale.US, "%.${digits}f", v).replace('.', ',')

/** число для поля ввода: без хвостовых нулей */
private fun fmtNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else fmt(v, 2).trimEnd('0').trimEnd(',')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlurryScreen(
    vm: SlurryViewModel,
    onTheme: (AppThemeMode) -> Unit,
    onFontScale: (Float) -> Unit,
    onShareText: (String, String) -> Unit = { _, _ -> },
    onPickTaskFile: () -> Unit = {},
    onExportFile: (String, String) -> Unit = { _, _ -> },
    taskIncoming: String? = null,
    taskError: String = "",
    onTaskConfirmed: (String) -> Unit = {},
    onTaskDismiss: () -> Unit = {},
    openJournal: Boolean = false,
    onJournalOpened: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(if (vm.simpleMode) 2 else 0) }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openJournal) { if (openJournal) { tab = 2; onJournalOpened() } }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("SlurryCalc", fontWeight = FontWeight.Bold) },
                    actions = {
                        // упрощённый режим: только внесение данных по готовому заданию
                        FilterChip(
                            selected = vm.simpleMode,
                            onClick = { vm.toggleSimpleMode(!vm.simpleMode); if (!vm.simpleMode) tab = 0 },
                            label = { Text(if (vm.simpleMode) "Простой" else "Полный") },
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { settingsOpen = true }) { Text("Тема") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    val tabs = if (vm.simpleMode)
                        listOf("Задания", "Журнал")
                    else listOf("Расчёт", "Материалы", "Журнал", "Задания")
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val simple = vm.simpleMode
            when {
                simple && tab == 0 -> TasksTab(vm, onShareText, onPickTaskFile, onExportFile)
                simple && tab == 1 -> JournalTab(vm, onShareText, onExportFile, readOnlyEditor = false)
                !simple && tab == 0 -> CalcTab(vm)
                !simple && tab == 1 -> MaterialsTab(vm)
                !simple && tab == 2 -> JournalTab(vm, onShareText, onExportFile, readOnlyEditor = false)
                !simple && tab == 3 -> TasksTab(vm, onShareText, onPickTaskFile, onExportFile)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        SettingsDialog(vm, settingsOpen, { settingsOpen = false }, onTheme, onFontScale)
        IncomingTaskDialog(taskIncoming, taskError, onTaskConfirmed, onTaskDismiss)
    }
}

/** Диалог подтверждения входящего задания (из QR/файла/ссылки/текста) */
@Composable
private fun IncomingTaskDialog(
    raw: String?,
    error: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (raw == null && error.isEmpty()) return
    val preview = if (raw != null) try {
        val e = TaskTransfer.parseIncoming(raw)
        buildString {
            append("«").append(e.title).append("»\n")
            append(e.componentNames.joinToString(" + "))
            append(" · СО ").append(fmt(e.plannedDryContentPct, 1)).append(" %")
            append(" · партия ").append(fmt(e.plannedWetMassG, 0)).append(" г")
            if (e.steps.isNotEmpty()) append("\nЭтапов: ").append(e.steps.size)
        }
    } catch (ex: Exception) { "⚠ ${ex.message}" } else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (raw != null) "Входящее задание" else "Ошибка импорта") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (raw != null) {
                    Text(preview, style = MaterialTheme.typography.bodyMedium)
                    Text("Задание сохранится в журнал как «задание на выполнение». Расчёт недоступен — только внесение факта и результатов.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (raw != null) Button(onClick = { onConfirm(raw) }) { Text("Принять") }
            else TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Диалог настроек: тема и размер шрифта */
@Composable
private fun SettingsDialog(
    vm: SlurryViewModel,
    open: Boolean,
    onDismiss: () -> Unit,
    onTheme: (AppThemeMode) -> Unit,
    onFontScale: (Float) -> Unit,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Оформление") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Тема", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = vm.settings.themeMode == m,
                            onClick = { vm.setThemeMode(m); onTheme(m) },
                            label = { Text(m.label) },
                        )
                    }
                }
                Text("Размер шрифта: ×${fmt(vm.settings.fontScale.toDouble(), 2)}",
                    style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = vm.settings.fontScale,
                    onValueChange = { v ->
                        val s = (Math.round(v * 20) / 20f).coerceIn(0.8f, 1.6f)
                        vm.setFontScale(s); onFontScale(s)
                    },
                    valueRange = 0.8f..1.6f,
                    steps = 15,
                )
                Text("Крупнее — текст подписей и подсказок переносится по словам, поля остаются читаемыми.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

// ============================ общие виджеты ============================

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Поле ввода: подпись — НАД полем, короткая подсказка/единицы — СПРАВА от подписи.
 * maxLines в тексте поля и wordBreak по словам — чтобы на прошивках с крупным
 * системным шрифтом (ColorOS/OxygenOS «Максимальный») ничего не съезжало.
 */
@Composable
fun LabeledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (!hint.isNullOrBlank()) {
                Text(hint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
            }
        }
        Spacer(Modifier.height(3.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
                
            ),
        )
    }
}

/** Строка результата: метка слева, значение справа; при тесноте значение переносится */
@Composable
private fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp))
    }
}

/** Чипы рядами по 2 — чтобы длинные названия умещались */
@Composable
private fun ChipFlow(names: List<String>, selected: String?, onSelect: (String) -> Unit) {
    names.chunked(2).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rowItems.forEach { n ->
                FilterChip(
                    selected = selected == n,
                    onClick = { onSelect(n) },
                    label = { Text(n, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

// ============================ TAB: РАСЧЁТ ============================

@Composable
private fun CalcTab(vm: SlurryViewModel) {
    if (vm.simpleMode) {
        // упрощённый режим: расчёт выключен, только внесение данных по готовому заданию
        SectionCard("Упрощённый режим включён") {
            Text(
                "Калькулятор замеса недоступен. Работайте по готовому заданию: вкладка «Задания» — " +
                        "принять/открыть полученное задание, «Журнал» — внести фактические массы и результаты.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    PresetsCard(vm)
    SolidsCard(vm)
    SolventCard(vm)
    BatchCard(vm)
    MixingCard(vm)
    ViscosityCard(vm)
    ResultCard(vm)
    SaveToJournalCard(vm)
}

@Composable
private fun PresetsCard(vm: SlurryViewModel) {
    SectionCard("Пресеты составов") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.loadPreset(cathode = true) }) { Text("Катод (NMC)") }
            Button(onClick = { vm.loadPreset(cathode = false) }) { Text("Анод (графит)") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Катод: 96/2/2 AM/SP/PVDF в NMP, 50 % СО.\nАнод: 95/2/1.5/1.5 Графит/SP/CMC/SBR в воде, 45 % СО.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SolidsCard(vm: SlurryViewModel) {
    SectionCard("Твёрдая фаза (сухие компоненты)") {
        vm.solids.forEachIndexed { i, c ->
            ComponentRow(
                component = c,
                materials = vm.materialsDb.byRole(MaterialRole.ACTIVE) +
                        vm.materialsDb.byRole(MaterialRole.CONDUCTIVE) +
                        vm.materialsDb.byRole(MaterialRole.BINDER) +
                        vm.materialsDb.byRole(MaterialRole.ADDITIVE),
                onPickMaterial = { entry -> vm.assignMaterial(i, entry) },
                onChange = { name, densityStr, fracStr ->
                    vm.updateComponent(
                        index = i,
                        name = name,
                        density = densityStr?.let { parseD(it) } ?: c.density,
                        fractionPct = fracStr?.let { parseD(it) } ?: (c.fraction * 100),
                    )
                },
                onRemove = { vm.removeComponent(i) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.addComponent() }) { Text("+ пустая строка") }
            Spacer(Modifier.width(12.dp))
            val sum = vm.fractionSumPct
            Text(
                "Σ долей: ${fmt(sum, 1)} %",
                color = if (vm.fractionsOk || abs(sum - 100) < 5) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Строка компонента: выбор материала из базы + поля с подписями сверху и подсказками справа */
@Composable
private fun ComponentRow(
    component: SolidComponent,
    materials: List<MaterialEntry>,
    onPickMaterial: (MaterialEntry) -> Unit,
    onChange: (name: String?, density: String?, fraction: String?) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember(component) { mutableStateOf(component.name) }
    var density by remember(component) { mutableStateOf(fmt(component.density, 3)) }
    var fraction by remember(component) { mutableStateOf(fmt(component.fraction * 100, 2)) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (component.materialId != null) "✓ ${component.name}" else "${component.name} ▾",
                        maxLines = 1,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    itemsFiltered(materials).forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${m.role.label} · ρ=${fmt(m.density, 2)}" +
                                                (m.tapDensity?.let { " · нас. ${fmt(it, 1)}" } ?: "") +
                                                (m.particleSizeD50Um?.let { " · d50 ${it} мкм" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = { expanded = false; onPickMaterial(m) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LabeledField(
                name, { name = it; onChange(it, null, null) },
                label = "Название", hint = "из базы или своё",
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(
                    density, { density = it; onChange(null, it, null) },
                    label = "ρ истинная", hint = "г/см³",
                    modifier = Modifier.weight(1f),
                )
                LabeledField(
                    fraction, { fraction = it; onChange(null, null, it) },
                    label = "Доля в сухом", hint = "%",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRemove) {
                    Text("удалить", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun itemsFiltered(materials: List<MaterialEntry>): List<MaterialEntry> =
    materials.sortedBy { it.name.lowercase() }.distinctBy { it.name }

@Composable
private fun SolventCard(vm: SlurryViewModel) {
    SectionCard("Растворитель / среда") {
        ChipFlow(
            (Solvents.all.map { it.name } + vm.materialsDb.byRole(MaterialRole.SOLVENT)
                .filter { it.custom }.map { it.name }).distinct(),
            vm.solventName,
        ) { sel ->
            val preset = Solvents.all.firstOrNull { it.name == sel }
            if (preset != null) vm.setSolvent(preset)
            else vm.materialsDb.all().firstOrNull { it.name == sel && it.role == MaterialRole.SOLVENT }?.let {
                vm.setSolvent(Solvent(it.name, it.density, it.viscosityMPas ?: 1.0))
            }
        }
        Spacer(Modifier.height(8.dp))
        var dens by remember(vm.solventName, vm.solventDensity) { mutableStateOf(fmt(vm.solventDensity, 3)) }
        var visc by remember(vm.solventName, vm.solventViscosity) { mutableStateOf(fmt(vm.solventViscosity, 2)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField(dens, {
                dens = it
                parseD(it)?.let { d -> vm.setSolvent(Solvent(vm.solventName, d, vm.solventViscosity)) }
            }, "Плотность р-ля", "г/см³", Modifier.weight(1f))
            LabeledField(visc, {
                visc = it
                parseD(it)?.let { v -> vm.setSolvent(Solvent(vm.solventName, vm.solventDensity, v)) }
            }, "Вязкость η₀", "мПа·с (= сПз)", Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatchCard(vm: SlurryViewModel) {
    SectionCard("Партия и сухой остаток") {
        var dc by remember(vm.dryContentPct) { mutableStateOf(fmt(vm.dryContentPct, 1)) }
        LabeledField(dc, { dc = it; parseD(it)?.let { v -> vm.setDryContent(v) } },
            "Сухой остаток", "обычно 30–70 %, %")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("масса суспензии", "масса сухого", "объём").forEachIndexed { i, label ->
                FilterChip(
                    selected = vm.batchMode == i,
                    onClick = { vm.selectBatchMode(i) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        var bv by remember(vm.batchValue) { mutableStateOf(fmt(vm.batchValue, 0)) }
        LabeledField(bv, { bv = it; parseD(it)?.let { v -> vm.updateBatchValue(v) } },
            "Значение партии",
            when (vm.batchMode) { 0 -> "г мокрой суспензии"; 1 -> "г сухого вещества"; else -> "мл готовой суспензии" })
    }
}

// ---------- карточка режимов смешивания с этапами ----------

@Composable
private fun MixingCard(vm: SlurryViewModel) {
    SectionCard("Режимы смешивания и этапы замеса") {
        Text("Основной режим", style = MaterialTheme.typography.titleSmall)
        Text("аппаратный режим; его параметры — ориентиры для этапов",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(4.dp))
        ChipFlow(vm.regimeNames(), vm.mixingRegimeName) { vm.setMixingRegime(it) }
        BuiltinRegimes.info(vm.mixingRegimeName)?.let { r ->
            Spacer(Modifier.height(6.dp))
            Text("${r.tipSpeedHint} · ${r.timeMinFirst}–${r.timeMinLast} мин" +
                    (r.tempLimitC?.let { " · T° ≤ ${fmt(it, 0)} °C" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(r.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.regimeStore.customList().firstOrNull { it.name == vm.mixingRegimeName }?.let { r ->
            Spacer(Modifier.height(6.dp))
            Text("свой режим · ${r.tool.label}" +
                        (r.disperserRpmHint?.let { " · дисп. $it об/мин" } ?: "") +
                        (r.bladeRpmHint?.let { " · лопасти $it об/мин" } ?: "") +
                        (r.timeMinHint?.let { " · ~${fmt(it, 0)} мин" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Этапы замеса", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.loadSequenceTemplate() }) { Text("шаблон") }
                TextButton(onClick = { vm.addStep() }) { Text("+ этап") }
            }
        }
        Text("типовой порядок: растворение связующего → ввод сажи → ввод активного материала → перетир → дегазация",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(4.dp))

        if (vm.steps.isEmpty()) {
            Text("Этапов нет. Нажмите «+ этап» (добавить вручную) или «шаблон».",
                style = MaterialTheme.typography.bodySmall)
        }
        vm.steps.forEachIndexed { i, s ->
            StepEditor(
                vm = vm,
                step = s,
                regimes = vm.regimeNames(),
                onUpdate = { patch -> vm.updateStep(i, patch) },
                onRemove = { vm.removeStep(i) },
                onMove = { d -> vm.moveStep(i, d) },
                onSaveAsRegime = { vm.saveStepAsRegime(s) },
                canUp = i > 0, canDown = i < vm.steps.lastIndex,
                number = i + 1,
            )
        }
    }
}

/** Карточка этапа: действие, обороты диспергатора/лопастей, время, T°, комментарий */
@Composable
private fun StepEditor(
    vm: SlurryViewModel,
    step: MixingStep,
    regimes: List<String>,
    onUpdate: ((MixingStep) -> MixingStep) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onSaveAsRegime: () -> Unit,
    canUp: Boolean,
    canDown: Boolean,
    number: Int,
) {
    // локальные строки отвязаны от id этапа — не перезаписываются при каждом keystroke
    var action by remember(step.id) { mutableStateOf(step.action) }
    var dispr by remember(step.id) { mutableStateOf(step.disperserRpm?.toString() ?: "") }
    var blader by remember(step.id) { mutableStateOf(step.bladeRpm?.toString() ?: "") }
    var dur by remember(step.id) { mutableStateOf(step.durationMin?.let(::fmtNum) ?: "") }
    var temp by remember(step.id) { mutableStateOf(step.tempC?.let(::fmtNum) ?: "") }
    var comment by remember(step.id) { mutableStateOf(step.comment) }
    var toolExpanded by remember { mutableStateOf(false) }
    var regimeExpanded by remember { mutableStateOf(false) }
    var addRegimeDialog by remember(step.id) { mutableStateOf(false) }

    if (addRegimeDialog) {
        AddRegimeDialog(
            presetName = step.action,
            presetTool = step.tool,
            presetDispr = dispr,
            presetBlader = blader,
            presetDur = dur,
            presetTemp = temp,
            presetVacuum = step.vacuum,
            presetDesc = comment,
            onDismiss = { addRegimeDialog = false },
            onSave = { name, tool, d, b, t, tc, vac, desc ->
                vm.regimeStore.add(name, tool, parseI(d), parseI(b), parseD(t), parseD(tc), desc)
                // сразу применим параметры нового режима к этому этапу
                onUpdate { s -> s.copy(tool = tool, disperserRpm = parseI(d), bladeRpm = parseI(b),
                    durationMin = parseD(t), tempC = parseD(tc), vacuum = vac) }
                addRegimeDialog = false
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.30f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                LabeledField(
                    action, { action = it; onUpdate { s -> s.copy(action = action) } },
                    label = "Действие / этап", hint = "напр.: растворение PVDF, ввод сажи, перетир",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { toolExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Оборудование: ${step.tool.label} ▾", maxLines = 1,
                        style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(expanded = toolExpanded, onDismissRequest = { toolExpanded = false }) {
                    MixToolType.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(t.label) }, onClick = {
                            toolExpanded = false
                            onUpdate { s -> s.copy(tool = t) }
                        })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(dispr, {
                    dispr = it
                    onUpdate { s -> s.copy(disperserRpm = parseI(it)) }
                }, "Обороты диспергатора", "об/мин", Modifier.weight(1f))
                LabeledField(blader, {
                    blader = it
                    onUpdate { s -> s.copy(bladeRpm = parseI(it)) }
                }, "Обороты лопастей", "об/мин", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(dur, {
                    dur = it
                    onUpdate { s -> s.copy(durationMin = parseD(it)) }
                }, "Время перемешивания", "мин", Modifier.weight(1f))
                LabeledField(temp, {
                    temp = it
                    onUpdate { s -> s.copy(tempC = parseD(it)) }
                }, "Температура", "°C", Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            // наличие вакуума на этапе
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = step.vacuum,
                    onCheckedChange = { v -> onUpdate { s -> s.copy(vacuum = v) } },
                )
                Text("Вакуум (дегазация / работа под разрежением)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (step.vacuum) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            LabeledField(comment, { comment = it; onUpdate { s -> s.copy(comment = comment) } },
                "Комментарий к действию", "что и как вносим, контроль готовности", singleLine = false)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    TextButton(onClick = { onMove(-1) }, enabled = canUp) { Text("↑") }
                    TextButton(onClick = { onMove(+1) }, enabled = canDown) { Text("↓") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { addRegimeDialog = true }) { Text("+ режим") }
                    TextButton(onClick = onSaveAsRegime) { Text("→ свой режим") }
                    TextButton(onClick = onRemove) {
                        Text("×", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // быстрый пресет: подставить параметры выбранного режима в этап
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { regimeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Подставить параметры режима…", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = regimeExpanded, onDismissRequest = { regimeExpanded = false }) {
                    regimes.forEach { rn ->
                        DropdownMenuItem(text = { Text(rn) }, onClick = {
                            regimeExpanded = false
                            val custom = vm.regimeStore.byName(rn)
                            val builtin = BuiltinRegimes.info(rn)
                            val toolNow = custom?.tool ?: builtin?.tool ?: null
                            onUpdate { s ->
                                s.copy(
                                    tool = toolNow ?: s.tool,
                                    disperserRpm = custom?.disperserRpmHint ?: s.disperserRpm,
                                    bladeRpm = custom?.bladeRpmHint ?: s.bladeRpm,
                                    durationMin = custom?.timeMinHint ?: s.durationMin,
                                    tempC = custom?.tempLimitC ?: s.tempC,
                                    vacuum = toolNow == MixToolType.VACUUM || s.vacuum,
                                )
                            }
                        })
                    }
                }
            }
        }
    }
}

/** Диалог ручного добавления режима смешивания (пресета параметров этапа) */
@Composable
private fun AddRegimeDialog(
    presetName: String,
    presetTool: MixToolType,
    presetDispr: String,
    presetBlader: String,
    presetDur: String,
    presetTemp: String,
    presetVacuum: Boolean,
    presetDesc: String,
    onDismiss: () -> Unit,
    onSave: (name: String, tool: MixToolType, dispr: String, blader: String,
             dur: String, temp: String, vacuum: Boolean, desc: String) -> Unit,
) {
    var name by remember { mutableStateOf(presetName) }
    var tool by remember { mutableStateOf(presetTool) }
    var dispr by remember { mutableStateOf(presetDispr) }
    var blader by remember { mutableStateOf(presetBlader) }
    var dur by remember { mutableStateOf(presetDur) }
    var temp by remember { mutableStateOf(presetTemp) }
    var vacuum by remember { mutableStateOf(presetVacuum) }
    var desc by remember { mutableStateOf(presetDesc) }
    var toolExpanded by remember { mutableStateOf(false) }

    AlertDialog(onDismissRequest = onDismiss, confirmButton = {
        Button(onClick = { onSave(name, tool, dispr, blader, dur, temp, vacuum, desc) }) { Text("Сохранить режим") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Новый режим смешивания") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledField(name, { name = it }, "Название режима", "напр.: «Планетарная 2500/800»")
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { toolExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Оборудование: ${tool.label} ▾", maxLines = 1,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    DropdownMenu(expanded = toolExpanded, onDismissRequest = { toolExpanded = false }) {
                        MixToolType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = {
                                toolExpanded = false; tool = t
                            })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(dispr, { dispr = it }, "Обороты диспергатора", "об/мин", Modifier.weight(1f))
                    LabeledField(blader, { blader = it }, "Обороты лопастей", "об/мин", Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(dur, { dur = it }, "Время", "мин", Modifier.weight(1f))
                    LabeledField(temp, { temp = it }, "T° (лимит)", "°C", Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vacuum, onCheckedChange = { vacuum = it })
                    Text("Вакуум", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                LabeledField(desc, { desc = it }, "Описание", "что делает режим, особенности", singleLine = false)
            }
        })
}

@Composable
private fun ViscosityCard(vm: SlurryViewModel) {
    SectionCard("Модель оценки вязкости") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val labels = mapOf(
                ViscosityModel.NONE to "выкл",
                ViscosityModel.MO to "Муни",
                ViscosityModel.MARON_PIERIS to "Марон–Пирис",
                ViscosityModel.KRIEGER_DOUGHERTY to "Кригер–Догерти",
            )
            ViscosityModel.values().toList().chunked(2).forEach { pair ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEach { m ->
                        val i = ViscosityModel.values().indexOf(m)
                        FilterChip(
                            selected = vm.viscosityModelIndex == i,
                            onClick = { vm.setViscosityModel(i) },
                            label = { Text(labels[m]!!, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Формулы (ηrel = ηсусп / ηр-ля): Муни exp(2,5φ/(1−φ/φm)); Марон–Пирис (1−kφ)⁻²; " +
                "Кригер–Догерти (1−φ/φm)⁻²·⁵, φm ≈ 0,71. Диапазон сдвига ~1–1000 с⁻¹; точность ±2× — ориентир для подбора СО.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(vm: SlurryViewModel) {
    val r = vm.result
    SectionCard("Результат расчёта") {
        if (vm.error.isNotEmpty()) {
            Text(vm.error, color = MaterialTheme.colorScheme.error)
            return@SectionCard
        }
        if (r == null) {
            Text("Заполните параметры партии.")
            return@SectionCard
        }
        Text("Компоненты для навески:", fontWeight = FontWeight.Bold)
        r.componentMassesG.forEach { (name, mass) ->
            Text("• $name — ${fmt(mass, 1)} г")
        }
        Text("• ${vm.solventName} — ${fmt(r.solventMassG, 1)} г", color = MaterialTheme.colorScheme.secondary)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Сухое вещество: ${fmt(r.dryMassG, 1)} г   |   Всего суспензии: ${fmt(r.wetMassG, 1)} г   |   Объём: ${fmt(r.volumeMl, 0)} мл")
        Text("Плотность суспензии: ${fmt(r.slurryDensityGcm3)} г/см³   |   ρ сухого: ${fmt(r.dryDensityGcm3)} г/см³")
        Text("Объёмная доля твёрдой фазы φ: ${fmt(r.solidVolumeFraction * 100, 1)} %")
        r.viscosityEstimateMPas?.let {
            Text("Оценка вязкости: ${fmt(it, 1)} мПа·с (= сПз; ηrel = ${fmt(r.relativeViscosity!!, 2)})")
        }
        r.warnings.forEach { w ->
            Spacer(Modifier.height(4.dp))
            Text("⚠ $w", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SaveToJournalCard(vm: SlurryViewModel) {
    if (vm.result == null) return
    SectionCard("Сохранить как план замеса") {
        var title by remember(vm) { mutableStateOf("") }
        LabeledField(title, { title = it }, "Название замеса", "напр. «NMC622 batch#7»")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.savePlanToJournal(title) }) {
            Text("→ В журнал замесов" + (if (vm.steps.isNotEmpty()) " (${vm.steps.size} эт.)" else ""))
        }
        Spacer(Modifier.height(4.dp))
        Text("Фактические массы и результаты внесёте во вкладке «Журнал».", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ TAB: МАТЕРИАЛЫ ============================

@Composable
private fun MaterialsTab(vm: SlurryViewModel) {
    SectionCard("Добавить материал в базу") {
        var name by remember(vm) { mutableStateOf("") }
        var role by remember(vm) { mutableStateOf(MaterialRole.ACTIVE) }
        var dens by remember(vm) { mutableStateOf("") }
        var tap by remember(vm) { mutableStateOf("") }
        var d50 by remember(vm) { mutableStateOf("") }
        var bet by remember(vm) { mutableStateOf("") }
        var cap by remember(vm) { mutableStateOf("") }
        var moist by remember(vm) { mutableStateOf("") }
        var note by remember(vm) { mutableStateOf("") }

        LabeledField(name, { name = it }, "Название", "* обязательное")
        Spacer(Modifier.height(6.dp))
        ChipFlow(MaterialRole.entries.map { it.label }, role.label) { sel ->
            role = MaterialRole.entries.first { it.label == sel }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField(dens, { dens = it }, "ρ истинная", "г/см³ *", Modifier.weight(1f))
            LabeledField(tap, { tap = it }, "ρ насыпная", "г/см³", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField(d50, { d50 = it }, "d50", "мкм", Modifier.weight(1f))
            LabeledField(bet, { bet = it }, "BET", "м²/г", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField(cap, { cap = it }, "Ёмкость", "мА·ч/г", Modifier.weight(1f))
            LabeledField(moist, { moist = it }, "Влага", "ppm", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        LabeledField(note, { note = it }, "Примечание", "особенности дозировки и т.п.", singleLine = false)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val d = parseD(dens) ?: return@Button
            vm.materialsDb.add(
                name = name,
                role = role,
                density = d,
                extras = mapOf(
                    "tap" to parseD(tap), "d50" to parseD(d50), "bet" to parseD(bet),
                    "cap" to parseD(cap), "visc" to null,
                ),
                moisturePpm = parseD(moist)?.toInt(),
                note = note,
            )
            name = ""; dens = ""; tap = ""; d50 = ""; bet = ""; cap = ""; moist = ""; note = ""
        }) { Text("Добавить в базу") }
        Text("Растворители добавляются чипом на вкладке «Расчёт» (роль «Растворитель» здесь тоже доступен).",
            style = MaterialTheme.typography.bodySmall)
    }

    SectionCard("Каталог и свои материалы (${vm.materialsDb.all().size})") {
        LazyColumn(modifier = Modifier.height(420.dp)) {
            items(vm.materialsDb.all(), key = { "mat-${it.id}" }) { m ->
                MaterialRow(m, onDelete = if (m.custom) {{ vm.materialsDb.remove(m.id) }} else null)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MaterialRow(m: MaterialEntry, onDelete: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (m.custom) Text("свой", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
            onDelete?.let { TextButton(onClick = it) { Text("удалить") } }
        }
        val parts = mutableListOf(m.role.label, "ρ=${fmt(m.density, 2)} г/см³")
        m.tapDensity?.let { parts.add("нас.=${fmt(it, 2)}") }
        m.particleSizeD50Um?.let { parts.add("d50=${fmt(it, 1)} мкм") }
        m.betAreaM2g?.let { parts.add("BET=${fmt(it, 0)} м²/г") }
        m.moisturePpm?.let { parts.add("H2O=$it ppm") }
        m.capacityMahG?.let { parts.add("Q=${fmt(it, 0)} мА·ч/г") }
        m.conductivityScm?.let { parts.add("σ=${fmt(it, 0)} См/см") }
        m.viscosityMPas?.let { parts.add("η=${fmt(it, 2)} мПа·с") }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ TAB: ЖУРНАЛ ============================

@Composable
private fun JournalTab(
    vm: SlurryViewModel,
    onShareText: (String, String) -> Unit = { _, _ -> },
    onExportFile: (String, String) -> Unit = { _, _ -> },
    readOnlyEditor: Boolean = false,
) {
    SectionCard("Экспорт журнала") {
        Text(
            "Выгрузка результатов замеса со всеми режимами: план, фактические массы, этапы " +
                    "смешивания, вязкость/плотность/перетир/вердикт.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                val text = buildString {
                    vm.journal.forEach { e ->
                        append(TaskTransfer.exportText(e, vm.actualFor(e)))
                        append("\n========================================\n\n")
                    }
                }
                onShareText("Журнал замесов", text)
            }, modifier = Modifier.weight(1f)) { Text("текст →") }
            OutlinedButton(onClick = {
                onExportFile("journal-${System.currentTimeMillis()}.csv", TaskTransfer.exportCsv(vm.journal.toList()))
            }, modifier = Modifier.weight(1f)) { Text("CSV ↓") }
        }
    }
    if (vm.journal.isEmpty()) {
        SectionCard("Журнал замесов пуст") {
            Text("Сохраните расчёт во вкладке «Расчёт» кнопкой «→ В журнал замесов», затем заполните этапы, фактические массы и результаты.")
        }
        return
    }
    // сначала активные задания (не выполненные), потом остальные
    val ordered = vm.journal.sortedWith(compareBy({ !(it.isTask && !it.hasActuals()) }, { -it.timestampMs }))
    ordered.forEach { e ->
        JournalEntryCard(vm, e, onShareText, onExportFile)
    }
}

@Composable
private fun JournalEntryCard(
    vm: SlurryViewModel,
    e: JournalEntry,
    onShareText: (String, String) -> Unit,
    onExportFile: (String, String) -> Unit,
) {
    var expanded by remember(e.id, e.timestampMs) { mutableStateOf(false) }
    val openTask = e.isTask && !e.hasActuals()
    SectionCard((if (e.isTask) "📋 ЗАДАНИЕ · " else "") + "${e.dateLabel()} · ${e.title}") {
        Text(e.recipeSummary, style = MaterialTheme.typography.bodySmall)
        Text(
            "План: ${fmt(e.plannedWetMassG, 0)} г · режим: ${vm.regimeName(e.mixingRegimeId)}" +
                    (if (e.hasActuals()) " · ФАКТ внесён ✓"
                     else if (e.isTask) " · ожидает выполнения" else " · факт не внесён"),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                e.hasActuals() -> MaterialTheme.colorScheme.primary
                e.isTask -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            },
        )
        if (e.steps.isNotEmpty()) {
            Text(
                "Этапов: ${e.steps.size}" +
                        (e.mixingDurationMin?.let { " · суммарно ~${fmt(it, 0)} мин" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "свернуть"
                     else if (openTask) "выполнить задание…" else "этапы, факт и результаты…")
            }
            if (!vm.simpleMode && !e.isTask) {
                TextButton(onClick = { vm.loadFromJournal(e) }) { Text("в калькулятор") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                onShareText(e.title, TaskTransfer.exportText(e, vm.actualFor(e)))
            }) { Text("отчёт →") }
            TextButton(onClick = {
                onShareText(e.title, TaskTransfer.toJsonCompact(e))
            }) { Text("задание QR/текст →") }
            TextButton(onClick = {
                onExportFile(TaskTransfer.taskFileName(e), TaskTransfer.toJsonFile(e))
            }) { Text("файл ↓") }
            TextButton(onClick = { vm.deleteJournalEntry(e.id) }) {
                Text("удалить", color = MaterialTheme.colorScheme.error)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            val journalDirty = remember(e.id, e.timestampMs) { mutableStateOf(false) }
            if (openTask || vm.simpleMode) {
                // исполнитель: карта этапов только для чтения + отметки выполнения
                TaskStepsView(vm, e)
            } else {
                JournalStepsEditor(vm, e, journalDirty)
            }
            ActualEditor(vm, e)
        }
    }
}

/** Карта этапов в режиме исполнителя: параметры видны, можно отметить статус */
@Composable
private fun TaskStepsView(vm: SlurryViewModel, e: JournalEntry) {
    Spacer(Modifier.height(6.dp))
    Text("Карта этапов (по заданию)", style = MaterialTheme.typography.titleSmall)
    if (e.steps.isEmpty()) {
        Text("Этапов нет — опишите процесс в комментарии ниже.", style = MaterialTheme.typography.bodySmall)
    }
    e.steps.forEachIndexed { i, s ->
        val status = e.stepStatuses.getOrElse(i) { "" }
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontWeight = FontWeight.Bold,
                        color = if (status.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.action, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(
                                s.tool.label,
                                s.disperserRpm?.let { "дисп. $it об/мин" },
                                s.bladeRpm?.let { "лопасти $it об/мин" },
                                s.durationMin?.let { "~${fmt(it, 0)} мин" },
                                s.tempC?.let { "T° ≤ ${fmt(it, 0)} °C" },
                                if (s.vacuum) "вакуум" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.comment.isNotBlank())
                            Text(s.comment, style = MaterialTheme.typography.bodySmall,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("✓ выполнено", "⚠ отклонение", "— пропущено").forEach { lbl ->
                        val key = lbl.substringAfter(' ')
                        FilterChip(
                            selected = status == key,
                            onClick = { vm.toggleStepStatus(e, i, key) },
                            label = { Text(lbl, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        }
    }
}

/** Редактирование карты этапов прямо в записи журнала: добавить/удалить/редактировать вручную */
@Composable
private fun JournalStepsEditor(vm: SlurryViewModel, e: JournalEntry, stepsDirty: MutableState<Boolean>? = null) {
    // локальная копия списка этапов записи
    val localSteps = remember(e.id, e.timestampMs) { mutableStateListOf(*e.steps.toTypedArray()) }
    var dirty by remember(e.id, e.timestampMs) { mutableStateOf(false) }

    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Карта этапов замеса", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                val cathode = e.plannedSolventName.contains("NMP", true)
                com.slurrycalc.domain.MixingSequences.template(cathode, e.plannedSolventName)
                    .forEach { localSteps.add(it.copy(id = System.nanoTime())) }
                dirty = true
            }) { Text("шаблон") }
            TextButton(onClick = {
                localSteps.add(MixingStep(id = System.nanoTime(), action = "Новый этап"))
                dirty = true
            }) { Text("+ этап") }
        }
    }
    if (localSteps.isEmpty()) {
        Text("Этапов нет — добавьте вручную («+ этап») или загрузите шаблон последовательности.",
            style = MaterialTheme.typography.bodySmall)
    }
    localSteps.forEachIndexed { i, s ->
        StepEditor(
            vm = vm,
            step = s,
            regimes = vm.regimeNames(),
            onUpdate = { patch ->
                if (i in localSteps.indices) {
                    localSteps[i] = patch(localSteps[i])
                    dirty = true
                }
            },
            onRemove = { if (i in localSteps.indices) { localSteps.removeAt(i); dirty = true } },
            onMove = { d ->
                val to = i + d
                if (i in localSteps.indices && to in localSteps.indices) {
                    val st = localSteps.removeAt(i); localSteps.add(to, st); dirty = true
                }
            },
            onSaveAsRegime = { vm.saveStepAsRegime(s) },
            canUp = i > 0, canDown = i < localSteps.lastIndex,
            number = i + 1,
        )
    }
    if (dirty) {
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            val time = localSteps.sumOf { it.durationMin ?: 0.0 }.takeIf { it > 0 }
            vm.updateJournalEntry(e.copy(steps = localSteps.toList(), mixingDurationMin = time))
            dirty = false
            stepsDirty?.value = true   // чтобы редактор факта перезахватил обновлённую запись
        }) { Text("Сохранить карту этапов") }
    }
}

/** Редактирование фактических масс и результатов + пересчёт реального состава */
@Composable
private fun ActualEditor(vm: SlurryViewModel, e: JournalEntry) {
    val n = e.componentNames.size
    val actuals = remember(e.id, e.timestampMs) {
        MutableList(n) { i -> e.actualComponentMassesG.getOrElse(i) { null }?.let { fmt(it, 2) } ?: "" }
    }
    var solv by remember(e.id, e.timestampMs) { mutableStateOf(e.actualSolventMassG?.let { fmt(it, 2) } ?: "") }
    var dur by remember(e.id, e.timestampMs) { mutableStateOf(e.mixingDurationMin?.let { fmt(it, 0) } ?: "") }
    var mixNotes by remember(e.id, e.timestampMs) { mutableStateOf(e.mixingNotes) }
    var visc by remember(e.id, e.timestampMs) { mutableStateOf(e.resultViscosityMPas?.let { fmt(it, 1) } ?: "") }
    var viscCond by remember(e.id, e.timestampMs) { mutableStateOf(e.resultViscosityMpaTime) }
    var temp by remember(e.id, e.timestampMs) { mutableStateOf(e.resultTemperatureC?.let { fmt(it, 1) } ?: "") }
    var grind by remember(e.id, e.timestampMs) { mutableStateOf(e.grindLevel) }
    var grindUm by remember(e.id, e.timestampMs) { mutableStateOf(e.grindMaxParticleUm?.let { fmt(it, 1) } ?: "") }
    var measDens by remember(e.id, e.timestampMs) { mutableStateOf(e.measuredDensityGcm3?.let { fmt(it, 3) } ?: "") }
    var comment by remember(e.id, e.timestampMs) { mutableStateOf(e.comment) }
    var verdict by remember(e.id, e.timestampMs) { mutableStateOf(e.verdict) }

    Spacer(Modifier.height(6.dp))
    Text("Фактические взвешенные массы", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    e.componentNames.forEachIndexed { i, name ->
        Column(Modifier.padding(vertical = 3.dp)) {
            Text("$name · план ${fmt(e.plannedComponentMassesG.getOrElse(i) { 0.0 }, 1)} г",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            LabeledField(actuals[i], { actuals[i] = it }, "Факт", "г")
        }
    }
    Column(Modifier.padding(vertical = 3.dp)) {
        Text("${e.plannedSolventName} · план ${fmt(e.plannedSolventMassG, 1)} г",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        LabeledField(solv, { solv = it }, "Факт", "г")
    }

    Spacer(Modifier.height(8.dp))
    Text("Процесс", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledField(dur, { dur = it }, "Длительность", "мин (итог)", Modifier.weight(1f))
        LabeledField(temp, { temp = it }, "T° суспензии", "°C", Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    LabeledField(mixNotes, { mixNotes = it }, "Комментарии процесса", "порядок загрузки, особенности", singleLine = false)

    Spacer(Modifier.height(8.dp))
    Text("Результаты замеса", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledField(visc, { visc = it }, "Вязкость η", "мПа·с", Modifier.weight(1f))
        LabeledField(viscCond, { viscCond = it }, "Условия измерения", "с⁻¹ / шпиндель", Modifier.weight(1.2f))
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledField(measDens, { measDens = it }, "Плотность пасты ρ", "г/см³", Modifier.weight(1f))
        LabeledField(grindUm, { grindUm = it }, "Крупн. агломератов", "мкм", Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Text("Степень перетира", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf("нет комков", "единичные агломераты", "видимые агломераты", "неудовлетворительно")
        .chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 2.dp)) {
                rowItems.forEach { g ->
                    FilterChip(selected = grind == g, onClick = { grind = if (grind == g) "" else g },
                        label = { Text(g, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.weight(1f))
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    Spacer(Modifier.height(6.dp))
    LabeledField(comment, { comment = it }, "Итоговый комментарий", "внешний вид, стабильность, наносимость", singleLine = false)
    Spacer(Modifier.height(6.dp))
    Text("Вердикт", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("OK", "на доработку", "брак").forEach { v ->
            FilterChip(selected = verdict == v, onClick = { verdict = if (verdict == v) "" else v },
                label = { Text(v) }, modifier = Modifier.weight(1f))
        }
    }

    Spacer(Modifier.height(10.dp))
    Button(onClick = {
        val updated = e.copy(
            actualComponentMassesG = List(n) { i -> parseD(actuals[i]) },
            actualSolventMassG = parseD(solv),
            mixingDurationMin = parseD(dur),
            mixingNotes = mixNotes,
            resultViscosityMPas = parseD(visc),
            resultViscosityMpaTime = viscCond,
            resultTemperatureC = parseD(temp),
            grindLevel = grind,
            grindMaxParticleUm = parseD(grindUm),
            measuredDensityGcm3 = parseD(measDens),
            comment = comment,
            verdict = verdict,
        )
        vm.updateJournalEntry(updated)
    }) { Text("Сохранить факт и результаты") }

    // Пересчёт реального состава по актуальным массам
    val previewComps = e.componentNames.mapIndexed { i, name ->
        BatchMath.ActualComponent(name, e.componentDensities.getOrElse(i) { 0.0 }, parseD(actuals[i]) ?: 0.0)
    }.filter { it.massG > 0 }
    val solvMass = parseD(solv) ?: 0.0
    val meas = parseD(measDens)
    if (previewComps.isNotEmpty() && solvMass > 0) {
        val calc = BatchMath.fromActualMasses(previewComps, solvMass, e.plannedSolventDensity, meas)
        calc.onSuccess { a ->
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("Реальный состав замеса (по фактическим массам):", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium)
            Text("СО факт: ${fmt(a.dryContentPct, 2)} % (план ${fmt(e.plannedDryContentPct, 1)} %)")
            Text("Сухое: ${fmt(a.dryMassG, 1)} г · всего: ${fmt(a.wetMassG, 1)} г")
            Text("Доли в сухом: " + a.componentPctInDry.entries.joinToString(" / ") { "${it.key} ${fmt(it.value, 2)} %" })
            Text("ρ сухого: ${fmt(a.dryDensityGcm3)} · ρ расч.: ${fmt(a.calcDensityGcm3)}" +
                    (a.measuredDensityGcm3?.let { " · ρ изм: ${fmt(it)}" } ?: ""))
            Text("φ = ${fmt(a.solidVolumeFraction * 100, 1)} % · Объём: ${fmt(a.volumeMl, 0)} мл (${a.volumeSource})")
            a.warnings.forEach { w ->
                Text("⚠ $w", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        calc.onFailure { err ->
            Spacer(Modifier.height(6.dp))
            Text(err.message ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ============================ TAB: ЗАДАНИЯ (передача «начальник → исполнитель») ============================

/**
 * Вкладка «Задания»: отправка планов исполнителю (QR / файл / текст через мессенджер)
 * и приём входящих заданий (файл или вставка текста/ссылки из буфера обмена).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksTab(
    vm: SlurryViewModel,
    onShareText: (String, String) -> Unit,
    onPickTaskFile: () -> Unit,
    onExportFile: (String, String) -> Unit,
) {
    SectionCard("Как это работает") {
        Text(
            "Начальник отправляет план замеса исполнителю одним из способов:\n" +
                    "  • QR-код — исполнитель сканирует камерой (без интернета); подходит для коротких заданий;\n" +
                    "  • Файл .slurrytask.json — через Telegram / Bluetooth / Quick Share; открывается в приложении;\n" +
                    "  • Текст/ссылка — копирование в буфер, вставляется на этой вкладке.\n" +
                    "Исполнитель включает чип «Простой» в шапке: калькулятор скрывается, остаются задания и журнал. " +
                    "Если у исполнителя iPhone/другая ОС — отправьте «отчёт →» (текст) или CSV: читается в любом редакторе.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // ---- приём задания ----
    var pasteText by remember { mutableStateOf("") }
    SectionCard("Принять задание") {
        LabeledField(pasteText, { pasteText = it }, "Вставьте текст задания",
            "JSON, base64 из QR или ссылка slurrycalc://…", singleLine = false)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                if (pasteText.isNotBlank()) {
                    try {
                        vm.importTask(TaskTransfer.parseIncoming(pasteText)); pasteText = ""
                    } catch (ex: Exception) {
                        pasteText = "⚠ ${ex.message ?: "не удалось прочитать"}"
                    }
                }
            }, modifier = Modifier.weight(1f)) { Text("импортировать") }
            OutlinedButton(onClick = onPickTaskFile, modifier = Modifier.weight(1f)) {
                Text("из файла…")
            }
        }
    }

    // ---- отправка задания ----
    val sendable = vm.journal.filter { !it.isTask || true } // любой записи можно отправить план
    SectionCard("Отправить задание исполнителю (${sendable.size})") {
        if (sendable.isEmpty()) {
            Text("Нет записей в журнале — сначала сохраните расчёт («→ В журнал замесов»).",
                style = MaterialTheme.typography.bodySmall)
        }
        sendable.take(20).forEach { e ->
            var showQr by remember(e.id) { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(e.title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Text(e.recipeSummary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    val b64 = remember(e) { TaskTransfer.encodeBase64Url(TaskTransfer.toJsonCompact(e)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showQr = !showQr }) { Text(if (showQr) "скрыть QR" else "QR →") }
                        TextButton(onClick = {
                            onShareText(e.title, "slurrycalc://task?d=$b64")
                        }) { Text("ссылка →") }
                        TextButton(onClick = { onShareText(e.title, TaskTransfer.toJsonCompact(e)) }) {
                            Text("json →")
                        }
                        TextButton(onClick = {
                            onExportFile(TaskTransfer.taskFileName(e), TaskTransfer.toJsonFile(e))
                        }) { Text("файл ↓") }
                    }
                    if (!TaskQr.fitsReliable(b64)) {
                        Text("⚠ Задание длинное (${b64.length} б) — QR может не считаться с расстояния, " +
                                "лучше отправить файл или ссылку.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (showQr) {
                        val bmp = remember(b64) { TaskQr.encode("slurrycalc://task?d=$b64", 512) }
                        if (bmp != null) {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier.align(Alignment.CenterHorizontally).background(androidx.compose.ui.graphics.Color.White).padding(8.dp),
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "QR задания",
                                    modifier = Modifier.size(240.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            Text("Сканируйте камерой телефона исполнителя (Google Camera умеет открывать ссылки).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Данные не помещаются в QR — используйте файл/ссылку.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    // ---- активные задания исполнителю ----
    val openTasks = vm.journal.filter { it.isTask && !it.hasActuals() }
    if (openTasks.isNotEmpty()) {
        SectionCard("Активные задания (${openTasks.size})") {
            Text("Откройте карточку во вкладке «Журнал», отметьте этапы и внесите фактические массы.",
                style = MaterialTheme.typography.bodySmall)
            openTasks.forEach { e ->
                JournalEntryCard(vm, e, onShareText, onExportFile)
            }
        }
    }
}
