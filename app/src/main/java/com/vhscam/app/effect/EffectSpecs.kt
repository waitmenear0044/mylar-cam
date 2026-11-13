package com.vhscam.app.effect

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Описание параметров режимов: подписи, диапазоны, группировка по категориям и тексты справки.
 * Один источник для панели ползунков, кнопки «Сбросить» и экрана справки.
 */

enum class CategoryIcon {
    GENERAL, PIXELS, SPLIT, COLOR, TONE, TINT, LIGHT, GRAIN, NOISE, MOTION, SIGNAL, EDGES, TAPE, DEFECTS, OVERLAY,
    FOG, STREAKS, PALETTE, DITHER, THRESHOLD, GLITCH,
}

/** Справка по одному ползунку: что это, что при малом и большом значении, когда пригодится. */
data class ParamHelp(val what: String, val low: String, val high: String, val whenUseful: String)

class ParamSpec<P>(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val format: (Float) -> String,
    val get: (P) -> Float,
    val set: (P, Float) -> P,
    val help: ParamHelp,
    /** Целочисленный ползунок: число промежуточных шагов (0 = непрерывный). */
    val steps: Int = 0,
    /**
     * Параметры для демонстрации в справке: из «нейтральных» (все эффекты выключены) делает базу,
     * на которой виден именно этот ползунок. null — на неподвижной картинке эффект не показать.
     */
    val demoBase: ((neutral: P) -> P)? = { it },
    /**
     * Участвует в «Силе пресета»: итог = нейтральное + (базовое − нейтральное) × сила.
     * Параметры формы (радиальность, качество, размеры, оттенки) не смешиваются.
     */
    val blend: Boolean = false,
)

/** Переключатель в категории (например, «REC» оверлея «VHS»). */
class ToggleSpec<P>(
    val label: String,
    val get: (P) -> Boolean,
    val set: (P, Boolean) -> P,
    val help: ParamHelp,
    /** База для демо в справке (переключатель включён на ней); null — демо нет. */
    val demoBase: ((neutral: P) -> P)? = { it },
)

class EffectCategory<P>(
    val title: String,
    val icon: CategoryIcon,
    val params: List<ParamSpec<P>>,
    /** Показывать в категории выключатель «Эффект». */
    val hasEnableSwitch: Boolean = false,
    val toggles: List<ToggleSpec<P>> = emptyList(),
    /** Особое содержимое панели вместо ползунков (ключ; сейчас «curve» — редактор кривой). */
    val custom: String? = null,
    /** Сброс особого содержимого («Сбросить» в шапке панели). */
    val resetCustom: ((P) -> P)? = null,
    /** Кружок-свотч в шапке панели (цвет слота «Выбора цвета»); null у категории — нет, у результата — слот выключен. */
    val swatch: ((P) -> Int?)? = null,
) {
    /** Сбрасывает только параметры этой категории к значениям по умолчанию. */
    fun reset(current: P, defaults: P): P {
        val withParams = params.fold(current) { acc, spec -> spec.set(acc, spec.get(defaults)) }
        val withToggles = toggles.fold(withParams) { acc, t -> t.set(acc, t.get(defaults)) }
        return resetCustom?.invoke(withToggles) ?: withToggles
    }
}

/** Всё о режиме, что нужно интерфейсу: категории, значения по умолчанию, нейтральные значения для справки. */
class ModeSpec<P>(
    val mode: EffectMode,
    val categories: List<EffectCategory<P>>,
    val defaults: P,
    val neutral: P,
    val get: (EffectSettings) -> P,
    val put: (EffectSettings, P) -> EffectSettings,
    val isEnabled: (P) -> Boolean,
    val setEnabled: (P, Boolean) -> P,
    val intro: String,
    /** Что из текущих параметров сохраняется при применении пресета (кроме «эффект включён»). */
    val keep: (current: P, preset: P) -> P = { _, preset -> preset },
    /** Категории, видимые при этих параметрах (у «Cores» — категории выбранного ядра). */
    val categoriesOf: (P) -> List<EffectCategory<P>> = { categories },
    /** Описание для справки при этих параметрах (у «Cores» — описание ядра). */
    val introOf: (P) -> String = { intro },
    /** Группа пресетов (у «Cores» — ядро): свой активный пресет и своя сила пресета у каждой группы. */
    val groupOf: (P) -> String? = { null },
) {
    val allParams: List<ParamSpec<P>> get() = categories.flatMap { it.params }

    /** Параметры, которые сейчас в деле (у «Cores» — только текущего ядра). */
    private fun activeParams(base: P): List<ParamSpec<P>> = categoriesOf(base).flatMap { it.params }

    /** Смесь нейтральных и базовых значений по силе пресета k (0..1.5), с обрезкой по диапазонам. */
    fun blend(base: P, k: Float): P {
        if (k == 1f) return base
        return activeParams(base).filter { it.blend }.fold(base) { acc, spec ->
            val n = spec.get(neutral)
            val b = spec.get(base)
            spec.set(acc, (n + (b - n) * k).coerceIn(spec.range.start, spec.range.endInclusive))
        }
    }

    /** Нейтральные значения для параметров, участвующих в силе пресета (кнопка «×»). */
    fun toNeutral(base: P): P = activeParams(base).filter { it.blend }.fold(base) { acc, spec -> spec.set(acc, spec.get(neutral)) }
}

private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
private fun signed(v: Float) = String.format(Locale.US, "%+.2f", v)
private fun degrees(v: Float) = "${v.roundToInt()}°"

/** Категория «Шум», общая для режимов: жёсткий цифровой шум дешёвой камеры на высоком ISO. */
internal fun <P> noiseCategory(get: (P) -> NoiseParams, set: (P, NoiseParams) -> P) = EffectCategory(
    "Шум", CategoryIcon.NOISE,
    listOf(
        ParamSpec<P>(
            "Яркостный шум", 0f..1f, ::f2, blend = true,
            get = { get(it).luma }, set = { p, v -> set(p, get(p).copy(luma = v)) },
            help = ParamHelp(
                what = "Жёсткий цифровой шум по яркости, как у дешёвой камеры в темноте. В отличие от плёночного зерна — колючий и попиксельный.",
                low = "0: без шума.",
                high = "1: сильный «песок» по всему кадру.",
                whenUseful = "Имитация старого телефона или веб-камеры, ночные кадры «на высоком ISO».",
            ),
            demoBase = { set(it, get(it).copy(luma = 0.7f)) },
        ),
        ParamSpec(
            "Цветной шум", 0f..1f, ::f2, blend = true,
            get = { get(it).chroma }, set = { p, v -> set(p, get(p).copy(chroma = v)) },
            help = ParamHelp(
                what = "Цветные пятна и точки: малиновые и зелёные разводы, заметные в тёмных участках.",
                low = "0: цвета чистые.",
                high = "1: заметные цветные разводы.",
                whenUseful = "Самая узнаваемая примета дешёвой матрицы. Лучше понемногу, вместе с яркостным шумом.",
            ),
            demoBase = { set(it, get(it).copy(chroma = 0.7f)) },
        ),
        ParamSpec(
            "Крупность", 0f..1f, ::f2,
            get = { get(it).size }, set = { p, v -> set(p, get(p).copy(size = v)) },
            help = ParamHelp(
                what = "Размер отдельной точки шума относительно кадра (в фото и видео одинаковый).",
                low = "0: мельчайшие точки.",
                high = "1: крупные квадратные «пиксели» шума.",
                whenUseful = "Крупный шум похож на сильно сжатое видео со старого телефона.",
            ),
            demoBase = { set(it, get(it).copy(luma = 0.8f, chroma = 0.4f)) },
        ),
        ParamSpec(
            "В тенях сильнее", 0f..1f, ::f2,
            get = { get(it).shadows }, set = { p, v -> set(p, get(p).copy(shadows = v)) },
            help = ParamHelp(
                what = "Насколько шум сосредоточен в тёмных местах — у настоящих матриц именно так.",
                low = "0: одинаковый шум и в тенях, и в светах.",
                high = "1: шум почти только в тенях, света чистые.",
                whenUseful = "0.5–0.8 выглядит естественнее всего.",
            ),
            demoBase = { set(it, get(it).copy(luma = 0.8f, chroma = 0.5f)) },
        ),
    ),
)

val EnableHelp = ParamHelp(
    what = "Включает и выключает эффект текущего режима. Выключенный эффект не попадает ни в превью, ни в видео, ни в фото.",
    low = "Выключено: чистая картинка камеры.",
    high = "Включено: эффект со всеми текущими ползунками.",
    whenUseful = "Чтобы снять кадр без обработки. Для быстрого сравнения удобнее долго подержать палец на превью.",
)

val PixelSpec = ModeSpec(
    mode = EffectMode.PIXEL,
    categories = listOf(
        EffectCategory(
            "Общее", CategoryIcon.GENERAL, emptyList(), hasEnableSwitch = true,
        ),
        EffectCategory(
            "Пиксели", CategoryIcon.PIXELS,
            listOf(
                ParamSpec(
                    "Разрешение",
                    PixelParams.MIN_WIDTH.toFloat()..PixelParams.MAX_WIDTH.toFloat(),
                    format = { "${it.roundToInt()} px" },
                    get = { it.resolutionWidth.toFloat() },
                    set = { p, v -> p.copy(resolutionWidth = v.roundToInt()) },
                    blend = true,
                    help = ParamHelp(
                        what = "Сколько крупных пикселей помещается по ширине кадра. Картинка рисуется в маленьком размере и растягивается без сглаживания.",
                        low = "120–200: крупная мозаика, как у старой игровой приставки.",
                        high = "500–640: пиксели почти не видны.",
                        whenUseful = "Главный ползунок режима: от «кнопочного телефона» до лёгкой цифровой шершавости.",
                    ),
                    demoBase = { it.copy(resolutionWidth = 200) },
                ),
            ),
        ),
        EffectCategory(
            "Цвет", CategoryIcon.COLOR,
            listOf(
                ParamSpec(
                    "Насыщенность", 0f..1f, ::f2, blend = true,
                    get = { it.saturation }, set = { p, v -> p.copy(saturation = v) },
                    help = ParamHelp(
                        what = "Насколько яркие цвета.",
                        low = "0: чёрно-белая картинка.",
                        high = "1: исходные цвета камеры.",
                        whenUseful = "Слегка приглушённый цвет (0.5–0.7) делает кадр похожим на старую цифровую камеру.",
                    ),
                    demoBase = { it.copy(saturation = 0.3f) },
                ),
                ParamSpec(
                    "Выцветшие чёрные", 0f..PixelParams.MAX_BLACK_LEVEL, ::f2, blend = true,
                    get = { it.blackLevel }, set = { p, v -> p.copy(blackLevel = v) },
                    help = ParamHelp(
                        what = "Поднимает самые тёмные места: чёрное становится тёмно-серым.",
                        low = "0: глубокий чёрный.",
                        high = "0.3: тени как на выгоревшем снимке.",
                        whenUseful = "Чтобы картинка выглядела старой и «мягкой».",
                    ),
                    demoBase = { it.copy(blackLevel = 0.2f) },
                ),
                ParamSpec(
                    "Оттенок", -1f..1f, ::signed, blend = true,
                    get = { it.tint }, set = { p, v -> p.copy(tint = v) },
                    help = ParamHelp(
                        what = "Сдвигает общий оттенок кадра.",
                        low = "−1: зеленоватый, как у дешёвой матрицы.",
                        high = "+1: тёплый, желтовато-оранжевый.",
                        whenUseful = "Тёплый — для уютных домашних кадров, зелёный — для «технического» вида.",
                    ),
                    demoBase = { it.copy(tint = 0.6f) },
                ),
            ),
        ),
        noiseCategory({ it.noise }, { p, n -> p.copy(noise = n) }),
    ),
    defaults = PixelParams(),
    neutral = PixelParams(resolutionWidth = PixelParams.MAX_WIDTH, saturation = 1f, blackLevel = 0f, tint = 0f),
    get = { it.pixel },
    put = { s, p -> s.copy(pixel = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = "«Пиксель» — вид ранней цифровой камеры и игровых приставок: крупные чёткие пиксели и приглушённый цвет.",
)

val FilmSpec = ModeSpec(
    mode = EffectMode.FILM,
    categories = listOf(
        EffectCategory(
            "Общее", CategoryIcon.GENERAL, hasEnableSwitch = true,
            params = listOf(
                ParamSpec(
                    "Сила эффекта", 0f..1f, ::f2,
                    get = { it.strength }, set = { p, v -> p.copy(strength = v) },
                    help = ParamHelp(
                        what = "Общая громкость режима: одновременно ослабляет или усиливает все ползунки «Плёнки».",
                        low = "0: картинка почти как с камеры.",
                        high = "1: все эффекты в полную силу своих ползунков.",
                        whenUseful = "Быстро сделать вид деликатнее, не трогая настройку каждого эффекта.",
                    ),
                    demoBase = { FilmParams() },
                ),
            ),
        ),
        EffectCategory(
            "Расщепление", CategoryIcon.SPLIT,
            listOf(
                ParamSpec(
                    "Сила", 0f..1f, ::f2, blend = true,
                    get = { it.splitStrength }, set = { p, v -> p.copy(splitStrength = v) },
                    help = ParamHelp(
                        what = "Цветная кайма по контурам, как у простого объектива: цвета света немного расходятся, и у тёмного предмета на светлом фоне появляется сине-фиолетовый край с одной стороны и жёлто-оранжевый с другой.",
                        low = "кайма едва заметна на самых резких краях.",
                        high = "широкая радужная кайма, картинка «разъезжается».",
                        whenUseful = "Главная примета старой мыльницы. Хорошо видна на ветках, проводах, рамах окон на фоне неба.",
                    ),
                    demoBase = { it.copy(splitStrength = 0.6f) },
                ),
                ParamSpec(
                    "Радиальность", 0f..1f, ::f2,
                    get = { it.radiality }, set = { p, v -> p.copy(radiality = v) },
                    help = ParamHelp(
                        what = "Как распределена кайма по кадру.",
                        low = "0: одинаковый сдвиг по всему кадру, по горизонтали.",
                        high = "1: в центре кайма исчезает и растёт к краям, как у настоящего объектива.",
                        whenUseful = "Ближе к 1 — естественнее; ближе к 0 — заметнее в центре кадра, где главный объект.",
                    ),
                    demoBase = { it.copy(splitStrength = 0.8f) },
                ),
                ParamSpec(
                    "Качество", FilmParams.MIN_SAMPLES.toFloat()..FilmParams.MAX_SAMPLES.toFloat(),
                    format = { "${it.roundToInt()}" },
                    get = { it.splitSamples.toFloat() }, set = { p, v -> p.copy(splitSamples = v.roundToInt()) },
                    steps = FilmParams.MAX_SAMPLES - FilmParams.MIN_SAMPLES - 1,
                    help = ParamHelp(
                        what = "Сколько цветных «копий» складывается в кайму. Это нижняя граница: при большой силе расщепления копий автоматически становится больше, чтобы спектр был плавным.",
                        low = "4: при умеренной силе кайма складывается из заметных цветных полос.",
                        high = "16: плавная радуга даже при умеренной силе.",
                        whenUseful = "Обычно не трогать: при сильном расщеплении выборки и так добавляются сами. Меньшие значения дают чуть «цифровой» ступенчатый спектр.",
                    ),
                    demoBase = { it.copy(splitStrength = 0.35f, radiality = 0f, splitSamples = FilmParams.MIN_SAMPLES) },
                ),
            ),
        ),
        EffectCategory(
            "Цвет", CategoryIcon.COLOR,
            listOf(
                ParamSpec(
                    "Цвет плёнки", 0f..1f, ::f2, blend = true,
                    get = { it.filmColor }, set = { p, v -> p.copy(filmColor = v) },
                    help = ParamHelp(
                        what = "Плёночная цветопередача: тени слегка уходят в пурпур, света — в холодный голубой, яркие места мягко «сворачиваются», а не обрезаются в белое.",
                        low = "0: цвета камеры как есть.",
                        high = "1: заметный плёночный характер, мягкие света.",
                        whenUseful = "Основа вида. Особенно хорошо на небе, снеге и белых стенах.",
                    ),
                    demoBase = { it.copy(filmColor = 1f) },
                ),
                ParamSpec(
                    "Насыщенность", 0f..1.5f, ::f2, blend = true,
                    get = { it.saturation }, set = { p, v -> p.copy(saturation = v) },
                    help = ParamHelp(
                        what = "Насколько яркие цвета (1 — как у камеры).",
                        low = "0: чёрно-белый кадр.",
                        high = "1.5: очень сочные цвета.",
                        whenUseful = "Плёночные кадры чаще слегка приглушённые (0.7–0.9).",
                    ),
                    demoBase = { it.copy(saturation = 0.4f) },
                ),
                ParamSpec(
                    "Тёплый/холодный", -1f..1f, ::signed, blend = true,
                    get = { it.temperature }, set = { p, v -> p.copy(temperature = v) },
                    help = ParamHelp(
                        what = "Общая окраска кадра.",
                        low = "−1: холодный, голубоватый.",
                        high = "+1: тёплый, янтарный.",
                        whenUseful = "Холодный — зима, сумерки, город; тёплый — солнце, лето, лампы дома.",
                    ),
                    demoBase = { it.copy(temperature = 0.6f) },
                ),
            ),
        ),
        // «Цвет» по списку из пяти ползунков разделён на два, чтобы в панели было не больше четырёх
        EffectCategory(
            "Тон", CategoryIcon.TONE,
            listOf(
                ParamSpec(
                    "Контраст", 0f..1f, ::f2, blend = true,
                    get = { it.contrast }, set = { p, v -> p.copy(contrast = v) },
                    help = ParamHelp(
                        what = "S-образная кривая: тёмное темнее, светлое светлее, середина сочнее.",
                        low = "0: мягкая, плоская картинка.",
                        high = "1: плотные тени и яркие света.",
                        whenUseful = "Пасмурный день просит больше контраста, яркое солнце — меньше.",
                    ),
                    demoBase = { it.copy(contrast = 0.8f) },
                ),
                ParamSpec(
                    "Выцветание", 0f..1f, ::f2, blend = true,
                    get = { it.fade }, set = { p, v -> p.copy(fade = v) },
                    help = ParamHelp(
                        what = "Поднимает чёрные и чуть притушает белые, как у выцветшего отпечатка.",
                        low = "0: глубокий чёрный.",
                        high = "1: тени серые и дымчатые.",
                        whenUseful = "Для ностальгического, «старого» кадра.",
                    ),
                    demoBase = { it.copy(fade = 0.7f) },
                ),
            ),
        ),
        // «Тон теней» и «Тон светов» — отдельная категория, чтобы в панели было не больше четырёх ползунков
        EffectCategory(
            "Тонировка", CategoryIcon.TINT,
            listOf(
                ParamSpec(
                    "Тени: оттенок", 0f..360f, ::degrees,
                    get = { it.shadowHue }, set = { p, v -> p.copy(shadowHue = v) },
                    help = ParamHelp(
                        what = "Каким цветом окрашены тени (круг оттенков в градусах). Работает вместе с «Тени: сила».",
                        low = "0°: красный; 120°: зелёный; 180°: бирюзовый.",
                        high = "240°: синий; 300°: пурпурный; 360°: снова красный.",
                        whenUseful = "Бирюзовые тени — ночной город под лампами, зеленоватые — прохладная цветная плёнка, тёплые — портретная плёнка.",
                    ),
                    demoBase = { it.copy(shadowTone = 0.8f) },
                ),
                ParamSpec(
                    "Тени: сила", 0f..1f, ::f2, blend = true,
                    get = { it.shadowTone }, set = { p, v -> p.copy(shadowTone = v) },
                    help = ParamHelp(
                        what = "Насколько заметно окрашены тени выбранным оттенком.",
                        low = "0: тени без окраски.",
                        high = "1: сильно окрашенные тени.",
                        whenUseful = "Обычно 0.2–0.5: лёгкая окраска теней сразу делает кадр «плёночным».",
                    ),
                    demoBase = { it.copy(shadowHue = 185f) },
                ),
                ParamSpec(
                    "Света: оттенок", 0f..360f, ::degrees,
                    get = { it.highlightHue }, set = { p, v -> p.copy(highlightHue = v) },
                    help = ParamHelp(
                        what = "Каким цветом окрашены светлые места. Работает вместе с «Света: сила».",
                        low = "30–50°: тёплые, золотистые света.",
                        high = "190–220°: холодные, голубоватые света.",
                        whenUseful = "Классика — тёплые света при холодных тенях (или наоборот).",
                    ),
                    demoBase = { it.copy(highlightTone = 0.8f) },
                ),
                ParamSpec(
                    "Света: сила", 0f..1f, ::f2, blend = true,
                    get = { it.highlightTone }, set = { p, v -> p.copy(highlightTone = v) },
                    help = ParamHelp(
                        what = "Насколько заметно окрашены светлые места выбранным оттенком.",
                        low = "0: света без окраски.",
                        high = "1: сильно окрашенные света.",
                        whenUseful = "Небольшая сила (0.2–0.4) — тёплое солнце или холодный зимний свет.",
                    ),
                    demoBase = { it.copy(highlightHue = 45f) },
                ),
            ),
        ),
        EffectCategory(
            "Свет", CategoryIcon.LIGHT,
            listOf(
                ParamSpec(
                    "Halation", 0f..1f, ::f2, blend = true,
                    get = { it.halation }, set = { p, v -> p.copy(halation = v) },
                    help = ParamHelp(
                        what = "Красно-оранжевое свечение вокруг ярких источников: свет отражается от подложки плёнки и «растекается».",
                        low = "0: лампы и окна без ореола.",
                        high = "1: заметный тёплый ореол вокруг фонарей, ламп, бликов.",
                        whenUseful = "Ночные улицы, гирлянды, неон, солнце в кадре.",
                    ),
                    demoBase = { it.copy(halation = 0.8f) },
                ),
                ParamSpec(
                    "Мягкость", 0f..1f, ::f2, blend = true,
                    get = { it.softness }, set = { p, v -> p.copy(softness = v) },
                    help = ParamHelp(
                        what = "Слегка снижает резкость мелких деталей, как у простого плёночного объектива.",
                        low = "0: резкость камеры телефона.",
                        high = "1: заметно мягкая картинка.",
                        whenUseful = "Портреты и «сонные» кадры; убирает излишнюю цифровую резкость.",
                    ),
                    demoBase = { it.copy(softness = 1f) },
                ),
            ),
        ),
        EffectCategory(
            "Зерно", CategoryIcon.GRAIN,
            listOf(
                ParamSpec(
                    "Зерно", 0f..1f, ::f2, blend = true,
                    get = { it.grain }, set = { p, v -> p.copy(grain = v) },
                    help = ParamHelp(
                        what = "Мягкое, слегка цветное плёночное зерно, новое в каждом кадре. Сильнее в тенях и средних тонах.",
                        low = "0: чистая картинка.",
                        high = "1: заметное зерно, как у высокочувствительной плёнки.",
                        whenUseful = "Почти всегда понемногу: зерно делает кадр «живым» и прячет цифровую гладкость.",
                    ),
                    demoBase = { it.copy(grain = 0.8f) },
                ),
                ParamSpec(
                    "Размер зерна", 0f..1f, ::f2,
                    get = { it.grainSize }, set = { p, v -> p.copy(grainSize = v) },
                    help = ParamHelp(
                        what = "Величина отдельного зерна относительно кадра (в фото и видео одинаковая).",
                        low = "0: очень мелкое, едва различимое.",
                        high = "1: крупное, комковатое.",
                        whenUseful = "Крупное зерно — для «дешёвой» плёнки и ночных кадров.",
                    ),
                    demoBase = { it.copy(grain = 1f) },
                ),
            ),
        ),
        noiseCategory({ it.noise }, { p, n -> p.copy(noise = n) }),
        EffectCategory(
            "Движение", CategoryIcon.MOTION,
            listOf(
                ParamSpec(
                    "Смаз", 0f..1f, ::f2, blend = true,
                    get = { it.motionBlur }, set = { p, v -> p.copy(motionBlur = v) },
                    help = ParamHelp(
                        what = "Подмешивает предыдущие кадры: за движущимися предметами тянется шлейф. Работает в превью и видео; на фото и неподвижной картинке не виден.",
                        low = "0: чёткое движение.",
                        high = "1: длинный мечтательный шлейф.",
                        whenUseful = "Огни ночного города, танцы, «сонное» видео.",
                    ),
                    demoBase = null,
                ),
            ),
        ),
    ),
    defaults = FilmParams(),
    neutral = FilmParams(
        strength = 1f, splitStrength = 0f, filmColor = 0f, contrast = 0f, fade = 0f, saturation = 1f,
        temperature = 0f, halation = 0f, grain = 0f, motionBlur = 0f, softness = 0f,
    ),
    get = { it.film },
    put = { s, p -> s.copy(film = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = "«Плёнка» — характер плёночной мыльницы: цветная кайма по контурам, мягкие холодные света, свечение ламп, зерно.",
)

@Suppress("UNCHECKED_CAST")
fun specFor(mode: EffectMode): ModeSpec<Any> = when (mode) {
    EffectMode.PIXEL -> PixelSpec
    EffectMode.FILM -> FilmSpec
    EffectMode.VHS -> VhsSpec
    EffectMode.CORES -> CoresSpec
    EffectMode.GRADIENT -> GradientSpec
    EffectMode.GHOST -> GhostSpec
    EffectMode.NEWSPAPER -> NewspaperSpec
    EffectMode.COLOR -> ColorSelectSpec
} as ModeSpec<Any>
