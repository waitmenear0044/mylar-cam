package com.vhscam.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.AppInfo
import com.vhscam.app.AppPrefs
import com.vhscam.app.DebugHooks
import com.vhscam.app.GridMode
import com.vhscam.app.JpegQuality
import com.vhscam.app.StartMode
import com.vhscam.app.VolumeAction

/**
 * Экран настроек: простой портретный список секций (съёмка, кнопки, файлы, общее). Значения запоминаются и
 * применяются сразу. Экран и его диалоги поворачиваются вместе с телефоном как единое целое.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var aboutOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (aboutOpen) aboutOpen = false else onBack() })
    val prefs = AppPrefs.get(LocalContext.current)
    val orientation = rememberDeviceOrientation(DebugHooks.orientationOverride)
    val rotation = rememberUiRotation(orientation, frozen = false)
    var choice by remember { mutableStateOf<Choice?>(null) }
    var folderDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalUiRotation provides rotation) {
        Box(Modifier.fillMaxSize().background(ScreenBackground).safeDrawingPadding()) {
            TurnedFrame(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().height(Dimens.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
                        IconTouch(description = "Назад", onClick = { if (aboutOpen) aboutOpen = false else onBack() }) { Text("←", color = TextPrimary, fontSize = 22.sp) }
                        Text(
                            if (aboutOpen) androidx.compose.ui.res.stringResource(com.vhscam.app.R.string.about_title) else "Настройки",
                            color = TextPrimary, style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (aboutOpen) AboutContent(Modifier.weight(1f)) else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Grid)) {
                        Section("Съёмка")
                        ValueRow("Сетка", prefs.grid.label) {
                            choice = Choice("Сетка", GridMode.entries.map { it.label }, prefs.grid.ordinal) { prefs.grid = GridMode.entries[it] }
                        }
                        SwitchRow("Уровень горизонта", prefs.level) { prefs.level = it }
                        ValueRow("Таймер", timerLabel(prefs.timerSec)) {
                            choice = Choice("Таймер", AppPrefs.TIMER_CHOICES.map(::timerLabel), AppPrefs.TIMER_CHOICES.indexOf(prefs.timerSec)) {
                                prefs.timerSec = AppPrefs.TIMER_CHOICES[it]
                            }
                        }
                        Section("Кнопки")
                        ValueRow("Кнопки громкости", prefs.volumeAction.label) {
                            choice = Choice("Кнопки громкости", VolumeAction.entries.map { it.label }, prefs.volumeAction.ordinal) {
                                prefs.volumeAction = VolumeAction.entries[it]
                            }
                        }
                        Section("Файлы")
                        ValueRow("Качество JPEG", prefs.jpegQuality.label) {
                            choice = Choice("Качество JPEG", JpegQuality.entries.map { it.label }, prefs.jpegQuality.ordinal) {
                                prefs.jpegQuality = JpegQuality.entries[it]
                            }
                        }
                        ValueRow("Папка сохранения", prefs.folder) { folderDialog = true }
                        Text(
                            "Pictures/${prefs.folder} и Movies/${prefs.folder}",
                            color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = Dimens.Grid, bottom = Dimens.HalfGrid),
                        )
                        Section("Общее")
                        SwitchRow("Вибрация", prefs.vibration) { prefs.vibration = it }
                        ValueRow("Режим при запуске", prefs.startMode.label) {
                            choice = Choice("Режим при запуске", StartMode.entries.map { it.label }, prefs.startMode.ordinal) {
                                prefs.startMode = StartMode.entries[it]
                            }
                        }
                        ValueRow("О приложении", AppInfo.LABEL, onClick = { aboutOpen = true })
                    }
                }
            }
        }

        choice?.let { c ->
            TurnedDialog(
                title = c.title,
                onDismiss = { choice = null },
                buttons = { TextButton(onClick = { choice = null }, colors = appTextButtonColors()) { Text("Отмена") } },
            ) {
                c.options.forEachIndexed { index, text ->
                    ChoiceRow(text, selected = index == c.selected) {
                        c.onSelect(index)
                        choice = null
                    }
                }
            }
        }
        if (folderDialog) {
            NameInputDialog(
                title = "Папка сохранения",
                initial = prefs.folder,
                label = "Название папки",
                onConfirm = { prefs.folder = it; folderDialog = false },
                onDismiss = { folderDialog = false },
            )
        }
    }
}

private class Choice(val title: String, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit)

private fun timerLabel(seconds: Int): String = if (seconds <= 0) "Выкл" else "$seconds с"

@Composable
private fun Section(title: String) {
    Text(title, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(start = Dimens.Grid, top = Dimens.Grid * 2, bottom = Dimens.HalfGrid))
}

@Composable
private fun ValueRow(title: String, value: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.IconButton)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.Grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Dimens.Grid))
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.IconButton).padding(horizontal = Dimens.Grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
