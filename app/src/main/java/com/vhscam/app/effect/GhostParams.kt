package com.vhscam.app.effect

import org.json.JSONObject
import java.util.Locale

/**
 * Параметры режима «Гостинг» (только живое превью и видео). Два типа: «глюк-копии» — от движущегося объекта
 * остаются застывшие копии, пока их не перекроет новое изображение; «эхо» — плавное накопление прошлых кадров.
 * Всё считается по времени кадра, поэтому на 24, 30 и 60 fps вид одинаковый.
 */
data class GhostParams(
    val enabled: Boolean = true,
    /** false — глюк-копии, true — эхо. */
    val echo: Boolean = false,
    /** Чувствительность к движению 0..1 (только глюк-копии). */
    val sensitivity: Float = 0.6f,
    /** Время жизни копий / длина эха, секунды. */
    val seconds: Float = 2f,
    /** Сила (непрозрачность копий или доля эха) 0..1. */
    val strength: Float = 0.8f,
    /** Цветовой сдвиг копий 0..1. */
    val colorShift: Float = 0.3f,
    /** Форма шлейфа (только след, текущий кадр чёткий): индекс [GhostShapeLabels]. */
    val shape: Int = 0,
    /** Размер блока / сила формы 0..1. */
    val shapeSize: Float = 0.4f,
    /** Сила деградации 0..1 (блочный и «шакальный»). */
    val degrade: Float = 0.5f,
    val noise: NoiseParams = NoiseParams(),
) {
    companion object {
        const val MIN_SECONDS = 0.2f
        const val MAX_SECONDS = 8f
    }
}

fun GhostParams.toJson(): JSONObject = JSONObject()
    .put("echo", echo).put("sensitivity", sensitivity.toDouble()).put("seconds", seconds.toDouble())
    .put("strength", strength.toDouble()).put("colorShift", colorShift.toDouble())
    .put("shape", shape).put("shapeSize", shapeSize.toDouble()).put("degrade", degrade.toDouble())
    .put("noiseLuma", noise.luma.toDouble()).put("noiseChroma", noise.chroma.toDouble())
    .put("noiseSize", noise.size.toDouble()).put("noiseShadows", noise.shadows.toDouble())

fun ghostParamsFromJson(o: JSONObject): GhostParams {
    val d = GhostParams()
    fun f(key: String, def: Float, min: Float, max: Float) = o.optDouble(key, def.toDouble()).toFloat().let { if (it.isNaN()) def else it.coerceIn(min, max) }
    return GhostParams(
        echo = o.optBoolean("echo", d.echo),
        sensitivity = f("sensitivity", d.sensitivity, 0f, 1f),
        seconds = f("seconds", d.seconds, GhostParams.MIN_SECONDS, GhostParams.MAX_SECONDS),
        strength = f("strength", d.strength, 0f, 1f),
        colorShift = f("colorShift", d.colorShift, 0f, 1f),
        shape = o.optInt("shape", d.shape).coerceIn(0, GhostShapeLabels.lastIndex),
        shapeSize = f("shapeSize", d.shapeSize, 0f, 1f),
        degrade = f("degrade", d.degrade, 0f, 1f),
        noise = NoiseParams(f("noiseLuma", 0f, 0f, 1f), f("noiseChroma", 0f, 0f, 1f), f("noiseSize", 0.3f, 0f, 1f), f("noiseShadows", 0.6f, 0f, 1f)),
    )
}

private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
private fun signed(v: Float) = String.format(Locale.US, "%+.2f", v)
private fun sec(v: Float) = String.format(Locale.US, "%.1f с", v)

// ---------------------------------------------------------------------------------------------------------------
// «Градиент»
// ---------------------------------------------------------------------------------------------------------------

val GradientSpec = ModeSpec(
    mode = EffectMode.GRADIENT,
    categories = listOf(
        EffectCategory(
            "Общее", CategoryIcon.GENERAL, hasEnableSwitch = true,
            toggles = listOf(
                ToggleSpec<GradientParams>(
                    "Инверсия",
                    get = { it.invert }, set = { p, v -> p.copy(invert = v) },
                    help = ParamHelp(
                        what = "Переворачивает карту: тёмные места получают цвета правого конца градиента, светлые — левого.",
                        low = "Выкл: тени — начало градиента, света — конец.",
                        high = "Вкл: наоборот, как негатив в цветах градиента.",
                        whenUseful = "Быстро получить другой характер из того же градиента.",
                    ),
                    demoBase = { it.copy(invert = true) },
                ),
            ),
            params = listOf(
                ParamSpec(
                    "Сила", 0f..1f, ::f2, blend = true,
                    get = { it.strength }, set = { p, v -> p.copy(strength = v) },
                    help = ParamHelp(
                        what = "Насколько раскрашенный кадр заменяет исходный.",
                        low = "0: исходные цвета.",
                        high = "1: только цвета градиента.",
                        whenUseful = "При 0.3–0.6 получается тонировка: цвета сцены остаются, но стягиваются к градиенту.",
                    ),
                ),
                ParamSpec(
                    "Контраст", -1f..1f, ::signed,
                    get = { it.contrast }, set = { p, v -> p.copy(contrast = v) },
                    help = ParamHelp(
                        what = "Контраст яркости до наложения карты: сколько места на градиенте занимают полутона.",
                        low = "−1: плоско, почти весь кадр — середина градиента.",
                        high = "+1: резко, кадр распадается на крайние цвета.",
                        whenUseful = "Плюс — для плакатного вида, минус — для мягкой дымки.",
                    ),
                    demoBase = { it.copy(contrast = 0.7f) },
                ),
                ParamSpec(
                    "Сдвиг", 0f..1f, ::f2,
                    get = { it.shift }, set = { p, v -> p.copy(shift = v) },
                    help = ParamHelp(
                        what = "Циклически сдвигает градиент: цвета «проворачиваются» по яркости, уходя за край и возвращаясь с другого.",
                        low = "0: градиент как задан.",
                        high = "1: полный оборот — снова как задан.",
                        whenUseful = "Подбор того, какой цвет попадёт на лица или небо; на середине появляется резкая граница — как у соляризации.",
                    ),
                    demoBase = { it.copy(shift = 0.35f) },
                ),
            ),
        ),
        noiseCategory({ it.noise }, { p, n -> p.copy(noise = n) }),
    ),
    defaults = GradientParams(),
    neutral = GradientParams(strength = 0f),
    get = { it.gradient },
    put = { s, p -> s.copy(gradient = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = "«Градиент» — кадр переводится в чёрно-белый по яркости и раскрашивается картой градиента: тени получают " +
        "цвета одного конца, света — другого. Градиент выбирается в ленте над полосой категорий, кнопка «изменить» " +
        "открывает редактор точек.",
)

// ---------------------------------------------------------------------------------------------------------------
// «Гостинг»
// ---------------------------------------------------------------------------------------------------------------

private val GhostGeneral = EffectCategory(
    "Общее", CategoryIcon.GENERAL, emptyList(), hasEnableSwitch = true,
    toggles = listOf(
        ToggleSpec<GhostParams>(
            "Эхо вместо глюк-копий",
            get = { it.echo }, set = { p, v -> p.copy(echo = v) },
            help = ParamHelp(
                what = "Тип гостинга. Глюк-копии: от движущегося объекта остаются застывшие копии, как при ошибке отрисовки в играх. Эхо: плавный шлейф, как смазанные люди на старом телевизоре.",
                low = "Выкл: глюк-копии.",
                high = "Вкл: эхо.",
                whenUseful = "Копии — для резкого «сломанного» вида, эхо — для мягкого призрачного движения.",
            ),
            demoBase = null,
        ),
    ),
)

private fun ghostTime(label: String, what: String) = ParamSpec<GhostParams>(
    label, GhostParams.MIN_SECONDS..GhostParams.MAX_SECONDS, ::sec,
    get = { it.seconds }, set = { p, v -> p.copy(seconds = v) },
    help = ParamHelp(what = what, low = "0.2 с: след исчезает почти сразу.", high = "8 с: след держится долго.", whenUseful = "Задаётся в секундах, поэтому на 24, 30 и 60 fps выглядит одинаково."),
    demoBase = null,
)

private fun ghostStrength(what: String) = ParamSpec<GhostParams>(
    "Сила", 0f..1f, ::f2, blend = true,
    get = { it.strength }, set = { p, v -> p.copy(strength = v) },
    help = ParamHelp(what = what, low = "0: эффекта нет.", high = "1: след такой же плотный, как сам кадр.", whenUseful = "Меньше — если след мешает разглядеть сцену."),
    demoBase = null,
)

private val ghostColor = ParamSpec<GhostParams>(
    "Цветовой сдвиг", 0f..1f, ::f2,
    get = { it.colorShift }, set = { p, v -> p.copy(colorShift = v) },
    help = ParamHelp(what = "Оттенок следа уходит от исходного: копии и эхо окрашиваются.", low = "0: след тех же цветов.", high = "1: след заметно другого оттенка.", whenUseful = "Чтобы след читался как отдельный «призрак», а не как смаз."),
    demoBase = null,
)

/** Формы шлейфа «Гостинга». */
val GhostShapeLabels = listOf("Чёткий", "Размытый", "Пиксельный", "Блочный", "Шакальный")

private val GhostShape = EffectCategory(
    "Форма шлейфа", CategoryIcon.DITHER,
    listOf(
        ParamSpec<GhostParams>(
            "Форма", 0f..GhostShapeLabels.lastIndex.toFloat(), { GhostShapeLabels[it.toInt().coerceIn(0, GhostShapeLabels.lastIndex)] },
            get = { it.shape.toFloat() }, set = { p, v -> p.copy(shape = Math.round(v)) },
            steps = GhostShapeLabels.size - 2,
            help = ParamHelp(
                what = "Каким выглядит след (текущий кадр остаётся чётким). Чёткий — как есть; размытый — мягкий след; " +
                    "пиксельный — блоки, как в режиме «Пиксель»; блочный — макроблоки сжатого видео; шакальный — " +
                    "убитый JPEG: низкое разрешение, постеризация, дизеринг, смаз цвета и шум.",
                low = "Чёткий.", high = "Шакальный.", whenUseful = "Глитч-эстетика: след как испорченное видео.",
            ),
            demoBase = null,
        ),
        ParamSpec<GhostParams>(
            "Размер блока/сила", 0f..1f, ::f2,
            get = { it.shapeSize }, set = { p, v -> p.copy(shapeSize = v) },
            help = ParamHelp(
                what = "Для размытого — радиус размытия (до 3% кадра), для пиксельного, блочного и шакального — размер блока (0.4–6% кадра).",
                low = "0: едва заметно.", high = "1: крупные блоки или сильное размытие.", whenUseful = "Крупнее — грубее след.",
            ),
            demoBase = null,
        ),
        ParamSpec<GhostParams>(
            "Сила деградации", 0f..1f, ::f2,
            get = { it.degrade }, set = { p, v -> p.copy(degrade = v) },
            help = ParamHelp(
                what = "Для блочного и шакального: насколько грубо квантуются цвета, сколько дизеринга, шума и смаза цвета.",
                low = "0: аккуратно.", high = "1: «убитое» сжатие.", whenUseful = "Сильнее — для явного глитча.",
            ),
            demoBase = null,
        ),
    ),
)

private val GhostGlitchCategories = listOf(
    GhostGeneral,
    EffectCategory(
        "Копии", CategoryIcon.MOTION,
        listOf(
            ParamSpec(
                "Чувствительность", 0f..1f, ::f2,
                get = { it.sensitivity }, set = { p, v -> p.copy(sensitivity = v) },
                help = ParamHelp(
                    what = "Насколько малое движение оставляет копию.",
                    low = "0: копии остаются только от резкого, контрастного движения.",
                    high = "1: копии оставляет почти любое изменение в кадре (и шум матрицы).",
                    whenUseful = "В темноте и при шуме — меньше, иначе кадр «зарастает» мусором.",
                ),
                demoBase = null,
            ),
            ghostTime("Время жизни", "Как быстро застывшие копии стираются, уступая место текущему кадру."),
            ghostStrength("Непрозрачность застывших копий поверх текущего кадра."),
            ghostColor,
        ),
    ),
    GhostShape,
    noiseCategory<GhostParams>({ it.noise }, { p, n -> p.copy(noise = n) }),
)

private val GhostEchoCategories = listOf(
    GhostGeneral,
    EffectCategory(
        "Эхо", CategoryIcon.MOTION,
        listOf(
            ghostTime("Длина", "Сколько секунд прошлые кадры остаются в накопленном шлейфе."),
            ghostStrength("Доля накопленного шлейфа в кадре."),
            ghostColor,
        ),
    ),
    GhostShape,
    noiseCategory<GhostParams>({ it.noise }, { p, n -> p.copy(noise = n) }),
)

val GhostSpec = ModeSpec(
    mode = EffectMode.GHOST,
    categories = GhostGlitchCategories + GhostEchoCategories[1],
    defaults = GhostParams(),
    neutral = GhostParams(strength = 0f),
    get = { it.ghost },
    put = { s, p -> s.copy(ghost = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = "«Гостинг» — следы движения, только для живого превью и видео (фото сохраняет то, что на экране). " +
        "Глюк-копии: движущийся объект оставляет застывшие копии, пока их не перекроет новое изображение, — как " +
        "«зал зеркал» при ошибке отрисовки в играх. Эхо: прошлые кадры плавно накапливаются в шлейф, как смазанные " +
        "люди на старом телевизоре. На неподвижной картинке эффект не виден, поэтому примеров в справке нет.",
    categoriesOf = { if (it.echo) GhostEchoCategories else GhostGlitchCategories },
    groupOf = { if (it.echo) "Эхо" else "Глюк-копии" },
)
