package com.vhscam.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.effect.ColorSelectParams
import com.vhscam.app.effect.CurvePoint
import com.vhscam.app.effect.curveLut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Редактор кривой: по горизонтали — исходная яркость, по вертикали — итоговая. Точки перетаскиваются; нажатие
 * в свободном месте добавляет точку (до [ColorSelectParams.MAX_CURVE_POINTS]); выбранную можно удалить.
 * Кривая — монотонный сплайн через точки: крайние точки можно опустить до чёрного или поднять до белого.
 * Пока палец на кривой, панель приглушается, как при перетаскивании ползунка.
 */
@Composable
fun CurveEditor(points: List<CurvePoint>, onChange: (List<CurvePoint>) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(points)
    var selected by remember { mutableIntStateOf(-1) }
    val dim = LocalPanelDim.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pad = with(density) { 12.dp.toPx() }
    val grab = with(density) { 26.dp.toPx() }
    Column(modifier = modifier.dimWhileDragging(CURVE_KEY)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .semantics { contentDescription = "Кривая" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val w = size.width - 2 * pad
                        val h = size.height - 2 * pad
                        if (w <= 0f || h <= 0f) return@awaitEachGesture
                        fun toPoint(o: Offset) = CurvePoint(((o.x - pad) / w).coerceIn(0f, 1f), (1f - (o.y - pad) / h).coerceIn(0f, 1f))
                        fun toOffset(p: CurvePoint) = Offset(pad + p.x * w, pad + (1f - p.y) * h)
                        var index = current.indices.minByOrNull { (toOffset(current[it]) - down.position).getDistance() }
                            ?.takeIf { hypot(toOffset(current[it]).x - down.position.x, toOffset(current[it]).y - down.position.y) <= grab } ?: -1
                        if (index < 0 && current.size < ColorSelectParams.MAX_CURVE_POINTS) {
                            val added = (current + toPoint(down.position)).sortedBy { it.x }
                            index = added.indexOfFirst { it == toPoint(down.position) }
                            onChange(added)
                        }
                        selected = index
                        dim?.active = CURVE_KEY
                        while (index >= 0) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero && index in current.indices) {
                                change.consume()
                                val p = toPoint(change.position)
                                // Крайние точки двигаются только по вертикали, остальные — между соседями
                                val x = when (index) {
                                    0 -> 0f
                                    current.lastIndex -> 1f
                                    else -> p.x.coerceIn(current[index - 1].x + 0.02f, current[index + 1].x - 0.02f)
                                }
                                onChange(current.toMutableList().also { it[index] = CurvePoint(x, p.y) })
                            }
                        }
                        scope.launch {
                            delay(300)
                            if (dim?.active == CURVE_KEY) dim.active = null
                        }
                    }
                },
        ) {
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            val grid = Color.White.copy(alpha = 0.16f)
            drawRoundRect(grid, topLeft = Offset(pad, pad), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
            for (i in 1..3) {
                drawLine(grid, Offset(pad + w * i / 4f, pad), Offset(pad + w * i / 4f, pad + h), 1.dp.toPx())
                drawLine(grid, Offset(pad, pad + h * i / 4f), Offset(pad + w, pad + h * i / 4f), 1.dp.toPx())
            }
            val lut = curveLut(points, 96)
            val path = Path()
            lut.forEachIndexed { i, v ->
                val x = pad + w * i / (lut.size - 1f)
                val y = pad + (1f - v) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Accent, style = Stroke(2.dp.toPx()))
            points.forEachIndexed { i, p ->
                val c = Offset(pad + p.x * w, pad + (1f - p.y) * h)
                drawCircle(if (i == selected) Accent else TextPrimary, 7.dp.toPx(), c)
                drawCircle(Color.Black.copy(alpha = 0.6f), 7.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Нажмите на поле — новая точка", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    onChange(points.filterIndexed { i, _ -> i != selected })
                    selected = -1
                },
                enabled = selected in 1 until points.lastIndex,
                colors = appTextButtonColors(),
            ) { Text("Удалить точку", fontSize = 13.sp) }
        }
    }
}

private const val CURVE_KEY = "curve"
