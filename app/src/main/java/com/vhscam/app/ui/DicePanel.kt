package com.vhscam.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.AppPrefs
import com.vhscam.app.R
import com.vhscam.app.effect.Dice
import com.vhscam.app.effect.DiceSet
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * Панель «Кубика» (редактор): большая кнопка «Бросить» (кубик вращается ~400 мс, лёгкая вибрация), выбор числа
 * слоёв 3/5/7/10, seed последнего броска (нажатие — бросок по своему seed) и обмен цепочкой слоёв кодом.
 */
@Composable
fun DicePanel(
    count: Int,
    onCount: (Int) -> Unit,
    rolling: Boolean,
    seed: Long?,
    onRoll: () -> Unit,
    onSeed: () -> Unit,
    onCopyChain: () -> Unit,
    onPasteChain: () -> Unit,
    modifier: Modifier = Modifier,
    canSave: Boolean = false,
    onSave: () -> Unit = {},
    saved: List<DiceSet> = emptyList(),
    onApplySaved: (DiceSet) -> Unit = {},
    onSavedActions: (DiceSet) -> Unit = {},
) {
    val spin = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.PanelCorner))
            .background(panelBackground())
            .absorbTouches()
            .padding(Dimens.Grid),
        verticalArrangement = Arrangement.spacedBy(Dimens.Grid),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Кубик", color = TextPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = Dimens.Grid).weight(1f))
            if (seed != null) {
                Text(
                    "seed $seed",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeed).padding(horizontal = Dimens.Grid, vertical = 6.dp)
                        .semantics { contentDescription = "Seed последнего броска $seed; нажмите, чтобы бросить по своему seed" },
                )
            }
            // Сохранить результат броска как набор слоёв
            if (canSave) {
                IconTouch(description = "Сохранить набор слоёв", onClick = onSave) {
                    Box(Modifier.rotateWithDevice()) { StarGlyph(Accent, 20.dp) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Grid)) {
            // Кнопка броска — только кубик (подпись — в описании для доступности), высота как у ячеек числа слоёв
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Dimens.IconButton)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Accent)
                    .clickable(enabled = !rolling) {
                        if (AppPrefs.get(context).vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch { spin.animateTo(spin.value + 360f, tween(400)) }
                        onRoll()
                    }
                    .semantics { contentDescription = "Бросить кубик" },
                contentAlignment = Alignment.Center,
            ) {
                if (rolling) {
                    CircularProgressIndicator(Modifier.size(Dimens.Icon), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Icon(
                        painterResource(R.drawable.ic_mode_random), contentDescription = null, tint = Color.Black,
                        modifier = Modifier.size(Dimens.Icon).rotate(spin.value).rotateWithDevice(),
                    )
                }
            }
            // Число слоёв
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dice.COUNTS.forEach { n ->
                    val isSelected = n == count
                    Box(
                        modifier = Modifier
                            .size(Dimens.IconButton)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) PanelSelected else PanelRaised)
                            .then(if (isSelected) Modifier.border(1.5.dp, Accent, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { onCount(n) }
                            .semantics {
                                contentDescription = "Слоёв: $n"
                                this.selected = isSelected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", color = if (isSelected) Accent else TextPrimary, fontSize = 15.sp, modifier = Modifier.rotateWithDevice())
                    }
                }
            }
        }
        // Сохранённые наборы: нажатие — применить к текущему фото, долгое нажатие — переименовать, код, удалить
        if (saved.isNotEmpty()) {
            Text("Сохранённые", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = Dimens.Grid))
            LazyRow(
                state = rememberRememberedListState("dice-saved", 0),
                horizontalArrangement = Arrangement.spacedBy(Dimens.HalfGrid),
            ) {
                items(saved, key = { it.id }) { set ->
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PanelRaised)
                            .pointerInput(set.id) { detectTapGestures(onTap = { onApplySaved(set) }, onLongPress = { onSavedActions(set) }) }
                            .padding(horizontal = Dimens.Grid * 1.5f)
                            .semantics { contentDescription = "Набор ${set.name}, слоёв ${set.count}; долгое нажатие — действия" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${set.name} · ${set.count}", color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Grid)) {
            SmallPanelButton("Скопировать набор", Modifier.weight(1f), onCopyChain)
            SmallPanelButton("Вставить набор", Modifier.weight(1f), onPasteChain)
        }
    }
}

@Composable
private fun SmallPanelButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(PanelRaised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TextPrimary, fontSize = 12.sp, maxLines = 1)
    }
}
