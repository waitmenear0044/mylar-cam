package com.vhscam.app.effect

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Запоминает между запусками: режим, параметры всех режимов, силу пресетов, активные пресеты,
 * «Фото/Видео», формат кадра и вспышку. Параметры хранятся в том же JSON, что и пресеты;
 * настройки версий до 4.2 (отдельные ключи pixel.* / film.*) читаются как раньше.
 */
class SettingsStore(context: Context) {
    private val gradients = GradientStore.get(context)

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("effect_settings", Context.MODE_PRIVATE)

    fun load(): EffectSettings {
        val d = EffectSettings()
        val mode = prefs.getString("mode", null)
            ?.let { name -> EffectMode.camera.firstOrNull { it.name == name } } ?: d.mode
        val pixel = prefs.getString("pixel.json", null)
            ?.let { runCatching { pixelParamsFromJson(JSONObject(it)) }.getOrNull() }
            ?: legacyPixel()
        val film = prefs.getString("film.json", null)
            ?.let { runCatching { filmParamsFromJson(JSONObject(it)) }.getOrNull() }
            ?: legacyFilm()
        val vhs = prefs.getString("vhs.json", null)
            ?.let { runCatching { vhsParamsFromJson(JSONObject(it)) }.getOrNull() }
            ?: VhsParams()
        val cores = prefs.getString("cores.json", null)
            ?.let { runCatching { coresParamsFromJson(JSONObject(it)) }.getOrNull() }
            ?: CoresParams()
        // Точки градиента берутся из набора: если выбранный градиент поправили в редакторе, режим это видит
        val gradient = (prefs.getString("gradient.json", null)
            ?.let { runCatching { gradientParamsFromJson(JSONObject(it)) }.getOrNull() } ?: GradientParams())
            .let { g -> gradients.find(g.gradientId)?.let { g.with(it) } ?: g }
        val ghost = prefs.getString("ghost.json", null)
            ?.let { runCatching { ghostParamsFromJson(JSONObject(it)) }.getOrNull() } ?: GhostParams()
        return EffectSettings(
            mode = mode,
            pixel = pixel.copy(enabled = prefs.getBoolean("pixel.enabled", true)),
            film = film.copy(enabled = prefs.getBoolean("film.enabled", true)),
            vhs = vhs.copy(enabled = prefs.getBoolean("vhs.enabled", true)),
            cores = cores.copy(enabled = prefs.getBoolean("cores.enabled", true)),
            gradient = gradient.copy(enabled = prefs.getBoolean("gradient.enabled", true)),
            ghost = ghost.copy(enabled = prefs.getBoolean("ghost.enabled", true)),
            gradientPresetStrength = prefs.getFloat("gradient.presetStrength", 1f).coerceIn(0f, 1.5f),
            ghostPresetStrength = prefs.getFloat("ghost.presetStrength", 1f).coerceIn(0f, 1.5f),
            pixelPresetStrength = prefs.getFloat("pixel.presetStrength", 1f).coerceIn(0f, 1.5f),
            filmPresetStrength = prefs.getFloat("film.presetStrength", 1f).coerceIn(0f, 1.5f),
            vhsPresetStrength = prefs.getFloat("vhs.presetStrength", 1f).coerceIn(0f, 1.5f),
            coresPresetStrength = CoreKind.entries.associateWith {
                prefs.getFloat("cores.presetStrength.${it.name}", 1f).coerceIn(0f, 1.5f)
            },
        )
    }

    fun save(s: EffectSettings) {
        prefs.edit()
            .putString("mode", s.mode.name)
            .putString("pixel.json", s.pixel.toJson().toString())
            .putString("film.json", s.film.toJson().toString())
            .putString("vhs.json", s.vhs.toJson().toString())
            .putString("cores.json", s.cores.toJson().toString())
            .putBoolean("pixel.enabled", s.pixel.enabled)
            .putBoolean("film.enabled", s.film.enabled)
            .putBoolean("vhs.enabled", s.vhs.enabled)
            .putBoolean("cores.enabled", s.cores.enabled)
            .putString("gradient.json", s.gradient.toJson().toString())
            .putString("ghost.json", s.ghost.toJson().toString())
            .putBoolean("gradient.enabled", s.gradient.enabled)
            .putBoolean("ghost.enabled", s.ghost.enabled)
            .putFloat("gradient.presetStrength", s.gradientPresetStrength)
            .putFloat("ghost.presetStrength", s.ghostPresetStrength)
            .putFloat("pixel.presetStrength", s.pixelPresetStrength)
            .putFloat("film.presetStrength", s.filmPresetStrength)
            .putFloat("vhs.presetStrength", s.vhsPresetStrength)
            .apply {
                CoreKind.entries.forEach { putFloat("cores.presetStrength.${it.name}", s.coresPresetStrength[it] ?: 1f) }
            }
            .apply()
    }

    private fun legacyPixel(): PixelParams {
        val p = PixelParams()
        return PixelParams(
            resolutionWidth = prefs.getInt("pixel.resolutionWidth", p.resolutionWidth),
            saturation = prefs.getFloat("pixel.saturation", p.saturation),
            blackLevel = prefs.getFloat("pixel.blackLevel", p.blackLevel),
            tint = prefs.getFloat("pixel.tint", p.tint),
        )
    }

    private fun legacyFilm(): FilmParams {
        val f = FilmParams()
        return FilmParams(
            strength = prefs.getFloat("film.strength", f.strength),
            splitStrength = prefs.getFloat("film.splitStrength", f.splitStrength),
            radiality = prefs.getFloat("film.radiality", f.radiality),
            splitSamples = prefs.getInt("film.splitSamples", f.splitSamples),
            filmColor = prefs.getFloat("film.filmColor", f.filmColor),
            contrast = prefs.getFloat("film.contrast", f.contrast),
            fade = prefs.getFloat("film.fade", f.fade),
            saturation = prefs.getFloat("film.saturation", f.saturation),
            temperature = prefs.getFloat("film.temperature", f.temperature),
            halation = prefs.getFloat("film.halation", f.halation),
            grain = prefs.getFloat("film.grain", f.grain),
            grainSize = prefs.getFloat("film.grainSize", f.grainSize),
            motionBlur = prefs.getFloat("film.motionBlur", f.motionBlur),
            softness = prefs.getFloat("film.softness", f.softness),
        )
    }

    /** Активный пресет группы: ключ — имя режима (у «Cores» — режим и ядро, см. PresetManager). */
    fun activePresetId(key: String): String? = prefs.getString("preset.$key", null)

    fun setActivePresetId(key: String, id: String?) {
        prefs.edit().apply { if (id == null) remove("preset.$key") else putString("preset.$key", id) }.apply()
    }

    var photoMode: Boolean
        get() = prefs.getBoolean("photoMode", false)
        set(value) = prefs.edit().putBoolean("photoMode", value).apply()

    var frameFormat: FrameFormat
        get() = prefs.getString("frameFormat", null)
            ?.let { name -> FrameFormat.entries.firstOrNull { it.name == name } } ?: FrameFormat.R3_4
        set(value) = prefs.edit().putString("frameFormat", value.name).apply()

    var flash: FlashSetting
        get() = prefs.getString("flash", null)
            ?.let { name -> FlashSetting.entries.firstOrNull { it.name == name } } ?: FlashSetting.OFF
        set(value) = prefs.edit().putString("flash", value.name).apply()

    var photoQuality: PhotoQuality
        get() = PhotoQuality.decode(prefs.getString("photoQuality", null))
        set(value) = prefs.edit().putString("photoQuality", value.encode()).apply()

    var videoResolution: VideoResolution
        get() = prefs.getString("videoResolution", null)
            ?.let { name -> VideoResolution.entries.firstOrNull { it.name == name } } ?: VideoResolution.HD
        set(value) = prefs.edit().putString("videoResolution", value.name).apply()

    var videoFps: VideoFps
        get() = prefs.getString("videoFps", null)
            ?.let { name -> VideoFps.entries.firstOrNull { it.name == name } } ?: VideoFps.FPS30
        set(value) = prefs.edit().putString("videoFps", value.name).apply()

    var torch: Boolean
        get() = prefs.getBoolean("torch", false)
        set(value) = prefs.edit().putBoolean("torch", value).apply()
}
