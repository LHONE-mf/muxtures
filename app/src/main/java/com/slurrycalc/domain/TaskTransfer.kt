package com.slurrycalc.domain

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Передача заданий между телефонами («выдача задачи подчинённому»).
 *
 * Задание = план замеса из журнала (состав, массы, этапы смешивания).
 * Формат — компактный JSON (версия 1), кодируется в base64url для QR/ссылки.
 *
 * Каналы доставки (по убыванию удобства):
 *  1. QR-код на экране — исполнитель сканирует камерой (Google Camera / сканер в приложении).
 *     Работает без интернета и Bluetooth, только для заданий ~до 1–2 КБ.
 *  2. Системный «Поделиться» (Share sheet): Telegram / WhatsApp / Email / SMS / Bluetooth /
 *     Nearby Share (Quick Share) — любой текст, работает всегда. На телефоне исполнителя
 *     файл .slurrytask.json открывается этим же приложением (фильтр *.json в манифесте)
 *     либо импортируется через «Импорт по буферу обмена».
 *  3. Ссылка slurrycalc://task?d=<base64> + deep link https://slurrycalc.local/task#d=... —
 *     удобно для мессенджеров; принимает только Android-приложение.
 *
 * Если у исполнителя НЕ Android: тот же JSON читается глазами и вводится вручную;
 * дополнительно задание можно выгрузить в CSV/текст (Export.exportCsv) — откроется в любом
 * редакторе/заметках iPhone или планшета. Для двусторонней синхронизации между платформами
 * нужен сервер (Firebase Realtime DB / Supabase / self-hosted API) — см. README.
 */
object TaskTransfer {

    const val FORMAT_ID = "slurry-task"
    const val VERSION = 1
    const val FILE_EXT = "slurrytask.json"
    /** границы читаемости QR (версия ≤37): до 900 б — надёжно, до 1500 б — крупный QR */
    const val QR_RELIABLE_B64 = 900
    const val QR_MAX_B64 = 1500

    private fun ts(ms: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(ms))

    // ---------- сериализация ----------

    /** Компактный JSON для QR/ссылки: ASCII + \\uXXXX-экранирование кириллицы и короткие имена полей */
    fun toJsonCompact(e: JournalEntry): String {
        val sb = StringBuilder()
        fun esc(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> {}
                c == '\t' -> b.append("\\t")
                c.code < 32 -> {}
                c.code < 128 -> b.append(c)
                else -> b.append(String.format("\\u%04x", c.code))
            }
            b.append('"')
            return b.toString()
        }
        fun d(v: Double?, digits: Int): String =
            if (v == null || v.isNaN()) "null" else String.format(Locale.US, "%.${digits}f", v)

        sb.append("{\"f\":\"$FORMAT_ID\",\"v\":$VERSION")
        sb.append(",\"t\":").append(esc(e.title))
        sb.append(",\"ts\":").append(e.timestampMs)
        sb.append(",\"dc\":").append(d(e.plannedDryContentPct, 2))
        sb.append(",\"wm\":").append(d(e.plannedWetMassG, 1))
        sb.append(",\"c\":[")
        e.componentNames.forEachIndexed { i, n ->
            if (i > 0) sb.append(',')
            sb.append('[').append(esc(n)).append(',')
              .append(d(e.componentDensities.getOrElse(i) { 0.0 }, 3)).append(',')
              .append(d(e.plannedComponentMassesG.getOrElse(i) { 0.0 }, 2)).append(']')
        }
        sb.append("],\"s\":[")
          .append(esc(e.plannedSolventName)).append(',')
          .append(d(e.plannedSolventDensity, 3)).append(',')
          .append(d(e.plannedSolventMassG, 1)).append(']')
        sb.append(",\"r\":").append(esc(e.mixingRegimeId))
        sb.append(",\"dur\":").append(d(e.mixingDurationMin, 0))
        sb.append(",\"notes\":").append(esc(e.mixingNotes))
        sb.append(",\"steps\":[")
        e.steps.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('[').append(esc(s.action)).append(',').append(esc(s.tool.name)).append(',')
              .append(s.disperserRpm?.toString() ?: "null").append(',')
              .append(s.bladeRpm?.toString() ?: "null").append(',')
              .append(d(s.durationMin, 1)).append(',')
              .append(d(s.tempC, 1)).append(',')
              .append(if (s.vacuum) 1 else 0).append(',')
              .append(esc(s.comment)).append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Парсит и компактный (QR), и полный (файл) формат задания */
    fun fromJson(raw: String): JournalEntry {
        val o = try { JSONObject(raw.trim()) } catch (ex: Exception) {
            throw IllegalArgumentException("Это не JSON-файл задания")
        }
        if (o.optString("f").ifBlank { o.optString("format") } != FORMAT_ID)
            throw IllegalArgumentException("Не формат «$FORMAT_ID» (чужой файл)")

        fun arrOrObj(nameKey: String, arrIdx: Int, objKey: String, def: String): String =
            if (o.optJSONArray(nameKey) != null) o.getJSONArray(nameKey).optString(arrIdx, def)
            else o.optString(objKey, def)

        val stepsList = ArrayList<MixingStep>()
        o.optJSONArray("steps")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.get(i)
                if (s is JSONArray) {   // компактный: [action, tool, dispr, blader, dur, temp, vac, cmt]
                    stepsList.add(MixingStep(
                        id = 0L,
                        action = s.optString(0, ""),
                        tool = MixToolType.fromName(s.optString(1)),
                        disperserRpm = if (s.isNull(2)) null else s.optInt(2).takeIf { it > 0 },
                        bladeRpm = if (s.isNull(3)) null else s.optInt(3).takeIf { it > 0 },
                        durationMin = if (s.isNull(4)) null else s.optDouble(4).takeIf { !it.isNaN() },
                        tempC = if (s.isNull(5)) null else s.optDouble(5).takeIf { !it.isNaN() },
                        vacuum = s.optInt(6, 0) == 1,
                        comment = s.optString(7, ""),
                    ))
                } else if (s is JSONObject) {  // полный формат файла
                    stepsList.add(MixingStep(
                        id = 0L,
                        action = s.optString("action"),
                        tool = MixToolType.fromName(s.optString("tool")),
                        disperserRpm = if (s.isNull("dispr")) null else s.optInt("dispr").takeIf { it > 0 },
                        bladeRpm = if (s.isNull("blader")) null else s.optInt("blader").takeIf { it > 0 },
                        durationMin = if (s.isNull("dur")) null else s.optDouble("dur").takeIf { !it.isNaN() },
                        tempC = if (s.isNull("temp")) null else s.optDouble("temp").takeIf { !it.isNaN() },
                        vacuum = s.optBoolean("vac", false),
                        comment = s.optString("cmt"),
                    ))
                }
            }
        }

        val names = ArrayList<String>(); val dens = ArrayList<Double>(); val pmass = ArrayList<Double>()
        o.optJSONArray("c")?.let { a ->       // компактный: [[name, density, plannedMass], …]
            for (i in 0 until a.length()) {
                val c = a.getJSONArray(i)
                names.add(c.optString(0)); dens.add(c.optDouble(1, 0.0)); pmass.add(c.optDouble(2, 0.0))
            }
        }
        o.optJSONArray("components")?.let { a ->   // полный формат файла
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                names.add(c.optString("name")); dens.add(c.optDouble("density", 0.0)); pmass.add(c.optDouble("plannedMassG", 0.0))
            }
        }
        if (names.isEmpty()) throw IllegalArgumentException("В задании нет компонентов")

        val solventArr = o.optJSONArray("s")
        val solvName = if (solventArr != null) solventArr.optString(0, "NMP") else o.optString("solventName", "NMP")
        val solvDens = if (solventArr != null) solventArr.optDouble(1, 1.0) else o.optDouble("solventDensity", 1.0)
        val solvMass = if (solventArr != null) solventArr.optDouble(2, 0.0) else o.optDouble("solventPlannedMassG", 0.0)
        val dc = if (o.has("dc")) o.optDouble("dc", 0.0) else o.optDouble("dryContentPct", 0.0)
        val wm = if (o.has("wm")) o.optDouble("wm", 0.0) else o.optDouble("wetMassG", 0.0)
        val tms = if (o.has("ts")) o.optLong("ts") else System.currentTimeMillis()
        val regime = arrOrObj("r", 0, "regime", "")
        val notes = arrOrObj("notes", 0, "mixNotes", "")
        val durV = if (o.isNull("dur")) null else o.optDouble("dur").takeIf { !it.isNaN() && it > 0 }
            ?: (if (o.has("durationMin") && !o.isNull("durationMin")) o.optDouble("durationMin").takeIf { !it.isNaN() } else null)

        return JournalEntry(
            id = 0L,
            timestampMs = tms,
            title = arrOrObj("t", 0, "title", "").ifBlank { "Задание ${ts(System.currentTimeMillis())}" },
            plannedDryContentPct = dc,
            plannedWetMassG = wm,
            recipeSummary = buildString {
                names.forEachIndexed { i, n ->
                    append(n); append(' '); append(String.format(Locale.US, "%.1f",
                        if (pmass[i] > 0 && wm > 0 && dc > 0) pmass[i] / (wm * dc / 100.0) * 100.0 else 0.0))
                    if (i != names.lastIndex) append(" / ")
                }
                append(" @ "); append(solvName); append(", СО ")
                append(String.format(Locale.US, "%.1f", dc)); append(" %")
            },
            componentNames = names,
            componentDensities = dens,
            plannedComponentMassesG = pmass,
            plannedSolventName = solvName,
            plannedSolventDensity = solvDens,
            plannedSolventMassG = solvMass,
            mixingRegimeId = regime,
            mixingDurationMin = durV,
            mixingNotes = notes,
            steps = stepsList,
            isTask = true,
        )
    }
    /** Полный читаемый JSON для файла задания (экспортируется как *.slurrytask.json) */
    fun toJsonFile(e: JournalEntry): String {
        val steps = JSONArray()
        e.steps.forEach { s ->
            steps.put(JSONObject().apply {
                put("action", s.action); put("tool", s.tool.name)
                put("dispr", s.disperserRpm ?: JSONObject.NULL)
                put("blader", s.bladeRpm ?: JSONObject.NULL)
                put("dur", s.durationMin ?: JSONObject.NULL)
                put("temp", s.tempC ?: JSONObject.NULL)
                put("vac", s.vacuum)
                put("cmt", s.comment)
            })
        }
        val o = JSONObject().apply {
            put("format", FORMAT_ID); put("version", VERSION)
            put("title", e.title); put("ts", e.timestampMs)
            put("recipe", e.recipeSummary)
            put("dryContentPct", e.plannedDryContentPct)
            put("wetMassG", e.plannedWetMassG)
            put("components", JSONArray().apply {
                e.componentNames.forEachIndexed { i, n ->
                    put(JSONObject().apply {
                        put("name", n)
                        put("density", e.componentDensities.getOrElse(i) { 0.0 })
                        put("plannedMassG", e.plannedComponentMassesG.getOrElse(i) { 0.0 })
                    })
                }
            })
            put("solventName", e.plannedSolventName)
            put("solventDensity", e.plannedSolventDensity)
            put("solventPlannedMassG", e.plannedSolventMassG)
            put("regime", e.mixingRegimeId)
            put("durationMin", e.mixingDurationMin ?: JSONObject.NULL)
            put("mixNotes", e.mixingNotes)
            put("steps", steps)
        }
        return o.toString()
    }

    // ---------- base64url (для QR и ссылок) ----------

    fun encodeBase64Url(json: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))

    fun decodeBase64Url(b64: String): String =
        String(java.util.Base64.getUrlDecoder().decode(b64.trim()), Charsets.UTF_8)

    /** Разбор входящего текста: ссылка slurrycalc:// или https-ссылка, base64payload или чистый JSON */
    fun parseIncoming(text: String): JournalEntry {
        var t = text.trim()
        val marker = Regex("[?&]d=([A-Za-z0-9_-]+)").find(t)?.groupValues?.get(1)
            ?: t.substringAfter("#d=", "").ifBlank { null }
        if (marker != null) return fromJson(decodeBase64Url(marker))
        if (t.startsWith("slurrycalc://") || t.startsWith("http"))
            throw IllegalArgumentException("В ссылке нет данных задания")
        // возможно, просто base64url из буфера обмена
        if (!t.startsWith("{") && t.matches(Regex("[A-Za-z0-9_-]{20,}")))
            try { return fromJson(decodeBase64Url(t)) } catch (_: Exception) { }
        return fromJson(t)
    }

    fun taskFileName(e: JournalEntry): String =
        "task-${ts(e.timestampMs)}-${e.title.replace(Regex("[^A-Za-z0-9._-]+"), "_")}.${FILE_EXT}"

    // ---------- человекочитаемый экспорт (CSV/TXT) ----------

    private fun f(v: Double?, digits: Int = 1): String =
        v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: ""

    /** Компактный отчёт для пересылки/печати: состав, навески, карта этапов. */
    fun exportText(e: JournalEntry, actual: BatchMath.ActualBatch?): String {
        val sb = StringBuilder()
        sb.append("ЗАДАНИЕ НА ЗАМЕС: ").append(e.title).append("\n")
        sb.append("Дата плана: ").append(e.dateLabel()).append("\n")
        sb.append("Состав: ").append(e.recipeSummary).append("\n")
        sb.append("СО план: ").append(f(e.plannedDryContentPct)).append(" % · Масса суспензии: ")
          .append(f(e.plannedWetMassG)).append(" г\n")
        sb.append("Режим (основной): ").append(e.mixingRegimeId).append("\n")
        if (e.mixingNotes.isNotBlank()) sb.append("Примечания: ").append(e.mixingNotes).append("\n")
        sb.append("\nНАВЕСКИ:\n")
        e.componentNames.forEachIndexed { i, n ->
            sb.append(String.format(Locale.US, "  %-24s %10s г  (ρ истинная %s г/см³)%n",
                n, f(e.plannedComponentMassesG.getOrElse(i) { 0.0 }), f(e.componentDensities.getOrElse(i) { 0.0 }, 3)))
        }
        sb.append(String.format(Locale.US, "  %-24s %10s г  (растворитель, ρ %s)%n",
            e.plannedSolventName, f(e.plannedSolventMassG), f(e.plannedSolventDensity, 3)))
        if (e.steps.isNotEmpty()) {
            sb.append("\nКАРТА ЭТАПОВ:\n")
            e.steps.forEachIndexed { i, s ->
                sb.append("  ").append(i + 1).append(". ").append(s.action).append(" — ").append(s.tool.label)
                val rpm = listOfNotNull(
                    s.disperserRpm?.let { "дисп. $it об/мин" },
                    s.bladeRpm?.let { "лопасти $it об/мин" },
                ).joinToString(", ")
                if (rpm.isNotEmpty()) sb.append(" [").append(rpm).append("]")
                s.durationMin?.let { sb.append(" · ").append(f(it, 0)).append(" мин") }
                s.tempC?.let { sb.append(" · T° ").append(f(it, 0)).append(" °C") }
                if (s.vacuum) sb.append(" · вакуум")
                sb.append("\n")
                if (s.comment.isNotBlank()) sb.append("     → ").append(s.comment).append("\n")
            }
        }
        // факт и результаты, если уже внесены
        if (e.hasActuals() || e.resultViscosityMPas != null || e.measuredDensityGcm3 != null) {
            sb.append("\nФАКТ (внесённые данные):\n")
            e.componentNames.forEachIndexed { i, n ->
                val a = e.actualComponentMassesG.getOrElse(i) { null }
                if (a != null) sb.append(String.format(Locale.US, "  %-24s %10s г%n", n, f(a)))
            }
            e.actualSolventMassG?.let {
                sb.append(String.format(Locale.US, "  %-24s %10s г%n", e.plannedSolventName, f(it)))
            }
            e.mixingDurationMin?.let { sb.append("  Длительность: ").append(f(it, 0)).append(" мин\n") }
            e.resultTemperatureC?.let { sb.append("  T° суспензии: ").append(f(it, 1)).append(" °C\n") }
            e.resultViscosityMPas?.let {
                sb.append("  Вязкость: ").append(f(it)).append(" мПа·с")
                if (e.resultViscosityMpaTime.isNotBlank()) sb.append(" (").append(e.resultViscosityMpaTime).append(")")
                sb.append("\n")
            }
            e.measuredDensityGcm3?.let { sb.append("  Плотность пасты (измер.): ").append(f(it, 3)).append(" г/см³\n") }
            if (e.grindLevel.isNotBlank()) sb.append("  Перетир: ").append(e.grindLevel)
            e.grindMaxParticleUm?.let { sb.append(", макс. агломерат ").append(f(it, 1)).append(" мкм") }
            if (e.grindLevel.isNotBlank() || e.grindMaxParticleUm != null) sb.append("\n")
            if (e.comment.isNotBlank()) sb.append("  Комментарий: ").append(e.comment).append("\n")
            if (e.verdict.isNotBlank()) sb.append("  Вердикт: ").append(e.verdict).append("\n")
            actual?.let { a ->
                sb.append("\nРЕАЛЬНЫЙ СОСТАВ ПО ФАКТУ:\n")
                sb.append("  СО факт: ").append(f(a.dryContentPct, 2)).append(" % · Сухое: ")
                  .append(f(a.dryMassG)).append(" г · Всего: ").append(f(a.wetMassG)).append(" г\n")
                sb.append("  Доли в сухом: ").append(
                    a.componentPctInDry.entries.joinToString(" / ") { "${it.key} ${f(it.value, 2)} %" }).append("\n")
                sb.append("  φ: ").append(f(a.solidVolumeFraction * 100, 1)).append(" % · Объём: ")
                  .append(f(a.volumeMl, 0)).append(" мл (").append(a.volumeSource).append(")\n")
            }
        }
        return sb.toString()
    }

    /** CSV: одна строка на компонент/этап — открывается в Excel/Numbers на любой платформе */
    fun exportCsv(entries: List<JournalEntry>): String {
        val sb = StringBuilder()
        sb.append("type;title;date;item;value;unit;comment\n")
        entries.forEach { e ->
            e.componentNames.forEachIndexed { i, n ->
                sb.append("component;").append(q(e.title)).append(";").append(q(e.dateLabel())).append(";")
                  .append(q(n)).append(";").append(f(e.plannedComponentMassesG.getOrElse(i) { 0.0 }, 2))
                  .append(";g;planned\n")
                e.actualComponentMassesG.getOrElse(i) { null }?.let {
                    sb.append("component;").append(q(e.title)).append(";").append(q(e.dateLabel())).append(";")
                      .append(q(n)).append(";").append(f(it, 2)).append(";g;actual\n")
                }
            }
            sb.append("solvent;").append(q(e.title)).append(";").append(q(e.dateLabel())).append(";")
              .append(q(e.plannedSolventName)).append(";").append(f(e.plannedSolventMassG, 2))
              .append(";g;planned\n")
            e.actualSolventMassG?.let {
                sb.append("solvent;").append(q(e.title)).append(";").append(q(e.dateLabel())).append(";")
                  .append(q(e.plannedSolventName)).append(";").append(f(it, 2)).append(";g;actual\n")
            }
            e.steps.forEachIndexed { i, s ->
                val rpm = listOfNotNull(
                    s.disperserRpm?.let { "disp=$it" }, s.bladeRpm?.let { "blade=$it" }).joinToString(",")
                sb.append("step;").append(q(e.title)).append(";").append(q(e.dateLabel())).append(";")
                  .append(q("${i + 1}. ${s.action}")).append(";")
                  .append(q((s.durationMin?.let { "${f(it, 0)} min" } ?: "") + " " + rpm))
                  .append(";min/rpm;")
                  .append(q(s.tool.label + (if (s.vacuum) " | вакуум" else "") + " | " + s.comment)).append("\n")
            }
            listOf(
                Triple("result", "viscosity mPas", e.resultViscosityMPas?.let { f(it) }),
                Triple("result", "temperature C", e.resultTemperatureC?.let { f(it) }),
                Triple("result", "measured density g/cm3", e.measuredDensityGcm3?.let { f(it, 3) }),
                Triple("result", "grind", e.grindLevel.ifBlank { null }),
                Triple("result", "verdict", e.verdict.ifBlank { null }),
                Triple("result", "comment", e.comment.ifBlank { null }),
            ).forEach { (t, item, v) ->
                if (v != null) sb.append(t).append(";").append(q(e.title)).append(";").append(q(e.dateLabel()))
                    .append(";").append(item).append(";").append(q(v)).append(";;\n")
            }
        }
        return sb.toString()
    }

    private fun q(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
