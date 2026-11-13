package com.vhscam.app.effect

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Описание режима «Cores»: у каждого ядра свои категории (не больше четырёх ползунков в каждой).
 * В полосе категорий видны «Общее», категории выбранного ядра и общий «Шум».
 */

private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
private fun signed(v: Float) = String.format(Locale.US, "%+.2f", v)

private fun dream(
    label: String, range: ClosedFloatingPointRange<Float>,
    get: (DreamParams) -> Float, set: (DreamParams, Float) -> DreamParams,
    help: ParamHelp, demo: ((DreamParams) -> DreamParams)?, format: (Float) -> String = ::f2, blend: Boolean = true,
) = ParamSpec<CoresParams>(
    label, range, format, { get(it.dream) }, { p, v -> p.copy(dream = set(p.dream, v)) }, help,
    demoBase = demo?.let { d -> { n: CoresParams -> n.copy(core = CoreKind.DREAM, dream = d(n.dream)) } }, blend = blend,
)

private fun sun(
    label: String, range: ClosedFloatingPointRange<Float>,
    get: (SunParams) -> Float, set: (SunParams, Float) -> SunParams,
    help: ParamHelp, demo: ((SunParams) -> SunParams)?, format: (Float) -> String = ::f2, blend: Boolean = true,
) = ParamSpec<CoresParams>(
    label, range, format, { get(it.sun) }, { p, v -> p.copy(sun = set(p.sun, v)) }, help,
    demoBase = demo?.let { d -> { n: CoresParams -> n.copy(core = CoreKind.SUN, sun = d(n.sun)) } }, blend = blend,
)

private fun dark(
    label: String, range: ClosedFloatingPointRange<Float>,
    get: (DarkParams) -> Float, set: (DarkParams, Float) -> DarkParams,
    help: ParamHelp, demo: ((DarkParams) -> DarkParams)?, format: (Float) -> String = ::f2, blend: Boolean = true,
) = ParamSpec<CoresParams>(
    label, range, format, { get(it.dark) }, { p, v -> p.copy(dark = set(p.dark, v)) }, help,
    demoBase = demo?.let { d -> { n: CoresParams -> n.copy(core = CoreKind.DARK, dark = d(n.dark)) } }, blend = blend,
)

private fun circuit(
    label: String, range: ClosedFloatingPointRange<Float>,
    get: (CircuitParams) -> Float, set: (CircuitParams, Float) -> CircuitParams,
    help: ParamHelp, demo: ((CircuitParams) -> CircuitParams)?, format: (Float) -> String = ::f2,
    blend: Boolean = true, steps: Int = 0,
) = ParamSpec<CoresParams>(
    label, range, format, { get(it.circuit) }, { p, v -> p.copy(circuit = set(p.circuit, v)) }, help,
    steps = steps, demoBase = demo?.let { d -> { n: CoresParams -> n.copy(core = CoreKind.CIRCUIT, circuit = d(n.circuit)) } }, blend = blend,
)

/** Нейтральные значения каждого ядра: картинка близка к оригиналу. От них — сила эффекта, сила пресета и «×». */
private val CoresNeutral = CoresParams(
    dream = DreamParams(
        pastel = 0f, shadowTone = 0f, highlightTone = 0f, contrast = 0f, haze = 0f, softness = 0f,
        glow = 0f, vignette = 0f, grain = 0f, posterize = 0f, noise = 0f,
    ),
    sun = SunParams(
        exposure = 0f, glow = 0f, lightLeak = 0f, softness = 0f, warmth = 0f, saturation = 1f,
        yellowGreen = 0f, contrast = 0f, grain = 0f, haze = 0f,
    ),
    dark = DarkParams(
        teal = 0f, darken = 0f, liftedBlacks = 0f, saturation = 1f, fog = 0f, softness = 0f, vignette = 0f,
        glow = 0f, streakLength = 0f, streakStrength = 0f, streakOpacity = 0f, noise = 0f, grain = 0f,
    ),
    circuit = CircuitParams(
        levels = CircuitParams.MAX_LEVELS, solarize = 0f, paletteAmount = 0f, dither = 0f, darkThreshold = 0f,
        contrast = 0f, bandInvert = 0f, channelSwap = 0f, blockShift = 0f, lineStick = 0f, burstRate = 0f, animSpeed = 0f,
    ),
)

private val General = EffectCategory<CoresParams>(
    "Общее", CategoryIcon.GENERAL, hasEnableSwitch = true,
    params = listOf(
        ParamSpec(
            "Сила эффекта", 0f..1f, ::f2,
            get = { it.strength }, set = { p, v -> p.copy(strength = v) },
            help = ParamHelp(
                what = "Общая громкость текущего ядра: ведёт все его ползунки от почти чистой картинки к текущим значениям.",
                low = "0: картинка почти как с камеры.",
                high = "1: ядро в полную силу своих ползунков.",
                whenUseful = "Быстро сделать стиль деликатнее, не трогая каждый ползунок.",
            ),
            demoBase = { CoresParams() },
        ),
    ),
)

private val DreamCategories = listOf(
    EffectCategory(
        "Цвет", CategoryIcon.COLOR,
        listOf(
            dream(
                "Пастель", 0f..1f, { it.pastel }, { p, v -> p.copy(pastel = v) },
                ParamHelp(
                    what = "Молочная пастель: цвета насыщенные, но как будто разбавлены молоком, чёрное уходит в мягкий тон.",
                    low = "0: цвета камеры.",
                    high = "1: сильно молочная, «конфетная» картинка.",
                    whenUseful = "Основа дримкора: пустые улицы, дома, лужайки, детские площадки.",
                ),
                demo = { it.copy(pastel = 0.8f) },
            ),
            dream(
                "Тени: тон", 0f..1f, { it.shadowTone }, { p, v -> p.copy(shadowTone = v) },
                ParamHelp(
                    what = "Окрашивает тени в сине-фиолетовый.",
                    low = "0: тени без окраски.",
                    high = "1: глубокие фиолетовые тени.",
                    whenUseful = "Вместе с розовыми светами даёт «сонный» контраст тёплого и холодного.",
                ),
                demo = { it.copy(shadowTone = 0.8f) },
            ),
            dream(
                "Света: тон", 0f..1f, { it.highlightTone }, { p, v -> p.copy(highlightTone = v) },
                ParamHelp(
                    what = "Окрашивает света в розово-оранжевый, а в светлом небе и облаках добавляет зелёно-фиолетовую игру.",
                    low = "0: света без окраски.",
                    high = "1: розовые света и переливы в облаках.",
                    whenUseful = "Небо, облака, закатный свет на стенах.",
                ),
                demo = { it.copy(highlightTone = 0.8f) },
            ),
            dream(
                "Контраст", -1f..1f, { it.contrast }, { p, v -> p.copy(contrast = v) },
                ParamHelp(
                    what = "Разница между светлым и тёмным. Дримкору обычно нужен низкий.",
                    low = "−1: плоская, «туманная» картинка.",
                    high = "+1: плотные тени и яркие света.",
                    whenUseful = "−0.2…−0.4 — мягкий сон, около 0 — чётче.",
                ),
                demo = { it.copy(contrast = -0.6f) }, format = ::signed,
            ),
        ),
    ),
    EffectCategory(
        "Дымка", CategoryIcon.FOG,
        listOf(
            dream(
                "Дымка", 0f..1f, { it.haze }, { p, v -> p.copy(haze = v) },
                ParamHelp(
                    what = "Светлая лавандовая дымка поверх кадра: детали тонут, воздух становится плотным.",
                    low = "0: прозрачный воздух.",
                    high = "1: всё как сквозь светлый туман.",
                    whenUseful = "Чтобы место казалось «знакомым, но неправильным».",
                ),
                demo = { it.copy(haze = 0.8f) },
            ),
            dream(
                "Мягкость", 0f..1f, { it.softness }, { p, v -> p.copy(softness = v) },
                ParamHelp(
                    what = "Размывает мелкие детали, как будто смотришь сквозь стекло.",
                    low = "0: резкость камеры.",
                    high = "1: сильно мягкая картинка.",
                    whenUseful = "Почти всегда понемногу: убирает цифровую резкость.",
                ),
                demo = { it.copy(softness = 0.9f) },
            ),
            dream(
                "Свечение", 0f..1f, { it.glow }, { p, v -> p.copy(glow = v) },
                ParamHelp(
                    what = "Светлые места мягко светятся розоватым ореолом.",
                    low = "0: без свечения.",
                    high = "1: облака и окна сияют.",
                    whenUseful = "Солнечный день, белые дома, облака.",
                ),
                demo = { it.copy(glow = 0.9f) },
            ),
            dream(
                "Виньетка", 0f..1f, { it.vignette }, { p, v -> p.copy(vignette = v) },
                ParamHelp(
                    what = "Слабое затемнение к углам кадра.",
                    low = "0: углы как в центре.",
                    high = "1: заметно тёмные углы.",
                    whenUseful = "Немного — собирает взгляд в центр.",
                ),
                demo = { it.copy(vignette = 0.9f) },
            ),
        ),
    ),
    EffectCategory(
        "Текстура", CategoryIcon.GRAIN,
        listOf(
            dream(
                "Зерно", 0f..1f, { it.grain }, { p, v -> p.copy(grain = v) },
                ParamHelp(
                    what = "Мелкое мягкое зерно, как у старой цифровой камеры или плёнки.",
                    low = "0: чистая картинка.",
                    high = "1: заметное зерно.",
                    whenUseful = "Немного — картинка оживает.",
                ),
                demo = { it.copy(grain = 0.9f) },
            ),
            dream(
                "Постеризация", 0f..1f, { it.posterize }, { p, v -> p.copy(posterize = v) },
                ParamHelp(
                    what = "Плавные переходы цвета превращаются в ступеньки, как у сильно сжатой картинки из интернета 2000-х.",
                    low = "0: плавные градиенты.",
                    high = "1: заметные цветовые ступеньки в небе.",
                    whenUseful = "Слегка (0.1–0.3) — для «найденной в сети» картинки.",
                ),
                demo = { it.copy(posterize = 0.8f) },
            ),
            dream(
                "Шум", 0f..1f, { it.noise }, { p, v -> p.copy(noise = v) },
                ParamHelp(
                    what = "Цифровой шум дешёвой камеры (тот же, что в категории «Шум», но хранится в ядре и в пресетах).",
                    low = "0: без шума.",
                    high = "1: сильный цифровой «песок».",
                    whenUseful = "Для вида старого телефона или веб-камеры.",
                ),
                demo = { it.copy(noise = 0.7f) },
            ),
        ),
    ),
)

private val SunCategories = listOf(
    EffectCategory(
        "Свет", CategoryIcon.LIGHT,
        listOf(
            sun(
                "Экспозиция", -1f..1f, { it.exposure }, { p, v -> p.copy(exposure = v) },
                ParamHelp(
                    what = "Общая яркость. Света не выбиваются в белое, а мягко сворачиваются в кремовый.",
                    low = "−1: темнее.",
                    high = "+1: залитая солнцем картинка.",
                    whenUseful = "Санкор любит светлый, воздушный кадр: +0.2…+0.4.",
                ),
                demo = { it.copy(exposure = 0.7f) }, format = ::signed,
            ),
            sun(
                "Свечение", 0f..1f, { it.glow }, { p, v -> p.copy(glow = v) },
                ParamHelp(
                    what = "Эффект Orton: размытая копия кадра накладывается на резкий — света сияют мягким золотым ореолом.",
                    low = "0: без свечения.",
                    high = "1: сильное сказочное сияние.",
                    whenUseful = "Солнце в листве, блики на воде, контровой свет.",
                ),
                demo = { it.copy(glow = 0.9f) },
            ),
            sun(
                "Засветка", 0f..1f, { it.lightLeak }, { p, v -> p.copy(lightLeak = v) },
                ParamHelp(
                    what = "Тёплые засветы от краёв кадра, как солнечный свет, попавший в объектив. В видео медленно плывут.",
                    low = "0: без засветов.",
                    high = "1: яркие оранжевые засветы по краям.",
                    whenUseful = "Летние прогулки, золотой час.",
                ),
                demo = { it.copy(lightLeak = 0.9f) },
            ),
            sun(
                "Мягкость", 0f..1f, { it.softness }, { p, v -> p.copy(softness = v) },
                ParamHelp(
                    what = "Смягчает мелкие детали.",
                    low = "0: резкость камеры.",
                    high = "1: мечтательная мягкость.",
                    whenUseful = "Портреты на солнце, трава и цветы.",
                ),
                demo = { it.copy(softness = 0.9f) },
            ),
        ),
    ),
    EffectCategory(
        "Цвет", CategoryIcon.COLOR,
        listOf(
            sun(
                "Тёплый оттенок", 0f..1f, { it.warmth }, { p, v -> p.copy(warmth = v) },
                ParamHelp(
                    what = "Света уходят в кремовый и золотой.",
                    low = "0: нейтральный цвет.",
                    high = "1: густое золото.",
                    whenUseful = "Почти всегда: это и есть «солнечный» цвет.",
                ),
                demo = { it.copy(warmth = 0.9f) },
            ),
            sun(
                "Насыщенность", 0f..1.5f, { it.saturation }, { p, v -> p.copy(saturation = v) },
                ParamHelp(
                    what = "Насколько яркие цвета. Оранжево-красные акценты сохраняют насыщенность даже при её снижении.",
                    low = "0: почти ч/б, но красное остаётся тёплым.",
                    high = "1.5: сочные летние цвета.",
                    whenUseful = "1–1.2 — свежий солнечный день.",
                ),
                demo = { it.copy(saturation = 1.4f) },
            ),
            sun(
                "Жёлто-зелёный", 0f..1f, { it.yellowGreen }, { p, v -> p.copy(yellowGreen = v) },
                ParamHelp(
                    what = "Сдвигает зелень к жёлто-зелёному, как листва на просвет.",
                    low = "0: зелень как есть.",
                    high = "1: лимонная, светящаяся зелень.",
                    whenUseful = "Сады, луга, деревья.",
                ),
                demo = { it.copy(yellowGreen = 0.9f) },
            ),
            sun(
                "Контраст", -1f..1f, { it.contrast }, { p, v -> p.copy(contrast = v) },
                ParamHelp(
                    what = "Разница между светлым и тёмным.",
                    low = "−1: воздушная, плоская картинка.",
                    high = "+1: плотная, контрастная.",
                    whenUseful = "Санкор обычно низкий: −0.2…−0.4.",
                ),
                demo = { it.copy(contrast = -0.6f) }, format = ::signed,
            ),
        ),
    ),
    EffectCategory(
        "Текстура", CategoryIcon.GRAIN,
        listOf(
            sun(
                "Зерно", 0f..1f, { it.grain }, { p, v -> p.copy(grain = v) },
                ParamHelp(
                    what = "Мягкое плёночное зерно.",
                    low = "0: чистая картинка.",
                    high = "1: заметное зерно.",
                    whenUseful = "Немного — вид летнего плёночного кадра.",
                ),
                demo = { it.copy(grain = 0.9f) },
            ),
            sun(
                "Дымка", 0f..1f, { it.haze }, { p, v -> p.copy(haze = v) },
                ParamHelp(
                    what = "Тёплая кремовая дымка: тени светлеют, воздух становится золотым.",
                    low = "0: прозрачный воздух.",
                    high = "1: густой тёплый свет.",
                    whenUseful = "Утро, пыльный свет, контровое солнце.",
                ),
                demo = { it.copy(haze = 0.8f) },
            ),
        ),
    ),
)

private val DarkCategories = listOf(
    EffectCategory(
        "Тон", CategoryIcon.TONE,
        listOf(
            dark(
                "Бирюзовый оттенок", 0f..1f, { it.teal }, { p, v -> p.copy(teal = v) },
                ParamHelp(
                    what = "Окрашивает кадр в холодный бирюзово-синий.",
                    low = "0: цвета камеры.",
                    high = "1: всё в холодной бирюзе.",
                    whenUseful = "Основа даркора: ночь, сумерки, туман, вода.",
                ),
                demo = { it.copy(teal = 0.9f) },
            ),
            dark(
                "Затемнение", 0f..1f, { it.darken }, { p, v -> p.copy(darken = v) },
                ParamHelp(
                    what = "Делает кадр темнее, сохраняя света.",
                    low = "0: яркость камеры.",
                    high = "1: глубокая ночь.",
                    whenUseful = "Сумеречные кадры, лес, город ночью.",
                ),
                demo = { it.copy(darken = 0.8f) },
            ),
            dark(
                "Приподнятые чёрные", 0f..1f, { it.liftedBlacks }, { p, v -> p.copy(liftedBlacks = v) },
                ParamHelp(
                    what = "Чёрное становится тёмным сине-зелёным, как на старом мониторе.",
                    low = "0: глубокий чёрный.",
                    high = "1: тени заметно сине-зелёные.",
                    whenUseful = "Даёт тот самый «мутный» даркор-вид.",
                ),
                demo = { it.copy(liftedBlacks = 0.8f) },
            ),
            dark(
                "Насыщенность", 0f..1.5f, { it.saturation }, { p, v -> p.copy(saturation = v) },
                ParamHelp(
                    what = "Насколько яркие цвета (1 — как у камеры).",
                    low = "0: почти монохромный кадр.",
                    high = "1.5: сочнее камеры.",
                    whenUseful = "Даркор приглушённый: 0.4–0.7.",
                ),
                demo = { it.copy(saturation = 0.3f) },
            ),
        ),
    ),
    EffectCategory(
        "Туман", CategoryIcon.FOG,
        listOf(
            dark(
                "Туман", 0f..1f, { it.fog }, { p, v -> p.copy(fog = v) },
                ParamHelp(
                    what = "Холодный туман, сильнее к верху кадра: дальнее становится светлее и холоднее, появляется глубина.",
                    low = "0: прозрачный воздух.",
                    high = "1: густой туман.",
                    whenUseful = "Лес, поле, улица в сумерках.",
                ),
                demo = { it.copy(fog = 0.8f) },
            ),
            dark(
                "Мягкость", 0f..1f, { it.softness }, { p, v -> p.copy(softness = v) },
                ParamHelp(
                    what = "Смягчает детали.",
                    low = "0: резкость камеры.",
                    high = "1: сильно мягкая картинка.",
                    whenUseful = "Вместе с туманом — сонная, зыбкая ночь.",
                ),
                demo = { it.copy(softness = 0.9f) },
            ),
            dark(
                "Виньетка", 0f..1f, { it.vignette }, { p, v -> p.copy(vignette = v) },
                ParamHelp(
                    what = "Тёмные углы кадра.",
                    low = "0: углы как в центре.",
                    high = "1: сильная виньетка, как взгляд из темноты.",
                    whenUseful = "Почти всегда в даркоре.",
                ),
                demo = { it.copy(vignette = 0.9f) },
            ),
            dark(
                "Свечение", 0f..1f, { it.glow }, { p, v -> p.copy(glow = v) },
                ParamHelp(
                    what = "Холодное свечение вокруг светлых мест: фонари, окна, белые цветы.",
                    low = "0: без свечения.",
                    high = "1: всё светлое сияет.",
                    whenUseful = "Ночные огни, туман у фонарей.",
                ),
                demo = { it.copy(glow = 0.9f) },
            ),
        ),
    ),
    EffectCategory(
        "Штрихи", CategoryIcon.STREAKS,
        listOf(
            dark(
                "Длина", 0f..1f, { it.streakLength }, { p, v -> p.copy(streakLength = v) },
                ParamHelp(
                    what = "Длина диагональных штрихов размытия (до ~15% ширины кадра), как при съёмке с рук на длинной выдержке.",
                    low = "0: штрихов нет.",
                    high = "1: длинные размазанные штрихи.",
                    whenUseful = "Дождь, ветер, ощущение движения.",
                ),
                demo = { it.copy(streakLength = 0.8f, streakOpacity = 0.6f) },
            ),
            dark(
                "Угол", 0f..180f, { it.streakAngle }, { p, v -> p.copy(streakAngle = v) },
                ParamHelp(
                    what = "Направление штрихов в градусах.",
                    low = "0°: горизонтально.",
                    high = "90°: вертикально (как дождь); 135° — диагональ в другую сторону.",
                    whenUseful = "60–80° — косой дождь.",
                ),
                demo = { it.copy(streakLength = 0.8f, streakOpacity = 0.6f) }, format = { "${it.roundToInt()}°" }, blend = false,
            ),
            dark(
                "Сила", 0f..1f, { it.streakStrength }, { p, v -> p.copy(streakStrength = v) },
                ParamHelp(
                    what = "Насколько ярко светятся штрихи от светлых мест.",
                    low = "0: штрихи — просто размытие.",
                    high = "1: светлые места тянут яркие полосы.",
                    whenUseful = "Фонари и окна ночью.",
                ),
                demo = { it.copy(streakLength = 0.7f, streakOpacity = 0.5f, streakStrength = 0.9f) },
            ),
            dark(
                "Прозрачность", 0f..1f, { it.streakOpacity }, { p, v -> p.copy(streakOpacity = v) },
                ParamHelp(
                    what = "Насколько штрихи перекрывают кадр.",
                    low = "0: штрихов не видно.",
                    high = "1: кадр почти целиком из штрихов.",
                    whenUseful = "0.2–0.4 — заметно, но кадр читается.",
                ),
                demo = { it.copy(streakLength = 0.7f, streakOpacity = 0.7f) },
            ),
        ),
    ),
    EffectCategory(
        "Текстура", CategoryIcon.GRAIN,
        listOf(
            dark(
                "Шум", 0f..1f, { it.noise }, { p, v -> p.copy(noise = v) },
                ParamHelp(
                    what = "Сильный цифровой шум, как на высоком ISO в темноте (хранится в ядре и в пресетах).",
                    low = "0: без шума.",
                    high = "1: густой «песок».",
                    whenUseful = "Ночные кадры: шум — часть даркора.",
                ),
                demo = { it.copy(noise = 0.8f) },
            ),
            dark(
                "Зерно", 0f..1f, { it.grain }, { p, v -> p.copy(grain = v) },
                ParamHelp(
                    what = "Мягкое зерно поверх шума.",
                    low = "0: без зерна.",
                    high = "1: крупное зерно.",
                    whenUseful = "Вместе с шумом — грязная ночная плёнка.",
                ),
                demo = { it.copy(grain = 0.9f) },
            ),
        ),
    ),
)

private val DitherTypeLabels = listOf("упоряд.", "диффуз.")

private val CircuitCategories = listOf(
    EffectCategory(
        "Палитра", CategoryIcon.PALETTE,
        listOf(
            circuit(
                "Уровни", CircuitParams.MIN_LEVELS.toFloat()..CircuitParams.MAX_LEVELS.toFloat(),
                { it.levels.toFloat() }, { p, v -> p.copy(levels = v.roundToInt()) },
                ParamHelp(
                    what = "Сколько ступенек яркости остаётся: картинка распадается на плоские цветные пятна.",
                    low = "2–3: жёсткие пятна, как у сломанного устройства.",
                    high = "12–16: почти плавно.",
                    whenUseful = "4–6 — классический circuit bend.",
                ),
                demo = { it.copy(levels = 4, paletteAmount = 0.6f) }, format = { "${it.roundToInt()}" },
                steps = CircuitParams.MAX_LEVELS - CircuitParams.MIN_LEVELS - 1,
            ),
            circuit(
                "Поворот палитры", 0f..1f, { it.paletteShift }, { p, v -> p.copy(paletteShift = v) },
                ParamHelp(
                    what = "Сдвигает психоделическую палитру по яркости: тени, середина и света меняются цветами.",
                    low = "0: исходный порядок цветов.",
                    high = "1: палитра провёрнута на полный круг.",
                    whenUseful = "Подобрать, в какой цвет уходит небо, а в какой — земля.",
                ),
                demo = { it.copy(levels = 5, paletteAmount = 0.9f, paletteShift = 0.4f) }, blend = false,
            ),
            circuit(
                "Соляризация", 0f..1f, { it.solarize }, { p, v -> p.copy(solarize = v) },
                ParamHelp(
                    what = "Светлое частично инвертируется: появляются негативные полосы и контуры.",
                    low = "0: без соляризации.",
                    high = "1: сильная, «кислотная» инверсия светов.",
                    whenUseful = "Небо и облака дают самые яркие полосы.",
                ),
                demo = { it.copy(solarize = 0.9f) },
            ),
            circuit(
                "Насыщенность", 0f..1f, { it.paletteAmount }, { p, v -> p.copy(paletteAmount = v) },
                ParamHelp(
                    what = "Насколько цвет заменяется психоделической палитрой: маджента, циан, лайм, оранжевый, фиолетовый.",
                    low = "0: постеризованный исходный цвет.",
                    high = "1: только палитра.",
                    whenUseful = "0.6–0.9 — узнаваемый «сломанный» цвет.",
                ),
                demo = { it.copy(levels = 5, paletteAmount = 0.9f) },
            ),
        ),
    ),
    EffectCategory(
        "Дизеринг", CategoryIcon.DITHER,
        listOf(
            circuit(
                "Сила", 0f..1f, { it.dither }, { p, v -> p.copy(dither = v) },
                ParamHelp(
                    what = "Переходы между уровнями рисуются точками вместо плавных градиентов.",
                    low = "0: ступеньки без точек.",
                    high = "1: всё в зернистом узоре точек.",
                    whenUseful = "Небо и градиенты становятся «точечными», как на старом принтере.",
                ),
                demo = { it.copy(levels = 4, dither = 0.9f, paletteAmount = 0.5f) },
            ),
            circuit(
                "Размер точек", 0f..1f, { it.ditherSize }, { p, v -> p.copy(ditherSize = v) },
                ParamHelp(
                    what = "Величина точек дизеринга относительно кадра (в фото и видео одинаковая).",
                    low = "0: мелкие точки.",
                    high = "1: крупные квадратные точки.",
                    whenUseful = "Крупные — для «пиксельного» вида.",
                ),
                demo = { it.copy(levels = 4, dither = 0.9f, ditherSize = 0.8f, paletteAmount = 0.5f) }, blend = false,
            ),
            circuit(
                "Тип", 0f..1f, { it.ditherType.toFloat() }, { p, v -> p.copy(ditherType = v.roundToInt()) },
                ParamHelp(
                    what = "Узор точек: упорядоченная сетка (Байер) или рассыпанные точки, как при диффузии ошибки.",
                    low = "Упорядоченный: ровная «вышивка» крестиком.",
                    high = "Диффузия: хаотичная россыпь точек.",
                    whenUseful = "Упорядоченный — «компьютерный» вид, диффузия — «газетный».",
                ),
                demo = { it.copy(levels = 4, dither = 0.9f, ditherSize = 0.5f, ditherType = 1, paletteAmount = 0.5f) },
                format = { DitherTypeLabels[it.roundToInt().coerceIn(0, 1)] }, blend = false,
            ),
        ),
    ),
    EffectCategory(
        "Порог", CategoryIcon.THRESHOLD,
        listOf(
            circuit(
                "Порог тёмного", 0f..1f, { it.darkThreshold }, { p, v -> p.copy(darkThreshold = v) },
                ParamHelp(
                    what = "Всё темнее порога схлопывается в глухой тёмно-синий.",
                    low = "0: тени как есть.",
                    high = "1: почти весь кадр тёмно-синий, остаются только света.",
                    whenUseful = "0.2–0.35 — силуэты деревьев и домов на ярком небе.",
                ),
                demo = { it.copy(darkThreshold = 0.4f) },
            ),
            circuit(
                "Контраст", 0f..1f, { it.contrast }, { p, v -> p.copy(contrast = v) },
                ParamHelp(
                    what = "Усиливает разницу светлого и тёмного перед постеризацией.",
                    low = "0: как есть.",
                    high = "1: жёсткий контраст.",
                    whenUseful = "Пасмурный день — больше контраста.",
                ),
                demo = { it.copy(contrast = 0.9f, levels = 6) },
            ),
            circuit(
                "Инверсия полос", 0f..1f, { it.bandInvert }, { p, v -> p.copy(bandInvert = v) },
                ParamHelp(
                    what = "Каждая вторая ступенька яркости инвертируется — получаются чередующиеся негативные полосы.",
                    low = "0: без инверсии.",
                    high = "1: полосатая, «перевёрнутая» картинка.",
                    whenUseful = "Градиенты неба и лица дают самые неожиданные полосы.",
                ),
                demo = { it.copy(bandInvert = 0.9f, levels = 5) },
            ),
            circuit(
                "Смена каналов", 0f..1f, { it.channelSwap }, { p, v -> p.copy(channelSwap = v) },
                ParamHelp(
                    what = "Красный, зелёный и синий каналы меняются местами: трава краснеет, небо зеленеет.",
                    low = "0: каналы на месте.",
                    high = "1: полная перестановка каналов.",
                    whenUseful = "Для совсем «чужих» цветов.",
                ),
                demo = { it.copy(channelSwap = 1f) },
            ),
        ),
    ),
    EffectCategory(
        "Сбой", CategoryIcon.GLITCH,
        listOf(
            circuit(
                "Сдвиг блоков", 0f..1f, { it.blockShift }, { p, v -> p.copy(blockShift = v) },
                ParamHelp(
                    what = "Во время всплесков сбоя прямоугольные блоки кадра съезжают по горизонтали. Анимированный эффект: в видео — короткими всплесками, на фото — как застал момент съёмки.",
                    low = "0: блоки на месте.",
                    high = "1: много сдвинутых блоков.",
                    whenUseful = "Ощущение «ломающегося» сигнала.",
                ),
                demo = null,
            ),
            circuit(
                "Залипание строк", 0f..1f, { it.lineStick }, { p, v -> p.copy(lineStick = v) },
                ParamHelp(
                    what = "Во время всплесков строки «залипают» и тянутся вниз полосами, а в видео иногда замирает весь кадр.",
                    low = "0: без залипаний.",
                    high = "1: частые полосы и замирания.",
                    whenUseful = "Вместе со сдвигом блоков — «потеря сигнала».",
                ),
                demo = null,
            ),
            circuit(
                "Частота всплесков", 0f..1f, { it.burstRate }, { p, v -> p.copy(burstRate = v) },
                ParamHelp(
                    what = "Как часто случаются всплески сбоя (сдвиги блоков и залипания).",
                    low = "0: сбоев нет.",
                    high = "1: сбои почти постоянно.",
                    whenUseful = "0.2–0.4 — изредка, 0.8+ — сломанный тракт.",
                ),
                demo = null,
            ),
            circuit(
                "Скорость палитры", 0f..1f, { it.animSpeed }, { p, v -> p.copy(animSpeed = v) },
                ParamHelp(
                    what = "Палитра и узор точек медленно «ползут» во времени (по времени, а не по кадрам). На фото не видно.",
                    low = "0: палитра стоит.",
                    high = "1: цвета заметно переливаются.",
                    whenUseful = "Немного движения делает видео живым.",
                ),
                demo = null,
            ),
        ),
    ),
)

private val NoiseCategory = noiseCategory<CoresParams>({ it.noise }, { p, n -> p.copy(noise = n) })

private fun coreCategories(core: CoreKind) = when (core) {
    CoreKind.DREAM -> DreamCategories
    CoreKind.SUN -> SunCategories
    CoreKind.DARK -> DarkCategories
    CoreKind.CIRCUIT -> CircuitCategories
}

/** Краткое описание ядра: что за стиль и для каких сцен. */
fun coreIntro(core: CoreKind): String = when (core) {
    CoreKind.DREAM -> "Dream (дримкор) — сюрреалистичная пастель и ощущение «знакомого, но неправильного места»: молочные насыщенные цвета, " +
        "фиолетовые тени и розовые света, дымка, мелкое зерно. Пустые улицы, дома, лужайки, небо с облаками."
    CoreKind.SUN -> "Sun (санкор) — яркий тёплый свет с эффектом Orton: кремовые и золотые света, жёлто-зелёная листва, сияние вокруг светлых мест, " +
        "засветы по краям. Сады, луга, вода на солнце, золотой час."
    CoreKind.DARK -> "Dark (даркор) — тёмный бирюзово-синий туман: приглушённый цвет, сине-зелёные тени, глубина через туман, шум высокого ISO, " +
        "косые штрихи размытия. Сумерки, лес, дождь, ночной город."
    CoreKind.CIRCUIT -> "Circuit (circuit bend) — нарочито «сломанный» тракт: жёсткая постеризация, психоделическая палитра, соляризация, " +
        "дизеринг точками, тёмное схлопывается в тёмно-синий, всплески цифрового сбоя. Небо, деревья, город — всё с яркими градиентами."
}

/** Копия [target] с ядром и параметрами этого ядра из [source] (остальные ядра не трогаются). */
fun CoresParams.withCoreFrom(source: CoresParams): CoresParams = when (source.core) {
    CoreKind.DREAM -> copy(core = source.core, dream = source.dream)
    CoreKind.SUN -> copy(core = source.core, sun = source.sun)
    CoreKind.DARK -> copy(core = source.core, dark = source.dark)
    CoreKind.CIRCUIT -> copy(core = source.core, circuit = source.circuit)
}

val CoresSpec = ModeSpec(
    mode = EffectMode.CORES,
    categories = listOf(General) + CoreKind.entries.flatMap { coreCategories(it) } + NoiseCategory,
    defaults = CoresParams(),
    neutral = CoresNeutral,
    get = { it.cores },
    put = { s, p -> s.copy(cores = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = coreIntro(CoreKind.DREAM),
    // Пресет ядра меняет ядро, его параметры, силу и шум; параметры остальных ядер остаются как были
    keep = { current, preset -> current.withCoreFrom(preset).copy(strength = preset.strength, noise = preset.noise) },
    categoriesOf = { p -> listOf(General) + coreCategories(p.core) + NoiseCategory },
    introOf = { coreIntro(it.core) },
    groupOf = { it.core.title },
)
