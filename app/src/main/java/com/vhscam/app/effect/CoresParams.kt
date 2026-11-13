package com.vhscam.app.effect

/** Ядра режима «Cores»: у каждого свой вид, свои параметры, категории и пресеты. */
enum class CoreKind(val title: String, val available: Boolean = true) {
    DREAM("Dream"),
    SUN("Sun"),
    DARK("Dark"),
    CIRCUIT("Circuit");

    companion object {
        val shown: List<CoreKind> get() = entries.filter { it.available }
    }
}

/**
 * Параметры режима «Cores»: выбранное ядро, параметры каждого ядра (запоминаются отдельно) и общий шум.
 * Все размеры — в долях полного кадра 3:4, направления — в координатах итогового портретного кадра.
 */
data class CoresParams(
    val enabled: Boolean = true,
    val core: CoreKind = CoreKind.DREAM,
    /** Общая сила режима 0..1: ведёт параметры текущего ядра от нейтральных к текущим. */
    val strength: Float = 1f,
    val dream: DreamParams = DreamParams(),
    val sun: SunParams = SunParams(),
    val dark: DarkParams = DarkParams(),
    val circuit: CircuitParams = CircuitParams(),
    /** Общая категория «Шум». */
    val noise: NoiseParams = NoiseParams(),
) {
    /** Шум-ползунок текущего ядра (у Dream и Dark он в «Текстуре»). */
    val coreNoise: Float
        get() = when (core) {
            CoreKind.DREAM -> dream.noise
            CoreKind.DARK -> dark.noise
            else -> 0f
        }
}

/** Dream (дримкор): молочная пастель, раздельная тонировка, дымка, мелкое зерно, лёгкая постеризация. */
data class DreamParams(
    /** Пастель 0..1: молочный подъём и насыщенный, но мягкий цвет. */
    val pastel: Float = 0.6f,
    /** Тени в сине-фиолетовый 0..1. */
    val shadowTone: Float = 0.55f,
    /** Света в розово-оранжевый 0..1 (в небе и облаках — зелёно-фиолетовая игра). */
    val highlightTone: Float = 0.55f,
    /** −1..1, по умолчанию низкий. */
    val contrast: Float = -0.3f,
    val haze: Float = 0.35f,
    val softness: Float = 0.35f,
    val glow: Float = 0.35f,
    val vignette: Float = 0.2f,
    val grain: Float = 0.25f,
    /** Постеризация цвета 0..1. */
    val posterize: Float = 0.2f,
    val noise: Float = 0f,
)

/** Sun (санкор): тёплый яркий свет с эффектом Orton, золотые света, жёлто-зелёная зелень. */
data class SunParams(
    /** −1..1. */
    val exposure: Float = 0.25f,
    /** Свечение Orton 0..1. */
    val glow: Float = 0.6f,
    /** Засветка по краям 0..1. */
    val lightLeak: Float = 0.35f,
    val softness: Float = 0.3f,
    val warmth: Float = 0.5f,
    /** 0..1.5. */
    val saturation: Float = 1.1f,
    /** Жёлто-зелёный сдвиг зелени 0..1. */
    val yellowGreen: Float = 0.5f,
    /** −1..1. */
    val contrast: Float = -0.25f,
    val grain: Float = 0.2f,
    val haze: Float = 0.25f,
)

/** Dark (даркор): тёмный бирюзово-синий туман, приподнятые чёрные, шум, диагональные штрихи. */
data class DarkParams(
    val teal: Float = 0.6f,
    val darken: Float = 0.4f,
    val liftedBlacks: Float = 0.35f,
    /** 0..1.5. */
    val saturation: Float = 0.55f,
    val fog: Float = 0.45f,
    val softness: Float = 0.3f,
    val vignette: Float = 0.45f,
    val glow: Float = 0.25f,
    /** Длина штрихов 0..1 (до ~15% ширины кадра). */
    val streakLength: Float = 0.4f,
    /** Угол штрихов в градусах 0..180 (0 — горизонтально, 90 — вертикально). */
    val streakAngle: Float = 60f,
    val streakStrength: Float = 0.3f,
    val streakOpacity: Float = 0.35f,
    val noise: Float = 0.3f,
    val grain: Float = 0.3f,
)

/** Circuit (circuit bend): постеризация, психоделическая палитра, соляризация, дизеринг, глитч. */
data class CircuitParams(
    /** Уровни постеризации 2..16. */
    val levels: Int = 5,
    /** Поворот палитры 0..1 (сдвиг цветов по яркости). */
    val paletteShift: Float = 0f,
    val solarize: Float = 0.4f,
    /** Насыщенность палитры 0..1: 0 — постеризованный исходный цвет, 1 — чистая психоделическая палитра. */
    val paletteAmount: Float = 0.85f,
    val dither: Float = 0.6f,
    /** Размер точек 0..1. */
    val ditherSize: Float = 0.3f,
    /** 0 — упорядоченный (Байер), 1 — «диффузия ошибки». */
    val ditherType: Int = 0,
    /** Порог тёмного 0..1: ниже — схлопывается в тёмно-синий. */
    val darkThreshold: Float = 0.22f,
    val contrast: Float = 0.5f,
    val bandInvert: Float = 0.2f,
    val channelSwap: Float = 0.3f,
    val blockShift: Float = 0.3f,
    val lineStick: Float = 0.25f,
    /** Частота всплесков сбоя 0..1. */
    val burstRate: Float = 0.3f,
    /** Скорость «ползания» палитры и дизеринга 0..1. */
    val animSpeed: Float = 0.3f,
) {
    companion object {
        const val MIN_LEVELS = 2
        const val MAX_LEVELS = 16
    }
}
