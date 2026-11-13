package com.vhscam.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Жесты превью в одном обработчике, чтобы они не мешали друг другу: короткий тап — фокус и экспозиция,
 * долгое нажатие — оригинал без эффекта (пока палец на экране), два пальца — плавный зум.
 * Касания, которые уже забрал дочерний элемент (ползунок экспозиции), сюда не попадают.
 */
suspend fun PointerInputScope.cameraGestures(
    longPressMs: Long,
    onTap: (Offset) -> Unit,
    onLongPress: (Boolean) -> Unit,
    onZoom: (Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    val slop = viewConfiguration.touchSlop
    var pinched = false
    var longPressed = false
    var moved = false
    while (true) {
        val waiting = !pinched && !longPressed && !moved
        val remaining = longPressMs - (SystemClock.uptimeMillis() - down.uptimeMillis)
        val event = if (waiting) withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() } else awaitPointerEvent()
        if (event == null) {
            // Палец лежит неподвижно дольше порога — долгое нажатие
            longPressed = true
            onLongPress(true)
            continue
        }
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size >= 2) {
            val zoom = event.calculateZoom()
            if (zoom != 1f) onZoom(zoom)
            pinched = true
            event.changes.forEach { it.consume() }
            continue
        }
        if (pressed.isEmpty()) {
            if (longPressed) onLongPress(false)
            else if (!pinched && !moved) onTap(down.position)
            break
        }
        if (!moved && (pressed.first().position - down.position).getDistance() > slop) moved = true
        pressed.forEach { if (it.positionChange() != Offset.Zero && pinched) it.consume() }
    }
}

enum class FocusState { PENDING, OK, FAILED }

/** Кольцо фокуса в точке тапа: появляется с уменьшением, цвет — поймал (акцент) или нет (полупрозрачное белое). */
@Composable
fun FocusRing(center: Offset, state: FocusState, key: Int) {
    val scale = remember(key) { Animatable(1.35f) }
    LaunchedEffect(key) { scale.animateTo(1f, tween(220)) }
    val color = when (state) {
        FocusState.PENDING -> TextPrimary
        FocusState.OK -> Accent
        FocusState.FAILED -> FocusFailed
    }
    val density = LocalDensity.current
    val sizePx = with(density) { 64.dp.toPx() }
    Canvas(
        modifier = Modifier
            .offset { IntOffset((center.x - sizePx / 2).roundToInt(), (center.y - sizePx / 2).roundToInt()) }
            .size(64.dp),
    ) {
        val r = size.minDimension / 2 * 0.9f * scale.value
        drawCircle(color, r, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color.copy(alpha = 0.6f), 3.dp.toPx())
    }
}

/**
 * Вертикальный ползунок экспозиции со значком солнца рядом с кольцом фокуса: тянуть вверх — светлее.
 * Весь диапазон камеры укладывается в высоту дорожки; шаг — целые индексы компенсации камеры.
 */
@Composable
fun ExposureSlider(
    anchor: Offset,
    boxWidth: Float,
    boxHeight: Float,
    index: Int,
    range: IntRange,
    step: Float,
    onChange: (Int) -> Unit,
    onInteract: () -> Unit,
) {
    val density = LocalDensity.current
    val trackDp = 150.dp
    val trackPx = with(density) { trackDp.toPx() }
    val widthPx = with(density) { 44.dp.toPx() }
    val gap = with(density) { 44.dp.toPx() }
    // Справа от кольца, у правого края — слева; по вертикали — центр на точке тапа, внутри превью
    val x = if (anchor.x + gap + widthPx < boxWidth) anchor.x + gap else anchor.x - gap - widthPx
    val totalH = trackPx + with(density) { 44.dp.toPx() }
    val y = (anchor.y - totalH / 2).coerceIn(0f, (boxHeight - totalH).coerceAtLeast(0f))
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = (index - range.first).toFloat() / span
    val latest = androidx.compose.runtime.rememberUpdatedState(index)
    Column(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(44.dp)
            .pointerInput(range) {
                // Забираем касание целиком, чтобы под ним не срабатывал тап-фокус
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onInteract()
                    val startIndex = latest.value
                    val startY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        val delta = (startY - change.position.y) / trackPx * span
                        onChange((startIndex + delta).roundToInt().coerceIn(range.first, range.last))
                        onInteract()
                    }
                }
            }
            .semantics { contentDescription = "Экспозиция" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            String.format(Locale.US, "%+.1f", index * step),
            color = Accent,
            fontSize = 11.sp,
        )
        Canvas(modifier = Modifier.width(44.dp).height(trackDp)) {
            val cx = size.width / 2
            drawLine(Micro.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx())
            val sunY = size.height * (1f - fraction)
            val r = 7.dp.toPx()
            drawCircle(Accent, r, Offset(cx, sunY))
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                val dir = Offset(cos(a), sin(a))
                drawLine(Accent, Offset(cx, sunY) + dir * (r + 2.dp.toPx()), Offset(cx, sunY) + dir * (r + 6.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}
