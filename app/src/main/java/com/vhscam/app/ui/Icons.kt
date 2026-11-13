package com.vhscam.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vhscam.app.effect.CategoryIcon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Простые иконки категорий, нарисованные линиями одним цветом [color] (без библиотеки иконок). */
@Composable
fun CategoryGlyph(icon: CategoryIcon, color: Color, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.09f)
        when (icon) {
            CategoryIcon.GENERAL -> {
                // три ползунка
                for (i in 0..2) {
                    val y = s * (0.22f + i * 0.28f)
                    drawLine(color, Offset(s * 0.08f, y), Offset(s * 0.92f, y), strokeWidth = stroke.width)
                    val x = s * floatArrayOf(0.3f, 0.68f, 0.45f)[i]
                    drawCircle(color, radius = s * 0.11f, center = Offset(x, y))
                }
            }
            CategoryIcon.PIXELS -> {
                val cell = s / 4f
                for (y in 0..3) for (x in 0..3) {
                    if ((x + y) % 2 == 0) drawRect(color, Offset(x * cell, y * cell), Size(cell * 0.9f, cell * 0.9f))
                }
            }
            CategoryIcon.SPLIT -> {
                // три смещённых круга (каналы R, G, B)
                val r = s * 0.25f
                drawCircle(color, r, Offset(s * 0.36f, s * 0.38f), style = Stroke(s * 0.07f))
                drawCircle(color, r, Offset(s * 0.64f, s * 0.38f), style = Stroke(s * 0.07f))
                drawCircle(color, r, Offset(s * 0.5f, s * 0.64f), style = Stroke(s * 0.07f))
            }
            CategoryIcon.COLOR -> {
                // палитра: круг из трёх секторов разной плотности
                drawArc(color, -90f, 120f, true, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), alpha = 0.9f)
                drawArc(color, 30f, 120f, true, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), alpha = 0.55f)
                drawArc(color, 150f, 120f, true, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), alpha = 0.25f)
                drawCircle(color, s * 0.4f, Offset(s / 2, s / 2), style = stroke)
            }
            CategoryIcon.TONE -> {
                // контраст: круг, левая половина залита
                drawArc(color, 90f, 180f, true, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f))
                drawCircle(color, s * 0.4f, Offset(s / 2, s / 2), style = stroke)
            }
            CategoryIcon.TINT -> {
                // два полукруга: тени (слабее) и света (плотнее)
                drawArc(color, 90f, 180f, true, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), alpha = 0.3f)
                drawArc(color, -90f, 180f, true, Offset(s * 0.1f, s * 0.1f), Size(s * 0.8f, s * 0.8f), alpha = 0.85f)
                drawLine(color, Offset(s / 2, s * 0.08f), Offset(s / 2, s * 0.92f), strokeWidth = stroke.width)
            }
            CategoryIcon.NOISE -> {
                // жёсткие квадратные точки разной яркости
                val cell = s / 5f
                for (y in 0..4) for (x in 0..4) {
                    val v = ((x * 7 + y * 13) % 5) / 4f
                    if (v > 0.3f) drawRect(color.copy(alpha = v), Offset(x * cell, y * cell), Size(cell * 0.85f, cell * 0.85f))
                }
            }
            CategoryIcon.LIGHT -> {
                drawCircle(color, s * 0.18f, Offset(s / 2, s / 2))
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4f
                    val c = Offset(s / 2, s / 2)
                    drawLine(
                        color,
                        c + Offset(cos(a), sin(a)) * (s * 0.3f),
                        c + Offset(cos(a), sin(a)) * (s * 0.46f),
                        strokeWidth = stroke.width,
                    )
                }
            }
            CategoryIcon.GRAIN -> {
                val dots = listOf(
                    0.2f to 0.25f, 0.55f to 0.15f, 0.8f to 0.35f, 0.35f to 0.5f, 0.65f to 0.6f,
                    0.15f to 0.75f, 0.45f to 0.85f, 0.85f to 0.82f, 0.5f to 0.35f,
                )
                dots.forEachIndexed { i, (x, y) ->
                    drawCircle(color, s * (0.05f + (i % 3) * 0.025f), Offset(x * s, y * s))
                }
            }
            CategoryIcon.SIGNAL -> {
                // синусоида сигнала
                val path = Path()
                for (i in 0..24) {
                    val x = s * (0.06f + 0.88f * i / 24f)
                    val y = s * (0.5f - 0.3f * sin(i / 24f * 4f * PI.toFloat()))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = stroke)
            }
            CategoryIcon.EDGES -> {
                // ступенька с перерегулированием (звон)
                val path = Path().apply {
                    moveTo(s * 0.06f, s * 0.78f)
                    lineTo(s * 0.4f, s * 0.78f)
                    lineTo(s * 0.46f, s * 0.86f)
                    lineTo(s * 0.52f, s * 0.12f)
                    lineTo(s * 0.6f, s * 0.3f)
                    lineTo(s * 0.68f, s * 0.2f)
                    lineTo(s * 0.94f, s * 0.22f)
                }
                drawPath(path, color, style = stroke)
            }
            CategoryIcon.TAPE -> {
                // кассета: корпус и две катушки
                drawRect(color, Offset(s * 0.06f, s * 0.2f), Size(s * 0.88f, s * 0.6f), style = Stroke(s * 0.08f))
                drawCircle(color, s * 0.11f, Offset(s * 0.32f, s * 0.46f), style = Stroke(s * 0.08f))
                drawCircle(color, s * 0.11f, Offset(s * 0.68f, s * 0.46f), style = Stroke(s * 0.08f))
                drawLine(color, Offset(s * 0.3f, s * 0.7f), Offset(s * 0.7f, s * 0.7f), strokeWidth = s * 0.07f)
            }
            CategoryIcon.DEFECTS -> {
                // строки с короткими светлыми чёрточками
                for (i in 0..3) {
                    val y = s * (0.18f + i * 0.21f)
                    drawLine(color.copy(alpha = 0.45f), Offset(s * 0.06f, y), Offset(s * 0.94f, y), strokeWidth = s * 0.06f)
                }
                drawLine(color, Offset(s * 0.2f, s * 0.39f), Offset(s * 0.55f, s * 0.39f), strokeWidth = s * 0.12f)
                drawLine(color, Offset(s * 0.5f, s * 0.81f), Offset(s * 0.8f, s * 0.81f), strokeWidth = s * 0.12f)
            }
            CategoryIcon.OVERLAY -> {
                // рамка видоискателя и точка REC
                drawRect(color, Offset(s * 0.06f, s * 0.14f), Size(s * 0.88f, s * 0.72f), style = Stroke(s * 0.07f))
                drawCircle(color, s * 0.1f, Offset(s * 0.26f, s * 0.34f))
                drawLine(color, Offset(s * 0.5f, s * 0.68f), Offset(s * 0.82f, s * 0.68f), strokeWidth = s * 0.08f)
            }
            CategoryIcon.FOG -> {
                // волнистые полосы тумана
                for (i in 0..2) {
                    val y0 = s * (0.3f + i * 0.2f)
                    val path = Path()
                    for (k in 0..16) {
                        val x = s * (0.06f + 0.88f * k / 16f)
                        val y = y0 + s * 0.05f * sin(k / 16f * 2f * PI.toFloat() + i)
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color.copy(alpha = 1f - i * 0.25f), style = Stroke(s * 0.08f))
                }
            }
            CategoryIcon.STREAKS -> {
                // косые штрихи
                for (i in 0..3) {
                    val x = s * (0.12f + i * 0.22f)
                    drawLine(color, Offset(x, s * 0.85f), Offset(x + s * 0.2f, s * 0.15f), strokeWidth = s * 0.08f, alpha = 1f - i * 0.15f)
                }
            }
            CategoryIcon.PALETTE -> {
                // четыре квадрата разной плотности
                floatArrayOf(1f, 0.6f, 0.35f, 0.8f).forEachIndexed { i, a ->
                    drawRect(color, Offset(s * (0.08f + (i % 2) * 0.44f), s * (0.08f + (i / 2) * 0.44f)), Size(s * 0.4f, s * 0.4f), alpha = a)
                }
            }
            CategoryIcon.DITHER -> {
                // точки дизеринга, редеющие слева направо
                val cell = s / 6f
                for (y in 0..5) for (x in 0..5) {
                    if ((x + y) % 2 == 0 && x < 6 - (y % 2) * (x / 3)) drawCircle(color, cell * (0.42f - x * 0.05f), Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
                }
            }
            CategoryIcon.THRESHOLD -> {
                // ступенька порога
                val path = Path().apply {
                    moveTo(s * 0.06f, s * 0.8f); lineTo(s * 0.45f, s * 0.8f); lineTo(s * 0.45f, s * 0.2f); lineTo(s * 0.94f, s * 0.2f)
                }
                drawPath(path, color, style = Stroke(s * 0.09f))
            }
            CategoryIcon.GLITCH -> {
                // сдвинутые блоки
                drawRect(color, Offset(s * 0.06f, s * 0.12f), Size(s * 0.6f, s * 0.18f))
                drawRect(color, Offset(s * 0.3f, s * 0.38f), Size(s * 0.64f, s * 0.18f), alpha = 0.7f)
                drawRect(color, Offset(s * 0.12f, s * 0.64f), Size(s * 0.5f, s * 0.18f))
            }
            CategoryIcon.MOTION -> {
                drawCircle(color, s * 0.2f, Offset(s * 0.7f, s / 2))
                for (i in 0..2) {
                    val y = s * (0.32f + i * 0.18f)
                    drawLine(color, Offset(s * 0.06f, y), Offset(s * 0.42f, y), strokeWidth = stroke.width, alpha = 1f - i * 0.25f)
                }
            }
        }
    }
}

enum class FlashIcon { OFF, AUTO, ON, TORCH_OFF, TORCH_ON }

/** Молния вспышки: выкл (перечёркнута), авто (с «A»), вкл; для видео — фонарик. */
@Composable
fun FlashGlyph(state: FlashIcon, color: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val bolt = Path().apply {
            moveTo(s * 0.58f, s * 0.06f)
            lineTo(s * 0.22f, s * 0.56f)
            lineTo(s * 0.48f, s * 0.56f)
            lineTo(s * 0.40f, s * 0.94f)
            lineTo(s * 0.78f, s * 0.42f)
            lineTo(s * 0.52f, s * 0.42f)
            close()
        }
        val filled = state == FlashIcon.ON || state == FlashIcon.TORCH_ON
        if (state == FlashIcon.TORCH_OFF || state == FlashIcon.TORCH_ON) {
            // фонарик: корпус и световой конус
            drawRect(color, Offset(s * 0.36f, s * 0.46f), Size(s * 0.28f, s * 0.48f), style = Stroke(s * 0.08f))
            val head = Path().apply {
                moveTo(s * 0.24f, s * 0.2f); lineTo(s * 0.76f, s * 0.2f); lineTo(s * 0.64f, s * 0.46f); lineTo(s * 0.36f, s * 0.46f); close()
            }
            if (filled) drawPath(head, color) else drawPath(head, color, style = Stroke(s * 0.08f))
        } else if (filled) {
            drawPath(bolt, color)
        } else {
            drawPath(bolt, color, style = Stroke(s * 0.07f))
        }
        if (state == FlashIcon.OFF || state == FlashIcon.TORCH_OFF) {
            drawLine(color, Offset(s * 0.12f, s * 0.12f), Offset(s * 0.88f, s * 0.88f), strokeWidth = s * 0.08f)
        }
        if (state == FlashIcon.AUTO) {
            // маленькая «A» справа снизу
            val a = Path().apply {
                moveTo(s * 0.70f, s * 0.98f); lineTo(s * 0.84f, s * 0.62f); lineTo(s * 0.98f, s * 0.98f)
                moveTo(s * 0.75f, s * 0.86f); lineTo(s * 0.93f, s * 0.86f)
            }
            drawPath(a, color, style = Stroke(s * 0.07f))
        }
    }
}

/** Переворот камеры: две круговые стрелки. */
@Composable
fun FlipGlyph(color: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.08f)
        val box = Size(s * 0.7f, s * 0.7f)
        val topLeft = Offset(s * 0.15f, s * 0.15f)
        drawArc(color, 200f, 140f, false, topLeft, box, style = stroke)
        drawArc(color, 20f, 140f, false, topLeft, box, style = stroke)
        // наконечники стрелок
        fun arrow(angleDeg: Float, dir: Float) {
            val a = angleDeg * PI.toFloat() / 180f
            val c = Offset(s / 2, s / 2) + Offset(cos(a), sin(a)) * (s * 0.35f)
            val t = Offset(-sin(a), cos(a)) * dir
            val n = Offset(cos(a), sin(a))
            val p = Path().apply {
                moveTo(c.x + t.x * s * 0.14f, c.y + t.y * s * 0.14f)
                lineTo(c.x + n.x * s * 0.1f, c.y + n.y * s * 0.1f)
                lineTo(c.x - n.x * s * 0.1f, c.y - n.y * s * 0.1f)
                close()
            }
            drawPath(p, color)
        }
        arrow(340f, 1f)
        arrow(160f, 1f)
    }
}

/** Звёздочка «готовый пресет». */
@Composable
fun StarGlyph(color: Color, size: Dp = 16.dp) {
    Canvas(modifier = Modifier.size(size)) { drawStar(color) }
}

private fun DrawScope.drawStar(color: Color) {
    val s = size.minDimension
    val c = Offset(s / 2, s / 2)
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) s * 0.48f else s * 0.2f
        val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
        val p = c + Offset(cos(a), sin(a)) * r
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
}

/** Галерея: рамка с горой и солнцем. */
@Composable
fun GalleryGlyph(color: Color, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        drawRect(color, Offset(s * 0.08f, s * 0.16f), Size(s * 0.84f, s * 0.68f), style = Stroke(s * 0.08f))
        val mountain = Path().apply {
            moveTo(s * 0.14f, s * 0.78f); lineTo(s * 0.4f, s * 0.46f); lineTo(s * 0.58f, s * 0.66f)
            lineTo(s * 0.7f, s * 0.54f); lineTo(s * 0.86f, s * 0.78f); close()
        }
        drawPath(mountain, color)
        drawCircle(color, s * 0.07f, Offset(s * 0.68f, s * 0.34f))
    }
}

/** Телефон вертикально или горизонтально — в какой ориентации сохранится снимок. */
@Composable
fun OrientationGlyph(landscape: Boolean, color: Color, size: Dp = 16.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val (w, h) = if (landscape) s * 0.9f to s * 0.56f else s * 0.56f to s * 0.9f
        val topLeft = Offset((s - w) / 2f, (s - h) / 2f)
        drawRoundRect(color, topLeft, Size(w, h), androidx.compose.ui.geometry.CornerRadius(s * 0.1f), style = Stroke(s * 0.09f))
        // «кнопка» у нижнего края телефона: видно, где у него низ
        val dot = if (landscape) Offset(topLeft.x + w - s * 0.12f, s / 2f) else Offset(s / 2f, topLeft.y + h - s * 0.12f)
        drawCircle(color, s * 0.05f, dot)
    }
}

/** Глаз — видимость слоя: открытый или перечёркнутый. */
@Composable
fun EyeGlyph(open: Boolean, color: Color, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(s * 0.08f)
        val lid = Path().apply {
            moveTo(s * 0.08f, s * 0.5f)
            quadraticTo(s * 0.5f, s * 0.08f, s * 0.92f, s * 0.5f)
            quadraticTo(s * 0.5f, s * 0.92f, s * 0.08f, s * 0.5f)
            close()
        }
        drawPath(lid, color, style = stroke)
        drawCircle(color, s * 0.13f, Offset(s * 0.5f, s * 0.5f))
        if (!open) drawLine(color, Offset(s * 0.18f, s * 0.82f), Offset(s * 0.82f, s * 0.18f), s * 0.09f)
    }
}
