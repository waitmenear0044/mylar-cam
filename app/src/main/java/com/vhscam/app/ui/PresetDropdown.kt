package com.vhscam.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.AppInfo
import com.vhscam.app.R
import com.vhscam.app.effect.CoreKind
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.Preset
import com.vhscam.app.effect.PresetManager
import com.vhscam.app.gl.OfflineRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Компактный выпадающий список пресетов текущего режима поверх нижней части превью: строки «миниатюра + название»
 * (звёздочка у готовых, «⋮» у своих, точка «изменён»). Нажатие по строке сразу применяет пресет, список остаётся
 * открытым. Миниатюры считает тот же рендерер на замороженном кадре [thumbSource] (маленький, свой GL-поток,
 * небольшими пакетами — превью камеры не останавливается); пока не посчитаны — плейсхолдер.
 * Список поворачивается вместе с телефоном как единый блок, его диалоги — тоже.
 */
@Composable
fun PresetDropdown(
    manager: PresetManager,
    settings: EffectSettings,
    onSettingsChange: (EffectSettings) -> Unit,
    onDismiss: () -> Unit,
    thumbSource: Bitmap?,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    onOpenHelp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun onMessage(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    val active = manager.active(settings)
    val modified = manager.isModified(settings)

    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var toDelete by remember { mutableStateOf<Preset?>(null) }
    var actionsFor by remember { mutableStateOf<Preset?>(null) }

    // У «Cores» пресеты сгруппированы по ядрам (заголовок перед первым пресетом группы)
    val list = manager.presetsFor(settings.mode).let { all ->
        if (all.any { manager.groupOf(it) != null }) all.sortedBy { manager.groupOf(it)?.let { g -> groupOrder(g) } ?: 0 } else all
    }

    // Миниатюры: свой внеэкранный рендерер на замороженном кадре, по два пресета за раз с паузой
    val renderer = remember(thumbSource) { thumbSource?.let { OfflineRenderer(context, it) } }
    DisposableEffect(renderer) { onDispose { renderer?.release() } }
    val thumbs = remember(renderer) { mutableStateMapOf<String, ImageBitmap>() }
    val ids = list.map { it.id }
    LaunchedEffect(renderer, ids) {
        val r = renderer ?: return@LaunchedEffect
        var batch = 0
        for (preset in list) {
            if (thumbs.containsKey(preset.id)) continue
            val look = manager.preview(preset, settings).effective()
            thumbs[preset.id] = r.render(look).asImageBitmap()
            if (++batch % THUMB_BATCH == 0) delay(THUMB_PAUSE_MS)
        }
    }

    // Предпросмотр долгим нажатием: список почти прозрачный, на превью — пресет под пальцем
    val presetPreview = LocalPresetPreview.current
    val listAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (presetPreview?.label != null) 0.25f else 1f, androidx.compose.animation.core.tween(150), label = "presetPreview",
    )
    TurnWithDevice(modifier.alpha(listAlpha), minTurnedHeight = 260.dp) { turned ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (turned) Modifier.fillMaxHeight() else Modifier.heightIn(max = maxHeight))
                .clip(RoundedCornerShape(Dimens.PanelCorner))
                .background(PanelBackground.copy(alpha = 0.92f))
                .absorbTouches()
                .padding(start = Dimens.Grid, end = Dimens.HalfGrid, bottom = Dimens.HalfGrid),
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(Dimens.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Пресеты: ${settings.mode.title}",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Dimens.Grid),
                )
                if (active != null && modified) {
                    TextButton(onClick = { onSettingsChange(manager.revert(settings)) }, colors = appTextButtonColors()) {
                        Text("Вернуть", fontSize = 13.sp)
                    }
                }
                // Справка по эффектам — неприметно, маленькой приглушённой иконкой
                if (onOpenHelp != null) {
                    IconTouch(description = "Справка по эффектам", onClick = onOpenHelp) {
                        Icon(painterResource(R.drawable.ic_help), contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                IconTouch(description = "Закрыть пресеты", onClick = onDismiss) { Text("✕", color = TextPrimary, fontSize = 16.sp) }
            }
            // Положение списка запоминается для режима (и слоя редактора); впервые — активный пресет примерно по центру
            val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { Dimens.IconButton.roundToPx() }
            val activeRow = list.indexOfFirst { it.id == active?.id }.let { i ->
                if (i < 0) 0 else i + list.take(i + 1).mapNotNull { manager.groupOf(it) }.distinct().size
            }
            val listState = rememberRememberedListState("presets:${settings.mode.name}", activeRow, centerOffsetPx = rowPx * 2)
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                list.forEachIndexed { index, preset ->
                    val group = manager.groupOf(preset)
                    if (group != null && (index == 0 || manager.groupOf(list[index - 1]) != group)) {
                        item(key = "group-$group") {
                            Text(group, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = Dimens.Grid, top = Dimens.HalfGrid))
                        }
                    }
                    item(key = preset.id) {
                        PresetRow(
                            preset = preset,
                            thumb = thumbs[preset.id],
                            isActive = preset.id == active?.id,
                            isModified = preset.id == active?.id && modified,
                            onApply = { onSettingsChange(manager.apply(preset, settings)) },
                            onPreview = presetPreview.handler(preset.name, settings, { manager.preview(preset, settings) }, onSettingsChange),
                            onActions = { actionsFor = preset },
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(Dimens.IconButton), verticalAlignment = Alignment.CenterVertically) {
                SmallAction("Сохранить текущее", Modifier.weight(1.3f)) { nameDialog = NameDialog.Save }
                SmallAction("Вставить код", Modifier.weight(1f)) {
                    val imported = context.clipboardText()?.let { manager.import(it) }
                    when {
                        imported == null -> onMessage(
                            context.clipboardText()?.let { com.vhscam.app.effect.PresetRepository.rejection(it) }
                                ?.let { "Пресет не добавлен: $it" } ?: "В буфере нет корректного кода пресета",
                        )
                        imported.mode != settings.mode -> onMessage("Пресет «${imported.name}» добавлен в режим «${imported.mode.title}»")
                        else -> {
                            onSettingsChange(manager.apply(imported, settings))
                            onMessage("Пресет «${imported.name}» добавлен и применён")
                        }
                    }
                }
                SmallAction("Скопировать код", Modifier.weight(1.2f)) {
                    context.copyText(manager.codeOfCurrent(settings))
                    onMessage("Код текущих настроек скопирован")
                }
            }
            SnackbarHost(snackbar)
        }
    }

    actionsFor?.let { preset ->
        TurnedDialog(
            title = preset.name,
            onDismiss = { actionsFor = null },
            buttons = { TextButton(onClick = { actionsFor = null }, colors = appTextButtonColors()) { Text("Отмена") } },
        ) {
            ActionRow("Скопировать код") {
                actionsFor = null
                context.copyText(manager.code(preset))
                onMessage("Код пресета «${preset.name}» скопирован")
            }
            if (!preset.builtIn) {
                ActionRow("Переименовать") { actionsFor = null; nameDialog = NameDialog.Rename(preset) }
                ActionRow("Удалить") { actionsFor = null; toDelete = preset }
            }
        }
    }

    nameDialog?.let { dialog ->
        NameInputDialog(
            title = if (dialog is NameDialog.Rename) "Переименовать пресет" else "Сохранить как пресет",
            initial = (dialog as? NameDialog.Rename)?.preset?.name ?: "",
            onConfirm = { name ->
                when (dialog) {
                    is NameDialog.Rename -> manager.rename(dialog.preset, name)
                    NameDialog.Save -> {
                        val preset = manager.saveCurrent(name, settings)
                        onMessage("Пресет «${preset.name}» сохранён")
                    }
                }
                nameDialog = null
            },
            onDismiss = { nameDialog = null },
        )
    }

    toDelete?.let { preset ->
        TurnedDialog(
            title = "Удалить пресет?",
            onDismiss = { toDelete = null },
            buttons = {
                TextButton(onClick = { toDelete = null }, colors = appTextButtonColors()) { Text("Отмена") }
                TextButton(onClick = { manager.delete(preset); toDelete = null }, colors = appTextButtonColors()) { Text("Удалить") }
            },
        ) {
            Text("«${preset.name}» будет удалён.", color = TextSecondary)
        }
    }
}

private sealed interface NameDialog {
    data object Save : NameDialog
    data class Rename(val preset: Preset) : NameDialog
}

@Composable
private fun SmallAction(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = Dimens.HalfGrid)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Пункт действия в диалоге: строка 48dp на всю ширину. */
@Composable
fun ActionRow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.IconButton)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.Grid),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = TextPrimary, fontSize = 16.sp)
    }
}

/** Строка пресета 48dp: миниатюра 40dp, название, точка «изменён», звёздочка (готовый) или «⋮» (свой). */
@Composable
private fun PresetRow(
    preset: Preset,
    thumb: ImageBitmap?,
    isActive: Boolean,
    isModified: Boolean,
    onApply: () -> Unit,
    onPreview: ((Boolean) -> Unit)?,
    onActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.IconButton)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) PanelSelected else Color.Transparent)
            .previewPress(onClick = onApply, onPreview = onPreview)
            .padding(start = Dimens.HalfGrid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(Dimens.IconHighlight).clip(RoundedCornerShape(8.dp)).background(PanelRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            preset.name,
            color = if (isActive) Accent else TextPrimary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Dimens.Grid),
        )
        if (isModified) Box(Modifier.padding(horizontal = 6.dp)) { ModifiedDot() }
        if (preset.builtIn) {
            // У готовых — звёздочка; действия (скопировать код) — по ней же
            IconTouch(description = "Действия с пресетом", onClick = onActions) { StarGlyph(Micro, 14.dp) }
        } else {
            IconTouch(description = "Действия с пресетом", onClick = onActions) { Text("⋮", fontSize = 20.sp, color = TextPrimary) }
        }
    }
}

@Composable
fun NameInputDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit, label: String = "Название") {
    var text by remember { mutableStateOf(initial) }
    TurnedDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, colors = appTextButtonColors()) { Text("Отмена") }
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank(), colors = appTextButtonColors()) { Text("Готово") }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(40) },
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun Context.copyText(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Код ${AppInfo.NAME}", text))
}

internal fun Context.clipboardText(): String? {
    val clipboard = getSystemService(ClipboardManager::class.java)
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
}

/** Порядок групп пресетов «Cores» — как у чипов ядер. */
private fun groupOrder(group: String): Int =
    CoreKind.entries.indexOfFirst { it.title == group }.let { if (it < 0) Int.MAX_VALUE else it }

private const val THUMB_BATCH = 2
private const val THUMB_PAUSE_MS = 24L
