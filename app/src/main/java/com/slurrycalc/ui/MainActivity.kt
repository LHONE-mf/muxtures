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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.slurrycalc.engine.SolidComponent
import com.slurrycalc.engine.Solvents
import com.slurrycalc.engine.ViscosityModel
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Если Compose-интерфейс по какой-то причине падает — показываем ошибку
        // прямо на экране (чтобы было видно стектрейс без adb), а не вылетаем молча.
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
        // Дополнительная защита: ошибки, возникающие уже после onCreate
        // (например, при первом compose-recompose), тоже ловим и показываем.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SlurryCalc", "Uncaught exception", throwable)
            try {
                runOnUiThread { showFatalError(throwable) }
                Thread.sleep(60_000) // даём время прочитать сообщение на экране
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SlurryCalc — расчёт суспензий") },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
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
            PresetsCard(vm)
            SolidsCard(vm)
            SolventCard(vm)
            BatchCard(vm)
            ViscosityCard(vm)
            ResultCard(vm)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
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
        Text("Катод: 96/2/2 AM/SP/PVDF в NMP, 50 % СО.\nАнод: 95/2/1.5/1.5 Графит/SP/CMC/SBR в воде, 45 % СО.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SolidsCard(vm: SlurryViewModel) {
    SectionCard("Твёрдая фаза (сухие компоненты)") {
        vm.solids.forEachIndexed { i, c ->
            ComponentRow(
                component = c,
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
            Button(onClick = { vm.addComponent() }) { Text("+ компонент") }
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

@Composable
private fun ComponentRow(
    component: SolidComponent,
    onChange: (name: String?, density: String?, fraction: String?) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember(component) { mutableStateOf(component.name) }
    var density by remember(component) { mutableStateOf(fmt(component.density, 3)) }
    var fraction by remember(component) { mutableStateOf(fmt(component.fraction * 100, 2)) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(name, { name = it; onChange(it, null, null) }, "Название", Modifier.weight(1.4f))
            Button(onClick = onRemove) { Text("×") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(density, { density = it; onChange(null, it, null) }, "ρ, г/см³", Modifier.weight(1f))
            NumberField(fraction, { fraction = it; onChange(null, null, it) }, "Доля, %", Modifier.weight(1f))
        }
    }
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
        }
        Spacer(Modifier.height(8.dp))
        var dens by remember(vm.solventName, vm.solventDensity) { mutableStateOf(fmt(vm.solventDensity, 3)) }
        var visc by remember(vm.solventName, vm.solventViscosity) { mutableStateOf(fmt(vm.solventViscosity, 2)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(dens, {
                dens = it
                parseD(it)?.let { d -> vm.setSolvent(com.slurrycalc.engine.Solvent(vm.solventName, d, vm.solventViscosity)) }
            }, "ρ р-ля, г/см³", Modifier.weight(1f))
            NumberField(visc, {
                visc = it
                parseD(it)?.let { v -> vm.setSolvent(com.slurrycalc.engine.Solvent(vm.solventName, vm.solventDensity, v)) }
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
