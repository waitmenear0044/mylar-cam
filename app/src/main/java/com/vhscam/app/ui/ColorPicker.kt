package com.vhscam.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Цвет #RRGGBB из строки (с «#» или без); null — строка не цвет. */
fun parseHexColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
    return 0xFF000000.toInt() or hex.toInt(16)
}

fun hexOf(color: Int): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

/**
 * Выбор цвета: образец, ползунки оттенка, насыщенности и яркости (HSV) с цветными дорожками и поле HEX.
 * Без прозрачности. [onChange] вызывается при каждом изменении.
 */
@Composable
fun ColorPickerFields(color: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var sat by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    var hex by remember { mutableStateOf(hexOf(color)) }
    var shown by remember { mutableStateOf(color) }
    // Цвет поменяли снаружи (выбрали другую точку, готовый цвет) — подхватываем
    if (color != shown) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        hex = hexOf(color)
        shown = color
    }
    fun emit() {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        shown = c
        hex = hexOf(c)
        onChange(c)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.HalfGrid)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Grid * 2)) {
            Box(
                Modifier
                    .size(Dimens.IconButton)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(shown))
                    .border(1.dp, TextSecondary, RoundedCornerShape(12.dp)),
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { text ->
                    hex = text.take(7)
                    parseHexColor(text)?.let { c ->
                        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                        shown = c
                        onChange(c)
                    }
                },
                singleLine = true,
                label = { Text("HEX") },
                isError = parseHexColor(hex) == null,
                modifier = Modifier.width(160.dp),
            )
        }
        val hueColors = remember { (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) } }
        ColorSlider("Оттенок", hue, 0f..360f, hueColors) { hue = it; emit() }
        ColorSlider(
            "Насыщенность", sat, 0f..1f,
            listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, value))), Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, value)))),
        ) { sat = it; emit() }
        ColorSlider(
            "Яркость", value, 0f..1f,
            listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))),
        ) { value = it; emit() }
    }
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, track: List<Color>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp)) {
                drawRoundRect(Brush.horizontalGradient(track), cornerRadius = CornerRadius(size.height / 2))
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = TextPrimary, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().height(Dimens.SliderTouch - 8.dp),
            )
        }
    }
}

/** Диалог выбора цвета (поворачивается вместе с телефоном). */
@Composable
fun ColorPickerDialog(title: String, initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var color by remember { mutableStateOf(initial) }
    TurnedDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, colors = appTextButtonColors()) { Text("Отмена") }
            TextButton(onClick = { onPick(color) }, colors = appTextButtonColors()) { Text("Готово") }
        },
    ) {
        ColorPickerFields(color, onChange = { color = it })
    }
}
