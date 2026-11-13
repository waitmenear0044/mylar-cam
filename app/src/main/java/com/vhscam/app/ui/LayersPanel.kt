package com.vhscam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.R
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.gl.EditLayer
import kotlin.math.roundToInt

/**
 * Панель слоёв редактора поверх нижней части превью. Список сверху вниз — порядок применения. У строки: «≡»
 * (долгое нажатие и перетаскивание меняет порядок), глаз (видимость), значок и название режима, непрозрачность,
 * удаление. Нажатие по строке делает слой активным — его настройки открываются в полосе категорий.
 * Под списком — ползунок непрозрачности активного слоя.
 */
@Composable
fun LayersPanel(
    layers: List<EditLayer>,
    activeId: String,
    onSelect: (EditLayer) -> Unit,
    onChange: (EditLayer) -> Unit,
    onDelete: (EditLayer) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxLayers: Int = EditLayer.MAX_LAYERS,
    showLocks: Boolean = false,
) {
    val rowPx = with(LocalDensity.current) { Dimens.IconButton.toPx() }
    val list by rememberUpdatedState(layers)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.PanelCorner))
            .background(PanelBackground.copy(alpha = 0.92f))
            .absorbTouches()
            .padding(start = Dimens.HalfGrid, end = Dimens.HalfGrid, bottom = Dimens.Grid),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(Dimens.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Слои: ${layers.size} из $maxLayers",
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = Dimens.Grid * 1.5f),
            )
            TextButton(onClick = onAdd, enabled = layers.size < maxLayers, colors = appTextButtonColors()) { Text("Добавить слой", fontSize = 13.sp) }
            IconTouch(description = "Закрыть слои", onClick = onClose) { Text("✕", color = TextPrimary, fontSize = 16.sp) }
        }
        layers.forEachIndexed { number, layer ->
            key(layer.id) {
                var drag by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                val isActive = layer.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.IconButton)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dragging) PanelRaised else if (isActive) PanelSelected else Color.Transparent)
                        .clickable { onSelect(layer) }
                        .semantics { contentDescription = "Слой ${number + 1}: ${layer.settings.mode.title}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(Dimens.IconButton)
                            .semantics { contentDescription = "Переместить слой ${number + 1}" }
                            .pointerInput(layer.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = true; drag = 0f },
                                    onDragEnd = { dragging = false },
                                    onDragCancel = { dragging = false },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag += amount.y
                                        val index = list.indexOfFirst { it.id == layer.id }
                                        if (drag > rowPx * 0.6f && index < list.lastIndex) {
                                            onMove(index, index + 1)
                                            drag -= rowPx
                                        } else if (drag < -rowPx * 0.6f && index > 0) {
                                            onMove(index, index - 1)
                                            drag += rowPx
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("≡", color = TextSecondary, fontSize = 20.sp)
                    }
                    IconTouch(
                        description = if (layer.visible) "Скрыть слой ${number + 1}" else "Показать слой ${number + 1}",
                        onClick = { onChange(layer.copy(visible = !layer.visible)) },
                    ) {
                        EyeGlyph(open = layer.visible, color = if (layer.visible) TextPrimary else TextDisabled)
                    }
                    Icon(
                        painterResource(modeIcon(layer.settings.mode)),
                        contentDescription = null,
                        tint = if (isActive) Accent else TextSecondary,
                        modifier = Modifier.size(Dimens.Icon),
                    )
                    Text(
                        layer.settings.mode.title,
                        color = if (!layer.visible) TextDisabled else if (isActive) Accent else TextPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = Dimens.Grid),
                    )
                    Text("${(layer.opacity * 100).roundToInt()}%", color = TextSecondary, fontSize = 12.sp)
                    // Замок «Кубика»: слой остаётся при следующем броске
                    if (showLocks) {
                        IconTouch(
                            description = if (layer.locked) "Снять замок со слоя ${number + 1}" else "Закрепить слой ${number + 1} при броске",
                            onClick = { onChange(layer.copy(locked = !layer.locked)) },
                        ) {
                            Icon(
                                painterResource(if (layer.locked) R.drawable.ic_lock else R.drawable.ic_lock_open), contentDescription = null,
                                tint = if (layer.locked) Accent else TextSecondary, modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconTouch(description = "Удалить слой ${number + 1}", onClick = { onDelete(layer) }, enabled = layers.size > 1) {
                        Text("✕", color = if (layers.size > 1) TextPrimary else TextDisabled, fontSize = 14.sp)
                    }
                }
            }
        }
        layers.firstOrNull { it.id == activeId }?.let { layer ->
            Box(Modifier.padding(horizontal = Dimens.Grid * 1.5f)) {
                LabeledSlider("Непрозрачность слоя", "${(layer.opacity * 100).roundToInt()}%", layer.opacity, 0f..1f) {
                    onChange(layer.copy(opacity = it))
                }
            }
        }
    }
}

/** Выбор режима для нового слоя. */
@Composable
fun AddLayerDialog(modes: List<EffectMode>, onPick: (EffectMode) -> Unit, onDismiss: () -> Unit) {
    TurnedDialog(
        title = "Добавить слой",
        onDismiss = onDismiss,
        buttons = { TextButton(onClick = onDismiss, colors = appTextButtonColors()) { Text("Отмена") } },
    ) {
        modes.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.IconButton)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(mode) }
                    .padding(horizontal = Dimens.Grid),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(modeIcon(mode)), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(Dimens.Icon))
                Text(mode.title, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.padding(start = Dimens.Grid * 2))
            }
        }
    }
}
