package com.slurrycalc.data

import android.content.Context
import com.slurrycalc.domain.JournalEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Миграция SharedPreferences → Room (этап 1e).
 *
 * Порядок:
 *  1. Читается JSON из старых prefs (journal.db / materials.db / regimes.db) — БЕЗ изменений;
 *  2. Делается резервная копия: файл backup/legacy-backup-<stamp>.json в filesDir приложения;
 *  3. Данные конвертируются в единицы БД (мг/Па·с/кг·м⁻³) и пишутся в Room;
 *  4. Ставится флаг meta("migrated_v1")=timestamp — повторная миграция не выполняется;
 *  5. Старые prefs НЕ удаляются (удаление — только по решению пользователя, этап вне этого кода).
 */
object MigrationEngine {

    const val FLAG_MIGRATED = "migrated_v1"

    data class Report(
        val ran: Boolean,
        val journalCount: Int,
        val materialCount: Int,
        val regimeCount: Int,
        val backupFile: String?,
        val errors: List<String>,
    )

    /** Выполнить миграцию при необходимости. Синхронно (вызывать с фоновой нити при старте). */
    fun migrateIfNeeded(context: Context, db: AppDatabase): Report {
        if (db.metaDao().get(FLAG_MIGRATED) != null)
            return Report(false, 0, 0, 0, null, emptyList())

        val errors = mutableListOf<String>()

        // ---- 1. читаем старые данные как raw JSON ----
        val legacyJournal = context.getSharedPreferences("journal.db", Context.MODE_PRIVATE)
            .getString("entries", "[]") ?: "[]"
        val legacyMaterials = context.getSharedPreferences("materials.db", Context.MODE_PRIVATE)
            .getString("entries", "[]") ?: "[]"
        val legacyRegimes = context.getSharedPreferences("regimes.db", Context.MODE_PRIVATE)
            .getString("customs", "[]") ?: "[]"

        // ---- 2. резервная копия в файл ----
        var backupPath: String? = null
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(context.filesDir, "backup").apply { mkdirs() }
            val file = File(dir, "legacy-backup-$stamp.json")
            file.writeText(JSONObject().apply {
                put("exportedAt", System.currentTimeMillis())
                put("source", "SharedPreferences -> Room migration")
                put("journal", JSONArray(legacyJournal))
                put("materials", JSONArray(legacyMaterials))
                put("regimes", JSONArray(legacyRegimes))
            }.toString(2))
            backupPath = file.absolutePath
        } catch (e: Exception) {
            errors.add("backup: ${e.message}")
        }

        // ---- 3. перенос в Room (единицы: г→мг, мПа·с→Па·с, г/см³→кг/м³) ----
        var jn = 0; var mn = 0; var rn = 0
        try {
            val jArr = JSONArray(legacyJournal)
            for (i in 0 until jArr.length()) {
                val o = jArr.getJSONObject(i)
                db.journalDao().insertRaw(
                    ts = o.optLong("ts"),
                    title = o.optString("title"),
                    dryContentPct = o.optDouble("pdc", 0.0),
                    wetMassMg = g(o, "pwet"),
                    recipeSummary = o.optString("recipe"),
                    componentsJson = convertComponents(o),
                    solventName = o.optString("solvName"),
                    solventDensityKgm3 = o.optDouble("solvDens", 1.0) * 1000.0,
                    solventPlannedMg = g(o, "solvMass"),
                    regimeId = o.optString("regime"),
                    mixDurMin = od(o, "dur"),
                    mixNotes = o.optString("mixNotes"),
                    stepsJson = convertSteps(o),
                    actualMassesJson = convertActual(o),
                    actualSolventMg = if (o.isNull("asolv")) null else g(o, "asolv"),
                    viscPas = od(o, "visc")?.let { it / 1000.0 },   // мПа·с → Па·с
                    viscCond = o.optString("viscCond"),
                    tempC = od(o, "temp"),
                    grindLevel = o.optString("grind"),
                    grindUm = od(o, "grindUm"),
                    measDensityKgm3 = od(o, "measDens")?.let { it * 1000.0 },
                    comment = o.optString("comment"),
                    verdict = o.optString("verdict"),
                    isTask = o.optBoolean("isTask", false),
                    stepStatusesJson = o.optJSONArray("stepStatuses")?.toString() ?: "[]",
                    immutable = true, // импортированные записи помечаем защищёнными (1f уточнит правила)
                )
                jn++
            }
        } catch (e: Exception) { errors.add("journal: ${e.message}") }

        try {
            val mArr = JSONArray(legacyMaterials)
            for (i in 0 until mArr.length()) {
                val o = mArr.getJSONObject(i)
                db.materialDao().insert(MaterialRow(
                    id = 2000L + o.getLong("id"),           // смещаем legacy-id, чтобы не сталкивались с новыми
                    name = o.getString("name"),
                    role = o.optString("role"),
                    densityKgm3 = o.getDouble("density") * 1000.0,
                    tapDensityKgm3 = odn(o, "tap")?.times(1000.0),
                    d50Um = odn(o, "d50"),
                    betM2g = odn(o, "bet"),
                    ph = odn(o, "ph"),
                    moisturePpm = if (o.has("moist")) o.optInt("moist") else null,
                    dryingTempC = odn(o, "dryT"),
                    capacityMahG = odn(o, "cap"),
                    conductivityScm = odn(o, "cond"),
                    viscosityPas = odn(o, "visc")?.let { it / 1000.0 },
                    note = o.optString("note"),
                    custom = true,
                ))
                mn++
            }
        } catch (e: Exception) { errors.add("materials: ${e.message}") }

        try {
            val rArr = JSONArray(legacyRegimes)
            for (i in 0 until rArr.length()) {
                val o = rArr.getJSONObject(i)
                db.regimeDao().insert(RegimeRow(
                    id = "legacy_" + o.optString("id"),
                    name = o.optString("name"),
                    tool = o.optString("tool"),
                    disperserRpmHint = if (!o.isNull("dispr")) o.optInt("dispr").takeIf { it > 0 } else null,
                    bladeRpmHint = if (!o.isNull("blader")) o.optInt("blader").takeIf { it > 0 } else null,
                    timeMinHint = odn(o, "time"),
                    tempLimitC = odn(o, "temp"),
                    description = o.optString("desc"),
                ))
                rn++
            }
        } catch (e: Exception) { errors.add("regimes: ${e.message}") }

        // ---- 4. флаг ----
        db.metaDao().put(MetaRow(FLAG_MIGRATED, "${System.currentTimeMillis()}"))

        return Report(true, jn, mn, rn, backupPath, errors)
    }

    // ---------- helpers (формат старых prefs см. JournalStore.toJson) ----------

    private fun g(o: JSONObject, key: String): Long =
        Math.round(o.optDouble(key, 0.0) * 1000.0)

    private fun od(o: JSONObject, key: String): Double? =
        if (o.has(key) && !o.isNull(key)) o.optDouble(key).takeIf { !it.isNaN() } else null

    private fun odn(o: JSONObject, key: String): Double? = od(o, key)

    /** names+dens+pmass (параллельные массивы в г/г·см⁻³) -> [{name, density_kgm3, planned_mg}] */
    private fun convertComponents(o: JSONObject): String {
        val names = o.optJSONArray("names") ?: JSONArray()
        val dens = o.optJSONArray("dens") ?: JSONArray()
        val mass = o.optJSONArray("pmass") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until names.length()) {
            out.put(JSONObject().apply {
                put("name", names.optString(i))
                put("density_kgm3", dens.optDouble(i, 0.0) * 1000.0)
                put("planned_mg", Math.round(mass.optDouble(i, 0.0) * 1000.0))
            })
        }
        return out.toString()
    }

    /** amass: [г|null] -> [мг|null] */
    private fun convertActual(o: JSONObject): String {
        val a = o.optJSONArray("amass") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until a.length()) {
            if (a.isNull(i)) out.put(JSONObject.NULL)
            else out.put(Math.round(a.optDouble(i, 0.0) * 1000.0).toLong())
        }
        return out.toString()
    }

    /** steps в старом формате уже нейтральны к единицам (об/мин, мин, °C) — переносим как есть */
    private fun convertSteps(o: JSONObject): String =
        (o.optJSONArray("steps") ?: JSONArray()).toString()

    /** Создать временную БД для теста миграции (instrumentation-тест, файл в cacheDir). */
    fun openTestDb(context: Context): AppDatabase =
        AppDatabase.openForTest(
            context,
            File(context.cacheDir, "migration-test-${System.nanoTime()}.db"),
        )

    /** Для теста: прямой перенос одной записи журнала из legacy-JSON в given базу. */
    fun migrateJournalEntry(e: JournalEntry, db: AppDatabase): Long {
        val o = JSONObject().apply {
            put("ts", e.timestampMs); put("title", e.title)
            put("pdc", e.plannedDryContentPct); put("pwet", e.plannedWetMassG)
            put("recipe", e.recipeSummary)
            put("names", JSONArray().apply { e.componentNames.forEach { put(it) } })
            put("dens", JSONArray().apply { e.componentDensities.forEach { put(it) } })
            put("pmass", JSONArray().apply { e.plannedComponentMassesG.forEach { put(it) } })
            put("solvName", e.plannedSolventName); put("solvDens", e.plannedSolventDensity)
            put("solvMass", e.plannedSolventMassG)
            put("regime", e.mixingRegimeId)
            put("dur", e.mixingDurationMin ?: JSONObject.NULL)
            put("mixNotes", e.mixingNotes)
            put("steps", JSONArray().apply { e.steps.forEach { st -> put(Mappers.stepJson(st)) } })
            put("amass", JSONArray().apply {
                e.actualComponentMassesG.forEach { if (it == null) put(JSONObject.NULL) else put(it) }
            })
            put("asolv", e.actualSolventMassG ?: JSONObject.NULL)
            put("visc", e.resultViscosityMPas ?: JSONObject.NULL)
            put("viscCond", e.resultViscosityMpaTime)
            put("temp", e.resultTemperatureC ?: JSONObject.NULL)
            put("grind", e.grindLevel); put("grindUm", e.grindMaxParticleUm ?: JSONObject.NULL)
            put("measDens", e.measuredDensityGcm3 ?: JSONObject.NULL)
            put("comment", e.comment); put("verdict", e.verdict)
            if (e.isTask) put("isTask", true)
            put("stepStatuses", JSONArray().apply { e.stepStatuses.forEach { put(it) } })
        }
        return db.journalDao().insertRaw(
            ts = o.optLong("ts"),
            title = o.optString("title"),
            dryContentPct = o.optDouble("pdc", 0.0),
            wetMassMg = g(o, "pwet"),
            recipeSummary = o.optString("recipe"),
            componentsJson = convertComponents(o),
            solventName = o.optString("solvName"),
            solventDensityKgm3 = o.optDouble("solvDens", 1.0) * 1000.0,
            solventPlannedMg = g(o, "solvMass"),
            regimeId = o.optString("regime"),
            mixDurMin = od(o, "dur"),
            mixNotes = o.optString("mixNotes"),
            stepsJson = convertSteps(o),
            actualMassesJson = convertActual(o),
            actualSolventMg = if (o.isNull("asolv")) null else g(o, "asolv"),
            viscPas = od(o, "visc")?.let { it / 1000.0 },
            viscCond = o.optString("viscCond"),
            tempC = od(o, "temp"),
            grindLevel = o.optString("grind"),
            grindUm = od(o, "grindUm"),
            measDensityKgm3 = od(o, "measDens")?.let { it * 1000.0 },
            comment = o.optString("comment"),
            verdict = o.optString("verdict"),
            isTask = o.optBoolean("isTask", false),
            stepStatusesJson = o.optJSONArray("stepStatuses")?.toString() ?: "[]",
            immutable = true,
        )
    }
}
