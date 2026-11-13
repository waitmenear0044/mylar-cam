package com.vhscam.app.effect

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Пресет — именованный набор всех параметров одного режима (кроме флага «эффект включён»).
 * Параметры хранятся как [PixelParams], [FilmParams] или [VhsParams] с enabled = true.
 */
data class Preset(
    val id: String,
    val name: String,
    val mode: EffectMode,
    val params: Any,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mode", mode.name)
        .put("params", paramsToJson(mode, params))
}

/** Сравнение параметров без учёта флага enabled (и оверлея «VHS» — он не часть вида пресета). */
fun sameLook(mode: EffectMode, a: Any, b: Any): Boolean = when (mode) {
    EffectMode.PIXEL -> (a as PixelParams).copy(enabled = true) == (b as PixelParams).copy(enabled = true)
    EffectMode.FILM -> (a as FilmParams).copy(enabled = true) == (b as FilmParams).copy(enabled = true)
    EffectMode.VHS -> (a as VhsParams).lookOnly() == (b as VhsParams).lookOnly()
    EffectMode.CORES -> (a as CoresParams).lookOnly() == (b as CoresParams).lookOnly()
    EffectMode.GRADIENT -> (a as GradientParams).copy(enabled = true) == (b as GradientParams).copy(enabled = true)
    EffectMode.GHOST -> (a as GhostParams).copy(enabled = true) == (b as GhostParams).copy(enabled = true)
    EffectMode.NEWSPAPER -> (a as NewspaperParams).copy(enabled = true) == (b as NewspaperParams).copy(enabled = true)
    EffectMode.COLOR -> (a as ColorSelectParams).copy(enabled = true) == (b as ColorSelectParams).copy(enabled = true)
}

/** Вид «Cores» — ядро, его параметры, сила и шум; параметры других ядер в сравнение не входят. */
private fun CoresParams.lookOnly() = CoresParams(core = core, strength = strength, noise = noise).withCoreFrom(this)

private fun VhsParams.lookOnly() = copy(enabled = true, showRec = false, recCorner = 3)

fun paramsToJson(mode: EffectMode, params: Any): JSONObject = when (mode) {
    EffectMode.PIXEL -> (params as PixelParams).toJson()
    EffectMode.FILM -> (params as FilmParams).toJson()
    EffectMode.VHS -> (params as VhsParams).toJson()
    EffectMode.CORES -> (params as CoresParams).toJson()
    EffectMode.GRADIENT -> (params as GradientParams).toJson()
    EffectMode.GHOST -> (params as GhostParams).toJson()
    EffectMode.NEWSPAPER -> (params as NewspaperParams).toJson()
    EffectMode.COLOR -> (params as ColorSelectParams).toJson()
}

fun paramsFromJson(mode: EffectMode, o: JSONObject): Any = when (mode) {
    EffectMode.PIXEL -> pixelParamsFromJson(o)
    EffectMode.FILM -> filmParamsFromJson(o)
    EffectMode.VHS -> vhsParamsFromJson(o)
    EffectMode.CORES -> coresParamsFromJson(o)
    EffectMode.GRADIENT -> gradientParamsFromJson(o)
    EffectMode.GHOST -> ghostParamsFromJson(o)
    EffectMode.NEWSPAPER -> newspaperParamsFromJson(o)
    EffectMode.COLOR -> colorSelectParamsFromJson(o)
}

/** Готовые пресеты. Значения подобраны по кадрам из reference/look и проверены на эмуляторе. */
object BuiltInPresets {
    val all: List<Preset> = listOf(
        // «Пиксель»
        Preset("builtin:pixel:classic", "Классика", EffectMode.PIXEL, PixelParams(), builtIn = true),
        Preset(
            "builtin:pixel:phone", "Кнопочный телефон", EffectMode.PIXEL,
            PixelParams(resolutionWidth = 176, saturation = 0.7f, blackLevel = 0.06f, tint = -0.35f), builtIn = true,
        ),
        Preset(
            "builtin:pixel:8bit", "8 бит", EffectMode.PIXEL,
            PixelParams(resolutionWidth = 128, saturation = 1f, blackLevel = 0f, tint = 0f), builtIn = true,
        ),

        // «Плёнка»
        Preset(
            "builtin:film:soft", "Мягкая плёнка", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.2f, radiality = 0.6f, filmColor = 0.7f, contrast = 0.25f, fade = 0.45f,
                saturation = 0.75f, temperature = -0.05f, halation = 0.2f, grain = 0.3f, grainSize = 0.5f,
                motionBlur = 0.1f, softness = 0.35f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:neon", "Неон ночью", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.8f, radiality = 0.45f, filmColor = 0.9f, contrast = 0.5f, fade = 0.12f,
                saturation = 1.15f, temperature = -0.45f, halation = 0.9f, grain = 0.45f, grainSize = 0.55f,
                motionBlur = 0.25f, softness = 0.2f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:soapbox", "Мыльница", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.6f, radiality = 0.55f, filmColor = 0.8f, contrast = 0.3f, fade = 0.5f,
                saturation = 0.85f, temperature = -0.15f, halation = 0.3f, grain = 0.6f, grainSize = 0.65f,
                motionBlur = 0.15f, softness = 0.65f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:warm", "Тёплый день", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.3f, radiality = 0.5f, filmColor = 0.7f, contrast = 0.2f, fade = 0.3f,
                saturation = 0.95f, temperature = 0.5f, halation = 0.3f, grain = 0.25f, grainSize = 0.45f,
                motionBlur = 0.1f, softness = 0.25f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:dream", "Сон", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.35f, radiality = 0.7f, filmColor = 0.6f, contrast = 0.15f, fade = 0.45f,
                saturation = 0.7f, temperature = 0.1f, halation = 0.7f, grain = 0.3f, grainSize = 0.7f,
                motionBlur = 0.8f, softness = 0.6f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:spectrum", "Спектр", EffectMode.FILM,
            FilmParams(
                splitStrength = 1f, radiality = 0.5f, splitSamples = 16, filmColor = 0.6f, contrast = 0.3f,
                fade = 0.2f, saturation = 0.9f, temperature = -0.1f, halation = 0.3f, grain = 0.3f,
                grainSize = 0.5f, motionBlur = 0.1f, softness = 0.1f,
            ),
            builtIn = true,
        ),

        // Приближённые имитации по характеру популярных плёнок (без претензии на точность)
        Preset(
            "builtin:film:portra400", "Тёплый портрет 400", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.25f, radiality = 0.6f, filmColor = 0.6f, contrast = 0.15f, fade = 0.3f,
                saturation = 0.8f, temperature = 0.25f, halation = 0.25f, grain = 0.3f, grainSize = 0.45f,
                motionBlur = 0.1f, softness = 0.3f,
                shadowHue = 25f, shadowTone = 0.2f, highlightHue = 40f, highlightTone = 0.2f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:gold200", "Золотая 200", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.3f, radiality = 0.55f, filmColor = 0.7f, contrast = 0.35f, fade = 0.2f,
                saturation = 1.2f, temperature = 0.5f, halation = 0.3f, grain = 0.5f, grainSize = 0.6f,
                motionBlur = 0.1f, softness = 0.2f,
                shadowHue = 30f, shadowTone = 0.15f, highlightHue = 50f, highlightTone = 0.4f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:superia400", "Зелёная 400", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.35f, radiality = 0.5f, filmColor = 0.8f, contrast = 0.35f, fade = 0.2f,
                saturation = 1.0f, temperature = -0.2f, halation = 0.25f, grain = 0.55f, grainSize = 0.55f,
                motionBlur = 0.1f, softness = 0.2f,
                shadowHue = 140f, shadowTone = 0.5f, highlightHue = 200f, highlightTone = 0.3f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:cinestill800t", "Ночная 800T", EffectMode.FILM,
            FilmParams(
                splitStrength = 0.4f, radiality = 0.5f, filmColor = 0.7f, contrast = 0.2f, fade = 0.25f,
                saturation = 0.95f, temperature = -0.35f, halation = 0.9f, grain = 0.45f, grainSize = 0.6f,
                motionBlur = 0.15f, softness = 0.3f,
                shadowHue = 185f, shadowTone = 0.6f, highlightHue = 25f, highlightTone = 0.2f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:film:hp5", "Ч/Б 400", EffectMode.FILM,
            FilmParams(
                splitStrength = 0f, radiality = 0.5f, filmColor = 0.4f, contrast = 0.75f, fade = 0.1f,
                saturation = 0f, temperature = 0f, halation = 0.15f, grain = 0.8f, grainSize = 0.75f,
                motionBlur = 0.1f, softness = 0.25f,
            ),
            builtIn = true,
        ),

        // «VHS»: значения по описанию камкордеров 90-х и стадиям ntsc-rs (SP/LP: яркость ~2 МГц,
        // цвет ~0.3 МГц — цвет размазан в 7–8 раз сильнее), проверены на кадрах с эмулятора
        Preset("builtin:vhs:sp", "VHS SP", EffectMode.VHS, VhsParams(), builtIn = true),
        Preset(
            "builtin:vhs:hi8", "Видео-8", EffectMode.VHS,
            VhsParams(
                lines = 576, lumaSharpness = 0.72f, chromaBlur = 0.4f, chromaShift = 0.22f,
                ringing = 0.3f, scanlines = 0.12f, interlace = 0.25f,
                saturation = 0.85f, fadedBlacks = 0.12f, tint = 0.05f, contrast = 0.15f,
                tapeNoise = 0.14f, jitter = 0.15f, headSwitch = 0.3f, tracking = 0f,
                dropouts = 0.08f, trail = 0.4f, bloom = 0.25f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:vhs:worn", "Затёртая кассета", EffectMode.VHS,
            VhsParams(
                lines = 400, lumaSharpness = 0.28f, chromaBlur = 0.8f, chromaShift = 0.65f,
                ringing = 0.45f, scanlines = 0.18f, interlace = 0.35f,
                saturation = 0.5f, fadedBlacks = 0.42f, tint = 0.25f, contrast = -0.1f,
                tapeNoise = 0.7f, jitter = 0.7f, headSwitch = 0.9f, tracking = 0.25f,
                dropouts = 0.5f, trail = 0.75f, bloom = 0.4f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:vhs:tracking", "Трекинг", EffectMode.VHS,
            VhsParams(
                lines = 440, lumaSharpness = 0.4f, chromaBlur = 0.65f, chromaShift = 0.45f,
                ringing = 0.35f, scanlines = 0.15f, interlace = 0.3f,
                saturation = 0.6f, fadedBlacks = 0.3f, tint = 0.1f, contrast = 0f,
                tapeNoise = 0.45f, jitter = 0.5f, headSwitch = 0.7f, tracking = 0.85f,
                dropouts = 0.8f, trail = 0.6f, bloom = 0.35f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:vhs:phone", "Кнопочный телефон", EffectMode.VHS,
            VhsParams(
                lines = 208, lumaSharpness = 0.6f, chromaBlur = 0.35f, chromaShift = 0.15f,
                ringing = 0.1f, scanlines = 0.04f, interlace = 0f,
                saturation = 0.5f, fadedBlacks = 0.35f, tint = -0.3f, contrast = 0.15f,
                tapeNoise = 0.08f, jitter = 0f, headSwitch = 0f, tracking = 0f,
                dropouts = 0f, trail = 0.45f, bloom = 0.2f, blockiness = 0.8f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:vhs:light", "Лёгкая ностальгия", EffectMode.VHS,
            VhsParams(
                lines = 600, lumaSharpness = 0.62f, chromaBlur = 0.45f, chromaShift = 0.25f,
                ringing = 0.2f, scanlines = 0.1f, interlace = 0.15f,
                saturation = 0.8f, fadedBlacks = 0.18f, tint = 0.12f, contrast = 0.05f,
                tapeNoise = 0.12f, jitter = 0.12f, headSwitch = 0.25f, tracking = 0f,
                dropouts = 0.05f, trail = 0.3f, bloom = 0.2f,
            ),
            builtIn = true,
        ),
        // ЭЛТ: экран кинескопа поверх сигнала VHS
        Preset(
            "builtin:vhs:crt_tv", "ЭЛТ-телевизор", EffectMode.VHS,
            VhsParams(
                lines = 480, scanlines = 0.35f, saturation = 0.85f, bloom = 0.45f,
                crtMask = 0.75f, crtMaskType = 1, crtMaskSize = 0.007f, crtCurve = 0.7f,
                crtClouds = 0.25f, crtCloudSpeed = 0.25f, crtWave = 0.2f,
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:vhs:monitor", "Монитор", EffectMode.VHS,
            VhsParams(
                lines = 600, lumaSharpness = 0.7f, chromaBlur = 0.3f, chromaShift = 0.15f, ringing = 0.15f, scanlines = 0.25f,
                interlace = 0f, saturation = 1f, fadedBlacks = 0.08f, tint = 0f, contrast = 0.2f,
                tapeNoise = 0f, jitter = 0.05f, headSwitch = 0f, tracking = 0f, dropouts = 0f, trail = 0.15f, bloom = 0.3f,
                crtMask = 0.85f, crtMaskType = 2, crtMaskSize = 0.005f, crtCurve = 0.35f,
            ),
            builtIn = true,
        ),

        // «Cores»: значения подобраны по кадрам из reference/look (dreamcore, sunkor, darkcore, circuitbend)
        Preset(
            "builtin:cores:dream:suburb", "Пригород", EffectMode.CORES,
            CoresParams(core = CoreKind.DREAM, dream = DreamParams()), builtIn = true,
        ),
        Preset(
            "builtin:cores:dream:haze", "Зыбкий сон", EffectMode.CORES,
            CoresParams(
                core = CoreKind.DREAM,
                dream = DreamParams(
                    pastel = 0.5f, shadowTone = 0.6f, highlightTone = 0.55f, contrast = -0.45f, haze = 0.65f,
                    softness = 0.7f, glow = 0.6f, vignette = 0.3f, grain = 0.3f, posterize = 0.1f,
                ),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:cores:dream:pastel", "Пастель", EffectMode.CORES,
            CoresParams(
                core = CoreKind.DREAM,
                dream = DreamParams(
                    pastel = 0.9f, shadowTone = 0.35f, highlightTone = 0.6f, contrast = -0.2f, haze = 0.2f,
                    softness = 0.2f, glow = 0.25f, vignette = 0.1f, grain = 0.15f, posterize = 0.35f,
                ),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:cores:sun:golden", "Золотой час", EffectMode.CORES,
            CoresParams(core = CoreKind.SUN, sun = SunParams()), builtIn = true,
        ),
        Preset(
            "builtin:cores:sun:bloom", "Пересвет", EffectMode.CORES,
            CoresParams(
                core = CoreKind.SUN,
                sun = SunParams(
                    exposure = 0.5f, glow = 0.9f, lightLeak = 0.55f, softness = 0.45f, warmth = 0.6f,
                    saturation = 1.0f, yellowGreen = 0.45f, contrast = -0.4f, grain = 0.2f, haze = 0.4f,
                ),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:cores:sun:garden", "Утро в саду", EffectMode.CORES,
            CoresParams(
                core = CoreKind.SUN,
                sun = SunParams(
                    exposure = 0.15f, glow = 0.45f, lightLeak = 0.15f, softness = 0.25f, warmth = 0.3f,
                    saturation = 1.25f, yellowGreen = 0.8f, contrast = -0.1f, grain = 0.15f, haze = 0.15f,
                ),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:cores:dark:fog", "Туман", EffectMode.CORES,
            CoresParams(core = CoreKind.DARK, dark = DarkParams()), builtIn = true,
        ),
        Preset(
            "builtin:cores:dark:forest", "Ночной лес", EffectMode.CORES,
            CoresParams(
                core = CoreKind.DARK,
                dark = DarkParams(
                    teal = 0.75f, darken = 0.6f, liftedBlacks = 0.45f, saturation = 0.45f, fog = 0.3f,
                    softness = 0.35f, vignette = 0.6f, glow = 0.4f, streakLength = 0.2f, streakAngle = 80f,
                    streakStrength = 0.2f, streakOpacity = 0.15f, noise = 0.45f, grain = 0.35f,
                ),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:cores:dark:rain", "Дождь", EffectMode.CORES,
            CoresParams(
                core = CoreKind.DARK,
                dark = DarkParams(
                    teal = 0.55f, darken = 0.35f, liftedBlacks = 0.3f, saturation = 0.5f, fog = 0.35f,
                    softness = 0.25f, vignette = 0.4f, glow = 0.3f, streakLength = 0.75f, streakAngle = 75f,
                    streakStrength = 0.55f, streakOpacity = 0.5f, noise = 0.3f, grain = 0.25f,
                ),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:cores:circuit:solar", "Соляризация", EffectMode.CORES,
            CoresParams(
                core = CoreKind.CIRCUIT,
                circuit = CircuitParams(
                    levels = 6, paletteShift = 0.15f, solarize = 0.75f, paletteAmount = 0.55f, dither = 0.45f,
                    ditherSize = 0.25f, ditherType = 0, darkThreshold = 0.15f, contrast = 0.35f, bandInvert = 0.35f,
                    channelSwap = 0.1f, blockShift = 0.15f, lineStick = 0.1f, burstRate = 0.15f, animSpeed = 0.2f,
                ),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:cores:circuit:acid", "Кислота", EffectMode.CORES,
            CoresParams(core = CoreKind.CIRCUIT, circuit = CircuitParams()), builtIn = true,
        ),
        Preset(
            "builtin:cores:circuit:lost", "Потеря сигнала", EffectMode.CORES,
            CoresParams(
                core = CoreKind.CIRCUIT,
                circuit = CircuitParams(
                    levels = 3, paletteShift = 0.55f, solarize = 0.3f, paletteAmount = 0.85f, dither = 0.85f,
                    ditherSize = 0.55f, ditherType = 1, darkThreshold = 0.32f, contrast = 0.7f, bandInvert = 0.15f,
                    channelSwap = 0.6f, blockShift = 0.8f, lineStick = 0.7f, burstRate = 0.75f, animSpeed = 0.6f,
                ),
            ),
            builtIn = true,
        ),
    ) + BuiltInGradients.all.take(3).zip(listOf("Закат на плёнке", "Неоновый плакат", "Тепловизор")).mapIndexed { i, (g, name) ->
        // «Градиент»: градиент из набора вместе с ползунками
        Preset(
            "builtin:gradient:$i", name, EffectMode.GRADIENT,
            GradientParams(strength = if (i == 0) 0.75f else 1f, contrast = if (i == 1) 0.45f else 0.1f).with(g), builtIn = true,
        )
    } + listOf(
        Preset("builtin:gradient:tone", "Лёгкая тонировка", EffectMode.GRADIENT, GradientParams(strength = 0.4f).with(BuiltInGradients.all[3]), builtIn = true),
    ) + BuiltInGradients.all.filter { it.name != "Тепловизор" }.map { g ->
        // По пресету на каждый готовый градиент: его название и параметры по умолчанию («Тепловизор» уже есть выше)
        Preset("builtin:gradient:map:${g.id.removePrefix("builtin:")}", g.name, EffectMode.GRADIENT, GradientParams().with(g), builtIn = true)
    } + listOf(
        // «Гостинг»: по два на тип
        Preset("builtin:ghost:mirrors", "Зал зеркал", EffectMode.GHOST, GhostParams(echo = false, sensitivity = 0.6f, seconds = 3f, strength = 0.9f, colorShift = 0.15f), builtIn = true),
        Preset("builtin:ghost:acid", "Кислотные копии", EffectMode.GHOST, GhostParams(echo = false, sensitivity = 0.75f, seconds = 1.5f, strength = 0.8f, colorShift = 0.9f), builtIn = true),
        Preset("builtin:ghost:tv", "Старый телевизор", EffectMode.GHOST, GhostParams(echo = true, seconds = 0.8f, strength = 0.6f, colorShift = 0.1f), builtIn = true),
        Preset("builtin:ghost:spirit", "Призрак", EffectMode.GHOST, GhostParams(echo = true, seconds = 4f, strength = 0.85f, colorShift = 0.5f), builtIn = true),
        // «Гостинг»: по пресету на форму шлейфа (чёткий — «Зал зеркал» выше)
        Preset("builtin:ghost:soft", "Размытый шлейф", EffectMode.GHOST, GhostParams(echo = true, seconds = 1.5f, strength = 0.8f, colorShift = 0.2f, shape = 1, shapeSize = 0.6f), builtIn = true),
        Preset("builtin:ghost:pixel", "Пиксельный шлейф", EffectMode.GHOST, GhostParams(echo = false, sensitivity = 0.6f, seconds = 2f, strength = 0.85f, colorShift = 0.3f, shape = 2, shapeSize = 0.35f), builtIn = true),
        Preset("builtin:ghost:blocks", "Блочный шлейф", EffectMode.GHOST, GhostParams(echo = false, sensitivity = 0.65f, seconds = 2.5f, strength = 0.85f, colorShift = 0.15f, shape = 3, shapeSize = 0.5f, degrade = 0.6f), builtIn = true),
        Preset("builtin:ghost:shakal", "Шакальный шлейф", EffectMode.GHOST, GhostParams(echo = true, seconds = 2f, strength = 0.9f, colorShift = 0.4f, shape = 4, shapeSize = 0.45f, degrade = 0.8f), builtIn = true),
        // «Газета»
        Preset("builtin:news:bw", "Газета ЧБ", EffectMode.NEWSPAPER, NewspaperParams(kind = 0, cell = 0.007f, gain = 1.3f, offset = 0.05f, paper = 1), builtIn = true),
        Preset("builtin:news:rosette", "Цветная розетка", EffectMode.NEWSPAPER, NewspaperParams(kind = 1, cell = 0.009f, gain = 1.2f, offset = 0.15f, paper = 0), builtIn = true),
        Preset("builtin:news:rgb", "RGB-сдвиг", EffectMode.NEWSPAPER, NewspaperParams(kind = 2, cell = 0.01f, gain = 1.1f, offset = 0.5f, angle = 0f), builtIn = true),
        Preset(
            "builtin:news:old", "Старая жёлтая", EffectMode.NEWSPAPER,
            NewspaperParams(kind = 0, cell = 0.008f, gain = 1.1f, offset = 0.2f, blueInk = true, paper = 2),
            builtIn = true,
        ),
        // «Выбор цвета»
        Preset(
            "builtin:color:red", "Красный остаётся", EffectMode.COLOR,
            ColorSelectParams(keep = true, slots = listOf(ColorSlot(true, 355f, 22f, 0.5f), ColorSlot(hue = 210f), ColorSlot(hue = 120f)), satMin = 0.3f), builtIn = true,
        ),
        Preset(
            "builtin:color:sky", "Небо в чёрный", EffectMode.COLOR,
            ColorSelectParams(
                keep = false, slots = listOf(ColorSlot(true, 212f, 38f, 0.5f), ColorSlot(hue = 0f), ColorSlot(hue = 120f)), satMin = 0.12f,
                curve = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0f)),
            ),
            builtIn = true,
        ),
        Preset(
            "builtin:color:warm", "Тёплые тона остаются", EffectMode.COLOR,
            ColorSelectParams(keep = true, slots = listOf(ColorSlot(true, 25f, 45f, 0.6f), ColorSlot(hue = 210f), ColorSlot(hue = 120f)), satMin = 0.18f), builtIn = true,
        ),
        // «Макро»
    )
}

/** Свои пресеты пользователя в JSON-файле во внутренней памяти приложения. */
class PresetRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "presets.json")

    fun loadUser(): List<Preset> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONObject(file.readText()).getJSONArray("presets")
        (0 until array.length()).mapNotNull { i -> parsePreset(array.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    fun saveUser(presets: List<Preset>) {
        val array = JSONArray()
        presets.filterNot { it.builtIn }.forEach { array.put(it.toJson()) }
        val tmp = File(file.parentFile, "presets.json.tmp")
        tmp.writeText(JSONObject().put("version", 1).put("presets", array).toString())
        tmp.renameTo(file)
    }

    private fun parsePreset(o: JSONObject): Preset? {
        val params = o.optJSONObject("params") ?: return null
        // Пресеты удалённых режимов и ядер молча пропускаются (в файле они останутся до следующего сохранения)
        if (rejection(o.optString("mode"), params) != null) return null
        val mode = EffectMode.entries.firstOrNull { it.name == o.optString("mode") } ?: return null
        return Preset(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { "Без названия" },
            mode = mode,
            params = paramsFromJson(mode, params),
        )
    }

    companion object {
        /** Коды пишутся с новым префиксом; старые (VHSCAM1:) читаются как раньше. */
        const val CODE_PREFIX = "MYLAR1:"
        const val OLD_CODE_PREFIX = "VHSCAM1:"

        /** Код для обмена: префикс + Base64 от JSON с режимом, именем и параметрами. */
        fun encode(preset: Preset): String {
            val json = JSONObject()
                .put("v", 1)
                .put("mode", preset.mode.name)
                .put("name", preset.name)
                .put("params", paramsToJson(preset.mode, preset.params))
            return CODE_PREFIX + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        }

        /** Режимы и ядра, которых в приложении больше нет: их коды и сохранённые пресеты пропускаются без падения. */
        private val REMOVED_MODES = setOf("MACRO", "HOLO")
        private val REMOVED_CORES = setOf("AERO")

        /** Почему код не принят: «режим удалён» / «ядро удалено»; null — причина в другом (код некорректный). */
        fun rejection(code: String): String? = runCatching {
            val body = code.trim().removePrefix(CODE_PREFIX).removePrefix(OLD_CODE_PREFIX).filterNot { it.isWhitespace() }
            val json = JSONObject(String(Base64.decode(body, Base64.DEFAULT)))
            rejection(json.optString("mode"), json.optJSONObject("params"))
        }.getOrNull()

        fun rejection(mode: String, params: JSONObject?): String? = when {
            mode in REMOVED_MODES -> "режим удалён"
            mode == "CORES" && params?.optString("core") in REMOVED_CORES -> "ядро удалено"
            else -> null
        }

        /** Разбирает код; null, если строка не похожа на код пресета. Новый пресет получает новый id. */
        fun decode(code: String): Preset? = runCatching {
            val body = code.trim().removePrefix(CODE_PREFIX).removePrefix(OLD_CODE_PREFIX).filterNot { it.isWhitespace() }
            val json = JSONObject(String(Base64.decode(body, Base64.DEFAULT)))
            if (rejection(json.getString("mode"), json.optJSONObject("params")) != null) return null
            val mode = EffectMode.entries.firstOrNull { it.name == json.getString("mode") } ?: return null
            Preset(
                id = UUID.randomUUID().toString(),
                name = json.optString("name").ifBlank { "Пресет" }.take(40),
                mode = mode,
                params = paramsFromJson(mode, json.getJSONObject("params")),
            )
        }.getOrNull()
    }
}
