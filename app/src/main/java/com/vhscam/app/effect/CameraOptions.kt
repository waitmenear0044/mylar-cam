package com.vhscam.app.effect

import android.util.Rational

/**
 * Формат итогового (портретного) кадра. Эффект всегда считается в полном кадре 3:4,
 * затем кадр обрезается по центру до выбранного формата.
 */
enum class FrameFormat(val label: String, val width: Int, val height: Int) {
    R3_4("3:4", 3, 4),
    R1_1("1:1", 1, 1),
    R4_5("4:5", 4, 5),
    R2_3("2:3", 2, 3),
    R9_16("9:16", 9, 16);

    val isFull: Boolean get() = this == R3_4

    /** Соотношение ширина/высота портретного кадра для ViewPort. */
    val rational: Rational get() = Rational(width, height)

    val aspect: Float get() = width.toFloat() / height

    /** Снимок в альбомной ориентации: формат переворачивается (3:4 -> 4:3, 9:16 -> 16:9). */
    fun aspectFor(landscape: Boolean): Float = if (landscape) height.toFloat() / width else aspect

    fun labelFor(landscape: Boolean): String = if (landscape) "$height:$width" else label
}

/** Режим вспышки для фото. Для видео вспышка работает как фонарик (отдельный флаг). */
enum class FlashSetting(val label: String) {
    OFF("Выкл"),
    AUTO("Авто"),
    ON("Вкл");

    fun next(): FlashSetting = entries[(ordinal + 1) % entries.size]
}

/** Разрешение видео (30 fps). Доступность зависит от камеры и кодека, проверяется в рантайме. */
enum class VideoResolution(val label: String) {
    HD("720p"),
    FHD("1080p"),
    UHD("4K");
}

/**
 * Частота кадров превью и видео. Какие доступны — зависит от камеры, разрешения и кодека,
 * проверяется в рантайме. По умолчанию 30.
 */
enum class VideoFps(val fps: Int) {
    FPS24(24),
    FPS30(30),
    FPS60(60);

    val label: String get() = "$fps fps"
}

/**
 * Качество фото: «Макс» (самый большой размер 4:3, который камера отдаёт приложению) или
 * конкретный размер в пикселях ориентации сенсора.
 */
data class PhotoQuality(val width: Int = 0, val height: Int = 0) {
    val isMax: Boolean get() = width == 0 || height == 0
    val megapixels: Float get() = width * height / 1_000_000f

    fun encode(): String = if (isMax) "MAX" else "${width}x$height"

    companion object {
        val MAX = PhotoQuality()

        fun decode(value: String?): PhotoQuality {
            val parts = value?.split("x")?.mapNotNull { it.toIntOrNull() }
            return if (parts != null && parts.size == 2) PhotoQuality(parts[0], parts[1]) else MAX
        }

        fun label(mp: Float): String = if (mp >= 10f) "${mp.toInt()} Мп" else String.format(java.util.Locale.US, "%.1f Мп", mp)
    }
}
