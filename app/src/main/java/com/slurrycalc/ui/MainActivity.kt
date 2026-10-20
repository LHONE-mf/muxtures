package com.slurrycalc.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.slurrycalc.engine.BatchMath
import com.slurrycalc.engine.Catalog
import com.slurrycalc.engine.JournalEntry
import com.slurrycalc.engine.MaterialEntry
import com.slurrycalc.engine.MaterialRole
import com.slurrycalc.engine.MixingRegime
import com.slurrycalc.engine.MixingRegimes
import com.slurrycalc.engine.SolidComponent
import com.slurrycalc.engine.Solvent
import com.slurrycalc.engine.Solvents
import com.slurrycalc.engine.ViscosityModel
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                MaterialTheme {
                    SlurryScreen()
                }
            }
        } catch (e: Throwable) {
            Log.e("SlurryCalc", "UI init failed", e)
            showFatalError(e)
        }
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
private fun fmt(v: Double, digits: Int = 2): String =
    String.format(java.util.Locale.US, "%.${digits}f", v).replace('.', ',')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlurryScreen(vm: SlurryViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("SlurryCalc") },
                    colors = TopAppBarDefaults.topAppBarColors(),
                )
                TabRow(selectedTabIndex = tab) {
                    listOf("Расчёт", "Материалы", "Журнал").forEachIndexed { i, t ->
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
            when (tab) {
                0 -> CalcTab(vm)
                1 -> MaterialsTab(vm)
                2 -> JournalTab(vm)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ============================ TAB: РАСЧЁТ ============================

@Composable
private fun CalcTab(vm: SlurryViewModel) {
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
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
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

/** Строка компонента с выпадающим списком выбора материала из базы */
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

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Кнопка-селектор материала
            MaterialPickerBox {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (component.materialId != null) "✓ ${component.name}" else "${component.name} ▾",
                        maxLines = 1)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    itemsFiltered(materials).forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.name)
                                    Text(
                                        "${m.role.label} · ρ=${fmt(m.density, 2)}" +
                                                (m.tapDensity?.let { " · нас.${fmt(it, 1)}" } ?: "") +
                                                (m.particleSizeD50Um?.let { " · d50 ${it} мкм" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onPickMaterial(m)
                            },
                        )
                    }
                }
            }
            Button(onClick = onRemove) { Text("×") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(name, { name = it; onChange(it, null, null) }, "Название", Modifier.weight(1.4f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(density, { density = it; onChange(null, it, null) }, "ρ, г/см³", Modifier.weight(1f))
            NumberField(fraction, { fraction = it; onChange(null, null, it) }, "Доля, %", Modifier.weight(1f))
        }
    }
}

private fun itemsFiltered(materials: List<MaterialEntry>): List<MaterialEntry> =
    materials.sortedBy { it.name.lowercase() }.distinctBy { it.name }

/** обёртка, чтобы DropdownMenu не ломал раскладку */
@Composable
private fun MaterialPickerBox(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun SolventCard(vm: SlurryViewModel) {
    SectionCard("Растворитель / среда") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Solvents.all.forEach { s ->
                FilterChip(
                    selected = vm.solventName == s.name,
                    onClick = { vm.setSolvent(s) },
                    label = { Text(s.name) },
                )
            }
            // из базы: растворители
            vm.materialsDb.byRole(MaterialRole.SOLVENT).filter { e -> e.custom }.forEach { e ->
                FilterChip(
                    selected = vm.solventName == e.name,
                    onClick = { vm.setSolvent(Solvent(e.name, e.density, e.viscosityMPas ?: 1.0)) },
                    label = { Text(e.name) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        var dens by remember(vm.solventName, vm.solventDensity) { mutableStateOf(fmt(vm.solventDensity, 3)) }
        var visc by remember(vm.solventName, vm.solventViscosity) { mutableStateOf(fmt(vm.solventViscosity, 2)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(dens, {
                dens = it
                parseD(it)?.let { d -> vm.setSolvent(Solvent(vm.solventName, d, vm.solventViscosity)) }
            }, "ρ р-ля, г/см³", Modifier.weight(1f))
            NumberField(visc, {
                visc = it
                parseD(it)?.let { v -> vm.setSolvent(Solvent(vm.solventName, vm.solventDensity, v)) }
            }, "η р-ля, мПа·с", Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatchCard(vm: SlurryViewModel) {
    SectionCard("Партия и сухой остаток") {
        var dc by remember(vm.dryContentPct) { mutableStateOf(fmt(vm.dryContentPct, 1)) }
        NumberField(dc, { dc = it; parseD(it)?.let { v -> vm.setDryContent(v) } }, "Сухой остаток, % (обычно 30–70)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("масса суспензии, г", "масса сухого, г", "объём, мл").forEachIndexed { i, label ->
                FilterChip(selected = vm.batchMode == i, onClick = { vm.selectBatchMode(i) }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        var bv by remember(vm.batchValue) { mutableStateOf(fmt(vm.batchValue, 0)) }
        NumberField(bv, { bv = it; parseD(it)?.let { v -> vm.updateBatchValue(v) } }, "Значение партии", Modifier.fillMaxWidth())
    }
}

@Composable
private fun MixingCard(vm: SlurryViewModel) {
    SectionCard("Режим смешивания") {
        MixingRegimes.ALL.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                row.forEach { r ->
                    FilterChip(
                        selected = vm.mixingRegimeId == r.id,
                        onClick = { vm.setMixingRegime(r.id) },
                        label = { Text(r.name) },
                    )
                }
            }
        }
        MixingRegimes.byId(vm.mixingRegimeId)?.let { r ->
            Spacer(Modifier.height(8.dp))
            RegimeDetails(r)
            Spacer(Modifier.height(6.dp))
            Text("Типовая последовательность стадий:", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
            val seq = MixingRegimes.recommendedSequence(r.id)
                .mapNotNull { MixingRegimes.byId(it) }
                .joinToString(" → ") { it.name }
            Text(seq, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RegimeDetails(r: MixingRegime) {
    Column {
        Text("Метод: ${r.method} · ${r.tipSpeedHint}", style = MaterialTheme.typography.bodySmall)
        Text("Время: ${r.timeHint}" + (r.tempLimitC?.let { " · T° ≤ ${fmt(it, 0)} °C" } ?: ""),
            style = MaterialTheme.typography.bodySmall)
        Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ViscosityCard(vm: SlurryViewModel) {
    SectionCard("Модель оценки вязкости") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = mapOf(
                ViscosityModel.NONE to "выкл",
                ViscosityModel.MO to "Муни",
                ViscosityModel.MARON_PIERIS to "Марон–Пирис",
                ViscosityModel.KRIEGER_DOUGHERTY to "Кригер–Догерти",
            )
            ViscosityModel.values().forEachIndexed { i, m ->
                FilterChip(selected = vm.viscosityModelIndex == i, onClick = { vm.setViscosityModel(i) }, label = { Text(labels[m]!!) })
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Оценки по объёмной доле φ; точность ±2×, для ориентира при подборе СО.", style = MaterialTheme.typography.bodySmall)
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
            Text("Оценка вязкости: ${fmt(it, 1)} мПа·с (ηrel = ${fmt(r.relativeViscosity!!, 2)})")
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
        NumberField(title, { title = it }, "Название замеса (напр. «NMC622 batch#7»)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.savePlanToJournal(title) }) {
            Text("→ В журнал замесов")
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

        NumberField(name, { name = it }, "Название *", Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MaterialRole.values().forEach { r ->
                FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r.label) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(dens, { dens = it }, "ρ истинская, г/см³ *", Modifier.weight(1f))
            NumberField(tap, { tap = it }, "ρ насып., г/см³", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(d50, { d50 = it }, "d50, мкм", Modifier.weight(1f))
            NumberField(bet, { bet = it }, "BET, м²/г", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(cap, { cap = it }, "Ёмкость, мА·ч/г", Modifier.weight(1f))
            NumberField(moist, { moist = it }, "Влага, ppm", Modifier.weight(1f))
        }
        NumberField(note, { note = it }, "Примечание", Modifier.fillMaxWidth())
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
        Text("* обязательные поля. Растворитель можно выбрать чипом в карточке «Растворитель» на вкладке «Расчёт».",
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
private fun JournalTab(vm: SlurryViewModel) {
    if (vm.journal.isEmpty()) {
        SectionCard("Журнал замесов пуст") {
            Text("Сохраните расчёт во вкладке «Расчёт» кнопкой «→ В журнал замесов», затем заполните фактические массы и результаты.")
        }
        return
    }
    vm.journal.forEach { e ->
        JournalEntryCard(vm, e)
    }
}

@Composable
private fun JournalEntryCard(vm: SlurryViewModel, e: JournalEntry) {
    var expanded by remember(e.id, e.timestampMs) { mutableStateOf(false) }
    SectionCard("${e.dateLabel()} · ${e.title}") {
        Text(e.recipeSummary, style = MaterialTheme.typography.bodySmall)
        Text(
            "План: ${fmt(e.plannedWetMassG, 0)} г · режим: ${vm.regimeName(e.mixingRegimeId)}" +
                    (if (e.hasActuals()) " · ФАКТ внесён ✓" else " · факт не внесён"),
            style = MaterialTheme.typography.bodySmall,
            color = if (e.hasActuals()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "свернуть" else "факт и результаты…") }
            TextButton(onClick = { vm.loadFromJournal(e) }) { Text("в калькулятор") }
            TextButton(onClick = { vm.deleteJournalEntry(e.id) }) { Text("удалить") }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            ActualEditor(vm, e)
        }
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

    Text("Фактические взвешенные массы, г:", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    e.componentNames.forEachIndexed { i, name ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
            Text("план ${fmt(e.plannedComponentMassesG.getOrElse(i) { 0.0 }, 1)} г",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            NumberField(actuals[i], { actuals[i] = it }, "факт", Modifier.weight(1f))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(e.plannedSolventName, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
        Text("план ${fmt(e.plannedSolventMassG, 1)} г", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        NumberField(solv, { solv = it }, "факт", Modifier.weight(1f))
    }

    Spacer(Modifier.height(8.dp))
    Text("Процесс:", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(dur, { dur = it }, "Длительность, мин", Modifier.weight(1f))
        NumberField(temp, { temp = it }, "T° суспензии, °C", Modifier.weight(1f))
    }
    NumberField(mixNotes, { mixNotes = it }, "Комментарии процесса (порядок загрузки, особенности)", Modifier.fillMaxWidth())

    Spacer(Modifier.height(8.dp))
    Text("Результаты замеса:", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(visc, { visc = it }, "η, мПа·с", Modifier.weight(1f))
        NumberField(viscCond, { viscCond = it }, "условия (с⁻¹/шпиндель)", Modifier.weight(1.2f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(measDens, { measDens = it }, "ρ пасты, г/см³", Modifier.weight(1f))
        NumberField(grindUm, { grindUm = it }, "крупн. агломер., мкм", Modifier.weight(1f))
    }
    Text("Степень перетира:", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("нет комков", "единичные агломераты", "видимые агломераты", "неудовлетворительно").forEach { g ->
            FilterChip(selected = grind == g, onClick = { grind = if (grind == g) "" else g }, label = { Text(g) })
        }
    }
    Spacer(Modifier.height(6.dp))
    NumberField(comment, { comment = it }, "Итоговый комментарий", Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("OK", "на доработку", "брак").forEach { v ->
            FilterChip(selected = verdict == v, onClick = { verdict = if (verdict == v) "" else v }, label = { Text(v) })
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
