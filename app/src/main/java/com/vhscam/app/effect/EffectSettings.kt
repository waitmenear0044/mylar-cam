package com.vhscam.app.effect

/**
 * Режимы эффектов. Новый режим = новый элемент, свой data class и свой рендерер.
 * [editorOnly] — только для редактора (слои фото и видео), в полосе режимов камеры таких нет.
 */
enum class EffectMode(val title: String, val editorOnly: Boolean = false) {
    PIXEL("Пиксель"),
    FILM("Плёнка"),
    VHS("VHS"),
    CORES("Cores"),
    GRADIENT("Градиент"),
    GHOST("Гостинг"),
    NEWSPAPER("Газета", editorOnly = true),
    COLOR("Выбор цвета", editorOnly = true);

    companion object {
        /** Режимы камеры (в порядке полосы режимов). */
        val camera: List<EffectMode> = entries.filter { !it.editorOnly }
    }
}

/**
 * Всё, что нужно рендереру на кадр: выбранный режим, «базовые» параметры каждого режима
 * (значения ползунков) и сила выбранного пресета каждого режима.
 */
data class EffectSettings(
    val mode: EffectMode = EffectMode.PIXEL,
    val pixel: PixelParams = PixelParams(),
    val film: FilmParams = FilmParams(),
    val vhs: VhsParams = VhsParams(),
    val cores: CoresParams = CoresParams(),
    val gradient: GradientParams = GradientParams(),
    val ghost: GhostParams = GhostParams(),
    val newspaper: NewspaperParams = NewspaperParams(),
    val colorSelect: ColorSelectParams = ColorSelectParams(),
    /** Сила пресета 0..1.5 (1 = как в пресете). Действует, пока у режима выбран пресет. */
    val pixelPresetStrength: Float = 1f,
    val filmPresetStrength: Float = 1f,
    val vhsPresetStrength: Float = 1f,
    /** У «Cores» сила пресета своя у каждого ядра. */
    val coresPresetStrength: Map<CoreKind, Float> = emptyMap(),
    val gradientPresetStrength: Float = 1f,
    val ghostPresetStrength: Float = 1f,
    /** Сила пресета редакторских режимов. */
    val editorPresetStrength: Map<EffectMode, Float> = emptyMap(),
) {
    val enabled: Boolean
        get() = when (mode) {
            EffectMode.PIXEL -> pixel.enabled
            EffectMode.FILM -> film.enabled
            EffectMode.VHS -> vhs.enabled
            EffectMode.CORES -> cores.enabled
            EffectMode.GRADIENT -> gradient.enabled
            EffectMode.GHOST -> ghost.enabled
            EffectMode.NEWSPAPER -> newspaper.enabled
            EffectMode.COLOR -> colorSelect.enabled
        }

    fun withEnabled(on: Boolean): EffectSettings = when (mode) {
        EffectMode.PIXEL -> copy(pixel = pixel.copy(enabled = on))
        EffectMode.FILM -> copy(film = film.copy(enabled = on))
        EffectMode.VHS -> copy(vhs = vhs.copy(enabled = on))
        EffectMode.CORES -> copy(cores = cores.copy(enabled = on))
        EffectMode.GRADIENT -> copy(gradient = gradient.copy(enabled = on))
        EffectMode.GHOST -> copy(ghost = ghost.copy(enabled = on))
        EffectMode.NEWSPAPER -> copy(newspaper = newspaper.copy(enabled = on))
        EffectMode.COLOR -> copy(colorSelect = colorSelect.copy(enabled = on))
    }

    fun presetStrength(m: EffectMode = mode): Float = when (m) {
        EffectMode.PIXEL -> pixelPresetStrength
        EffectMode.FILM -> filmPresetStrength
        EffectMode.VHS -> vhsPresetStrength
        EffectMode.CORES -> coresPresetStrength[cores.core] ?: 1f
        EffectMode.GRADIENT -> gradientPresetStrength
        EffectMode.GHOST -> ghostPresetStrength
        EffectMode.NEWSPAPER, EffectMode.COLOR -> editorPresetStrength[m] ?: 1f
    }

    fun withPresetStrength(m: EffectMode, k: Float): EffectSettings = when (m) {
        EffectMode.PIXEL -> copy(pixelPresetStrength = k)
        EffectMode.FILM -> copy(filmPresetStrength = k)
        EffectMode.VHS -> copy(vhsPresetStrength = k)
        EffectMode.CORES -> copy(coresPresetStrength = coresPresetStrength + (cores.core to k))
        EffectMode.GRADIENT -> copy(gradientPresetStrength = k)
        EffectMode.GHOST -> copy(ghostPresetStrength = k)
        EffectMode.NEWSPAPER, EffectMode.COLOR -> copy(editorPresetStrength = editorPresetStrength + (m to k))
    }

    /** Цифровой шум текущего режима (категория «Шум», общая для режимов; у редакторских режимов её нет). */
    val noise: NoiseParams
        get() = when (mode) {
            EffectMode.PIXEL -> pixel.noise
            EffectMode.FILM -> film.noise
            EffectMode.VHS -> vhs.noise
            EffectMode.CORES -> cores.noise
            EffectMode.GRADIENT -> gradient.noise
            EffectMode.GHOST -> ghost.noise
            EffectMode.NEWSPAPER, EffectMode.COLOR -> NoiseParams()
        }

    /**
     * Итоговые параметры для рендера: смесь нейтральных и базовых значений по силе пресета
     * (выше 100% — экстраполяция с обрезкой по диапазону). При силе 1 — ровно базовые значения.
     */
    fun effective(): EffectSettings {
        val coresStrength = coresPresetStrength[cores.core] ?: 1f
        if (pixelPresetStrength == 1f && filmPresetStrength == 1f && vhsPresetStrength == 1f && coresStrength == 1f &&
            gradientPresetStrength == 1f && ghostPresetStrength == 1f && editorPresetStrength.values.all { it == 1f }
        ) return this
        return copy(
            pixel = PixelSpec.blend(pixel, pixelPresetStrength),
            film = FilmSpec.blend(film, filmPresetStrength),
            vhs = VhsSpec.blend(vhs, vhsPresetStrength),
            cores = CoresSpec.blend(cores, coresStrength),
            gradient = GradientSpec.blend(gradient, gradientPresetStrength),
            ghost = GhostSpec.blend(ghost, ghostPresetStrength),
            newspaper = NewspaperSpec.blend(newspaper, editorPresetStrength[EffectMode.NEWSPAPER] ?: 1f),
            colorSelect = ColorSelectSpec.blend(colorSelect, editorPresetStrength[EffectMode.COLOR] ?: 1f),
            pixelPresetStrength = 1f,
            filmPresetStrength = 1f,
            vhsPresetStrength = 1f,
            coresPresetStrength = emptyMap(),
            gradientPresetStrength = 1f,
            ghostPresetStrength = 1f,
            editorPresetStrength = emptyMap(),
        )
    }
}
