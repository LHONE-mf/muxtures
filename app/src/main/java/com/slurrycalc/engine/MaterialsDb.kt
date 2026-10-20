package com.slurrycalc.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Роль компонента в суспензии */
enum class MaterialRole(val label: String) {
    ACTIVE("Активная масса"),
    CONDUCTIVE("Электропроводная добавка"),
    BINDER("Связующее"),
    ADDITIVE("Добавка"),
    SOLVENT("Растворитель");

    companion object {
        fun fromName(n: String?): MaterialRole =
            values().firstOrNull { it.name == n } ?: ACTIVE
    }
}

/**
 * Запись базы материалов. Плотность обязательна для расчёта;
 * остальные поля — справочные, используются для подсказок и журнала.
 */
data class MaterialEntry(
    val id: Long,
    val name: String,
    val role: MaterialRole,
    /** истинская плотность, г/см³ */
    val density: Double,
    /** насыпная (упаковочная) плотность, г/см³ — полезна при дозировании */
    val tapDensity: Double? = null,
    /** d50, мкм — подсказка для режима диспергирования */
    val particleSizeD50Um: Double? = null,
    /** удельная поверхность BET, м²/г — влияет на потребность в связующем */
    val betAreaM2g: Double? = null,
    /** pH 1 %-й водной суспензии — контроль промывки NMC */
    val ph: Double? = null,
    /** остаточная влага, ppm — важно для NMC/LFP в NMP */
    val moisturePpm: Int? = null,
    /** температура сушки перед использованием, °C */
    val dryingTempC: Double? = null,
    /** специфическая ёмкость, мА·ч/г — пересчёт loading при нанесении */
    val capacityMahG: Double? = null,
    /** электропроводность, См/см */
    val conductivityScm: Double? = null,
    /** растворитель: вязкость, мПа·с (при ~25 °C) */
    val viscosityMPas: Double? = null,
    val note: String = "",
    val custom: Boolean = false,
)

/** Встроенный каталог типовых материалов (истинские плотности, г/см³) */
object Catalog {
    val DEFAULTS: List<MaterialEntry> = listOf(
        // --- катодные активные материалы ---
        MaterialEntry(1, "LCO", MaterialRole.ACTIVE, 5.0, tapDensity = 2.4, d50Hint = 8.0, cap = 140.0),
        MaterialEntry(2, "NMC 111", MaterialRole.ACTIVE, 4.78, tapDensity = 2.2, d50Hint = 9.0, cap = 160.0),
        MaterialEntry(3, "NMC 532", MaterialRole.ACTIVE, 4.76, tapDensity = 2.2, d50Hint = 9.0, cap = 170.0),
        MaterialEntry(4, "NMC 622", MaterialRole.ACTIVE, 4.67, tapDensity = 2.1, d50Hint = 9.0, cap = 180.0),
        MaterialEntry(5, "NMC 811", MaterialRole.ACTIVE, 4.45, tapDensity = 2.0, d50Hint = 9.0, cap = 200.0),
        MaterialEntry(6, "NCA", MaterialRole.ACTIVE, 4.37, tapDensity = 1.9, d50Hint = 9.0, cap = 200.0),
        MaterialEntry(7, "LFP", MaterialRole.ACTIVE, 3.6, tapDensity = 1.3, d50Hint = 2.0, bet = 35.0, cap = 155.0),
        MaterialEntry(8, "LMO", MaterialRole.ACTIVE, 4.28, tapDensity = 2.1, d50Hint = 5.0, cap = 110.0),
        MaterialEntry(9, "NMM (Na-катод)", MaterialRole.ACTIVE, 3.45, cap = 120.0),
        // --- анодные активные материалы ---
        MaterialEntry(10, "Графит (натуральный)", MaterialRole.ACTIVE, 2.26, tapDensity = 1.0, d50Hint = 15.0, bet = 2.0, cap = 345.0),
        MaterialEntry(11, "Графит (искусственный)", MaterialRole.ACTIVE, 2.24, tapDensity = 1.0, d50Hint = 13.0, bet = 1.5, cap = 340.0),
        MaterialEntry(12, "Si / SiOx-композит", MaterialRole.ACTIVE, 2.33, tapDensity = 0.9, d50Hint = 5.0, bet = 20.0, cap = 1500.0),
        MaterialEntry(13, "Li4Ti5O12 (LTO)", MaterialRole.ACTIVE, 3.5, tapDensity = 1.6, d50Hint = 3.0, bet = 8.0, cap = 175.0),
        MaterialEntry(14, "Li-metal (фольга)", MaterialRole.ACTIVE, 0.534),
        // --- проводящие добавки ---
        MaterialEntry(20, "Super P / Carbon Black", MaterialRole.CONDUCTIVE, 1.95, d50Hint = 0.04, bet = 62.0, cond = 10.0),
        MaterialEntry(21, "C45 / Ac-Di-Sol", MaterialRole.CONDUCTIVE, 1.88, bet = 120.0, cond = 10.0),
        MaterialEntry(22, "CNT (трубки)", MaterialRole.CONDUCTIVE, 2.1, bet = 200.0, cond = 100.0),
        MaterialEntry(23, "Графен", MaterialRole.CONDUCTIVE, 2.2, bet = 500.0, cond = 1000.0),
        MaterialEntry(24, "Ketjenblack EC-300J", MaterialRole.CONDUCTIVE, 1.94, bet = 1430.0, cond = 100.0),
        // --- связующие ---
        MaterialEntry(30, "PVDF (сухое)", MaterialRole.BINDER, 1.77),
        MaterialEntry(31, "PVDF (раств. 5 % в NMP)", MaterialRole.BINDER, 1.05, viscosityMPas = 20.0, note = "обычно дозируют как раствор: 10 % сухого PVDF от партии"),
        MaterialEntry(32, "CMC", MaterialRole.BINDER, 1.6),
        MaterialEntry(33, "SBR (сухое)", MaterialRole.BINDER, 1.05),
        MaterialEntry(34, "SBR латекс (45 % сух.)", MaterialRole.BINDER, 1.02, viscosityMPas = 10.0, note = "массу сухого SBR пересчитывать: m_латекса = m_SBR/0.45"),
        MaterialEntry(35, "PTFE", MaterialRole.BINDER, 2.15),
        MaterialEntry(36, "PAI / аримид", MaterialRole.BINDER, 1.4),
        // --- добавки ---
        MaterialEntry(40, "Al2O3 (покрытие/добавка)", MaterialRole.ADDITIVE, 3.95, d50Hint = 0.3, bet = 12.0),
        MaterialEntry(41, "Mg(OH)2", MaterialRole.ADDITIVE, 2.36),
        MaterialEntry(42, "LiF (добавка)", MaterialRole.ADDITIVE, 2.64),
        MaterialEntry(43, "Boehmite (AlOOH)", MaterialRole.ADDITIVE, 3.01, d50Hint = 0.15),
        // --- растворители и среды ---
        MaterialEntry(50, "NMP (25 °C)", MaterialRole.SOLVENT, 1.028, viscosityMPas = 1.87, note = "T° влияния: ρ≈1.03→1.01 при 25→60 °C"),
        MaterialEntry(51, "Вода деиониз. (25 °C)", MaterialRole.SOLVENT, 0.997, viscosityMPas = 0.89),
        MaterialEntry(52, "Этанол", MaterialRole.SOLVENT, 0.789, viscosityMPas = 1.07),
        MaterialEntry(53, "Диацетоновый спирт (DAA)", MaterialRole.SOLVENT, 0.926, viscosityMPas = 3.2),
        MaterialEntry(54, "Water + EtOH (1:1)", MaterialRole.SOLVENT, 0.92, viscosityMPas = 1.6, note = "снижает поверхностное натяжение для LFP"),
    )

    private fun MaterialEntry(
        id: Long, name: String, role: MaterialRole, density: Double,
        tapDensity: Double? = null, d50Hint: Double? = null, bet: Double? = null,
        ph: Double? = null, moisture: Int? = null, dryT: Double? = null,
        cap: Double? = null, cond: Double? = null, visc: Double? = null, note: String = "",
    ) = MaterialEntry(id, name, role, density, tapDensity, d50Hint, bet, ph, moisture, dryT, cap, cond, visc, note, false)
}

/**
 * Локальная база материалов: встроенный каталог + пользовательские записи.
 * Хранится в SharedPreferences (JSON). Удаление возможно только у своих записей.
 */
class MaterialsDb(context: Context) {

    private val prefs = context.getSharedPreferences("materials.db", Context.MODE_PRIVATE)
    private var nextId: Long = 1000L

    private val customs = mutableListOf<MaterialEntry>()

    init {
        val raw = prefs.getString("entries", null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) customs.add(fromJson(arr.getJSONObject(i)))
                nextId = (customs.maxOfOrNull { it.id } ?: 1000L) + 1
            } catch (_: Exception) { /* повреждённое хранилище игнорируем */ }
        }
    }

    /** все доступные материалы: каталог + пользовательские */
    fun all(): List<MaterialEntry> = customs + Catalog.DEFAULTS

    fun byRole(role: MaterialRole): List<MaterialEntry> =
        all().filter { it.role == role }.sortedBy { it.name.lowercase() }

    fun add(name: String, role: MaterialRole, density: Double, extras: Map<String, Double?> = emptyMap(),
            moisturePpm: Int? = null, note: String = ""): MaterialEntry {
        val e = MaterialEntry(
            id = nextId++,
            name = name.trim().ifEmpty { "Материал" },
            role = role,
            density = density.coerceIn(0.01, 20.0),
            tapDensity = extras["tap"],
            particleSizeD50Um = extras["d50"],
            betAreaM2g = extras["bet"],
            ph = extras["ph"],
            moisturePpm = moisturePpm,
            dryingTempC = extras["dryT"],
            capacityMahG = extras["cap"],
            conductivityScm = extras["cond"],
            viscosityMPas = extras["visc"],
            note = note.trim(),
            custom = true,
        )
        customs.add(0, e)
        persist()
        return e
    }

    fun remove(id: Long): Boolean {
        val removed = customs.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    private fun persist() {
        val arr = JSONArray()
        customs.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun toJson(e: MaterialEntry) = JSONObject().apply {
        put("id", e.id); put("name", e.name); put("role", e.role.name)
        put("density", e.density)
        e.tapDensity?.let { put("tap", it) }
        e.particleSizeD50Um?.let { put("d50", it) }
        e.betAreaM2g?.let { put("bet", it) }
        e.ph?.let { put("ph", it) }
        e.moisturePpm?.let { put("moist", it) }
        e.dryingTempC?.let { put("dryT", it) }
        e.capacityMahG?.let { put("cap", it) }
        e.conductivityScm?.let { put("cond", it) }
        e.viscosityMPas?.let { put("visc", it) }
        put("note", e.note)
    }

    private fun fromJson(o: JSONObject) = MaterialEntry(
        id = o.getLong("id"),
        name = o.getString("name"),
        role = MaterialRole.fromName(o.optString("role")),
        density = o.getDouble("density"),
        tapDensity = o.optDoubleOrNull("tap"),
        particleSizeD50Um = o.optDoubleOrNull("d50"),
        betAreaM2g = o.optDoubleOrNull("bet"),
        ph = o.optDoubleOrNull("ph"),
        moisturePpm = if (o.has("moist")) o.getInt("moist") else null,
        dryingTempC = o.optDoubleOrNull("dryT"),
        capacityMahG = o.optDoubleOrNull("cap"),
        conductivityScm = o.optDoubleOrNull("cond"),
        viscosityMPas = o.optDoubleOrNull("visc"),
        note = o.optString("note"),
        custom = true,
    )

    private fun JSONObject.optDoubleOrNull(k: String): Double? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
}
