package com.slurrycalc.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Кодек старого JSON-формата SharedPreferences (этап 1e: тесты миграции).
 *
 * Формат сериализации перенесён дословно из legacy-хранилищ
 * (JournalStore / MaterialsDb / RegimeStore), чтобы тесты могли строить
 * эталонные legacy-документы без Android-контекста, а MigrationEngine —
 * читать ровно то, что писали старые классы.
 *
 * Единицы в этом формате — UI-старые: г, г/см³, мПа·с.
 */
object LegacyJson {

    /** Ключ, под которым старый JournalStore/MaterialsDb хранили массив записей. */
    const val KEY_ENTRIES = "entries"
    /** Ключ, под которым старый RegimeStore хранил массив пользовательских режимов. */
    const val KEY_CUSTOMS = "customs"

    // ---------- helpers ----------

    private fun JSONArray.putDoubles(v: List<Double?>) {
        v.forEach { if (it == null) put(JSONObject.NULL) else put(it) }
    }

    private fun JSONArray.doubles(): List<Double?> {
        val out = ArrayList<Double?>(length())
        for (i in 0 until length()) out.add(if (isNull(i)) null else getDouble(i))
        return out
    }

    private fun JSONArray.addDoubles(v: List<Double>) { v.forEach { put(it) } }

    fun stepJson(s: MixingStep): JSONObject = JSONObject().apply {
        put("action", s.action)
        put("tool", s.tool.name)
        put("dispr", s.disperserRpm ?: JSONObject.NULL)
        put("blader", s.bladeRpm ?: JSONObject.NULL)
        put("time", s.durationMin ?: JSONObject.NULL)
        put("temp", s.tempC ?: JSONObject.NULL)
        put("vacuum", s.vacuum)
        put("note", s.comment)
    }

    fun stepFromJson(o: JSONObject): MixingStep = MixingStep(
        action = o.optString("action"),
        tool = runCatching { MixToolType.valueOf(o.optString("tool")) }.getOrDefault(MixToolType.OTHER),
        disperserRpm = if (!o.isNull("dispr")) o.optInt("dispr").takeIf { it > 0 } else null,
        bladeRpm = if (!o.isNull("blader")) o.optInt("blader").takeIf { it > 0 } else null,
        durationMin = if (!o.isNull("time")) o.optDouble("time").takeIf { !it.isNaN() } else null,
        tempC = if (!o.isNull("temp")) o.optDouble("temp").takeIf { !it.isNaN() } else null,
        vacuum = o.optBoolean("vacuum", false),
        comment = o.optString("note"),
    )

    // ---------- journal ----------

    fun journalEntryToJson(e: JournalEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("ts", e.timestampMs)
        put("title", e.title)
        put("pdc", e.plannedDryContentPct)
        put("pwet", e.plannedWetMassG)
        put("recipe", e.recipeSummary)
        put("names", JSONArray().also { a -> e.componentNames.forEach { a.put(it) } })
        put("dens", JSONArray().also { a -> e.componentDensities.forEach { a.put(it) } })
        put("pmass", JSONArray().also { a -> e.plannedComponentMassesG.forEach { a.put(it) } })
        put("solvName", e.plannedSolventName)
        put("solvDens", e.plannedSolventDensity)
        put("solvMass", e.plannedSolventMassG)
        put("regime", e.mixingRegimeId)
        put("dur", e.mixingDurationMin ?: JSONObject.NULL)
        put("mixNotes", e.mixingNotes)
        put("steps", JSONArray().also { a -> e.steps.forEach { a.put(stepJson(it)) } })
        put("amass", JSONArray().also { a -> a.putDoubles(e.actualComponentMassesG) })
        put("asolv", e.actualSolventMassG ?: JSONObject.NULL)
        put("visc", e.resultViscosityMPas ?: JSONObject.NULL)
        put("viscCond", e.resultViscosityMpaTime)
        put("temp", e.resultTemperatureC ?: JSONObject.NULL)
        put("grind", e.grindLevel)
        put("grindUm", e.grindMaxParticleUm ?: JSONObject.NULL)
        put("measDens", e.measuredDensityGcm3 ?: JSONObject.NULL)
        put("comment", e.comment)
        put("verdict", e.verdict)
        put("isTask", e.isTask)
        put("stepStatuses", JSONArray().also { a -> e.stepStatuses.forEach { a.put(it) } })
    }

    fun journalEntryFromJson(o: JSONObject): JournalEntry {
        fun strList(key: String): List<String> =
            (o.optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } }

        fun dblList(key: String): List<Double> =
            (o.optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).map { a.optDouble(it, 0.0) } }

        fun optD(key: String): Double? =
            if (o.has(key) && !o.isNull(key)) o.optDouble(key).takeIf { !it.isNaN() } else null

        val names = strList("names")
        val stepsArr = o.optJSONArray("steps") ?: JSONArray()
        val statusesArr = o.optJSONArray("stepStatuses") ?: JSONArray()
        return JournalEntry(
            id = o.optLong("id"),
            timestampMs = o.optLong("ts"),
            title = o.optString("title"),
            plannedDryContentPct = o.optDouble("pdc", 0.0),
            plannedWetMassG = o.optDouble("pwet", 0.0),
            recipeSummary = o.optString("recipe"),
            componentNames = names,
            componentDensities = dblList("dens"),
            plannedComponentMassesG = dblList("pmass"),
            plannedSolventName = o.optString("solvName"),
            plannedSolventDensity = o.optDouble("solvDens", 1.0),
            plannedSolventMassG = o.optDouble("solvMass", 0.0),
            mixingRegimeId = o.optString("regime"),
            mixingDurationMin = optD("dur"),
            mixingNotes = o.optString("mixNotes"),
            steps = (0 until stepsArr.length()).map { stepFromJson(stepsArr.getJSONObject(it)) },
            actualComponentMassesG = (o.optJSONArray("amass") ?: JSONArray()).doubles(),
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
            stepStatuses = (0 until statusesArr.length()).map { statusesArr.optString(it) },
        )
    }

    // ---------- materials ----------

    fun materialToJson(e: MaterialEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("name", e.name)
        put("role", e.role.name)
        put("density", e.density)
        put("tap", e.tapDensity ?: JSONObject.NULL)
        put("d50", e.particleSizeD50Um ?: JSONObject.NULL)
        put("bet", e.betAreaM2g ?: JSONObject.NULL)
        put("ph", e.ph ?: JSONObject.NULL)
        put("moist", e.moisturePpm ?: JSONObject.NULL)
        put("dryT", e.dryingTempC ?: JSONObject.NULL)
        put("cap", e.capacityMahG ?: JSONObject.NULL)
        put("cond", e.conductivityScm ?: JSONObject.NULL)
        put("visc", e.viscosityMPas ?: JSONObject.NULL)
        put("note", e.note)
    }

    fun materialFromJson(o: JSONObject): MaterialEntry {
        fun od(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
        return MaterialEntry(
            id = o.optLong("id"),
            name = o.optString("name"),
            role = MaterialRole.fromName(o.optString("role")),
            density = o.optDouble("density", 1.0),
            tapDensity = od("tap"),
            particleSizeD50Um = od("d50"),
            betAreaM2g = od("bet"),
            ph = od("ph"),
            moisturePpm = if (o.has("moist") && !o.isNull("moist")) o.optInt("moist") else null,
            dryingTempC = od("dryT"),
            capacityMahG = od("cap"),
            conductivityScm = od("cond"),
            viscosityMPas = od("visc"),
            note = o.optString("note"),
            custom = true,
        )
    }

    // ---------- regimes ----------

    fun regimeToJson(r: CustomRegime): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("tool", r.tool.name)
        put("dispr", r.disperserRpmHint ?: JSONObject.NULL)
        put("blader", r.bladeRpmHint ?: JSONObject.NULL)
        put("time", r.timeMinHint ?: JSONObject.NULL)
        put("temp", r.tempLimitC ?: JSONObject.NULL)
        put("desc", r.description)
    }

    fun regimeFromJson(o: JSONObject): CustomRegime = CustomRegime(
        id = o.optString("id"),
        name = o.optString("name"),
        tool = runCatching { MixToolType.valueOf(o.optString("tool")) }.getOrDefault(MixToolType.PLANETARY),
        disperserRpmHint = if (!o.isNull("dispr")) o.optInt("dispr").takeIf { it > 0 } else null,
        bladeRpmHint = if (!o.isNull("blader")) o.optInt("blader").takeIf { it > 0 } else null,
        timeMinHint = if (!o.isNull("time")) o.optDouble("time").takeIf { !it.isNaN() } else null,
        tempLimitC = if (!o.isNull("temp")) o.optDouble("temp").takeIf { !it.isNaN() } else null,
        description = o.optString("desc"),
    )
}
