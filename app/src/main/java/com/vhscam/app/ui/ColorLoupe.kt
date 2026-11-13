package com.vhscam.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Средний цвет квадрата [size]x[size] вокруг ([x], [y]) — чтобы пипетка не ловила шум отдельных пикселей. */
fun averageColor(bitmap: Bitmap, x: Int, y: Int, size: Int = 5): Int {
    val half = size / 2
    val x0 = (x - half).coerceIn(0, bitmap.width - 1)
    val y0 = (y - half).coerceIn(0, bitmap.height - 1)
    val w = minOf(size, bitmap.width - x0)
    val h = minOf(size, bitmap.height - y0)
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, x0, y0, w, h)
    var r = 0; var g = 0; var b = 0
    for (c in px) { r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
    val n = px.size.coerceAtLeast(1)
    return android.graphics.Color.rgb(r / n, g / n, b / n)
}

/**
 * Лупа пипетки поверх превью: круг ~72dp над пальцем [finger] (у верхнего края — сбоку), внутри — увеличенный
 * фрагмент [bitmap] вокруг пикселя ([px], [py]) с перекрестием; рамка — цвет, который будет выбран ([picked]);
 * маленький кружок сравнения «текущий | новый» ([current] | [picked]). Не выходит за границы области.
 */
@Composable
fun ColorLoupe(bitmap: Bitmap, px: Int, py: Int, finger: Offset, picked: Int, current: Int?, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier.fillMaxSize()) {
        val radius = 36.dp.toPx()
        val lift = radius + 44.dp.toPx()
        val ring = 5.dp.toPx()
        var center = Offset(finger.x, finger.y - lift)
        if (center.y - radius < 0f) {
            // У верхнего края — сбоку от пальца, где больше места
            val side = if (finger.x > size.width / 2f) -1f else 1f
            center = Offset(finger.x + side * lift, finger.y)
        }
        center = Offset(
            center.x.coerceIn(radius + ring, size.width - radius - ring),
            center.y.coerceIn(radius + ring, size.height - radius - ring),
        )
        // Увеличенный фрагмент: ~13 пикселей картинки на диаметр лупы
        val span = 13
        val half = span / 2
        val sx = (px - half).coerceIn(0, (bitmap.width - span).coerceAtLeast(0))
        val sy = (py - half).coerceIn(0, (bitmap.height - span).coerceAtLeast(0))
        val circle = Path().apply { addOval(Rect(center, radius)) }
        clipPath(circle) {
            drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
            drawImage(
                image,
                srcOffset = IntOffset(sx, sy),
                srcSize = IntSize(minOf(span, bitmap.width), minOf(span, bitmap.height)),
                dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
                dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt()),
                filterQuality = FilterQuality.None,
            )
            // Перекрестие на выбранном пикселе
            val cell = radius * 2 / span
            val cx = center.x - radius + (px - sx + 0.5f) * cell
            val cy = center.y - radius + (py - sy + 0.5f) * cell
            val arm = cell * 1.6f
            for (c in listOf(Color.Black.copy(alpha = 0.6f) to 3.dp.toPx(), Color.White to 1.2.dp.toPx())) {
                drawLine(c.first, Offset(cx - arm, cy), Offset(cx - cell * 0.6f, cy), c.second)
                drawLine(c.first, Offset(cx + cell * 0.6f, cy), Offset(cx + arm, cy), c.second)
                drawLine(c.first, Offset(cx, cy - arm), Offset(cx, cy - cell * 0.6f), c.second)
                drawLine(c.first, Offset(cx, cy + cell * 0.6f), Offset(cx, cy + arm), c.second)
            }
        }
        drawCircle(Color(picked), radius + ring / 2, center, style = Stroke(ring))
        drawCircle(Color.White.copy(alpha = 0.8f), radius + ring, center, style = Stroke(1.dp.toPx()))
        // Сравнение «текущий | новый»
        val small = 11.dp.toPx()
        val sc = Offset(center.x + radius * 0.78f, center.y + radius * 0.78f)
        val rect = Rect(sc, small)
        if (current != null) drawArc(Color(current), 90f, 180f, true, rect.topLeft, rect.size)
        drawArc(Color(picked), -90f, if (current != null) 180f else 360f, true, rect.topLeft, rect.size)
        drawCircle(Color.White, small, sc, style = Stroke(1.5.dp.toPx()))
    }
}
