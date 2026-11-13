package com.vhscam.app.effect

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Эффекты только для редактора фото и видео (в полосе режимов камеры их нет): «Газета» и «Выбор цвета».
 * Действуют как любой слой, поэтому сочетаются с остальными режимами.
 */

private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun deg(v: Float) = "${v.roundToInt()}°"
private fun JSONObject.fl(key: String, def: Float, min: Float, max: Float) =
    optDouble(key, def.toDouble()).toFloat().let { if (it.isNaN()) def else it.coerceIn(min, max) }

// ---------------------------------------------------------------------------------------------------------------
// «Выбор цвета»
// ---------------------------------------------------------------------------------------------------------------

/** Диапазон оттенка: центр (градусы), полуширина (градусы) и мягкость границы (доля ширины). */
data class ColorSlot(val on: Boolean = false, val hue: Float = 0f, val width: Float = 25f, val feather: Float = 0.4f)

/** Точка кривой ЧБ-части: вход и выход 0..1. */
data class CurvePoint(val x: Float, val y: Float)

data class ColorSelectParams(
    val enabled: Boolean = true,
    /** true — выбранный цвет остаётся, остальное ЧБ; false — выбранный становится ЧБ. */
    val keep: Boolean = true,
    val slots: List<ColorSlot> = listOf(ColorSlot(on = true, hue = 0f), ColorSlot(hue = 210f), ColorSlot(hue = 120f)),
    /** Ниже этой насыщенности цвет выбранным не считается. */
    val satMin: Float = 0.15f,
    /** Кривая ЧБ-части (по возрастанию x), до [MAX_CURVE_POINTS] точек. */
    val curve: List<CurvePoint> = LINEAR_CURVE,
) {
    fun slot(i: Int) = slots.getOrElse(i) { ColorSlot() }
    fun withSlot(i: Int, s: ColorSlot) = copy(slots = List(SLOTS) { if (it == i) s else slot(it) })

    companion object {
        const val SLOTS = 3
        const val MAX_CURVE_POINTS = 8
        val LINEAR_CURVE = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

fun ColorSelectParams.toJson(): JSONObject = JSONObject()
    .put("keep", keep).put("satMin", satMin.toDouble())
    .put("slots", JSONArray().also { a -> slots.forEach { a.put(JSONObject().put("on", it.on).put("hue", it.hue.toDouble()).put("width", it.width.toDouble()).put("feather", it.feather.toDouble())) } })
    .put("curve", JSONArray().also { a -> curve.forEach { a.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } })

fun colorSelectParamsFromJson(o: JSONObject): ColorSelectParams {
    val d = ColorSelectParams()
    val slots = o.optJSONArray("slots")?.let { a ->
        (0 until minOf(a.length(), ColorSelectParams.SLOTS)).map { i ->
            val s = a.getJSONObject(i)
            ColorSlot(s.optBoolean("on"), s.fl("hue", 0f, 0f, 360f), s.fl("width", 25f, 3f, 120f), s.fl("feather", 0.4f, 0f, 1f))
        }
    } ?: d.slots
    val curve = o.optJSONArray("curve")?.let { a ->
        (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { CurvePoint(it.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f), it.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f)) } }
            .sortedBy { it.x }.take(ColorSelectParams.MAX_CURVE_POINTS).takeIf { it.size >= 2 }
    } ?: d.curve
    return ColorSelectParams(keep = o.optBoolean("keep", d.keep), slots = List(ColorSelectParams.SLOTS) { slots.getOrElse(it) { ColorSlot() } }, satMin = o.fl("satMin", d.satMin, 0f, 1f), curve = curve)
}

/** Кривая -> таблица 256 значений: монотонный кубический сплайн через точки (без перелётов), слева и справа — плоско. */
fun curveLut(points: List<CurvePoint>, size: Int = 256): FloatArray {
    val p = points.sortedBy { it.x }.distinctBy { it.x }
    val out = FloatArray(size)
    if (p.size < 2) return FloatArray(size) { it / (size - 1f) }
    val n = p.size
    val dx = FloatArray(n - 1) { (p[it + 1].x - p[it].x).coerceAtLeast(1e-4f) }
    val slope = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / dx[it] }
    val m = FloatArray(n)
    m[0] = slope[0]
    m[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) m[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / slope[i]
        val b = m[i + 1] / slope[i]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / kotlin.math.sqrt(s)
            m[i] = t * a * slope[i]
            m[i + 1] = t * b * slope[i]
        }
    }
    for (k in 0 until size) {
        val x = k / (size - 1f)
        out[k] = when {
            x <= p[0].x -> p[0].y
            x >= p[n - 1].x -> p[n - 1].y
            else -> {
                val i = (0 until n - 1).first { x <= p[it + 1].x }
                val t = (x - p[i].x) / dx[i]
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * p[i].y + (t3 - 2 * t2 + t) * dx[i] * m[i] + (-2 * t3 + 3 * t2) * p[i + 1].y + (t3 - t2) * dx[i] * m[i + 1]
            }
        }.coerceIn(0f, 1f)
    }
    return out
}

private fun slotCategory(index: Int) = EffectCategory<ColorSelectParams>(
    "Цвет ${index + 1}", CategoryIcon.PALETTE,
    swatch = { p -> p.slot(index).takeIf { it.on }?.let { android.graphics.Color.HSVToColor(floatArrayOf(it.hue, 0.85f, 1f)) } },
    toggles = listOf(
        ToggleSpec(
            "Цвет ${index + 1} включён",
            get = { it.slot(index).on }, set = { p, v -> p.withSlot(index, p.slot(index).copy(on = v)) },
            help = ParamHelp(
                what = "Диапазон оттенка №${index + 1}. Оттенок задаётся пипеткой: откройте эту категорию и нажмите на нужное место картинки.",
                low = "Выкл: диапазон не участвует.",
                high = "Вкл: цвета этого диапазона считаются выбранными.",
                whenUseful = "До трёх диапазонов одновременно — например, красный и жёлтый.",
            ),
            demoBase = null,
        ),
    ),
    params = listOf(
        ParamSpec(
            "Оттенок", 0f..360f, ::deg,
            get = { it.slot(index).hue }, set = { p, v -> p.withSlot(index, p.slot(index).copy(hue = v)) },
            help = ParamHelp(
                what = "Центр выбранного диапазона на цветовом круге. Подкрутка, если пипетка взяла не совсем то.",
                low = "0°: красный; 60° — жёлтый, 120° — зелёный.",
                high = "360°: снова красный; 210° — синий, 300° — пурпурный.",
                whenUseful = "Тонкая настройка после пипетки.",
            ),
            demoBase = { it.copy(slots = listOf(ColorSlot(true, 120f, 40f, 0.4f), ColorSlot(), ColorSlot())) },
        ),
        ParamSpec(
            "Ширина диапазона", 3f..120f, ::deg,
            get = { it.slot(index).width }, set = { p, v -> p.withSlot(index, p.slot(index).copy(width = v)) },
            help = ParamHelp(
                what = "Насколько далеко от выбранного оттенка цвет ещё считается выбранным.",
                low = "3°: только точно такой оттенок.",
                high = "120°: почти треть цветового круга в каждую сторону.",
                whenUseful = "Узко — для одного предмета, широко — для «всех тёплых тонов».",
            ),
            demoBase = { it.copy(slots = listOf(ColorSlot(true, 20f, 80f, 0.4f), ColorSlot(), ColorSlot())) },
        ),
        ParamSpec(
            "Мягкость границы", 0f..1f, ::f2,
            get = { it.slot(index).feather }, set = { p, v -> p.withSlot(index, p.slot(index).copy(feather = v)) },
            help = ParamHelp(
                what = "Плавность перехода между выбранным и невыбранным.",
                low = "0: жёсткая граница, возможны рваные края.",
                high = "1: переход шириной с сам диапазон.",
                whenUseful = "Мягче — для кожи и неба, жёстче — для чётких предметов.",
            ),
            demoBase = { it.copy(slots = listOf(ColorSlot(true, 20f, 30f, 1f), ColorSlot(), ColorSlot())) },
        ),
    ),
)

val ColorSelectSpec = ModeSpec(
    mode = EffectMode.COLOR,
    categories = listOf(
        EffectCategory(
            "Общее", CategoryIcon.GENERAL, hasEnableSwitch = true,
            toggles = listOf(
                ToggleSpec<ColorSelectParams>(
                    "Выбранный цвет остаётся",
                    get = { it.keep }, set = { p, v -> p.copy(keep = v) },
                    help = ParamHelp(
                        what = "Что делать с выбранным цветом.",
                        low = "Выкл: выбранный цвет становится ЧБ, остальное цветное.",
                        high = "Вкл: выбранный цвет остаётся, остальное становится ЧБ.",
                        whenUseful = "Вкл — «красное платье на ЧБ-фото»; выкл — «небо в чёрный» вместе с кривой.",
                    ),
                    demoBase = { it.copy(keep = false, slots = listOf(ColorSlot(true, 120f, 50f, 0.4f), ColorSlot(), ColorSlot())) },
                ),
            ),
            params = listOf(
                ParamSpec(
                    "Насыщенность от", 0f..1f, ::f2,
                    get = { it.satMin }, set = { p, v -> p.copy(satMin = v) },
                    help = ParamHelp(
                        what = "Отсечка по насыщенности: бледные цвета выбранными не считаются.",
                        low = "0: выбираются даже почти серые пиксели нужного оттенка.",
                        high = "1: только самые сочные.",
                        whenUseful = "Выше — если в выбранное попадают серые стены и тени.",
                    ),
                    demoBase = { it.copy(satMin = 0.6f, slots = listOf(ColorSlot(true, 120f, 60f, 0.4f), ColorSlot(), ColorSlot())) },
                ),
            ),
        ),
        slotCategory(0), slotCategory(1), slotCategory(2),
        EffectCategory<ColorSelectParams>(
            "Кривая ЧБ", CategoryIcon.TONE, emptyList(), custom = "curve",
            resetCustom = { p -> p.copy(curve = ColorSelectParams.LINEAR_CURVE) },
        ),
    ),
    defaults = ColorSelectParams(),
    neutral = ColorSelectParams(),
    get = { it.colorSelect },
    put = { s, p -> s.copy(colorSelect = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = "«Выбор цвета» (только редактор) — выбранный оттенок остаётся цветным на чёрно-белом кадре или, наоборот, " +
        "один он становится чёрно-белым. Оттенок берётся пипеткой: откройте категорию «Цвет 1–3» и нажмите на картинку. " +
        "ЧБ-часть настраивается кривой — вплоть до полностью чёрного или белого (например, синее небо в чёрный).",
)

// ---------------------------------------------------------------------------------------------------------------
// «Газета»
// ---------------------------------------------------------------------------------------------------------------

val PrintKinds = listOf("ЧБ-растр", "Розетка CMYK", "RGB-каналы")
val PaperColors = listOf("Белая", "Серая", "Желтоватая")

data class NewspaperParams(
    val enabled: Boolean = true,
    /** 0 — ЧБ полутоновый растр, 1 — цветная «розетка» CMYK, 2 — RGB-каналы. */
    val kind: Int = 0,
    /** Размер ячейки, доля короткой стороны кадра. */
    val cell: Float = 0.008f,
    /** Контраст точек. */
    val gain: Float = 1.2f,
    /** Смещение каналов (неточное совмещение красок), доля ячейки. */
    val offset: Float = 0.1f,
    /** Угол сетки ЧБ, градусы. */
    val angle: Float = 45f,
    /** Сине-чёрная краска вместо чёрной. */
    val blueInk: Boolean = false,
    /** 0 — белая, 1 — серая, 2 — желтоватая. */
    val paper: Int = 0,
) {
    companion object {
        const val MIN_CELL = 0.003f
        const val MAX_CELL = 0.03f
    }
}

fun NewspaperParams.toJson(): JSONObject = JSONObject()
    .put("kind", kind).put("cell", cell.toDouble()).put("gain", gain.toDouble()).put("offset", offset.toDouble())
    .put("angle", angle.toDouble()).put("blueInk", blueInk).put("paper", paper)

fun newspaperParamsFromJson(o: JSONObject): NewspaperParams {
    val d = NewspaperParams()
    return NewspaperParams(
        kind = o.optInt("kind", d.kind).coerceIn(0, 2),
        cell = o.fl("cell", d.cell, NewspaperParams.MIN_CELL, NewspaperParams.MAX_CELL),
        gain = o.fl("gain", d.gain, 0.5f, 3f), offset = o.fl("offset", d.offset, 0f, 1f), angle = o.fl("angle", d.angle, 0f, 90f),
        // Поля удалённых эффектов бумаги (texture, crumple, folds, dust, creases) в старых кодах молча пропускаются
        blueInk = o.optBoolean("blueInk", d.blueInk), paper = o.optInt("paper", d.paper).coerceIn(0, 2),
    )
}

private fun np(
    label: String, range: ClosedFloatingPointRange<Float>, format: (Float) -> String,
    get: (NewspaperParams) -> Float, set: (NewspaperParams, Float) -> NewspaperParams,
    what: String, low: String, high: String, useful: String,
    steps: Int = 0, demo: ((NewspaperParams) -> NewspaperParams)? = { it },
) = ParamSpec(label, range, format, get, set, ParamHelp(what, low, high, useful), steps = steps, demoBase = demo)

val NewspaperSpec = ModeSpec(
    mode = EffectMode.NEWSPAPER,
    categories = listOf(
        EffectCategory(
            "Печать", CategoryIcon.DITHER, hasEnableSwitch = true,
            toggles = listOf(
                ToggleSpec<NewspaperParams>(
                    "Сине-чёрная краска",
                    get = { it.blueInk }, set = { p, v -> p.copy(blueInk = v) },
                    help = ParamHelp("Цвет чёрной краски.", "Выкл: чёрная.", "Вкл: сине-чёрная, как в старых газетах.", "Для ЧБ-растра и чёрной сетки «розетки»."),
                    demoBase = { it.copy(blueInk = true, cell = 0.014f) },
                ),
            ),
            params = listOf(
                np("Вид печати", 0f..2f, { PrintKinds[it.roundToInt().coerceIn(0, 2)] }, { it.kind.toFloat() }, { p, v -> p.copy(kind = v.roundToInt()) },
                    "Способ печати. ЧБ-растр: одна сетка точек, чем темнее — тем крупнее точка. Розетка: четыре сетки CMYK под углами 15°, 75°, 0° и 45°, их наложение даёт узор «розетка». RGB-каналы: красный, зелёный и синий каналы — каждый своей сеткой со смещением.",
                    "ЧБ-растр.", "RGB-каналы.", "ЧБ — газета, розетка — журнал и комикс, RGB — экран под лупой.", steps = 1, demo = { it.copy(kind = 1, cell = 0.014f) }),
                np("Размер ячейки", NewspaperParams.MIN_CELL..NewspaperParams.MAX_CELL, { String.format(Locale.US, "%.1f%%", it * 100) }, { it.cell }, { p, v -> p.copy(cell = v) },
                    "Шаг сетки точек в долях короткой стороны кадра — одинаковый на превью и в сохранённом файле.",
                    "0.3%: мелкий растр, издалека почти обычное фото.", "3%: крупные точки, как на плакате.", "Крупнее — для выразительного «печатного» вида.", demo = { it.copy(cell = 0.02f) }),
                np("Контраст точек", 0.5f..3f, ::f2, { it.gain }, { p, v -> p.copy(gain = v) },
                    "Насколько резко размер точки зависит от яркости.", "0.5: точки почти одного размера, картинка плоская.", "3: в тенях сплошная краска, в светах чистая бумага.",
                    "Выше — для плакатного вида.", demo = { it.copy(gain = 2.4f, cell = 0.014f) }),
                np("Смещение каналов", 0f..1f, ::f2, { it.offset }, { p, v -> p.copy(offset = v) },
                    "Неточное совмещение красок при печати: сетки каналов сдвинуты друг относительно друга.", "0: совмещение точное.", "1: сдвиг на целую ячейку — цветные каймы.",
                    "Немного — для живого «дешёвого» оттиска.", demo = { it.copy(kind = 1, offset = 0.8f, cell = 0.014f) }),
            ),
        ),
        EffectCategory(
            "Сетка", CategoryIcon.PIXELS,
            listOf(
                np("Угол сетки", 0f..90f, ::deg, { it.angle }, { p, v -> p.copy(angle = v) },
                    "Поворот сетки точек у ЧБ-растра и RGB-каналов (у «розетки» углы классические и не меняются).", "0°: точки рядами по горизонтали и вертикали.", "90°: то же самое; 45° — классический газетный растр.",
                    "45° меньше всего бросается в глаза.", demo = { it.copy(angle = 0f, cell = 0.014f) }),
            ),
        ),
        EffectCategory(
            "Бумага", CategoryIcon.TINT,
            params = listOf(
                np("Цвет бумаги", 0f..2f, { PaperColors[it.roundToInt().coerceIn(0, 2)] }, { it.paper.toFloat() }, { p, v -> p.copy(paper = v.roundToInt()) },
                    "Цвет подложки под краской (у RGB-каналов подложка всегда тёмная).", "Белая.", "Желтоватая, как у старой газеты.", "Серая — газетная бумага, желтоватая — старый оттиск.", steps = 1, demo = { it.copy(paper = 2) }),
            ),
        ),
    ),
    defaults = NewspaperParams(),
    neutral = NewspaperParams(),
    get = { it.newspaper },
    put = { s, p -> s.copy(newspaper = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = "«Газета» (только редактор) — полутоновая печать: картинка складывается из точек краски на бумаге. " +
        "Три вида печати (ЧБ-растр, «розетка» CMYK, RGB-каналы) и цвет бумаги-подложки.",
)
