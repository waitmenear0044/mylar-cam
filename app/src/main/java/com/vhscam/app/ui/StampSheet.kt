package com.vhscam.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.StampFont
import com.vhscam.app.StampFormat
import com.vhscam.app.StampSettings
import com.vhscam.app.gl.stampBitmap
import com.vhscam.app.gl.stampText
import com.vhscam.app.gl.stampTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Ряд чипов «один из нескольких». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.Grid)) {
        options.forEachIndexed { index, text ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(text, fontSize = 13.sp, maxLines = 1) },
                colors = appChipColors(),
            )
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = Dimens.Grid))
}

/**
 * Лист настроек штампа даты (открывается долгим нажатием на кнопку штампа). Поворачивается вместе с телефоном;
 * фон не затемняется — изменения сразу видны на превью над листом, а сама надпись показана и в шапке листа.
 * [autoMs] — «автоматическая» дата: текущее время у камеры, дата файла у фото из галереи ([autoLabel] — её название).
 */
@Composable
fun StampSheet(
    stamp: StampSettings,
    autoMs: Long,
    autoLabel: String,
    onChange: (StampSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var dateDialog by remember { mutableStateOf(false) }
    var colorDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    TurnedSheet(onDismiss = onDismiss, scrim = Color.Transparent, maxFraction = 0.56f) {
        Row(modifier = Modifier.fillMaxWidth().height(Dimens.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
            Text("Штамп даты", color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = stamp.enabled, onCheckedChange = { onChange(stamp.copy(enabled = it)) })
            IconTouch(description = "Закрыть", onClick = onDismiss) { Text("✕", color = TextPrimary, fontSize = 16.sp) }
        }
        // Живой образец: та же надпись, что попадёт в кадр
        val timeMs = stampTime(stamp, autoMs)
        val sample = remember(stamp, timeMs / 60_000L) {
            stampBitmap(stamp, stampText(stamp, timeMs), with(density) { 22.dp.roundToPx() }).asImageBitmap()
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(Dimens.IconButton).clip(RoundedCornerShape(12.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(sample, contentDescription = "Образец штампа", contentScale = ContentScale.Inside, alpha = stamp.opacity)
        }
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            SheetLabel("Дата")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Grid)) {
                ChoiceChips(listOf(autoLabel, "Своя"), if (stamp.custom) 1 else 0, onSelect = { index ->
                    val custom = index == 1
                    onChange(stamp.copy(custom = custom, customMs = if (custom && stamp.customMs <= 0L) autoMs else stamp.customMs))
                    if (custom) dateDialog = true
                })
                if (stamp.custom) {
                    TextButton(onClick = { dateDialog = true }, colors = appTextButtonColors()) {
                        Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(stamp.customMs)), color = Accent, fontSize = 13.sp)
                    }
                }
            }
            SheetLabel("Формат")
            ChoiceChips(StampFormat.entries.map { it.label }, stamp.format.ordinal, onSelect = { onChange(stamp.copy(format = StampFormat.entries[it])) })
            Row(modifier = Modifier.fillMaxWidth().height(Dimens.IconButton), verticalAlignment = Alignment.CenterVertically) {
                Text("Показывать время", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = stamp.showTime, onCheckedChange = { onChange(stamp.copy(showTime = it)) })
            }
            SheetLabel("Шрифт")
            ChoiceChips(StampFont.entries.map { it.label }, stamp.font.ordinal, onSelect = { onChange(stamp.copy(font = StampFont.entries[it])) })
            SheetLabel("Цвет")
            Row(modifier = Modifier.height(Dimens.IconButton), verticalAlignment = Alignment.CenterVertically) {
                StampSettings.COLORS.forEach { (name, color) ->
                    ColorDot(Color(color), name, selected = stamp.color == color) { onChange(stamp.copy(color = color)) }
                }
                val custom = StampSettings.COLORS.none { it.second == stamp.color }
                TextButton(onClick = { colorDialog = true }, colors = appTextButtonColors()) {
                    Text("Свой", color = if (custom) Accent else TextPrimary, fontSize = 13.sp)
                }
            }
            LabeledSlider("Размер", "${(stamp.size * 100).roundToInt()}%", stamp.size, StampSettings.MIN_SIZE..StampSettings.MAX_SIZE) {
                onChange(stamp.copy(size = it))
            }
            LabeledSlider("Прозрачность", "${((1f - stamp.opacity) * 100).roundToInt()}%", 1f - stamp.opacity, 0f..0.9f) {
                onChange(stamp.copy(opacity = 1f - it))
            }
            SheetLabel("Угол кадра")
            // Порядок чипов — как углы на экране: верхние, затем нижние
            val corners = listOf(3 to "↖", 2 to "↗", 1 to "↙", 0 to "↘")
            ChoiceChips(corners.map { it.second }, corners.indexOfFirst { it.first == stamp.corner }, onSelect = { onChange(stamp.copy(corner = corners[it].first)) })
        }
    }

    if (dateDialog) {
        DateTimeDialog(
            initialMs = if (stamp.customMs > 0L) stamp.customMs else autoMs,
            onConfirm = { onChange(stamp.copy(custom = true, customMs = it)); dateDialog = false },
            onDismiss = { dateDialog = false },
        )
    }
    if (colorDialog) {
        ColorPickerDialog("Цвет штампа", stamp.color, onPick = { onChange(stamp.copy(color = it)); colorDialog = false }, onDismiss = { colorDialog = false })
    }
}

@Composable
private fun ColorDot(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(Dimens.IconButton).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(2.dp, TextPrimary, CircleShape) else Modifier),
        )
    }
}

/** Строка ползунка в две строки: название и значение, под ними ползунок на всю ширину. */
@Composable
fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(Dimens.SliderLabelRow), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = TextSecondary, fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, colors = appSliderColors(), modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Своя дата и время: календарь месяца (листание стрелками, нажатие по дню) и поле времени Material (часы и минуты).
 * Помещается в диалог на экране от 320dp и поворачивается вместе с ним.
 */
@Composable
private fun DateTimeDialog(initialMs: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val start = remember { Calendar.getInstance().apply { timeInMillis = initialMs } }
    var year by remember { mutableStateOf(start.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(start.get(Calendar.MONTH)) }
    var day by remember { mutableStateOf(start.get(Calendar.DAY_OF_MONTH)) }
    // Месяц, который показывает календарь (может отличаться от выбранного дня)
    var shownYear by remember { mutableStateOf(year) }
    var shownMonth by remember { mutableStateOf(month) }
    var hour by remember { mutableStateOf(start.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(start.get(Calendar.MINUTE)) }
    TurnedDialog(
        title = "Своя дата и время",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, colors = appTextButtonColors()) { Text("Отмена") }
            TextButton(
                onClick = {
                    onConfirm(Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute, 0) }.timeInMillis)
                },
                colors = appTextButtonColors(),
            ) { Text("Готово") }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTouch(description = "Предыдущий месяц", onClick = {
                if (shownMonth == 0) { shownMonth = 11; shownYear-- } else shownMonth--
            }) { Text("‹", color = TextPrimary, fontSize = 22.sp) }
            Text(
                "${MONTHS[shownMonth]} $shownYear",
                color = TextPrimary, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            IconTouch(description = "Следующий месяц", onClick = {
                if (shownMonth == 11) { shownMonth = 0; shownYear++ } else shownMonth++
            }) { Text("›", color = TextPrimary, fontSize = 22.sp) }
        }
        val first = Calendar.getInstance().apply { clear(); set(shownYear, shownMonth, 1) }
        val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Неделя с понедельника
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        Column {
            Row(Modifier.fillMaxWidth()) {
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                    Text(it, color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            for (week in 0 until (offset + days + 6) / 7) {
                Row(Modifier.fillMaxWidth()) {
                    for (dow in 0 until 7) {
                        val d = week * 7 + dow - offset + 1
                        val selected = d == day && shownMonth == month && shownYear == year
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(if (selected) Accent else Color.Transparent)
                                .then(
                                    if (d in 1..days) {
                                        Modifier.clickable { year = shownYear; month = shownMonth; day = d }.semantics { contentDescription = "$d ${MONTHS[shownMonth]}" }
                                    } else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (d in 1..days) Text(d.toString(), color = if (selected) Color.Black else TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        // Время — кнопками «−/+» (без клавиатуры, которая закрыла бы диалог)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Время", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Stepper("час", hour, onChange = { hour = (it + 24) % 24 })
            Text(":", color = TextPrimary, fontSize = 20.sp)
            Stepper("минуты", minute, onChange = { minute = (it + 60) % 60 })
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTouch(description = "Меньше: $label", onClick = { onChange(value - 1) }, size = 40.dp) { Text("−", color = TextPrimary, fontSize = 18.sp) }
        Text(String.format(java.util.Locale.US, "%02d", value), color = Accent, fontSize = 20.sp)
        IconTouch(description = "Больше: $label", onClick = { onChange(value + 1) }, size = 40.dp) { Text("+", color = TextPrimary, fontSize = 18.sp) }
    }
}

private val MONTHS = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
