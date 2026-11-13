package com.vhscam.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * Зум как в стандартной камере: в свёрнутом виде — таблетка с текущим значением рядом со спуском,
 * в раскрытом — линейка с рисками в логарифмическом масштабе от минимального до максимального зума
 * камеры (всё из zoomState в рантайме). Крупные риски с подписями — «круглые» значения из диапазона
 * и собственный зум физических камер; к последним палец примагничивается с лёгкой вибрацией.
 */

/** Подпись текущего зума: один знак после запятой до 10× (без «.0»), дальше целое. */
fun zoomLabel(r: Float): String = zoomNumber(r) + "×"

private fun zoomNumber(r: Float): String {
    val tenths = (r * 10f).roundToInt()
    return when {
        r >= 10f -> r.roundToInt().toString()
        tenths % 10 == 0 -> (tenths / 10).toString()
        else -> String.format(Locale.US, "%.1f", tenths / 10f)
    }
}

/** Таблетка зума: тап раскрывает шкалу, перетаскивание по ней раскрывает и сразу меняет зум. */
@Composable
fun ZoomPill(
    ratio: Float,
    min: Float,
    max: Float,
    onExpand: () -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // Перетаскивание по таблетке: вся шкала — на ~240dp пути пальца
    val spanPx = with(density) { 240.dp.toPx() }
    val current by rememberUpdatedState(ratio)
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val start = current
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val d = change.position - down.position
                        val along = d.x
                        if (!dragged && abs(along) > viewConfiguration.touchSlop) {
                            dragged = true
                            onExpand()
                        }
                        if (dragged) {
                            change.consume()
                            val l = ln(start) + along / spanPx * (ln(max) - ln(min))
                            onZoom(exp(l).coerceIn(min, max))
                        }
                    }
                    if (!dragged) onExpand()
                }
            }
            .semantics { contentDescription = "Зум ${zoomLabel(ratio)}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            zoomLabel(ratio),
            color = Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .rotateWithDevice()
                .clip(RoundedCornerShape(16.dp))
                .background(PanelRaised)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Раскрытая шкала (слева направо, не поворачивается с телефоном). [marks] — собственный зум физических камер
 * (и 1×): крупные риски с подписью и примагничивание. [onInteract] — любое касание (для сворачивания по таймеру).
 */
@Composable
fun ZoomScale(
    ratio: Float,
    min: Float,
    max: Float,
    marks: List<Float>,
    onZoom: (Float) -> Unit,
    onInteract: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val appPrefs = com.vhscam.app.AppPrefs.get(androidx.compose.ui.platform.LocalContext.current)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = TextSecondary, fontSize = 10.sp)
    val markStyle = TextStyle(color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val pillStyle = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val lnMin = ln(min)
    val lnRange = (ln(max) - lnMin).coerceAtLeast(1e-3f)
    val pad = with(density) { 22.dp.toPx() }
    val snapPx = with(density) { 10.dp.toPx() }
    val shownMarks = remember(marks, min, max) { marks.filter { it in min - 0.01f..max + 0.01f }.distinct().sorted() }
    val labels = remember(min, max, shownMarks) { roundValues(min, max).filter { v -> shownMarks.none { abs(it - v) / v < 0.12f } } }
    val latestMarks by rememberUpdatedState(shownMarks)

    Canvas(
        modifier = modifier
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onInteract(true)
                    var snapped: Float? = null
                    fun apply(pos: Offset) {
                        val length = size.width - 2 * pad
                        if (length <= 0f) return
                        val along = pos.x - pad
                        // Рядом с риской физической камеры — ровно её значение (и короткая вибрация при входе)
                        val mark = latestMarks.firstOrNull { abs(along - (ln(it) - lnMin) / lnRange * length) < snapPx }
                        if (mark != null && mark != snapped && appPrefs.vibration) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        snapped = mark
                        val value = mark ?: exp(lnMin + (along / length).coerceIn(0f, 1f) * lnRange)
                        onZoom(value.coerceIn(min, max))
                    }
                    apply(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        apply(change.position)
                        onInteract(true)
                    }
                    onInteract(false)
                }
            }
            .semantics { contentDescription = "Шкала зума ${zoomLabel(ratio)}" },
    ) {
        val length = size.width - 2 * pad
        if (length <= 0f) return@Canvas
        // Точка на шкале: along — от начала (минимум) к концу (максимум)
        fun at(along: Float, across: Float) = Offset(pad + along, across)
        fun pos(v: Float) = (ln(v) - lnMin) / lnRange * length
        val axis = 26.dp.toPx() // линия рисок
        val minor = 4.dp.toPx()
        val major = 9.dp.toPx()
        val stroke = 1.dp.toPx()

        // Мелкие риски — равномерно по логарифмической шкале
        val step = 6.dp.toPx()
        var a = 0f
        while (a <= length + 0.5f) {
            drawLine(Micro.copy(alpha = 0.7f), at(a, axis - minor), at(a, axis + minor), strokeWidth = stroke)
            a += step
        }
        fun label(v: Float, style: TextStyle) {
            val text = measurer.measure(zoomNumber(v), style)
            val p = at(pos(v), axis)
            val topLeft = Offset(p.x - text.size.width / 2f, p.y + major + 2.dp.toPx())
            drawText(text, topLeft = topLeft)
        }
        // Крупные риски: физические камеры, затем «круглые» значения, если не налезают на соседние подписи
        val gap = 24.dp.toPx()
        val kept = shownMarks.map(::pos).toMutableList()
        labels.filter { v -> kept.none { abs(it - pos(v)) < gap }.also { ok -> if (ok) kept += pos(v) } }.forEach { v ->
            drawLine(TextSecondary, at(pos(v), axis - major), at(pos(v), axis + major), strokeWidth = stroke)
            label(v, labelStyle)
        }
        shownMarks.forEach { v ->
            drawLine(TextPrimary, at(pos(v), axis - major), at(pos(v), axis + major), strokeWidth = 2.dp.toPx())
            label(v, markStyle)
        }

        // Текущее значение: оранжевая риска и таблетка с числом
        val cur = at(pos(ratio.coerceIn(min, max)), axis)
        drawLine(Accent, at(pos(ratio.coerceIn(min, max)), axis - major - 3.dp.toPx()), at(pos(ratio.coerceIn(min, max)), axis + major + 3.dp.toPx()), strokeWidth = 3.dp.toPx())
        val text = measurer.measure(zoomLabel(ratio), pillStyle)
        val pw = text.size.width + 14.dp.toPx()
        val ph = text.size.height + 4.dp.toPx()
        val pillTopLeft = Offset((cur.x - pw / 2f).coerceIn(0f, size.width - pw), (cur.y - major - 6.dp.toPx() - ph).coerceAtLeast(0f))
        drawRoundRect(PanelRaised, pillTopLeft, Size(pw, ph), CornerRadius(ph / 2f))
        drawText(text, topLeft = pillTopLeft + Offset(7.dp.toPx(), 2.dp.toPx()))
    }
}

/** «Круглые» значения для подписей, попадающие в диапазон камеры. */
private fun roundValues(min: Float, max: Float): List<Float> {
    val candidates = mutableListOf<Float>()
    var decade = 0.1f
    while (decade <= max * 1.01f) {
        for (m in floatArrayOf(1f, 2f, 3f, 5f)) candidates += decade * m
        decade *= 10f
    }
    val inRange = candidates.filter { it >= min - 0.01f && it <= max + 0.01f }
    // У минимального зума ниже 1× (широкий угол) — своя подпись: обычно это физическая камера
    return (if (min < 0.95f) listOf(min) + inRange.filter { it >= 1f } else inRange).distinct()
}
