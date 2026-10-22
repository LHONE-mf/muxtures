package com.slurrycalc.domain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись журнала замесов. Хранит и план (расчётные массы), и факт (реальные взвешенные массы,
 * измеренные результаты). Реальные массы всегда отличаются от расчётных — фактический состав
 * пересчитывается движком BatchMath при просмотре записи.
 */
data class JournalEntry(
    val id: Long,
    /** метка времени создания */
    val timestampMs: Long,
    val title: String,
    // ---- план (из калькулятора) ----
    val plannedDryContentPct: Double,
    val plannedWetMassG: Double,
    val recipeSummary: String,               // "NMC 622 96 / SP 2 / PVDF 2 @ NMP"
    val componentNames: List<String>,
    val componentDensities: List<Double>,
    val plannedComponentMassesG: List<Double>,
    val plannedSolventName: String,
    val plannedSolventDensity: Double,
    val plannedSolventMassG: Double,
    // ---- этапы смешивания (режим + действия) ----
    val mixingRegimeId: String,          // legacy: основной режим (id или имя)
    val mixingDurationMin: Double?,
    val mixingNotes: String,             // комментарии процесса (порядок загрузки и т.п.)
    /** пошаговая карта замеса: действие, обороты диспергатора/лопастей, время, T°, вакуум, комментарий */
    val steps: List<MixingStep> = emptyList(),
    // ---- факт: реальные взвешенные массы ----
    val actualComponentMassesG: List<Double?> = componentNames.map { null },
    val actualSolventMassG: Double? = null,
    // ---- результаты замеса ----
    val resultViscosityMPas: Double? = null,
    val resultViscosityMpaTime: String = "", // напр. "300 с⁻¹" или "rot №3, 12 об/мин"
    val resultTemperatureC: Double? = null,
    /** степень перетира: текстовая оценка + опц. размер агломерата */
    val grindLevel: String = "",             // "нет комков", "единичные агломераты"…
    val grindMaxParticleUm: Double? = null,
    val measuredDensityGcm3: Double? = null,
    val comment: String = "",
    val verdict: String = "",                // OK / на доработку / брак
    // ---- передача заданий между телефонами ----
    /** true — задание получено от другого пользователя (режим исполнителя):
     *  расчёт недоступен, только внесение факта и результатов */
    val isTask: Boolean = false,
    /** статус задания для режимов смешивания («выполнено»/«пропущено» + комментарий) */
    val stepStatuses: List<String> = emptyList(),
) {
    fun dateLabel(): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(timestampMs))

    /** есть ли внесённые фактические массы */
    fun hasActuals(): Boolean =
        actualSolventMassG != null || actualComponentMassesG.any { it != null && it > 0 }

    /** пересчёт факта: реальные массы → реальный состав. null если факта нет/некорректен */
    fun computeActual(): BatchMath.ActualBatch? {
        if (!hasActuals()) return null
        val comps = componentNames.mapIndexed { i, n ->
            BatchMath.ActualComponent(
                name = n,
                density = componentDensities.getOrElse(i) { 0.0 },
                massG = actualComponentMassesG.getOrElse(i) { null } ?: 0.0,
            )
        }
        val solv = actualSolventMassG ?: plannedSolventMassG
        return BatchMath.fromActualMasses(comps, solv, plannedSolventDensity, measuredDensityGcm3)
            .getOrNull()
    }
}

/** Персистентный журнал в SharedPreferences (JSON-массив, новые сверху) */
class JournalStore(context: Context) {

    private val prefs = context.getSharedPreferences("journal.db", Context.MODE_PRIVATE)
    private var nextId = 1L
    private val entries = mutableListOf<JournalEntry>()

    init {
        reload()
        nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
    }

    private fun reload() {
        entries.clear()
        val raw = prefs.getString("entries", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) entries.add(fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) { }
        entries.sortByDescending { it.timestampMs }
    }

    fun all(): List<JournalEntry> = entries.toList()

    fun add(e: JournalEntry): JournalEntry {
        val withId = e.copy(id = nextId++, timestampMs = if (e.timestampMs > 0) e.timestampMs else System.currentTimeMillis())
        entries.add(0, withId)
        persist()
        return withId
    }

    fun update(e: JournalEntry) {
        val idx = entries.indexOfFirst { it.id == e.id }
        if (idx >= 0) {
            entries[idx] = e
            persist()
        }
    }

    fun remove(id: Long) {
        if (entries.removeAll { it.id == id }) persist()
    }

    private fun persist() {
        val arr = JSONArray()
        entries.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun JSONArray.putDoubles(v: List<Double?>) {
        v.forEach { if (it == null) put(JSONObject.NULL) else put(it) }
    }

    private fun JSONArray.doubles(): List<Double?> {
        val out = ArrayList<Double?>(length())
        for (i in 0 until length()) out.add(if (isNull(i)) null else getDouble(i))
        return out
    }

    private fun JSONArray.addDoubles(v: List<Double>) { v.forEach { put(it) } }

    private fun stepJson(s: MixingStep) = JSONObject().apply {
        put("action", s.action); put("tool", s.tool.name)
        put("dispr", s.disperserRpm ?: JSONObject.NULL)
        put("blader", s.bladeRpm ?: JSONObject.NULL)
        put("dur", s.durationMin ?: JSONObject.NULL)
        put("temp", s.tempC ?: JSONObject.NULL)
        if (s.vacuum) put("vac", true)
        put("cmt", s.comment)
    }

    private fun stepFromJson(o: JSONObject) = MixingStep(
        action = o.optString("action"),
        tool = MixToolType.fromName(o.optString("tool")),
        disperserRpm = if (o.isNull("dispr")) null else o.optInt("dispr").takeIf { it > 0 },
        bladeRpm = if (o.isNull("blader")) null else o.optInt("blader").takeIf { it > 0 },
        durationMin = if (o.isNull("dur")) null else o.optDouble("dur").takeIf { !it.isNaN() },
        tempC = if (o.isNull("temp")) null else o.optDouble("temp").takeIf { !it.isNaN() },
        vacuum = o.optBoolean("vac", false),
        comment = o.optString("cmt"),
    )

    private fun toJson(e: JournalEntry) = JSONObject().apply {
        put("id", e.id); put("ts", e.timestampMs); put("title", e.title)
        put("pdc", e.plannedDryContentPct); put("pwet", e.plannedWetMassG)
        put("recipe", e.recipeSummary)
        put("names", JSONArray().apply { e.componentNames.forEach { put(it) } })
        put("dens", JSONArray().apply { addDoubles(e.componentDensities) })
        put("pmass", JSONArray().apply { addDoubles(e.plannedComponentMassesG) })
        put("solvName", e.plannedSolventName); put("solvDens", e.plannedSolventDensity); put("solvMass", e.plannedSolventMassG)
        put("regime", e.mixingRegimeId); put("dur", e.mixingDurationMin ?: JSONObject.NULL)
        put("mixNotes", e.mixingNotes)
        put("steps", JSONArray().apply { e.steps.forEach { s -> put(stepJson(s)) } })
        put("amass", JSONArray().apply { putDoubles(e.actualComponentMassesG) })
        put("asolv", e.actualSolventMassG ?: JSONObject.NULL)
        put("visc", e.resultViscosityMPas ?: JSONObject.NULL)
        put("viscCond", e.resultViscosityMpaTime)
        put("temp", e.resultTemperatureC ?: JSONObject.NULL)
        put("grind", e.grindLevel); put("grindUm", e.grindMaxParticleUm ?: JSONObject.NULL)
        put("measDens", e.measuredDensityGcm3 ?: JSONObject.NULL)
        put("comment", e.comment); put("verdict", e.verdict)
        if (e.isTask) put("isTask", true)
        if (e.stepStatuses.isNotEmpty())
            put("stepStatuses", JSONArray().apply { e.stepStatuses.forEach { put(it) } })
    }

    private fun fromJson(o: JSONObject): JournalEntry {
        fun strList(key: String): List<String> =
            o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        fun dblList(key: String): List<Double> =
            o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optDouble(it, 0.0) } } ?: emptyList()
        fun optD(key: String): Double? = if (o.has(key) && !o.isNull(key)) o.optDouble(key).takeIf { !it.isNaN() } else null
        return JournalEntry(
            id = o.optLong("id"),
            timestampMs = o.optLong("ts"),
            title = o.optString("title"),
            plannedDryContentPct = o.optDouble("pdc", 0.0),
            plannedWetMassG = o.optDouble("pwet", 0.0),
            recipeSummary = o.optString("recipe"),
            componentNames = strList("names"),
            componentDensities = dblList("dens"),
            plannedComponentMassesG = dblList("pmass"),
            plannedSolventName = o.optString("solvName"),
            plannedSolventDensity = o.optDouble("solvDens", 1.0),
            plannedSolventMassG = o.optDouble("solvMass", 0.0),
            mixingRegimeId = o.optString("regime"),
            mixingDurationMin = optD("dur"),
            mixingNotes = o.optString("mixNotes"),
            steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { stepFromJson(a.getJSONObject(it)) } } ?: emptyList(),
            actualComponentMassesG = o.optJSONArray("amass")?.doubles() ?: emptyList(),
            actualSolventMassG = optD("asolv"),
            resultViscosityMPas = optD("visc"),
            resultViscosityMpaTime = o.optString("viscCond"),
            resultTemperatureC = optD("temp"),
            grindLevel = o.optString("grind"),
            grindMaxParticleUm = optD("grindUm"),
            measuredDensityGcm3 = optD("measDens"),
            comment = o.optString("comment"),
            verdict = o.optString("verdict"),
            isTask = o.optBoolean("isTask", false),
            stepStatuses = o.optJSONArray("stepStatuses")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList(),
        )
    }
}
