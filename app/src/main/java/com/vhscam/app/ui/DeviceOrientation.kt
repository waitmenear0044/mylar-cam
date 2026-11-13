package com.vhscam.app.ui

import android.view.OrientationEventListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * Экран камеры всегда портретный, как в обычных камерах. При повороте телефона на месте поворачиваются только
 * иконки и подписи элементов управления; угол — из датчика (OrientationEventListener), независимо от системного
 * автоповорота. Он же задаёт ориентацию фото и видео.
 */

/**
 * Поворот телефона по датчику в градусах по часовой: 0, 90 или 270. Положение «вверх ногами» (180°)
 * игнорируется — остаётся прежнее. [override] — отладочная подмена (только debug-сборка).
 */
@Composable
fun rememberDeviceOrientation(override: Int?): Int {
    val context = LocalContext.current
    var degrees by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val nearest = (orientation + 45) / 90 % 4 * 90
                // Небольшой гистерезис: на границе между положениями не дёргаемся туда-сюда
                val distance = abs((orientation - nearest + 540) % 360 - 180)
                if (nearest != 180 && nearest != degrees && distance <= HYSTERESIS_DEG) degrees = nearest
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return override ?: degrees
}

/** Угол поворота иконок (против поворота телефона), с анимацией ~200 мс по кратчайшему пути. [frozen] — не менять. */
@Composable
fun rememberUiRotation(deviceDegrees: Int, frozen: Boolean): Float {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(deviceDegrees, frozen) {
        if (frozen) return@LaunchedEffect
        val want = -deviceDegrees.toFloat()
        val delta = ((want - target) % 360f + 540f) % 360f - 180f
        target += delta
    }
    val animated by animateFloatAsState(target, tween(ROTATE_MS), label = "uiRotation")
    return animated
}

/** Текущий угол поворота иконок; вне экрана камеры — 0. */
val LocalUiRotation = compositionLocalOf { 0f }

/**
 * Повернуть элемент на месте вокруг его центра на угол [LocalUiRotation]. Если повёрнутый элемент выше [maxHeight]
 * (подпись в ряду фиксированной высоты), он уменьшается, чтобы не налезать на соседние ряды.
 */
fun Modifier.rotateWithDevice(maxHeight: Dp = Dp.Unspecified): Modifier = composed {
    val angle = LocalUiRotation.current
    if (angle % 360f == 0f) {
        this
    } else {
        graphicsLayer {
            rotationZ = angle
            if (maxHeight != Dp.Unspecified && size.width > 0f && size.height > 0f) {
                val r = Math.toRadians(angle.toDouble())
                val rotatedHeight = size.width * abs(sin(r)).toFloat() + size.height * abs(cos(r)).toFloat()
                val k = minOf(1f, maxHeight.toPx() / rotatedHeight)
                scaleX = k
                scaleY = k
            }
        }
    }
}

private const val HYSTERESIS_DEG = 35
private const val ROTATE_MS = 200
