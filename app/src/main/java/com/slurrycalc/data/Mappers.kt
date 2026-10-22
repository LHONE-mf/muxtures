package com.slurrycalc.data

import android.content.Context
import com.slurrycalc.calc.Units
import com.slurrycalc.domain.JournalEntry
import com.slurrycalc.domain.MaterialEntry
import com.slurrycalc.domain.MaterialRole
import com.slurrycalc.domain.MixToolType
import com.slurrycalc.domain.CustomRegime
import com.slurrycalc.domain.MixingStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Мапперы domain <-> Room. ЕДИНСТВЕННОЕ место (вместе с UI), где происходит
 * перевод единиц: г ↔ мг, мПа·с ↔ Па·с, г/см³ ↔ кг/м³ (правило SPEC п.0.5).
 */
object Mappers {

    // ---------------- Journal ----------------

    fun toRow(e: JournalEntry): JournalRow = JournalRow(
        id = e.id,
        ts = e.timestampMs,
        title = e.title,
        dryContentPct = e.plannedDryContentPct,
        wetMassMg = Units.gToMg(e.plannedWetMassG),
        recipeSummary = e.recipeSummary,
        componentsJson = JSONArray().apply {
            e.componentNames.forEachIndexed { i, n ->
                put(JSONObject().apply {
                    put("name", n)
                    put("density_kgm3", Units.gcm3ToKgM3(e.componentDensities.getOrElse(i) { 0.0 }))
                    put("planned_mg", Units.gToMg(e.plannedComponentMassesG.getOrElse(i) { 0.0 }))
                })
            }
        }.toString(),
        solventName = e.plannedSolventName,
        solventDensityKgm3 = Units.gcm3ToKgM3(e.plannedSolventDensity),
        solventPlannedMg = Units.gToMg(e.plannedSolventMassG),
        regimeId = e.mixingRegimeId,
        mixDurMin = e.mixingDurationMin,
        mixNotes = e.mixingNotes,
        stepsJson = JSONArray().apply { e.steps.forEach { put(stepJson(it)) } }.toString(),
        actualMassesJson = JSONArray().apply {
            e.actualComponentMassesG.forEach { m ->
                if (m == null) put(JSONObject.NULL) else put(Units.gToMg(m))
            }
        }.toString(),
        actualSolventMg = e.actualSolventMassG?.let { Units.gToMg(it) },
        viscPas = e.resultViscosityMPas?.let { Units.mPasToPas(it) },
        viscCond = e.resultViscosityMpaTime,
        tempC = e.resultTemperatureC,
        grindLevel = e.grindLevel,
        grindUm = e.grindMaxParticleUm,
        measDensityKgm3 = e.measuredDensityGcm3?.let { Units.gcm3ToKgM3(it) },
        comment = e.comment,
        verdict = e.verdict,
        isTask = e.isTask,
        stepStatusesJson = JSONArray().apply { e.stepStatuses.forEach { put(it) } }.toString(),
        // этап 1f: любая запись, попавшая в журнал, неизменяема (удаление запрещено)
        immutable = true,
    )

    fun fromRow(r: JournalRow): JournalEntry {
        val comps = parseComponents(r.componentsJson)
        val actuals = parseActualMasses(r.actualMassesJson, comps.size)
        return JournalEntry(
            id = r.id,
            timestampMs = r.ts,
            title = r.title,
            plannedDryContentPct = r.dryContentPct,
            plannedWetMassG = Units.mgToG(r.wetMassMg),
            recipeSummary = r.recipeSummary,
            componentNames = comps.map { it.name },
            componentDensities = comps.map { Units.kgm3ToGcm3(it.densityKgm3) },
            plannedComponentMassesG = comps.map { Units.mgToG(it.plannedMg) },
            plannedSolventName = r.solventName,
            plannedSolventDensity = Units.kgm3ToGcm3(r.solventDensityKgm3),
            plannedSolventMassG = Units.mgToG(r.solventPlannedMg),
            mixingRegimeId = r.regimeId,
            mixingDurationMin = r.mixDurMin,
            mixingNotes = r.mixNotes,
            steps = parseStepsForTest(r.stepsJson),
            actualComponentMassesG = actuals,
            actualSolventMassG = r.actualSolventMg?.let { Units.mgToG(it) },
            resultViscosityMPas = r.viscPas?.let { Units.pasToMPas(it) },
            resultViscosityMpaTime = r.viscCond,
            resultTemperatureC = r.tempC,
            grindLevel = r.grindLevel,
            grindMaxParticleUm = r.grindUm,
            measuredDensityGcm3 = r.measDensityKgm3?.let { Units.kgm3ToGcm3(it) },
            comment = r.comment,
            verdict = r.verdict,
            isTask = r.isTask,
            stepStatuses = parseStrings(r.stepStatusesJson),
        )
    }

    private data class Comp(val name: String, val densityKgm3: Double, val plannedMg: Long)

    private fun parseComponents(json: String): List<Comp> = try {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Comp(o.optString("name"), o.optDouble("density_kgm3", 0.0), o.optLong("planned_mg"))
        }
    } catch (_: Exception) { emptyList() }

    private fun parseActualMasses(json: String, n: Int): List<Double?> = try {
        val a = JSONArray(json)
        (0 until minOf(a.length(), n)).map {
            if (a.isNull(it)) null else Units.mgToG(a.getLong(it))
        }
    } catch (_: Exception) { List(n) { null } }

    private fun parseStrings(json: String): List<String> = try {
        val a = JSONArray(json); (0 until a.length()).map { a.optString(it) }
    } catch (_: Exception) { emptyList() }

    fun stepJson(s: MixingStep) = JSONObject().apply {
        put("action", s.action); put("tool", s.tool.name)
        put("dispr", s.disperserRpm ?: JSONObject.NULL)
        put("blader", s.bladeRpm ?: JSONObject.NULL)
        put("dur", s.durationMin ?: JSONObject.NULL)
        put("temp", s.tempC ?: JSONObject.NULL)
        if (s.vacuum) put("vac", true)
        put("cmt", s.comment)
    }

    fun parseStepsForTest(json: String): List<MixingStep> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            MixingStep(
                action = o.optString("action"),
                tool = MixToolType.fromName(o.optString("tool")),
                disperserRpm = if (o.isNull("dispr")) null else o.optInt("dispr").takeIf { it > 0 },
                bladeRpm = if (o.isNull("blader")) null else o.optInt("blader").takeIf { it > 0 },
                durationMin = if (o.isNull("dur")) null else o.optDouble("dur").takeIf { !it.isNaN() },
                tempC = if (o.isNull("temp")) null else o.optDouble("temp").takeIf { !it.isNaN() },
                vacuum = o.optBoolean("vac", false),
                comment = o.optString("cmt"),
            )
        }
    } catch (_: Exception) { emptyList() }

    // ---------------- Materials ----------------

    fun toRow(m: MaterialEntry): MaterialRow = MaterialRow(
        id = m.id,
        name = m.name,
        role = m.role.name,
        densityKgm3 = Units.gcm3ToKgM3(m.density),
        tapDensityKgm3 = m.tapDensity?.let { Units.gcm3ToKgM3(it) },
        d50Um = m.particleSizeD50Um,
        betM2g = m.betAreaM2g,
        ph = m.ph,
        moisturePpm = m.moisturePpm,
        dryingTempC = m.dryingTempC,
        capacityMahG = m.capacityMahG,
        conductivityScm = m.conductivityScm,
        viscosityPas = m.viscosityMPas?.let { Units.mPasToPas(it) },
        note = m.note,
        custom = m.custom,
    )

    fun fromRow(r: MaterialRow): MaterialEntry = MaterialEntry(
        id = r.id,
        name = r.name,
        role = MaterialRole.fromName(r.role),
        density = Units.kgm3ToGcm3(r.densityKgm3),
        tapDensity = r.tapDensityKgm3?.let { Units.kgm3ToGcm3(it) },
        particleSizeD50Um = r.d50Um,
        betAreaM2g = r.betM2g,
        ph = r.ph,
        moisturePpm = r.moisturePpm,
        dryingTempC = r.dryingTempC,
        capacityMahG = r.capacityMahG,
        conductivityScm = r.conductivityScm,
        viscosityMPas = r.viscosityPas?.let { Units.pasToMPas(it) },
        note = r.note,
        custom = r.custom,
    )

    // ---------------- Regimes ----------------

    fun toRow(r: CustomRegime): RegimeRow = RegimeRow(
        id = r.id, name = r.name, tool = r.tool.name,
        disperserRpmHint = r.disperserRpmHint, bladeRpmHint = r.bladeRpmHint,
        timeMinHint = r.timeMinHint, tempLimitC = r.tempLimitC, description = r.description,
    )

    fun fromRow(r: RegimeRow): CustomRegime = CustomRegime(
        id = r.id, name = r.name, tool = MixToolType.fromName(r.tool),
        disperserRpmHint = r.disperserRpmHint, bladeRpmHint = r.bladeRpmHint,
        timeMinHint = r.timeMinHint, tempLimitC = r.tempLimitC, description = r.description,
    )
}
