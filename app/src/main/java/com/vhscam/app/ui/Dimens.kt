package com.vhscam.app.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Сетка и размеры интерфейса — единственное место, где они заданы. Базовая единица 8dp, все отступы и промежутки
 * кратны 4/8dp; однотипные элементы одного размера и стоят на общей оси.
 */
object Dimens {
    /** Базовая единица сетки. */
    val Grid = 8.dp
    val HalfGrid = 4.dp

    /** Отступ блока управления от краёв (если зона жестов меньше). */
    val EdgePadding = 8.dp

    /** Промежуток между элементами в прокручиваемой полосе (там, где равномерное распределение невозможно). */
    val ItemGap = 8.dp

    /** Малая кнопка-иконка (вспышка, камера, FX, галерея, режимы, категории, «Пресеты»…): зона нажатия. */
    val IconButton = 48.dp

    /** Значок внутри кнопки. */
    val Icon = 24.dp

    /** Видимая подложка выбранной кнопки-иконки внутри 48dp. */
    val IconHighlight = 40.dp

    /** Таблетка с текстом: зона нажатия и видимая высота. */
    val PillHeight = 48.dp
    val PillVisualHeight = 32.dp
    val PillTextPadding = 8.dp
    val PillText = 12.sp

    /** Спуск. */
    val Shutter = 72.dp

    /** Переключатель «Фото/Видео»: капсула из двух ячеек по 48dp. */
    val CaptureToggleCell = 48.dp

    /** Ряд спуска и ряд зума. */
    val ShutterRow = 88.dp
    val ZoomRow = 56.dp

    /** Строка ползунка: подпись со значением и сам ползунок под ней. */
    val SliderLabelRow = 24.dp
    val SliderTouch = 48.dp

    /** Шапка панели, листа, диалога. */
    val HeaderHeight = 48.dp

    /** Скругления. */
    val PanelCorner = 16.dp
    val SheetCorner = 24.dp
}
