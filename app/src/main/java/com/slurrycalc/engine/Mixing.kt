package com.slurrycalc.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Тип инструмента/оборудования этапа смешивания */
enum class MixToolType(val label: String) {
    MANUAL("Ручная лопатка"),
    MAGNETIC("Магнитная мешалка"),
    PLANETARY("Планетарный диспергатор"),
    DISC("Диспергатор (ротор-статор)"),
    THREE_ROLL("Трёхвальцовая мельница"),
    ULTRASONIC("УЗ-зонд"),
    VACUUM("Вакуум / дегазация"),
    OTHER("Другое");

    companion object {
        fun fromName(n: String?): MixToolType =
            entries.firstOrNull { it.name == n } ?: OTHER
    }
}

/**
 * Этап смешивания внутри замеса: действие + режим оборудования.
 * Примеры: «Растворение PVDF в NMP» (магнитная, 600 об/мин),
 * «Ввод Super P» (планетарная, 1500/400 об/мин), «Перетир» (3-вальцы, зазор 8 мкм).
 */
data class MixingStep(
    val id: Long = 0L,
    /** что делаем: растворение связующего, ввод сажи, ввод AM, перетир, дегазация… */
    val action: String = "",
    val tool: MixToolType = MixToolType.PLANETARY,
    /** обороты диспергатора (ротора/пальца), об/мин */
    val disperserRpm: Int? = null,
    /** обороты лопастей/чашки (для планетарных машин), об/мин */
    val bladeRpm: Int? = null,
    /** длительность этапа, мин */
    val durationMin: Double? = null,
    /** температура суспензии на этапе, °C (замер или ограничение) */
    val tempC: Double? = null,
    /** ведётся ли этап под вакуумом (дегазация / работа в вакууме) */
    val vacuum: Boolean = false,
    /** комментарий по выполняемым действиям (что и как вносим, контроль готовности) */
    val comment: String = "",
) {
    fun rpmLabel(): String {
        val d = disperserRpm?.let { "дисп. ${it} об/мин" }
        val b = bladeRpm?.let { "лопасти ${it} об/мин" }
        return listOfNotNull(d, b).joinToString(" · ")
    }
}

/**
 * Сохранённый пользовательский режим смешивания (пресет параметров этапа),
 * добавляется вручную из карточки режима.
 */
data class CustomRegime(
    val id: String,
    val name: String,
    val tool: MixToolType,
    val disperserRpmHint: Int? = null,
    val bladeRpmHint: Int? = null,
    val timeMinHint: Double? = null,
    val tempLimitC: Double? = null,
    val description: String = "",
)

/** Встроенные режимы: метод, ориентиры по оборотам, время, T° */
object BuiltinRegimes {
    data class Info(
        val name: String,
        val tool: MixToolType,
        val tipSpeedHint: String,
        val timeMinFirst: Int,
        val timeMinLast: Int,
        val tempLimitC: Double?,
        val description: String,
    )

    val ALL: Map<String, Info> = linkedMapOf(
        "manual_beaker" to Info(
            "Ручное (стакан + лопатка)", MixToolType.MANUAL,
            "вручную, ~1 м/с", 15, 30, null,
            "Для пробников <100 г. Однородность низкая, агломераты сохраняются.",
        ),
        "magnetic" to Info(
            "Магнитная мешалка", MixToolType.MAGNETIC,
            "300–1500 об/мин", 20, 60, null,
            "Хороша для растворения PVDF и разбавленных составов; для высокого φ слаба.",
        ),
        "planetary_low" to Info(
            "Планетарная (низкая)", MixToolType.PLANETARY,
            "дисп. ~1200 · лопасти ~400 об/мин", 10, 20, 60.0,
            "Pre-mix сухих CB+AM или мягкий wet-mix малых партий.",
        ),
        "planetary_std" to Info(
            "Планетарная (стандарт)", MixToolType.PLANETARY,
            "дисп. ~2500 · лопасти ~800 об/мин", 20, 40, 60.0,
            "Базовый режим lab-диспергирования суспензий 0.2–2 л.",
        ),
        "planetary_high" to Info(
            "Планетарная (высокая)", MixToolType.PLANETARY,
            "дисп. ~4000 · лопасти ~1200 об/мин", 30, 60, 50.0,
            "Интенсивное диспергирование; греется — контролируйте T°, возможен гель PVDF.",
        ),
        "disc_dispersion" to Info(
            "Диспергатор ротор-статор", MixToolType.DISC,
            "8000–20000 об/мин (Diskpress, Ultra-Turrax)", 10, 45, 55.0,
            "Высокий сдвиг; вводить сажу постепенно, следить за нагревом и деградацией PVDF.",
        ),
        "three_roll" to Info(
            "Трёхвальцовая мельница", MixToolType.THREE_ROLL,
            "зазор 5–10 мкм, 2–3 прохода", 5, 15, 45.0,
            "Финишный перетир после предсмеса; лучший деагломератор для φ > 35 %.",
        ),
        "ultrasonic" to Info(
            "УЗ-зонд (sonication)", MixToolType.ULTRASONIC,
            "20 кГц, импульсный режим", 5, 20, 40.0,
            "Гомогенизация малых объёмов; следить за перегревом и деградацией связующего.",
        ),
        "degassing" to Info(
            "Дегазация (вакуум)", MixToolType.VACUUM,
            "< −0.08 МПа при помешивании", 5, 15, null,
            "Удаление пузырей перед нанесением; финальный шаг любого режима.",
        ),
    )

    fun info(idOrName: String): Info? = ALL[idOrName] ?: ALL.values.firstOrNull { it.name == idOrName }
}

/**
 * Репозиторий режимов смешивания: встроенные + пользовательские (SharedPreferences).
 * Пользовательский режим можно создать прямо из карточки этапа («Сохранить как режим»).
 */
class RegimeStore(context: Context) {

    private val prefs = context.getSharedPreferences("regimes.db", Context.MODE_PRIVATE)
    private var nextId = 1L
    private val customs = mutableListOf<CustomRegime>()

    init {
        val raw = prefs.getString("customs", null)
        if (raw != null) try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) customs.add(fromJson(arr.getJSONObject(i)))
            nextId = customs.mapNotNull { it.id.removePrefix("custom_").toLongOrNull() }.maxOrNull()?.plus(1) ?: 1L
        } catch (_: Exception) { }
    }

    fun customList(): List<CustomRegime> = customs.toList()

    /** все режимы для выбора в этапе: свои + встроенные */
    fun allNames(): List<String> = customs.map { it.name } + BuiltinRegimes.ALL.values.map { it.name }

    fun add(name: String, tool: MixToolType, dispr: Int?, blader: Int?, timeMin: Double?,
            tempLimit: Double?, description: String): CustomRegime {
        val r = CustomRegime(
            id = "custom_${nextId++}",
            name = name.trim().ifEmpty { "Мой режим" },
            tool = tool, disperserRpmHint = dispr, bladeRpmHint = blader,
            timeMinHint = timeMin, tempLimitC = tempLimit, description = description.trim(),
        )
        customs.add(0, r)
        persist()
        return r
    }

    fun remove(id: String): Boolean {
        val removed = customs.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    fun byName(name: String): CustomRegime? = customs.firstOrNull { it.name == name }

    private fun persist() {
        val arr = JSONArray()
        customs.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("customs", arr.toString()).apply()
    }

    private fun toJson(r: CustomRegime) = JSONObject().apply {
        put("id", r.id); put("name", r.name); put("tool", r.tool.name)
        put("dispr", r.disperserRpmHint ?: JSONObject.NULL)
        put("blader", r.bladeRpmHint ?: JSONObject.NULL)
        put("time", r.timeMinHint ?: JSONObject.NULL)
        put("temp", r.tempLimitC ?: JSONObject.NULL)
        put("desc", r.description)
    }

    private fun fromJson(o: JSONObject) = CustomRegime(
        id = o.optString("id"),
        name = o.optString("name"),
        tool = MixToolType.fromName(o.optString("tool")),
        disperserRpmHint = if (o.isNull("dispr")) null else o.optInt("dispr").takeIf { it > 0 },
        bladeRpmHint = if (o.isNull("blader")) null else o.optInt("blader").takeIf { it > 0 },
        timeMinHint = if (o.isNull("time")) null else o.optDouble("time").takeIf { !it.isNaN() },
        tempLimitC = if (o.isNull("temp")) null else o.optDouble("temp").takeIf { !it.isNaN() },
        description = o.optString("desc"),
    )
}

/**
 * Рекомендуемая типовая последовательность этапов для состава.
 * @param cathode true — NMP/PVDF-маршрут; false — водный маршрут CMC/SBR
 */
object MixingSequences {

    fun template(cathode: Boolean, solventName: String): List<MixingStep> {
        val binderFirst = if (cathode)
            // PVDF сначала растворяем в NMP — иначе комки геля
            listOf(
                MixingStep(
                    action = "Растворение связующего (PVDF)",
                    tool = MixToolType.MAGNETIC, disperserRpm = null, bladeRpm = 400,
                    durationMin = 60.0, tempC = 45.0, vacuum = false,
                    comment = "PVDF тонкой струйкой в часть NMP; полный раствор (без «рыбьих глаз»), затем охладить до RT.",
                ),
            )
        else
            // CMC предварительно гидратируем горячей водой, SBR — латекс в конце
            listOf(
                MixingStep(
                    action = "Предгидратация CMC",
                    tool = MixToolType.MAGNETIC, bladeRpm = 500,
                    durationMin = 30.0, tempC = 60.0, vacuum = false,
                    comment = "CMC в горячую деионизованную воду, без комков; охладить перед использованием.",
                ),
            )
        return binderFirst + listOf(
            MixingStep(
                action = if (cathode) "Ввод проводящей добавки (Super P)" else "Ввод проводящей добавки + pre-mix сухих",
                tool = MixToolType.PLANETARY, disperserRpm = 1200, bladeRpm = 400,
                durationMin = 15.0, tempC = null,
                comment = "Сажа постепенно, смачиваем в части растворителя; высокая доля CB — отдельным этапом.",
            ),
            MixingStep(
                action = "Ввод активного материала",
                tool = MixToolType.PLANETARY, disperserRpm = 2500, bladeRpm = 800,
                durationMin = 30.0, tempC = 45.0,
                comment = "AM порциями; остаток растворителя — по вязкости. Контроль нагрева: не выше T°limit.",
            ),
            MixingStep(
                action = "Финишное диспергирование / перетир",
                tool = MixToolType.THREE_ROLL, disperserRpm = null, bladeRpm = null,
                durationMin = 10.0, tempC = null,
                comment = "2–3 прохода, зазор 5–10 мкм; проверка крупности агломератов на пластинке.",
            ),
            MixingStep(
                action = "Дегазация",
                tool = MixToolType.VACUUM, bladeRpm = 200,
                durationMin = 10.0, tempC = null, vacuum = true,
                comment = "Вакуум при медленном помешивании до прекращения пузырения.",
            ),
        )
    }
}

/**
 * Пересчёт замеса по ФАКТИЧЕСКИМ взвешенным массам.
 * Реальные массы всегда отличаются от расчётных — здесь считаем «как получилось»:
 * фактический СО, доли компонентов в сухом, ρ сухого, φ и объём (из измеренной ρ, если есть).
 */
object BatchMath {

    data class ActualComponent(val name: String, val density: Double, val massG: Double)

    data class ActualBatch(
        val dryMassG: Double,
        val solventMassG: Double,
        val wetMassG: Double,
        /** фактический сухой остаток, % */
        val dryContentPct: Double,
        /** фактические доли компонентов в сухом веществе, % */
        val componentPctInDry: Map<String, Double>,
        /** истинная ρ сухого вещества (правило обратных долей), г/см³ */
        val dryDensityGcm3: Double,
        /** расчётная ρ суспензии (идеальное смешение), г/см³ */
        val calcDensityGcm3: Double,
        /** измеренная ρ пасты, если указана, г/см³ */
        val measuredDensityGcm3: Double?,
        /** объём: m/ρ_изм (если ρ измерена), иначе расчётный */
        val volumeMl: Double,
        val volumeSource: String,
        /** φ — объёмная доля твёрдой фазы */
        val solidVolumeFraction: Double,
        val warnings: List<String>,
    )

    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    fun fromActualMasses(
        components: List<ActualComponent>,
        solventMassG: Double,
        solventDensity: Double,
        measuredDensityGcm3: Double? = null,
    ): Result<ActualBatch> {
        val warnings = mutableListOf<String>()
        val comps = components.filter { it.massG > 0 }
        if (comps.isEmpty())
            return Result.failure(IllegalArgumentException("Укажите хотя бы одну фактическую массу компонента"))
        if (solventMassG <= 0)
            return Result.failure(IllegalArgumentException("Укажите фактическую массу растворителя"))
        if (solventDensity <= 0)
            return Result.failure(IllegalArgumentException("Некорректная плотность растворителя"))
        if (comps.any { it.density <= 0 })
            return Result.failure(IllegalArgumentException("Для всех компонентов должна быть задана плотность"))

        val dryMass = comps.sumOf { it.massG }
        val wetMass = dryMass + solventMassG
        val dcPct = dryMass / wetMass * 100.0

        val dryVolume = comps.sumOf { it.massG / it.density }
        val totalVolume = dryVolume + solventMassG / solventDensity
        val dryDensity = dryMass / dryVolume
        val phi = dryVolume / totalVolume
        val calcDensity = wetMass / totalVolume

        val compPct = comps.associate { it.name to it.massG / dryMass * 100.0 }

        val volume: Double
        val source: String
        if (measuredDensityGcm3 != null && measuredDensityGcm3 > 0) {
            volume = wetMass / measuredDensityGcm3
            source = "измеренная ρ"
            val diff = (measuredDensityGcm3 - calcDensity) / calcDensity * 100.0
            if (kotlin.math.abs(diff) > 3) warnings.add(
                "Измеренная ρ=${f3(measuredDensityGcm3)} отличается от расчётной ${f3(calcDensity)} на " +
                        "${f1(diff)} % — вероятны захват воздуха (ρ ниже) либо " +
                        "неполная смачиваемость / ошибка ввода масс."
            )
        } else {
            volume = totalVolume
            source = "расчётная ρ"
        }

        if (phi > 0.65) warnings.add("Высокая φ = ${f1(phi * 100)} % — возможна трудноразливаемость.")

        return Result.success(
            ActualBatch(
                dryMassG = dryMass,
                solventMassG = solventMassG,
                wetMassG = wetMass,
                dryContentPct = dcPct,
                componentPctInDry = compPct,
                dryDensityGcm3 = dryDensity,
                calcDensityGcm3 = calcDensity,
                measuredDensityGcm3 = measuredDensityGcm3,
                volumeMl = volume,
                volumeSource = source,
                solidVolumeFraction = phi,
                warnings = warnings,
            )
        )
    }
}
