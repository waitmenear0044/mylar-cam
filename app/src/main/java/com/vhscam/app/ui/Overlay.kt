package com.vhscam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Листы и диалоги экрана камеры поворачиваются вместе с телефоном как единое целое: окно на весь экран, содержимое
 * компонуется в рамке с поменянными шириной и высотой (если телефон боком) и поворачивается вокруг центра на угол
 * [LocalUiRotation]. У держащего телефон горизонтально лист выглядит как обычный лист на горизонтальном экране.
 * Вне экрана камеры угол 0 — те же листы просто портретные.
 */

/** Рамка на всё доступное место, содержимое которой повёрнуто на угол телефона (с поменянными сторонами). */
@Composable
fun TurnedFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val angle = LocalUiRotation.current
    val turned = abs((angle / 90f).roundToInt()) % 2 == 1
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val inner = if (turned) Constraints.fixed(h, w) else Constraints.fixed(w, h)
        val placeables = measurables.map { it.measure(inner) }
        layout(w, h) {
            placeables.forEach { p ->
                p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) { rotationZ = angle }
            }
        }
    }
}

/** Окно поверх всего экрана без системных панелей; внутри — повёрнутая рамка с затемнением. */
@Composable
private fun TurnedOverlay(onDismiss: () -> Unit, scrim: Color = Scrim, content: @Composable BoxScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        HideSystemBarsInDialog()
        // Без затемнения (лист штампа): окно диалога тоже не должно гасить экран под собой
        if (scrim.alpha == 0f) ClearDialogDim()
        TurnedFrame(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .safeDrawingPadding(),
                content = content,
            )
        }
    }
}

/**
 * Лист снизу (в повёрнутом положении — снизу для держащего телефон человека): высота по содержимому, но не выше
 * [SHEET_MAX_FRACTION] экрана; длинный список внутри должен занимать weight(1f, fill = false), чтобы кнопки
 * внизу листа оставались видны.
 */
@Composable
fun TurnedSheet(
    onDismiss: () -> Unit,
    scrim: Color = Scrim,
    maxFraction: Float = SHEET_MAX_FRACTION,
    content: @Composable ColumnScope.() -> Unit,
) {
    TurnedOverlay(onDismiss, scrim) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * maxFraction)
                    .clip(RoundedCornerShape(topStart = Dimens.SheetCorner, topEnd = Dimens.SheetCorner))
                    .background(PanelBackground)
                    // Касания внутри листа не закрывают его
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = Dimens.Grid * 2, vertical = Dimens.Grid),
                content = content,
            )
        }
    }
}

/** Диалог по центру: заголовок, содержимое и кнопки внизу справа. */
@Composable
fun TurnedDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    TurnedOverlay(onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(Dimens.Grid * 3)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(Dimens.SheetCorner))
                .background(PanelRaised)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(Dimens.Grid * 3)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.Grid * 2),
        ) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            content()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Grid, Alignment.End)) {
                buttons()
            }
        }
    }
}

private const val SHEET_MAX_FRACTION = 0.85f

/** Пункт выбора в диалоге: строка 48dp на всю ширину, выбранный — акцентный с «✓». */
@Composable
fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.IconButton)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (selected) Accent else TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = Accent, fontSize = 16.sp)
    }
}
