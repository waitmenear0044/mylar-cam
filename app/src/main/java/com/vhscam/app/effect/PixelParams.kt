package com.vhscam.app.effect

/**
 * Параметры режима «Пиксель» (вид этапов 2–3). Неизменяемый: GL-поток читает его на каждом кадре,
 * UI просто подменяет ссылку на новую копию.
 */
data class PixelParams(
    val enabled: Boolean = true,
    /** Число крупных пикселей по короткой стороне кадра (по ширине портретного кадра). */
    val resolutionWidth: Int = 320,
    /** 0 = ч/б, 1 = исходные цвета. */
    val saturation: Float = 0.6f,
    /** Поднятый уровень чёрного: 0 = как есть, 0.3 = сильно выцветшие тени. */
    val blackLevel: Float = 0.08f,
    /** Сдвиг оттенка: -1 = зелёный, 0 = нейтрально, +1 = тёплый. */
    val tint: Float = 0.3f,
    /** Цифровой шум (по умолчанию выключен). */
    val noise: NoiseParams = NoiseParams(),
) {
    companion object {
        const val MIN_WIDTH = 120
        const val MAX_WIDTH = 640
        const val MAX_BLACK_LEVEL = 0.3f
    }
}
