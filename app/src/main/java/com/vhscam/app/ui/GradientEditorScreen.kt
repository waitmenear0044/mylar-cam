package com.vhscam.app.ui

import com.vhscam.app.R
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.DebugHooks
import com.vhscam.app.effect.Gradient
import com.vhscam.app.effect.GradientStop
import com.vhscam.app.effect.GradientStore
import com.vhscam.app.effect.SettingsStore
import com.vhscam.app.effect.gradientLut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Кисть-полоска градиента (для ленты, списка и редактора). */
fun gradientBrush(stops: List<GradientStop>): Brush {
    val sorted = stops.sortedBy { it.pos }
    return Brush.horizontalGradient(*sorted.map { it.pos to Color(it.color) }.toTypedArray())
}

/** Цвет градиента в точке [pos] (как его посчитает карта режима). */
private fun colorAt(stops: List<GradientStop>, pos: Float): Int {
    val lut = gradientLut(stops, 256)
    val i = (pos.coerceIn(0f, 1f) * 255).roundToInt() * 4
    return 0xFF000000.toInt() or ((lut[i].toInt() and 0xFF) shl 16) or ((lut[i + 1].toInt() and 0xFF) shl 8) or (lut[i + 2].toInt() and 0xFF)
}

/**
 * Редактор градиентов (общий для камеры и обработки фото): полоска с точками (до 30) — добавить нажатием по полоске,
 * перетащить, удалить; цвет точки — HSV и HEX; список градиентов — порядок перетаскиванием за «≡» (долгое нажатие),
 * новый, дубль, переименовать, удалить свой, сбросить готовый; обмен кодом одного градиента и всего набора.
 * При выходе выбранный градиент становится градиентом режима. Экран поворачивается вместе с телефоном.
 */
@Composable
fun GradientEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GradientStore.get(context) }
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    var selectedId by remember { mutableStateOf(settingsStore.load().gradient.gradientId.takeIf { store.find(it) != null } ?: store.gradients.first().id) }
    val selected = store.find(selectedId) ?: store.gradients.first()
    // Правки точек — в рабочей копии, на диск пишем с небольшой задержкой (не на каждом движении пальца)
    var working by remember(selectedId) { mutableStateOf(selected.stops) }
    var stopIndex by remember(selectedId) { mutableIntStateOf(0) }
    LaunchedEffect(working) {
        if (working != store.find(selectedId)?.stops) {
            delay(250)
            store.find(selectedId)?.let { store.update(it.copy(stops = working.sortedBy { s -> s.pos })) }
        }
    }
    fun leave() {
        store.find(selectedId)?.let { g ->
            val final = g.copy(stops = working.sortedBy { it.pos })
            store.update(final)
            val s = settingsStore.load()
            settingsStore.save(s.copy(gradient = s.gradient.with(final)))
        }
        onBack()
    }
    BackHandler(onBack = ::leave)

    var menu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Gradient?>(null) }
    var rename by remember { mutableStateOf<Gradient?>(null) }
    var newDialog by remember { mutableStateOf(false) }

    val orientation = rememberDeviceOrientation(DebugHooks.orientationOverride)
    val rotation = rememberUiRotation(orientation, frozen = false)
    CompositionLocalProvider(LocalUiRotation provides rotation) {
        Box(Modifier.fillMaxSize().background(ScreenBackground).safeDrawingPadding()) {
            TurnedFrame(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().height(Dimens.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
                        IconTouch(description = "Назад", onClick = ::leave) { Text("←", color = TextPrimary, fontSize = 22.sp) }
                        Text("Градиенты", color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { menu = true }, colors = appTextButtonColors()) { Text("Код") }
                    }
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberRememberedScroll("gradient-editor")).padding(horizontal = Dimens.Grid * 2),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Grid),
                    ) {
                        Text(selected.name, color = Accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StopsBar(
                            stops = working,
                            selected = stopIndex,
                            onSelect = { stopIndex = it },
                            onMove = { index, pos -> working = working.toMutableList().also { it[index] = it[index].copy(pos = pos) } },
                            onAdd = { pos ->
                                if (working.size >= Gradient.MAX_STOPS) {
                                    message("Не больше ${Gradient.MAX_STOPS} точек")
                                } else {
                                    working = working + GradientStop(pos, colorAt(working, pos))
                                    stopIndex = working.lastIndex
                                }
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Точек: ${working.size} из ${Gradient.MAX_STOPS}", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            // «Копировать»: та же точка (тот же HEX) рядом с исходной, копия сразу выбрана
                            val canCopy = working.size < Gradient.MAX_STOPS && stopIndex in working.indices
                            IconTouch(
                                description = if (canCopy) "Копировать точку" else "Копировать точку: достигнут предел ${Gradient.MAX_STOPS}",
                                onClick = {
                                    if (!canCopy) {
                                        message("Не больше ${Gradient.MAX_STOPS} точек")
                                    } else {
                                        val src = working[stopIndex]
                                        working = working + src.copy(pos = copyPosition(working, stopIndex))
                                        stopIndex = working.lastIndex
                                    }
                                },
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_copy), contentDescription = null,
                                    tint = if (canCopy) TextPrimary else TextDisabled,
                                    modifier = Modifier.size(Dimens.Icon).rotateWithDevice(),
                                )
                            }
                            TextButton(
                                onClick = {
                                    working = working.filterIndexed { i, _ -> i != stopIndex }
                                    stopIndex = stopIndex.coerceAtMost(working.lastIndex)
                                },
                                enabled = working.size > 2,
                                colors = appTextButtonColors(),
                            ) { Text("Удалить точку") }
                        }
                        working.getOrNull(stopIndex)?.let { stop ->
                            key(selectedId, stopIndex) {
                                ColorPickerFields(
                                    color = stop.color,
                                    onChange = { c -> working = working.toMutableList().also { if (stopIndex in it.indices) it[stopIndex] = it[stopIndex].copy(color = c) } },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Набор", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { newDialog = true }, colors = appTextButtonColors()) { Text("Новый") }
                        }
                        GradientList(
                            gradients = store.gradients,
                            selectedId = selectedId,
                            workingStops = working,
                            onSelect = { selectedId = it.id },
                            onActions = { actionsFor = it },
                            onMove = { from, to -> store.move(from, to) },
                        )
                    }
                    SnackbarHost(snackbar)
                }
            }
        }

        if (menu) {
            TurnedDialog(
                title = "Обмен кодом",
                onDismiss = { menu = false },
                buttons = { TextButton(onClick = { menu = false }, colors = appTextButtonColors()) { Text("Отмена") } },
            ) {
                ActionRow("Скопировать код градиента") {
                    menu = false
                    context.copyText(GradientStore.encode(listOf(selected.copy(stops = working))))
                    message("Код градиента «${selected.name}» скопирован")
                }
                ActionRow("Скопировать код всего набора") {
                    menu = false
                    context.copyText(GradientStore.encode(store.gradients))
                    message("Код набора (${store.gradients.size}) скопирован")
                }
                ActionRow("Вставить код") {
                    menu = false
                    val added = context.clipboardText()?.let { store.import(it) }.orEmpty()
                    if (added.isEmpty()) {
                        message("В буфере нет корректного кода градиента")
                    } else {
                        selectedId = added.first().id
                        message(if (added.size == 1) "Градиент «${added.first().name}» добавлен" else "Добавлено градиентов: ${added.size}")
                    }
                }
            }
        }
        actionsFor?.let { g ->
            TurnedDialog(
                title = g.name,
                onDismiss = { actionsFor = null },
                buttons = { TextButton(onClick = { actionsFor = null }, colors = appTextButtonColors()) { Text("Отмена") } },
            ) {
                ActionRow("Дублировать") {
                    actionsFor = null
                    val stops = if (g.id == selectedId) working else g.stops
                    selectedId = store.add("${g.name} (копия)", stops).id
                }
                ActionRow("Переименовать") { actionsFor = null; rename = g }
                if (g.builtIn) {
                    ActionRow("Сбросить к исходному") {
                        actionsFor = null
                        store.reset(g)?.let { if (g.id == selectedId) { working = it.stops; stopIndex = 0 } }
                        message("«${g.name}» сброшен")
                    }
                } else {
                    ActionRow("Удалить") {
                        actionsFor = null
                        if (g.id == selectedId) selectedId = store.gradients.first { it.id != g.id }.id
                        store.delete(g)
                    }
                }
            }
        }
        rename?.let { g ->
            NameInputDialog(
                title = "Переименовать градиент",
                initial = g.name,
                onConfirm = { name ->
                    store.find(g.id)?.let { store.update(it.copy(name = name.trim().ifBlank { it.name }.take(40))) }
                    rename = null
                },
                onDismiss = { rename = null },
            )
        }
        if (newDialog) {
            NameInputDialog(
                title = "Новый градиент",
                initial = "",
                onConfirm = { name ->
                    selectedId = store.add(name, listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, 0xFFFFFFFF.toInt()))).id
                    newDialog = false
                },
                onDismiss = { newDialog = false },
            )
        }
    }
}

/**
 * Полоска градиента и ряд точек под ней. Нажатие рядом с точкой выбирает её, дальше палец её тащит;
 * нажатие в свободном месте добавляет точку с цветом градиента в этом месте.
 */
/**
 * Положение копии точки [index]: чуть правее (+2.5% длины полосы), не за край и не на другую точку; справа места нет —
 * чуть левее; рядом занято — середина ближайшего свободного промежутка.
 */
internal fun copyPosition(stops: List<GradientStop>, index: Int): Float {
    val p = stops[index].pos
    val step = 0.025f
    val minGap = 0.008f
    fun free(x: Float) = x in 0f..1f && stops.none { abs(it.pos - x) < minGap }
    if (free(p + step)) return p + step
    if (free(p - step)) return p - step
    val xs = (stops.map { it.pos } + 0f + 1f).sorted().distinct()
    val gap = xs.zipWithNext().filter { it.second - it.first >= 2 * minGap }
        .minByOrNull { abs((it.first + it.second) / 2f - p) } ?: return p
    return (gap.first + gap.second) / 2f
}

@Composable
private fun StopsBar(
    stops: List<GradientStop>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, pos: Float) -> Unit,
    onAdd: (pos: Float) -> Unit,
) {
    val current by rememberUpdatedState(stops)
    val density = LocalDensity.current
    val pad = with(density) { 14.dp.toPx() }
    val grab = with(density) { 22.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .semantics { contentDescription = "Полоска градиента" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val length = size.width - 2 * pad
                    if (length <= 0f) return@awaitEachGesture
                    fun posOf(x: Float) = ((x - pad) / length).coerceIn(0f, 1f)
                    val nearest = current.indices.minByOrNull { abs(current[it].pos * length + pad - down.position.x) }
                    val index = if (nearest != null && abs(current[nearest].pos * length + pad - down.position.x) <= grab) {
                        onSelect(nearest)
                        nearest
                    } else {
                        onAdd(posOf(down.position.x))
                        -1
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                            // Только что добавленная точка — последняя в списке
                            val i = if (index >= 0) index else current.lastIndex
                            if (i in current.indices) onMove(i, posOf(change.position.x))
                        }
                    }
                }
            },
    ) {
        val length = size.width - 2 * pad
        val barH = 56.dp.toPx()
        drawRoundRect(gradientBrush(stops), topLeft = Offset(pad, 0f), size = Size(length, barH), cornerRadius = CornerRadius(12.dp.toPx()))
        val cy = barH + 24.dp.toPx()
        stops.forEachIndexed { i, stop ->
            val x = pad + stop.pos * length
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, barH), Offset(x, cy - 10.dp.toPx()), 1.dp.toPx())
            drawCircle(Color(stop.color), 11.dp.toPx(), Offset(x, cy))
            drawCircle(if (i == selected) Accent else Color.White.copy(alpha = 0.7f), 11.dp.toPx(), Offset(x, cy), style = Stroke(if (i == selected) 3.dp.toPx() else 1.5.dp.toPx()))
        }
    }
}

/** Список градиентов: строка 48dp — «≡» (долгое нажатие и перетаскивание меняет порядок), полоска, название, «⋮». */
@Composable
private fun GradientList(
    gradients: List<Gradient>,
    selectedId: String,
    workingStops: List<GradientStop>,
    onSelect: (Gradient) -> Unit,
    onActions: (Gradient) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val rowPx = with(LocalDensity.current) { Dimens.IconButton.toPx() }
    val list by rememberUpdatedState(gradients)
    Column {
        gradients.forEach { g ->
            key(g.id) {
                var drag by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.IconButton)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dragging) PanelRaised else if (g.id == selectedId) PanelSelected else Color.Transparent)
                        .clickable { onSelect(g) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(Dimens.IconButton)
                            .semantics { contentDescription = "Переместить ${g.name}" }
                            .pointerInput(g.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = true; drag = 0f },
                                    onDragEnd = { dragging = false },
                                    onDragCancel = { dragging = false },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag += amount.y
                                        val index = list.indexOfFirst { it.id == g.id }
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
                    Box(
                        Modifier
                            .width(72.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(gradientBrush(if (g.id == selectedId) workingStops else g.stops)),
                    )
                    Text(
                        g.name,
                        color = if (g.id == selectedId) Accent else TextPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = Dimens.Grid),
                    )
                    if (g.builtIn) StarGlyph(Micro, 14.dp)
                    IconTouch(description = "Действия с градиентом ${g.name}", onClick = { onActions(g) }) { Text("⋮", fontSize = 20.sp, color = TextPrimary) }
                }
            }
        }
    }
}

/**
 * Лента градиентов в панели режима «Градиент» (поверх превью): полоски-миниатюры, выбранная — в акцентной рамке;
 * «изменить» открывает редактор.
 */
@Composable
fun GradientStrip(
    selectedId: String,
    onSelect: (Gradient) -> Unit,
    onEdit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onPreview: ((Gradient, Boolean) -> Unit)? = null,
) {
    val store = GradientStore.get(LocalContext.current)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.IconButton)
            .dimWhileDragging()
            .clip(RoundedCornerShape(Dimens.SheetCorner))
            .background(panelBackground())
            .absorbTouches()
            .padding(horizontal = Dimens.Grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val density = LocalDensity.current
        val selIndex = store.gradients.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val stripScroll = rememberRememberedScroll("gradient-strip") {
            with(density) { ((56.dp + Dimens.Grid).toPx() * selIndex - 56.dp.toPx()).roundToInt().coerceAtLeast(0) }
        }
        // Лента выбирает только карту градиента; полный набор (карта + параметры) — «Пресеты»
        Text("Градиент", color = TextSecondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(end = Dimens.Grid).rotateWithDevice())
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(stripScroll),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Grid),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            store.gradients.forEach { g ->
                val isSelected = g.id == selectedId
                Box(
                    Modifier
                        .size(width = 56.dp, height = Dimens.PillVisualHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(gradientBrush(g.stops))
                        .then(if (isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(10.dp)) else Modifier)
                        .previewPress(onClick = { onSelect(g) }, onPreview = onPreview?.let { cb -> { on: Boolean -> cb(g, on) } })
                        .semantics { contentDescription = "Градиент ${g.name}" },
                )
            }
        }
        if (onEdit != null) {
            TextButton(onClick = onEdit, colors = appTextButtonColors()) { Text("изменить", fontSize = 13.sp, modifier = Modifier.rotateWithDevice()) }
        }
    }
}
