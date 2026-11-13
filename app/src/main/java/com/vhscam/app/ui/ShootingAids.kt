package com.vhscam.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.GridMode
import kotlin.math.abs
import kotlin.math.atan2

/** Кадр формата [aspect] (ширина/высота), вписанный целиком в область [boxW]x[boxH] по центру (как ContentScale.Fit). */
fun fitRect(boxW: Float, boxH: Float, aspect: Float): Rect {
    if (boxW <= 0f || boxH <= 0f) return Rect.Zero
    val (w, h) = if (boxW / boxH > aspect) boxH * aspect to boxH else boxW to boxW / aspect
    return Rect((boxW - w) / 2f, (boxH - h) / 2f, (boxW + w) / 2f, (boxH + h) / 2f)
}

/**
 * Наклон телефона вокруг оси объектива в градусах (0 — ровно вертикально, знак — как у поворота линии горизонта
 * на экране) по датчику гравитации; нет датчика — акселерометр; нет и его — null (уровень не показывается).
 */
@Composable
fun rememberTilt(enabled: Boolean): Float? {
    val context = LocalContext.current
    var tilt by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        // Слушаем оба датчика: гравитация точнее, но на части устройств она заявлена и молчит — тогда акселерометр
        val sensors = listOfNotNull(manager?.getDefaultSensor(Sensor.TYPE_GRAVITY), manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
        if (!enabled || manager == null || sensors.isEmpty()) {
            tilt = null
            return@DisposableEffect onDispose { }
        }
        var fx = 0f
        var fy = 0f
        var primed = false
        var gravityAlive = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_GRAVITY) gravityAlive = true else if (gravityAlive) return
                val x = event.values[0]
                val y = event.values[1]
                if (!primed) { fx = x; fy = y; primed = true } else { fx += (x - fx) * 0.2f; fy += (y - fy) * 0.2f }
                // Телефон лежит плашмя — наклон вокруг оси объектива не определён
                tilt = if (fx * fx + fy * fy < 4f) null else Math.toDegrees(atan2(fx.toDouble(), fy.toDouble())).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.forEach { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { manager.unregisterListener(listener) }
    }
    return tilt
}

/**
 * Сетка и уровень поверх кадра превью: тонкие белые линии с прозрачностью (3×3, по желанию с диагоналями) и линия
 * горизонта по центру — наклоняется по датчику и становится акцентной, когда телефон ровный (±1°), в том числе боком.
 */
@Composable
fun FrameGuides(aspect: Float, grid: GridMode, tilt: Float?, modifier: Modifier = Modifier) {
    if (grid == GridMode.OFF && tilt == null) return
    Canvas(modifier.fillMaxSize()) {
        val frame = fitRect(size.width, size.height, aspect)
        if (frame.isEmpty) return@Canvas
        val line = Color.White.copy(alpha = 0.45f)
        val stroke = 1.dp.toPx()
        if (grid != GridMode.OFF) {
            for (i in 1..2) {
                val x = frame.left + frame.width * i / 3f
                val y = frame.top + frame.height * i / 3f
                drawLine(line, Offset(x, frame.top), Offset(x, frame.bottom), stroke)
                drawLine(line, Offset(frame.left, y), Offset(frame.right, y), stroke)
            }
            if (grid == GridMode.DIAGONALS) {
                drawLine(line, frame.topLeft, frame.bottomRight, stroke)
                drawLine(line, frame.topRight, frame.bottomLeft, stroke)
            }
        }
        if (tilt != null) {
            // Отклонение от ближайшего ровного положения (вертикально или боком)
            val off = ((tilt + 45f) % 90f + 90f) % 90f - 45f
            val level = abs(off) <= LEVEL_TOLERANCE_DEG
            val half = minOf(frame.width, frame.height) * 0.28f
            val center = frame.center
            val gap = half * 0.18f
            rotate(tilt - off, center) {
                // Неподвижные метки «как должно быть»
                drawLine(line, Offset(center.x - half - gap * 2, center.y), Offset(center.x - half - gap, center.y), stroke * 1.5f)
                drawLine(line, Offset(center.x + half + gap, center.y), Offset(center.x + half + gap * 2, center.y), stroke * 1.5f)
            }
            rotate(tilt, center) {
                drawLine(
                    if (level) Accent else Color.White.copy(alpha = 0.85f),
                    Offset(center.x - half, center.y), Offset(center.x + half, center.y), stroke * 1.5f,
                )
            }
        }
    }
}

/** Обратный отсчёт таймера крупными цифрами поверх превью. */
@Composable
fun CountdownOverlay(seconds: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            seconds.toString(),
            color = TextPrimary,
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.rotateWithDevice().semantics { contentDescription = "Таймер: $seconds" },
        )
    }
}

/** Маленькая метка включённого таймера («⏱ 3 с») в ряду зума — не поверх превью. */
@Composable
fun TimerBadge(seconds: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(Dimens.PillVisualHeight)
            .rotateWithDevice()
            .clip(RoundedCornerShape(Dimens.PillVisualHeight / 2))
            .background(PanelRaised)
            .padding(horizontal = Dimens.PillTextPadding)
            .semantics { contentDescription = "Таймер $seconds секунд" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏱ $seconds", color = Accent, fontSize = Dimens.PillText, maxLines = 1)
    }
}

private const val LEVEL_TOLERANCE_DEG = 1f

/** Кнопки громкости: экран камеры ставит обработчик (вверх/вниз, номер автоповтора); true — нажатие обработано. */
object HardwareKeys {
    @Volatile var onVolume: ((up: Boolean, repeat: Int) -> Boolean)? = null
}
