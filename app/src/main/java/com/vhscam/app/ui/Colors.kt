package com.vhscam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * Палитра интерфейса — единственное место, где заданы цвета. Правила:
 * - акцент (оранжевый) — выбранные состояния, спуск, активные чипы и категории, текущий зум, «поймал» у фокуса;
 * - фиолетовый — только микроэлементы: тонкие разделители, точка «изменён», вторая часть дорожки
 *   ползунка, риски шкалы, мелкие метки; не для площадей, кнопок и текста;
 * - текст и иконки белые, второстепенные — белые с прозрачностью;
 * - красный — только запись и ошибки; панели тёмные, слегка тёплые.
 */

val Accent = Color(0xFFFFD54F)
val Micro = Color(0xFF9B6BFF)

val TextPrimary = Color.White
val TextSecondary = Color.White.copy(alpha = 0.65f)
val TextDisabled = Color.White.copy(alpha = 0.38f)

val ScreenBackground = Color.Black
/** Фон панелей и полос (категории, ряд пресета, кнопки). */
val PanelBackground = Color(0xFF1A1918)
/** Приподнятые элементы: таблетки, кнопки на панели. */
val PanelRaised = Color(0xFF2A2826)
/** Выбранный элемент на панели (вместе с акцентным текстом). */
val PanelSelected = Color(0xFF3A3734)
/** Подложка временных плашек поверх превью. */
val Scrim = Color(0x99000000)

val RecordRed = Color(0xFFE53935)
val ErrorRed = Color(0xFFFF5449)
/** «Не поймал» у кольца фокуса. */
val FocusFailed = Color.White.copy(alpha = 0.55f)

/** Тема Material: компоненты (ползунки, переключатели, листы, меню, диалоги) берут цвета отсюда. */
val AppColorScheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    primaryContainer = PanelSelected,
    onPrimaryContainer = TextPrimary,
    secondary = Accent,
    onSecondary = Color.Black,
    secondaryContainer = PanelSelected,
    onSecondaryContainer = TextPrimary,
    tertiary = Micro,
    onTertiary = TextPrimary,
    background = ScreenBackground,
    onBackground = TextPrimary,
    surface = PanelBackground,
    onSurface = TextPrimary,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = PanelSelected,
    inverseOnSurface = TextPrimary,
    inversePrimary = Accent,
    outline = Color.White.copy(alpha = 0.3f),
    // Рамки чипов и кнопок — нейтральные; фиолетовые тонкие разделители задаются явно (MicroDivider)
    outlineVariant = Color.White.copy(alpha = 0.18f),
    error = ErrorRed,
    onError = TextPrimary,
    surfaceBright = PanelSelected,
    surfaceDim = ScreenBackground,
    surfaceContainerLowest = ScreenBackground,
    surfaceContainerLow = PanelBackground,
    surfaceContainer = PanelBackground,
    surfaceContainerHigh = PanelRaised,
    surfaceContainerHighest = PanelSelected,
)

/** Тонкий разделитель — микроэлемент фиолетового. */
val MicroDivider = Micro.copy(alpha = 0.5f)

/** Ползунок: пройденная часть и бегунок — акцент, остаток дорожки — приглушённый фиолетовый. */
@Composable
fun appSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = Accent,
    activeTrackColor = Accent,
    inactiveTrackColor = Micro.copy(alpha = 0.32f),
    activeTickColor = Color.Black.copy(alpha = 0.4f),
    inactiveTickColor = Micro.copy(alpha = 0.6f),
)

/** Текстовые кнопки — белые (акцент только у выбранного). */
@Composable
fun appTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(
    contentColor = TextPrimary,
    disabledContentColor = TextDisabled,
)

/** Основная кнопка — приподнятая тёмная плашка с белым текстом. */
@Composable
fun appButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = PanelSelected,
    contentColor = TextPrimary,
    disabledContainerColor = PanelRaised,
    disabledContentColor = TextDisabled,
)

/** Чипы режимов и ядер: выбранный — акцентный текст на светлее фоне. */
@Composable
fun appChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = TextPrimary,
    selectedContainerColor = PanelSelected,
    selectedLabelColor = Accent,
    disabledLabelColor = TextDisabled,
)

/** Точка «изменён» у пресета (микроэлемент фиолетового). */
@Composable
fun ModifiedDot() {
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(Micro)
            .semantics { contentDescription = "изменён" },
    )
}
