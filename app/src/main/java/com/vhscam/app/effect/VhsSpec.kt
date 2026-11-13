package com.vhscam.app.effect

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Описание режима «VHS» для интерфейса: категории, подписи, диапазоны и тексты справки.
 * В каждой категории не больше четырёх ползунков.
 */

private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
private fun signed(v: Float) = String.format(Locale.US, "%+.2f", v)

/** Подписи углов даты (ползунок «Угол даты» со значениями 0..3). */
val VhsCornerLabels = listOf("↘", "↙", "↗", "↖")

/** Подписи типов маски люминофора. */
val CrtMaskLabels = listOf("нет", "точки", "решётка", "щели")

/** Нейтральные значения: эффект почти не виден. От них считаются «Сила эффекта», «Сила пресета» и «×». */
private val VhsNeutral = VhsParams(
    lines = VhsParams.MAX_LINES, lumaSharpness = 1f, chromaBlur = 0f, chromaShift = 0f,
    ringing = 0f, scanlines = 0f, interlace = 0f,
    saturation = 1f, fadedBlacks = 0f, tint = 0f, contrast = 0f,
    tapeNoise = 0f, jitter = 0f, headSwitch = 0f, tracking = 0f,
    dropouts = 0f, trail = 0f, bloom = 0f, blockiness = 0f,
)

private fun vhsParam(
    label: String,
    range: ClosedFloatingPointRange<Float>,
    get: (VhsParams) -> Float,
    set: (VhsParams, Float) -> VhsParams,
    help: ParamHelp,
    demoBase: ((VhsParams) -> VhsParams)?,
    format: (Float) -> String = ::f2,
) = ParamSpec(label, range, format, get, set, help, demoBase = demoBase, blend = true)

val VhsSpec = ModeSpec(
    mode = EffectMode.VHS,
    categories = listOf(
        EffectCategory(
            "Общее", CategoryIcon.GENERAL, hasEnableSwitch = true,
            params = listOf(
                ParamSpec(
                    "Сила эффекта", 0f..1f, ::f2,
                    get = { it.strength }, set = { p, v -> p.copy(strength = v) },
                    help = ParamHelp(
                        what = "Общая громкость режима: одновременно ведёт все ползунки «VHS» от почти чистой картинки к текущим значениям.",
                        low = "0: картинка почти как с камеры.",
                        high = "1: все эффекты в полную силу своих ползунков.",
                        whenUseful = "Быстро сделать вид деликатнее, не трогая настройку каждого эффекта.",
                    ),
                    demoBase = { VhsParams() },
                ),
            ),
        ),
        EffectCategory(
            "Сигнал", CategoryIcon.SIGNAL,
            listOf(
                vhsParam(
                    "Строки", VhsParams.MIN_LINES.toFloat()..VhsParams.MAX_LINES.toFloat(),
                    format = { "${it.roundToInt()}" },
                    get = { it.lines.toFloat() }, set = { p, v -> p.copy(lines = v.roundToInt()) },
                    help = ParamHelp(
                        what = "Сколько строк развёртки помещается по высоте кадра. Картинка собирается из горизонтальных строк, а между строками сглаживается — без квадратных пикселей.",
                        low = "176–240: очень грубо, как видео со старого телефона.",
                        high = "600–720: строки почти не видны, как у кассет высокого качества.",
                        whenUseful = "480 — обычная кассета. Меньше — дешевле и старше вид.",
                    ),
                    demoBase = { it.copy(lines = 260, scanlines = 0.3f) },
                ),
                vhsParam(
                    "Чёткость яркости", 0f..1f,
                    get = { it.lumaSharpness }, set = { p, v -> p.copy(lumaSharpness = v) },
                    help = ParamHelp(
                        what = "Насколько резко по горизонтали передаётся яркость. Лента не пропускает мелкие детали, поэтому картинка размыта вдоль строк, а по вертикали остаётся чётче.",
                        low = "0: сильно «мыльная» картинка, мелкий текст не читается.",
                        high = "1: почти резкость камеры.",
                        whenUseful = "0.3–0.5 — типичная домашняя кассета, 0.7 — кассета высокого качества.",
                    ),
                    demoBase = { it.copy(lumaSharpness = 0.15f) },
                ),
                vhsParam(
                    "Размытие цвета", 0f..1f,
                    get = { it.chromaBlur }, set = { p, v -> p.copy(chromaBlur = v) },
                    help = ParamHelp(
                        what = "Цвет на кассете записывается с полосой в 7–10 раз уже, чем яркость: цвет размазывается вдоль строк и «вытекает» за контуры предметов.",
                        low = "0: цвет точно по контурам.",
                        high = "1: красная кофта красит фон на полкадра вокруг.",
                        whenUseful = "Главная примета VHS. Лучше всего видно на ярких цветных предметах.",
                    ),
                    demoBase = { it.copy(chromaBlur = 0.9f, saturation = 1.2f) },
                ),
                vhsParam(
                    "Сдвиг цвета", 0f..1f,
                    get = { it.chromaShift }, set = { p, v -> p.copy(chromaShift = v) },
                    help = ParamHelp(
                        what = "Цвет запаздывает относительно яркости и съезжает вправо; заодно цвет соседних строк усредняется, как в видеомагнитофоне.",
                        low = "0: цвет на своём месте.",
                        high = "1: заметная цветная тень справа от предметов.",
                        whenUseful = "Небольшой сдвиг (0.3–0.5) делает картинку «магнитофонной».",
                    ),
                    demoBase = { it.copy(chromaShift = 0.9f, chromaBlur = 0.3f, saturation = 1.2f) },
                ),
            ),
        ),
        EffectCategory(
            "Края", CategoryIcon.EDGES,
            listOf(
                vhsParam(
                    "Звон", 0f..1f,
                    get = { it.ringing }, set = { p, v -> p.copy(ringing = v) },
                    help = ParamHelp(
                        what = "Камкордер и магнитофон «подчёркивают» резкость с перебором: у контуров появляются светлые ореолы и тёмные каёмки.",
                        low = "0: контуры без ореолов.",
                        high = "1: яркие белые обводки вокруг тёмных предметов.",
                        whenUseful = "Хорошо видно на тёмных силуэтах на фоне неба и на тексте.",
                    ),
                    demoBase = { it.copy(ringing = 0.9f, lumaSharpness = 0.5f) },
                ),
                vhsParam(
                    "Видимость строк", 0f..1f,
                    get = { it.scanlines }, set = { p, v -> p.copy(scanlines = v) },
                    help = ParamHelp(
                        what = "Тёмные промежутки между строками развёртки, как на экране кинескопа. На маленьком превью при большом числе строк сами ослабевают, чтобы не было ряби.",
                        low = "0: строк не видно.",
                        high = "1: отчётливые тёмные полосы.",
                        whenUseful = "Слегка (0.1–0.2) — для «телевизионного» вида.",
                    ),
                    demoBase = { it.copy(lines = 300, scanlines = 0.6f) },
                ),
                vhsParam(
                    "Чересстрочность", 0f..1f,
                    get = { it.interlace }, set = { p, v -> p.copy(interlace = v) },
                    help = ParamHelp(
                        what = "Чётные и нечётные строки берутся из соседних кадров: на быстром движении края становятся «гребёнкой». Видно только в превью и видео; на фото и неподвижной картинке не заметно.",
                        low = "0: движение без гребёнки.",
                        high = "1: заметная гребёнка на всём, что движется.",
                        whenUseful = "Махи руками, проезжающие машины, быстрый поворот камеры.",
                    ),
                    demoBase = null,
                ),
            ),
        ),
        EffectCategory(
            "Цвет", CategoryIcon.COLOR,
            listOf(
                vhsParam(
                    "Насыщенность", 0f..1.5f,
                    get = { it.saturation }, set = { p, v -> p.copy(saturation = v) },
                    help = ParamHelp(
                        what = "Насколько яркие цвета (1 — как у камеры).",
                        low = "0: чёрно-белая запись.",
                        high = "1.5: кислотные цвета дешёвого телевизора.",
                        whenUseful = "Затёртая кассета — 0.5–0.7, свежая — 0.8–1.",
                    ),
                    demoBase = { it.copy(saturation = 0.35f) },
                ),
                vhsParam(
                    "Выцветшие чёрные", 0f..1f,
                    get = { it.fadedBlacks }, set = { p, v -> p.copy(fadedBlacks = v) },
                    help = ParamHelp(
                        what = "Чёрное становится тёмно-серым, как на переписанной кассете.",
                        low = "0: глубокий чёрный.",
                        high = "1: серые дымчатые тени.",
                        whenUseful = "Почти всегда немного: настоящая запись редко бывает по-настоящему чёрной.",
                    ),
                    demoBase = { it.copy(fadedBlacks = 0.7f) },
                ),
                vhsParam(
                    "Тёплый/зелёный", -1f..1f, format = ::signed,
                    get = { it.tint }, set = { p, v -> p.copy(tint = v) },
                    help = ParamHelp(
                        what = "Общий оттенок записи.",
                        low = "−1: зеленоватый, как у старой ленты или дешёвой камеры.",
                        high = "+1: тёплый, желтоватый, как домашний свет ламп.",
                        whenUseful = "Тёплый — домашние праздники, зелёный — «найденная плёнка».",
                    ),
                    demoBase = { it.copy(tint = 0.6f) },
                ),
                vhsParam(
                    "Контраст", -1f..1f, format = ::signed,
                    get = { it.contrast }, set = { p, v -> p.copy(contrast = v) },
                    help = ParamHelp(
                        what = "Разница между светлым и тёмным.",
                        low = "−1: плоская, серая картинка.",
                        high = "+1: плотные тени и яркие света.",
                        whenUseful = "Вместе с «Выцветшими чёрными» задаёт возраст записи.",
                    ),
                    demoBase = { it.copy(contrast = 0.7f) },
                ),
            ),
        ),
        EffectCategory(
            "Лента", CategoryIcon.TAPE,
            listOf(
                vhsParam(
                    "Шум ленты", 0f..1f,
                    get = { it.tapeNoise }, set = { p, v -> p.copy(tapeNoise = v) },
                    help = ParamHelp(
                        what = "Аналоговый шум: короткие горизонтальные полоски, у каждой строки свои, сильнее в тенях, плюс плавающие цветные пятна.",
                        low = "0: чистый сигнал.",
                        high = "1: заметная «рябь» по всему кадру.",
                        whenUseful = "Немного шума делает картинку живой; много — запись в темноте или старая кассета.",
                    ),
                    demoBase = { it.copy(tapeNoise = 0.8f, lumaSharpness = 0.5f) },
                ),
                vhsParam(
                    "Дрожание строк", 0f..1f,
                    get = { it.jitter }, set = { p, v -> p.copy(jitter = v) },
                    help = ParamHelp(
                        what = "Строки чуть смещаются по горизонтали и медленно «плавают», вертикальные линии становятся волнистыми; изредка — короткий всплеск сильнее. В движении заметнее, чем на картинке.",
                        low = "0: строки стоят ровно.",
                        high = "1: картинку заметно «водит» из стороны в сторону.",
                        whenUseful = "0.2–0.4 — обычная кассета, больше — растянутая лента.",
                    ),
                    demoBase = { it.copy(jitter = 0.9f) },
                ),
                vhsParam(
                    "Головки", 0f..1f,
                    get = { it.headSwitch }, set = { p, v -> p.copy(headSwitch = v) },
                    help = ParamHelp(
                        what = "Внизу кадра (5–8% высоты) видно переключение видеоголовок: несколько строк сбиты вправо и шумят.",
                        low = "0: низ кадра чистый.",
                        high = "1: широкая сбитая полоса внизу.",
                        whenUseful = "Самая узнаваемая примета записи с магнитофона.",
                    ),
                    demoBase = { it.copy(headSwitch = 1f) },
                ),
                vhsParam(
                    "Трекинг", 0f..1f,
                    get = { it.tracking }, set = { p, v -> p.copy(tracking = v) },
                    help = ParamHelp(
                        what = "Изредка снизу вверх по кадру проходит полоса искажений со «снегом», как при плохо настроенном трекинге. Видно только в превью и видео, на картинке не показать.",
                        low = "0: полос нет.",
                        high = "1: полоса проходит часто и заметно сильнее.",
                        whenUseful = "Немного — для достоверности; много — для эффекта «старой кассеты с чердака».",
                    ),
                    demoBase = null,
                ),
            ),
        ),
        EffectCategory(
            "Дефекты", CategoryIcon.DEFECTS,
            listOf(
                vhsParam(
                    "Дропауты", 0f..1f,
                    get = { it.dropouts }, set = { p, v -> p.copy(dropouts = v) },
                    help = ParamHelp(
                        what = "Осыпавшийся магнитный слой: короткие светлые чёрточки в одну-две строки, несколько в секунду, каждый раз в новом месте.",
                        low = "0: без дропаутов.",
                        high = "1: много вспыхивающих чёрточек.",
                        whenUseful = "Немного — почти всегда; много — для очень старой кассеты.",
                    ),
                    demoBase = { it.copy(dropouts = 1f) },
                ),
                vhsParam(
                    "Смаз/шлейф", 0f..1f,
                    get = { it.trail }, set = { p, v -> p.copy(trail = v) },
                    help = ParamHelp(
                        what = "Подмешивает предыдущий кадр (до 20–35%), сильнее в тёмных местах: за движущимися предметами тянется шлейф. Работает в превью и видео; на фото не виден.",
                        low = "0: чёткое движение.",
                        high = "1: заметный шлейф, особенно в темноте.",
                        whenUseful = "Огни, движение в сумерках — то самое «плывущее» видео камкордера.",
                    ),
                    demoBase = null,
                ),
                vhsParam(
                    "Свечение", 0f..1f,
                    get = { it.bloom }, set = { p, v -> p.copy(bloom = v) },
                    help = ParamHelp(
                        what = "Пересвеченные места светятся и расплываются вдоль строк — так камкордер переполнялся светом.",
                        low = "0: без свечения.",
                        high = "1: окна и лампы растекаются широкими полосами.",
                        whenUseful = "Окна, лампы, солнце, фары ночью.",
                    ),
                    demoBase = { it.copy(bloom = 0.9f) },
                ),
                vhsParam(
                    "Блочность", 0f..1f,
                    get = { it.blockiness }, set = { p, v -> p.copy(blockiness = v) },
                    help = ParamHelp(
                        what = "Квадратные блоки цифрового сжатия, как у видео с кнопочного телефона. На кассете их нет — по умолчанию выключено.",
                        low = "0: без блоков.",
                        high = "1: картинка рассыпается на блоки, цвет — пятнами.",
                        whenUseful = "Для вида «снято на телефон в 2005-м» вместе с малым числом строк.",
                    ),
                    demoBase = { it.copy(blockiness = 0.9f, lines = 240) },
                ),
            ),
        ),
        noiseCategory({ it.noise }, { p, n -> p.copy(noise = n) }),
        EffectCategory(
            "ЭЛТ экран", CategoryIcon.PIXELS,
            listOf(
                vhsParam(
                    "Маска люминофора", 0f..1f, { it.crtMask }, { p, v -> p.copy(crtMask = v) },
                    ParamHelp(
                        what = "Решётка цветных ячеек люминофора кинескопа (красный, зелёный, синий), как если снять экран телевизора вблизи. Размер ячейки — в долях кадра, на превью и в файле одинаковый; слишком мелкая для экрана ячейка плавно исчезает (без ряби).",
                        low = "0: маски нет.",
                        high = "1: ячейки видны отчётливо, картинка состоит из цветных полосок.",
                        whenUseful = "Вместе с «Кривизной» — вид экрана старого телевизора или монитора.",
                    ),
                    demoBase = { it.copy(crtMask = 1f, crtMaskSize = 0.012f) },
                ),
                ParamSpec(
                    "Тип маски", 0f..3f, { CrtMaskLabels[it.roundToInt().coerceIn(0, 3)] },
                    get = { it.crtMaskType.toFloat() }, set = { p, v -> p.copy(crtMaskType = v.roundToInt()) },
                    steps = 2,
                    help = ParamHelp(
                        what = "Рисунок маски: RGB-точки (теневая маска, как у телевизоров), апертурная решётка (сплошные вертикальные полоски, как у мониторов) или щелевая маска (полоски с короткими разрывами).",
                        low = "Нет: маска выключена.",
                        high = "Щели: полоски с разрывами.",
                        whenUseful = "Точки — телевизор, решётка — монитор, щели — большинство кинескопов 90-х.",
                    ),
                    demoBase = { it.copy(crtMask = 1f, crtMaskType = 1, crtMaskSize = 0.012f) },
                ),
                ParamSpec(
                    "Размер ячейки", VhsParams.MIN_CRT_CELL..VhsParams.MAX_CRT_CELL, { String.format(Locale.US, "%.1f%%", it * 100) },
                    get = { it.crtMaskSize }, set = { p, v -> p.copy(crtMaskSize = v) },
                    help = ParamHelp(
                        what = "Ширина тройки RGB в долях короткой стороны кадра.",
                        low = "0.2%: мелкая маска, видна в основном на фото полного размера.",
                        high = "2%: крупные ячейки, как в макросъёмке экрана.",
                        whenUseful = "Крупнее — заметнее на превью и в соцсетях.",
                    ),
                    demoBase = { it.copy(crtMask = 1f, crtMaskSize = 0.018f) },
                ),
                vhsParam(
                    "Кривизна экрана", 0f..1f, { it.crtCurve }, { p, v -> p.copy(crtCurve = v) },
                    ParamHelp(
                        what = "Выпуклое стекло кинескопа: картинка изгибается бочкой, углы скругляются, по краю — тёмная рамка и виньетка.",
                        low = "0: плоский кадр.",
                        high = "1: заметно выпуклый экран со скруглёнными углами.",
                        whenUseful = "Кадр «с экрана телевизора».",
                    ),
                    demoBase = { it.copy(crtCurve = 1f) },
                ),
            ),
        ),
        EffectCategory(
            "ЭЛТ цвет", CategoryIcon.SIGNAL,
            listOf(
                vhsParam(
                    "Цветные облака", 0f..1f, { it.crtClouds }, { p, v -> p.copy(crtClouds = v) },
                    ParamHelp(
                        what = "Крупные пастельные пятна цвета (голубые, жёлтые, пурпурные), растянутые по горизонтали, со сдвигом строк — как помехи цвета на старом экране.",
                        low = "0: облаков нет.",
                        high = "1: кадр в цветных разводах.",
                        whenUseful = "Сон, воспоминание, «сломанный» телевизор.",
                    ),
                    demoBase = { it.copy(crtClouds = 1f) },
                ),
                ParamSpec(
                    "Скорость облаков", 0f..1f, ::f2,
                    get = { it.crtCloudSpeed }, set = { p, v -> p.copy(crtCloudSpeed = v) },
                    help = ParamHelp(
                        what = "Как быстро облака плывут по кадру на видео и в превью (на фото — как в момент съёмки).",
                        low = "0: облака стоят на месте.",
                        high = "1: быстро плывут.",
                        whenUseful = "Медленно — спокойный фон, быстро — тревожные помехи.",
                    ),
                    demoBase = null,
                ),
                vhsParam(
                    "Муар/волна", 0f..1f, { it.crtWave }, { p, v -> p.copy(crtWave = v) },
                    ParamHelp(
                        what = "Волнистое искажение геометрии, сильнее к краям кадра, как у кинескопа с плохой сведением; вместе с маской даёт муар.",
                        low = "0: строки прямые.",
                        high = "1: заметная волна по краям.",
                        whenUseful = "Дешёвый или старый телевизор.",
                    ),
                    demoBase = { it.copy(crtWave = 1f, crtMask = 0.7f, crtMaskSize = 0.01f) },
                ),
            ),
        ),
        EffectCategory(
            "Оверлей", CategoryIcon.OVERLAY,
            toggles = listOf(
                ToggleSpec(
                    "REC ●",
                    get = { it.showRec }, set = { p, v -> p.copy(showRec = v) },
                    help = ParamHelp(
                        what = "Надпись «REC» с мигающей красной точкой в углу, как в видоискателе камкордера. Попадает и в видео, и в фото.",
                        low = "Выкл: без надписи.",
                        high = "Вкл: «● REC» в верхнем углу.",
                        whenUseful = "Для вида «запись с камеры» в сторис и клипах.",
                    ),
                    demoBase = { it.copy(showRec = true) },
                ),
            ),
            params = listOf(
                ParamSpec(
                    "Угол REC", 0f..VhsParams.MAX_CORNER.toFloat(),
                    format = { VhsCornerLabels[it.roundToInt().coerceIn(0, VhsParams.MAX_CORNER)] },
                    get = { it.recCorner.toFloat() }, set = { p, v -> p.copy(recCorner = v.roundToInt()) },
                    steps = VhsParams.MAX_CORNER - 1,
                    help = ParamHelp(
                        what = "В каком углу кадра стоит «REC»: справа внизу, слева внизу, справа вверху или слева вверху. Дата теперь общий штамп для всех режимов (кнопка в верхней полосе).",
                        low = "↘: справа внизу.",
                        high = "↖: слева вверху, как в видоискателе камкордера.",
                        whenUseful = "Чтобы «REC» не закрывал важную часть кадра и не спорил со штампом даты. Учитывается выбранный формат кадра.",
                    ),
                    demoBase = { it.copy(showRec = true, recCorner = 2) },
                ),
            ),
        ),
    ),
    defaults = VhsParams(),
    neutral = VhsNeutral,
    get = { it.vhs },
    put = { s, p -> s.copy(vhs = p) },
    isEnabled = { it.enabled },
    setEnabled = { p, on -> p.copy(enabled = on) },
    intro = "«VHS» — домашнее видео с камкордера 90-х: мягкая, размазанная вдоль строк картинка, цвет «вытекает» за контуры, видны строки, немного шума ленты и сбитые строки внизу.",
    // Оверлей — не часть вида пресета: при выборе пресета остаётся как был
    keep = { current, preset -> preset.copy(showRec = current.showRec, recCorner = current.recCorner) },
)
