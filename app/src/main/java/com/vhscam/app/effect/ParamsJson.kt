package com.vhscam.app.effect

import org.json.JSONObject

/*
 * JSON-представление параметров режимов (для пресетов и кодов обмена). Флаг enabled не хранится:
 * пресет описывает вид, а не то, включён ли эффект. Неизвестные ключи игнорируются, недостающие
 * берутся по умолчанию, значения обрезаются до допустимых диапазонов — коды от старых или новых
 * версий не ломают приложение.
 */

fun PixelParams.toJson(): JSONObject = JSONObject()
    .put("resolutionWidth", resolutionWidth)
    .put("saturation", r(saturation))
    .put("blackLevel", r(blackLevel))
    .put("tint", r(tint))
    .putNoise(noise)

fun pixelParamsFromJson(o: JSONObject): PixelParams {
    val d = PixelParams()
    return PixelParams(
        resolutionWidth = o.optInt("resolutionWidth", d.resolutionWidth)
            .coerceIn(PixelParams.MIN_WIDTH, PixelParams.MAX_WIDTH),
        saturation = o.f("saturation", d.saturation, 0f, 1f),
        blackLevel = o.f("blackLevel", d.blackLevel, 0f, PixelParams.MAX_BLACK_LEVEL),
        tint = o.f("tint", d.tint, -1f, 1f),
        noise = o.noise(),
    )
}

fun FilmParams.toJson(): JSONObject = JSONObject()
    .put("strength", r(strength))
    .put("splitStrength", r(splitStrength))
    .put("radiality", r(radiality))
    .put("splitSamples", splitSamples)
    .put("filmColor", r(filmColor))
    .put("contrast", r(contrast))
    .put("fade", r(fade))
    .put("saturation", r(saturation))
    .put("temperature", r(temperature))
    .put("halation", r(halation))
    .put("grain", r(grain))
    .put("grainSize", r(grainSize))
    .put("motionBlur", r(motionBlur))
    .put("softness", r(softness))
    .put("shadowHue", r(shadowHue))
    .put("shadowTone", r(shadowTone))
    .put("highlightHue", r(highlightHue))
    .put("highlightTone", r(highlightTone))
    .putNoise(noise)

fun filmParamsFromJson(o: JSONObject): FilmParams {
    val d = FilmParams()
    return FilmParams(
        strength = o.f("strength", d.strength, 0f, 1f),
        splitStrength = o.f("splitStrength", d.splitStrength, 0f, 1f),
        radiality = o.f("radiality", d.radiality, 0f, 1f),
        splitSamples = o.optInt("splitSamples", d.splitSamples)
            .coerceIn(FilmParams.MIN_SAMPLES, FilmParams.MAX_SAMPLES),
        filmColor = o.f("filmColor", d.filmColor, 0f, 1f),
        contrast = o.f("contrast", d.contrast, 0f, 1f),
        fade = o.f("fade", d.fade, 0f, 1f),
        saturation = o.f("saturation", d.saturation, 0f, 1.5f),
        temperature = o.f("temperature", d.temperature, -1f, 1f),
        halation = o.f("halation", d.halation, 0f, 1f),
        grain = o.f("grain", d.grain, 0f, 1f),
        grainSize = o.f("grainSize", d.grainSize, 0f, 1f),
        motionBlur = o.f("motionBlur", d.motionBlur, 0f, 1f),
        softness = o.f("softness", d.softness, 0f, 1f),
        shadowHue = o.f("shadowHue", d.shadowHue, 0f, 360f),
        shadowTone = o.f("shadowTone", d.shadowTone, 0f, 1f),
        highlightHue = o.f("highlightHue", d.highlightHue, 0f, 360f),
        highlightTone = o.f("highlightTone", d.highlightTone, 0f, 1f),
        noise = o.noise(),
    )
}

fun VhsParams.toJson(): JSONObject = JSONObject()
    .put("strength", r(strength))
    .put("lines", lines)
    .put("lumaSharpness", r(lumaSharpness))
    .put("chromaBlur", r(chromaBlur))
    .put("chromaShift", r(chromaShift))
    .put("ringing", r(ringing))
    .put("scanlines", r(scanlines))
    .put("interlace", r(interlace))
    .put("saturation", r(saturation))
    .put("fadedBlacks", r(fadedBlacks))
    .put("tint", r(tint))
    .put("contrast", r(contrast))
    .put("tapeNoise", r(tapeNoise))
    .put("jitter", r(jitter))
    .put("headSwitch", r(headSwitch))
    .put("tracking", r(tracking))
    .put("dropouts", r(dropouts))
    .put("trail", r(trail))
    .put("bloom", r(bloom))
    .put("blockiness", r(blockiness))
    .put("crtMask", r(crtMask)).put("crtMaskType", crtMaskType).put("crtMaskSize", r(crtMaskSize))
    .put("crtCurve", r(crtCurve)).put("crtClouds", r(crtClouds)).put("crtCloudSpeed", r(crtCloudSpeed)).put("crtWave", r(crtWave))
    .put("showRec", showRec)
    .put("recCorner", recCorner)
    .putNoise(noise)

fun vhsParamsFromJson(o: JSONObject): VhsParams {
    val d = VhsParams()
    return VhsParams(
        strength = o.f("strength", d.strength, 0f, 1f),
        lines = o.optInt("lines", d.lines).coerceIn(VhsParams.MIN_LINES, VhsParams.MAX_LINES),
        lumaSharpness = o.f("lumaSharpness", d.lumaSharpness, 0f, 1f),
        chromaBlur = o.f("chromaBlur", d.chromaBlur, 0f, 1f),
        chromaShift = o.f("chromaShift", d.chromaShift, 0f, 1f),
        ringing = o.f("ringing", d.ringing, 0f, 1f),
        scanlines = o.f("scanlines", d.scanlines, 0f, 1f),
        interlace = o.f("interlace", d.interlace, 0f, 1f),
        saturation = o.f("saturation", d.saturation, 0f, 1.5f),
        fadedBlacks = o.f("fadedBlacks", d.fadedBlacks, 0f, 1f),
        tint = o.f("tint", d.tint, -1f, 1f),
        contrast = o.f("contrast", d.contrast, -1f, 1f),
        tapeNoise = o.f("tapeNoise", d.tapeNoise, 0f, 1f),
        jitter = o.f("jitter", d.jitter, 0f, 1f),
        headSwitch = o.f("headSwitch", d.headSwitch, 0f, 1f),
        tracking = o.f("tracking", d.tracking, 0f, 1f),
        dropouts = o.f("dropouts", d.dropouts, 0f, 1f),
        trail = o.f("trail", d.trail, 0f, 1f),
        bloom = o.f("bloom", d.bloom, 0f, 1f),
        blockiness = o.f("blockiness", d.blockiness, 0f, 1f),
        crtMask = o.f("crtMask", d.crtMask, 0f, 1f),
        crtMaskType = o.optInt("crtMaskType", d.crtMaskType).coerceIn(0, 3),
        crtMaskSize = o.f("crtMaskSize", d.crtMaskSize, VhsParams.MIN_CRT_CELL, VhsParams.MAX_CRT_CELL),
        crtCurve = o.f("crtCurve", d.crtCurve, 0f, 1f),
        crtClouds = o.f("crtClouds", d.crtClouds, 0f, 1f),
        crtCloudSpeed = o.f("crtCloudSpeed", d.crtCloudSpeed, 0f, 1f),
        crtWave = o.f("crtWave", d.crtWave, 0f, 1f),
        showRec = o.optBoolean("showRec", d.showRec),
        recCorner = o.optInt("recCorner", d.recCorner).coerceIn(0, VhsParams.MAX_CORNER),
        noise = o.noise(),
    )
}

fun CoresParams.toJson(): JSONObject = JSONObject()
    .put("core", core.name)
    .put("strength", r(strength))
    .put("dream", JSONObject()
        .put("pastel", r(dream.pastel)).put("shadowTone", r(dream.shadowTone)).put("highlightTone", r(dream.highlightTone))
        .put("contrast", r(dream.contrast)).put("haze", r(dream.haze)).put("softness", r(dream.softness))
        .put("glow", r(dream.glow)).put("vignette", r(dream.vignette)).put("grain", r(dream.grain))
        .put("posterize", r(dream.posterize)).put("noise", r(dream.noise)))
    .put("sun", JSONObject()
        .put("exposure", r(sun.exposure)).put("glow", r(sun.glow)).put("lightLeak", r(sun.lightLeak))
        .put("softness", r(sun.softness)).put("warmth", r(sun.warmth)).put("saturation", r(sun.saturation))
        .put("yellowGreen", r(sun.yellowGreen)).put("contrast", r(sun.contrast)).put("grain", r(sun.grain))
        .put("haze", r(sun.haze)))
    .put("dark", JSONObject()
        .put("teal", r(dark.teal)).put("darken", r(dark.darken)).put("liftedBlacks", r(dark.liftedBlacks))
        .put("saturation", r(dark.saturation)).put("fog", r(dark.fog)).put("softness", r(dark.softness))
        .put("vignette", r(dark.vignette)).put("glow", r(dark.glow)).put("streakLength", r(dark.streakLength))
        .put("streakAngle", r(dark.streakAngle)).put("streakStrength", r(dark.streakStrength))
        .put("streakOpacity", r(dark.streakOpacity)).put("noise", r(dark.noise)).put("grain", r(dark.grain)))
    .put("circuit", JSONObject()
        .put("levels", circuit.levels).put("paletteShift", r(circuit.paletteShift)).put("solarize", r(circuit.solarize))
        .put("paletteAmount", r(circuit.paletteAmount)).put("dither", r(circuit.dither))
        .put("ditherSize", r(circuit.ditherSize)).put("ditherType", circuit.ditherType)
        .put("darkThreshold", r(circuit.darkThreshold)).put("contrast", r(circuit.contrast))
        .put("bandInvert", r(circuit.bandInvert)).put("channelSwap", r(circuit.channelSwap))
        .put("blockShift", r(circuit.blockShift)).put("lineStick", r(circuit.lineStick))
        .put("burstRate", r(circuit.burstRate)).put("animSpeed", r(circuit.animSpeed)))
    .putNoise(noise)

fun coresParamsFromJson(o: JSONObject): CoresParams {
    val d = CoresParams()
    val dr = o.optJSONObject("dream") ?: JSONObject()
    val su = o.optJSONObject("sun") ?: JSONObject()
    val da = o.optJSONObject("dark") ?: JSONObject()
    val ci = o.optJSONObject("circuit") ?: JSONObject()
    return CoresParams(
        core = CoreKind.entries.firstOrNull { it.name == o.optString("core") } ?: d.core,
        strength = o.f("strength", d.strength, 0f, 1f),
        dream = d.dream.let { x ->
            DreamParams(
                pastel = dr.f("pastel", x.pastel, 0f, 1f), shadowTone = dr.f("shadowTone", x.shadowTone, 0f, 1f),
                highlightTone = dr.f("highlightTone", x.highlightTone, 0f, 1f), contrast = dr.f("contrast", x.contrast, -1f, 1f),
                haze = dr.f("haze", x.haze, 0f, 1f), softness = dr.f("softness", x.softness, 0f, 1f),
                glow = dr.f("glow", x.glow, 0f, 1f), vignette = dr.f("vignette", x.vignette, 0f, 1f),
                grain = dr.f("grain", x.grain, 0f, 1f), posterize = dr.f("posterize", x.posterize, 0f, 1f),
                noise = dr.f("noise", x.noise, 0f, 1f),
            )
        },
        sun = d.sun.let { x ->
            SunParams(
                exposure = su.f("exposure", x.exposure, -1f, 1f), glow = su.f("glow", x.glow, 0f, 1f),
                lightLeak = su.f("lightLeak", x.lightLeak, 0f, 1f), softness = su.f("softness", x.softness, 0f, 1f),
                warmth = su.f("warmth", x.warmth, 0f, 1f), saturation = su.f("saturation", x.saturation, 0f, 1.5f),
                yellowGreen = su.f("yellowGreen", x.yellowGreen, 0f, 1f), contrast = su.f("contrast", x.contrast, -1f, 1f),
                grain = su.f("grain", x.grain, 0f, 1f), haze = su.f("haze", x.haze, 0f, 1f),
            )
        },
        dark = d.dark.let { x ->
            DarkParams(
                teal = da.f("teal", x.teal, 0f, 1f), darken = da.f("darken", x.darken, 0f, 1f),
                liftedBlacks = da.f("liftedBlacks", x.liftedBlacks, 0f, 1f), saturation = da.f("saturation", x.saturation, 0f, 1.5f),
                fog = da.f("fog", x.fog, 0f, 1f), softness = da.f("softness", x.softness, 0f, 1f),
                vignette = da.f("vignette", x.vignette, 0f, 1f), glow = da.f("glow", x.glow, 0f, 1f),
                streakLength = da.f("streakLength", x.streakLength, 0f, 1f), streakAngle = da.f("streakAngle", x.streakAngle, 0f, 180f),
                streakStrength = da.f("streakStrength", x.streakStrength, 0f, 1f), streakOpacity = da.f("streakOpacity", x.streakOpacity, 0f, 1f),
                noise = da.f("noise", x.noise, 0f, 1f), grain = da.f("grain", x.grain, 0f, 1f),
            )
        },
        circuit = d.circuit.let { x ->
            CircuitParams(
                levels = ci.optInt("levels", x.levels).coerceIn(CircuitParams.MIN_LEVELS, CircuitParams.MAX_LEVELS),
                paletteShift = ci.f("paletteShift", x.paletteShift, 0f, 1f), solarize = ci.f("solarize", x.solarize, 0f, 1f),
                paletteAmount = ci.f("paletteAmount", x.paletteAmount, 0f, 1f), dither = ci.f("dither", x.dither, 0f, 1f),
                ditherSize = ci.f("ditherSize", x.ditherSize, 0f, 1f), ditherType = ci.optInt("ditherType", x.ditherType).coerceIn(0, 1),
                darkThreshold = ci.f("darkThreshold", x.darkThreshold, 0f, 1f), contrast = ci.f("contrast", x.contrast, 0f, 1f),
                bandInvert = ci.f("bandInvert", x.bandInvert, 0f, 1f), channelSwap = ci.f("channelSwap", x.channelSwap, 0f, 1f),
                blockShift = ci.f("blockShift", x.blockShift, 0f, 1f), lineStick = ci.f("lineStick", x.lineStick, 0f, 1f),
                burstRate = ci.f("burstRate", x.burstRate, 0f, 1f), animSpeed = ci.f("animSpeed", x.animSpeed, 0f, 1f),
            )
        },
        noise = o.noise(),
    )
}

private fun JSONObject.putNoise(n: NoiseParams): JSONObject = this
    .put("noiseLuma", r(n.luma))
    .put("noiseChroma", r(n.chroma))
    .put("noiseSize", r(n.size))
    .put("noiseShadows", r(n.shadows))

private fun JSONObject.noise(): NoiseParams {
    val d = NoiseParams()
    return NoiseParams(
        luma = f("noiseLuma", d.luma, 0f, 1f),
        chroma = f("noiseChroma", d.chroma, 0f, 1f),
        size = f("noiseSize", d.size, 0f, 1f),
        shadows = f("noiseShadows", d.shadows, 0f, 1f),
    )
}

/** Округление до 4 знаков: в кодах пресетов 0.6, а не 0.6000000238. */
private fun r(v: Float): Double = Math.round(v * 10000.0) / 10000.0

private fun JSONObject.f(key: String, default: Float, min: Float, max: Float): Float {
    val v = optDouble(key, r(default)).toFloat()
    return if (v.isNaN()) default else v.coerceIn(min, max)
}
