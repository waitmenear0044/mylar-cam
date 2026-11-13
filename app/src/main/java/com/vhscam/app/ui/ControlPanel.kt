package com.vhscam.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Constraints
import com.vhscam.app.R
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.effect.CoreKind
import com.vhscam.app.effect.EffectCategory
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.ParamSpec
import com.vhscam.app.effect.PresetManager
import com.vhscam.app.effect.specFor
import kotlin.math.roundToInt

private val LABEL_SIZE = 13.sp
private const val LABEL_MS = 1500L
private val PANEL_END_INSET = 12.dp


/** Номер открытой категории для текущего режима и ядра (null — панель закрыта или открыта у другого режима). */
private fun openCategoryIndex(settings: EffectSettings, openCategory: Pair<String, Int>?): Int? {
    val spec = specFor(settings.mode)
    val modeParams = spec.get(settings)
    val key = settings.mode.name + ":" + spec.groupOf(modeParams).orEmpty()
    return openCategory?.takeIf { it.first == key }?.second?.takeIf { it in spec.categoriesOf(modeParams).indices }
}

/**
 * Панели поверх превью: пока палец тянет ползунок, панель становится заметно прозрачнее, а остальные строки гаснут —
 * видно, как меняется картинка. [active] — ключ ползунка, который сейчас тянут (null — никакой).
 */
@Stable
class PanelDim {
    var active by mutableStateOf<String?>(null)
}

val LocalPanelDim = compositionLocalOf<PanelDim?> { null }

/** Фон панели: поверх превью — полупрозрачный, при перетаскивании ползунка — почти прозрачный. */
@Composable
fun panelBackground(): Color {
    val dim = LocalPanelDim.current ?: return PanelBackground
    val alpha by animateFloatAsState(if (dim.active != null) PANEL_DRAG_ALPHA else PANEL_OVERLAY_ALPHA, tween(PANEL_DIM_MS), label = "panelAlpha")
    return PanelBackground.copy(alpha = alpha)
}

/** Строка панели гаснет, пока тянут другой ползунок ([key] — ключ своего ползунка; null — у строки ползунка нет). */
fun Modifier.dimWhileDragging(key: String? = null): Modifier = composed {
    val dim = LocalPanelDim.current ?: return@composed this
    val alpha by animateFloatAsState(if (dim.active != null && dim.active != key) 0.08f else 1f, tween(PANEL_DIM_MS), label = "rowAlpha")
    if (alpha == 1f) this else this.alpha(alpha)
}

/** Источник взаимодействий ползунка: пока его держат, панели приглушены; через ~300 мс после отпускания возвращаются. */
@Composable
fun rememberSliderHold(key: String): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    val dim = LocalPanelDim.current ?: return source
    val dragged by source.collectIsDraggedAsState()
    val pressed by source.collectIsPressedAsState()
    val held = dragged || pressed
    LaunchedEffect(held) {
        if (held) {
            dim.active = key
        } else if (dim.active == key) {
            delay(PANEL_RESTORE_MS)
            dim.active = null
        }
    }
    DisposableEffect(key) { onDispose { if (dim.active == key) dim.active = null } }
    return source
}

/** Короткий ярлык с названием выбранного режима или ядра (значки без подписей), общий для полосы и панелей. */
@Stable
class EffectUiState {
    var label by mutableStateOf<String?>(null)
        private set
    var labelKey by mutableIntStateOf(0)
        private set
    /**
     * Элементы управления эффектом свёрнуты (повторное нажатие на выбранный режим): остаются полоса режимов и блок
     * спуска. Открытая категория и список пресетов при этом не сбрасываются. После перезапуска — развёрнуто.
     */
    var collapsed by mutableStateOf(false)

    fun show(text: String) {
        label = text
        labelKey++
    }

    fun hide() {
        label = null
    }
}

/**
 * Постоянная часть управления эффектом (высота не меняется, превью от неё не зависит): значки режимов с кнопкой
 * «Пресеты» и полоса категорий. Открытая категория хранится у вызывающего ([openCategory] — режим/ядро и номер).
 */
@Composable
fun EffectModeBar(
    settings: EffectSettings,
    onUpdate: (EffectSettings) -> Unit,
    openCategory: Pair<String, Int>?,
    onOpenCategory: (Pair<String, Int>?) -> Unit,
    presetsOpen: Boolean,
    onTogglePresets: () -> Unit,
    ui: EffectUiState,
    modifier: Modifier = Modifier,
    modes: List<EffectMode> = EffectMode.camera,
    extra: ModeBarExtra? = null,
    noneSelected: Boolean = false,
) {
    val spec = specFor(settings.mode)
    val modeParams = spec.get(settings)
    // Открытая категория привязана к режиму (и ядру «Cores»): при их смене панель закрывается
    val categories = spec.categoriesOf(modeParams)
    val categoryKey = settings.mode.name + ":" + spec.groupOf(modeParams).orEmpty()
    val selected = openCategoryIndex(settings, openCategory)
    LaunchedEffect(ui.labelKey) { if (ui.label != null) { delay(LABEL_MS); ui.hide() } }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.HalfGrid)) {
        // Режимы и «Пресеты»: ячейки 48dp, равные промежутки. Если режимы не помещаются (узкий экран, редактор
        // с редакторскими режимами), прокручиваются только они — «Пресеты» всегда на месте справа
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fits = Dimens.IconButton * (modes.size + 1 + (if (extra != null) 1 else 0)) <= maxWidth
            val chips = @Composable {
                modes.forEach { mode ->
                    IconChip(
                        icon = modeIcon(mode),
                        description = mode.title,
                        selected = settings.mode == mode && extra?.selected != true && !noneSelected,
                        onClick = {
                            if (noneSelected && extra?.selected != true) {
                                // Ни один режим ещё не выбран (чистый редактор): нажатие выбирает режим, даже текущий
                                ui.show(mode.title)
                                ui.collapsed = false
                                onUpdate(settings.copy(mode = mode))
                            } else if (extra?.selected == true) {
                                // Открыт «Кубик»: нажатие на режим закрывает его (и переключает режим слоя, если другой)
                                extra.onClick()
                                if (mode != settings.mode) {
                                    ui.show(mode.title)
                                    onUpdate(settings.copy(mode = mode))
                                }
                            } else if (mode != settings.mode) {
                                ui.show(mode.title)
                                ui.collapsed = false
                                onUpdate(settings.copy(mode = mode))
                            } else {
                                ui.collapsed = !ui.collapsed
                            }
                        },
                    )
                }
                extra?.let { e -> IconChip(icon = e.icon, description = e.description, selected = e.selected, onClick = e.onClick) }
            }
            val star = @Composable {
                IconTouch(description = "Пресеты", onClick = {
                    // В свёрнутом виде ★ разворачивает элементы и открывает список
                    if (ui.collapsed) {
                        ui.collapsed = false
                        if (!presetsOpen) onTogglePresets()
                    } else {
                        onTogglePresets()
                    }
                }) {
                    Box(Modifier.rotateWithDevice()) { StarGlyph(if (presetsOpen && !ui.collapsed) Accent else TextPrimary, 20.dp) }
                }
            }
            if (fits) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    chips()
                    star()
                }
            } else {
                // Прокрутка полосы запоминается (своя у камеры и у каждого слоя редактора); впервые — к выбранному режиму
                val density = LocalDensity.current
                val selIndex = if (extra?.selected == true) modes.size else modes.indexOf(settings.mode).coerceAtLeast(0)
                val modeScroll = rememberRememberedScroll("modes:" + modes.joinToString(",") { it.name }) {
                    with(density) { ((Dimens.IconButton + Dimens.HalfGrid).toPx() * selIndex - Dimens.IconButton.toPx() * 1.5f).roundToInt().coerceAtLeast(0) }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(modeScroll),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.HalfGrid),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { chips() }
                    star()
                }
            }
        }
        // Название выбранного режима или ядра — коротким ярлыком поверх полосы категорий (не поверх превью).
        // Свёрнутая полоса гаснет, но место за собой оставляет: превью не меняет размер
        val stripAlpha by animateFloatAsState(if (ui.collapsed) 0f else 1f, tween(COLLAPSE_MS), label = "strip")
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.alpha(stripAlpha)) {
                CategoryStrip(
                    categories = categories,
                    selected = selected,
                    onSelect = { index -> if (!ui.collapsed) onOpenCategory(if (selected == index) null else categoryKey to index) },
                )
            }
            if (ui.collapsed) Box(Modifier.matchParentSize().absorbTouches())
            // Во время предпросмотра долгим нажатием — название того, что показано на превью
            TransientLabel(LocalPresetPreview.current?.label ?: ui.label)
        }
    }
}

/**
 * Панели, которые лежат поверх нижней части превью (на его размер не влияют): значки ядер «Cores», панель открытой
 * категории с ползунками, ряд активного пресета. Фон полупрозрачный; пока тянут ползунок — почти прозрачный.
 */
@Composable
fun EffectOverlayPanels(
    settings: EffectSettings,
    presets: PresetManager,
    onUpdate: (EffectSettings) -> Unit,
    openCategory: Pair<String, Int>?,
    onOpenCategory: (Pair<String, Int>?) -> Unit,
    onOpenPresets: () -> Unit,
    ui: EffectUiState,
    modifier: Modifier = Modifier,
    showPreset: Boolean = true,
    onEditGradient: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val spec = specFor(settings.mode)
    val modeParams = spec.get(settings)
    val categories = spec.categoriesOf(modeParams)
    val selected = openCategoryIndex(settings, openCategory)
    val dim = remember { PanelDim() }
    val presetPreview = LocalPresetPreview.current
    // Пока идёт предпросмотр долгим нажатием, панели приглушены, как при перетаскивании ползунка
    LaunchedEffect(presetPreview?.label) {
        if (presetPreview?.label != null) dim.active = PREVIEW_DIM_KEY else if (dim.active == PREVIEW_DIM_KEY) dim.active = null
    }
    CompositionLocalProvider(LocalPanelDim provides dim) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.HalfGrid)) {
            // Ядра «Cores»: переключение мгновенное, параметры каждого ядра хранятся отдельно
            if (settings.mode == EffectMode.CORES) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dimWhileDragging()
                        .clip(RoundedCornerShape(Dimens.SheetCorner))
                        .background(panelBackground())
                        .absorbTouches(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoreKind.shown.forEach { core ->
                        IconChip(
                            icon = coreIcon(core),
                            description = core.title,
                            selected = settings.cores.core == core,
                            onClick = {
                                ui.show(core.title)
                                if (core != settings.cores.core) onUpdate(settings.copy(cores = settings.cores.copy(core = core)))
                            },
                            onPreview = presetPreview.handler(core.title, settings, { settings.copy(cores = settings.cores.copy(core = core)) }, onUpdate),
                        )
                    }
                }
            }
            // «Градиент»: лента градиентов набора и вход в редактор
            if (settings.mode == EffectMode.GRADIENT) {
                GradientStrip(
                    selectedId = settings.gradient.gradientId,
                    onSelect = { onUpdate(settings.copy(gradient = settings.gradient.with(it))) },
                    onEdit = onEditGradient,
                    onPreview = presetPreview?.let { _ ->
                        { g: com.vhscam.app.effect.Gradient, on: Boolean ->
                            presetPreview.handler(g.name, settings, { settings.copy(gradient = settings.gradient.with(g)) }, onUpdate)?.invoke(on)
                        }
                    },
                )
            }
            extra()
            AnimatedVisibility(visible = selected != null) {
                val index = selected ?: return@AnimatedVisibility
                val category = categories[index]
                // У держащего телефон боком панель целиком поворачивается и читается как обычная
                TurnWithDevice { turned ->
                    CategoryPanel(
                        category = category,
                        params = modeParams,
                        enabled = spec.isEnabled(modeParams),
                        onEnabledChange = { on -> onUpdate(spec.put(settings, spec.setEnabled(modeParams, on))) },
                        onParamsChange = { onUpdate(spec.put(settings, it)) },
                        onReset = { onUpdate(spec.put(settings, category.reset(modeParams, spec.defaults))) },
                        onClose = { onOpenCategory(null) },
                        scrollable = turned,
                    )
                }
            }
            if (showPreset) {
                val active = presets.active(settings)
                if (active != null) {
                    PresetStrengthRow(
                        name = active.name,
                        modified = presets.isModified(settings),
                        strength = settings.presetStrength(),
                        onStrengthChange = { onUpdate(settings.withPresetStrength(settings.mode, it)) },
                        onClear = { onUpdate(presets.clear(settings)) },
                        onOpen = onOpenPresets,
                        onPreview = presetPreview.handler(active.name, settings, { presets.preview(active, settings) }, onUpdate),
                    )
                } else if (settings.mode == EffectMode.GRADIENT) {
                    // «Градиент» без пресета: эффект активен, кнопка «Пресеты» показывает «Свой»
                    PresetStrengthRow(
                        name = "Пресеты: свой",
                        modified = false,
                        strength = settings.presetStrength(),
                        onStrengthChange = { onUpdate(settings.withPresetStrength(settings.mode, it)) },
                        onClear = null,
                        onOpen = onOpenPresets,
                    )
                }
            }
        }
    }
}

/** Дополнительный значок в конце полосы режимов (в редакторе — «Кубик»). */
class ModeBarExtra(val icon: Int, val description: String, val selected: Boolean, val onClick: () -> Unit)

/** Панели эффекта поверх превью, которые прячутся при сворачивании ([EffectUiState.collapsed]). */
@Composable
fun CollapsibleEffectControls(ui: EffectUiState, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = !ui.collapsed,
        enter = fadeIn(tween(COLLAPSE_MS)) + slideInVertically(tween(COLLAPSE_MS)) { it / 4 },
        exit = fadeOut(tween(COLLAPSE_MS)) + slideOutVertically(tween(COLLAPSE_MS)) { it / 4 },
    ) {
        Column { content() }
    }
}

private const val COLLAPSE_MS = 180

/** Касания по фону панели не уходят в превью под ней (тап-фокус, долгое нажатие). */
fun Modifier.absorbTouches(): Modifier = pointerInput(Unit) { detectTapGestures { } }

private const val PANEL_OVERLAY_ALPHA = 0.8f
private const val PREVIEW_DIM_KEY = "preset-preview"
private const val PANEL_DRAG_ALPHA = 0.25f
private const val PANEL_DIM_MS = 120
private const val PANEL_RESTORE_MS = 300L

fun modeIcon(mode: EffectMode): Int = when (mode) {
    EffectMode.PIXEL -> R.drawable.ic_mode_pixel
    EffectMode.FILM -> R.drawable.ic_mode_film
    EffectMode.VHS -> R.drawable.ic_mode_vhs
    EffectMode.CORES -> R.drawable.ic_mode_cores
    EffectMode.GRADIENT -> R.drawable.ic_mode_gradient
    EffectMode.GHOST -> R.drawable.ic_mode_ghost
    EffectMode.NEWSPAPER -> R.drawable.ic_mode_newspaper
    EffectMode.COLOR -> R.drawable.ic_mode_color
}

private fun coreIcon(core: CoreKind): Int = when (core) {
    CoreKind.DREAM -> R.drawable.ic_core_dream
    CoreKind.SUN -> R.drawable.ic_core_sun
    CoreKind.DARK -> R.drawable.ic_core_dark
    CoreKind.CIRCUIT -> R.drawable.ic_core_circuit
}

/**
 * Значок-переключатель без подписи: 24dp в таблетке, зона нажатия 48dp. Выбранный — акцентный значок в акцентной
 * рамке, остальные — белые с прозрачностью. Значок поворачивается вместе с телефоном, размер не меняется.
 */
@Composable
fun IconChip(icon: Int, description: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, onPreview: ((Boolean) -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(Dimens.IconButton)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onPreview != null && enabled) Modifier.previewPress(onClick = onClick, onPreview = onPreview) else Modifier.clickable(enabled = enabled, onClick = onClick))
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.IconHighlight)
                .clip(RoundedCornerShape(14.dp))
                .then(if (selected) Modifier.border(1.5.dp, Accent, RoundedCornerShape(14.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = when {
                    selected -> Accent
                    enabled -> TextSecondary
                    else -> TextDisabled
                },
                modifier = Modifier.size(Dimens.Icon).rotateWithDevice(),
            )
        }
    }
}

/** Короткий ярлык с названием выбранного значка (касания проходят сквозь него). */
@Composable
private fun TransientLabel(text: String?) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
        var shown by remember { mutableStateOf(text.orEmpty()) }
        if (text != null) shown = text
        Text(
            shown,
            color = TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(Dimens.PillVisualHeight / 2))
                .background(PanelSelected)
                .padding(horizontal = Dimens.Grid * 2, vertical = Dimens.Grid),
        )
    }
}

/**
 * Блок, который поворачивается на угол телефона целиком вокруг своего центра: содержимое компонуется как в портрете,
 * но в рамке с поменянными шириной и высотой, поэтому в повёрнутом виде занимает ту же область и не обрезается.
 * Место блока в раскладке не меняется (повёрнутый — не ниже [minTurnedHeight], чтобы строки поместились по ширине).
 * [content] получает turned = true в повёрнутом положении (тогда содержимое должно уметь прокручиваться).
 */
@Composable
fun TurnWithDevice(modifier: Modifier = Modifier, minTurnedHeight: Dp = 220.dp, content: @Composable (turned: Boolean) -> Unit) {
    val angle = LocalUiRotation.current
    val turned = abs((angle / 90f).roundToInt()) % 2 == 1
    if (angle % 360f == 0f) {
        Box(modifier) { content(false) }
        return
    }
    SubcomposeLayout(modifier) { constraints ->
        // Размер, который блок занимал бы в портрете, — по невидимой портретной копии
        val loose = constraints.copy(minHeight = 0)
        val portrait = subcompose("portrait") { content(false) }.map { it.measure(loose) }
        val w = portrait.maxOfOrNull { it.width } ?: 0
        var h = portrait.maxOfOrNull { it.height } ?: 0
        if (turned) h = maxOf(h, minTurnedHeight.roundToPx()).coerceAtMost(constraints.maxHeight)
        val inner = if (turned) Constraints.fixed(h, w) else Constraints.fixed(w, h)
        val placeables = subcompose("shown") { content(turned) }.map { it.measure(inner) }
        layout(w, h) {
            placeables.forEach { p ->
                p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) { rotationZ = angle }
            }
        }
    }
}

/** Полоса значков-категорий (без подписей; название — в шапке открытой панели и в описании для доступности). */
@Composable
fun CategoryStrip(
    categories: List<EffectCategory<Any>>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Другой набор категорий (режим, ядро) — полоса снова с начала
    val scroll = remember(categories.map { it.title }) { ScrollState(0) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.SheetCorner))
            .background(PanelBackground)
            .padding(vertical = Dimens.HalfGrid),
    ) {
        val fits = Dimens.IconButton * categories.size + Dimens.ItemGap * (categories.size + 1) <= maxWidth
        Row(
            modifier = if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll).padding(horizontal = Dimens.ItemGap),
            horizontalArrangement = if (fits) Arrangement.SpaceEvenly else Arrangement.spacedBy(Dimens.ItemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            categories.forEachIndexed { index, category ->
                val isSelected = selected == index
                Box(
                    modifier = Modifier
                        .size(Dimens.IconButton)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) PanelSelected else Color.Transparent)
                        .clickable { onSelect(index) }
                        .semantics {
                            contentDescription = category.title
                            this.selected = isSelected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.rotateWithDevice()) {
                        CategoryGlyph(category.icon, if (isSelected) Accent else TextPrimary, Dimens.Icon)
                    }
                }
            }
        }
    }
}

/** Компактная панель ползунков одной категории (до 4), с «Сбросить» и «✕». */
@Composable
fun CategoryPanel(
    category: EffectCategory<Any>,
    params: Any,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onParamsChange: (Any) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(panelBackground())
            .absorbTouches()
            .then(if (scrollable) Modifier.fillMaxHeight().verticalScroll(rememberScrollState()) else Modifier)
            .padding(start = Dimens.Grid * 2, end = Dimens.HalfGrid, bottom = Dimens.Grid),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(Dimens.HeaderHeight).dimWhileDragging(), verticalAlignment = Alignment.CenterVertically) {
            if (category.swatch != null) {
                // Выбранный цвет слота кружком (выключенный слот — пустой контур)
                val color = category.swatch.invoke(params)
                Box(
                    Modifier.padding(end = Dimens.Grid).size(18.dp).clip(CircleShape)
                        .then(if (color != null) Modifier.background(Color(color)) else Modifier)
                        .border(1.5.dp, TextSecondary, CircleShape),
                )
            }
            Text(
                category.title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (category.params.isNotEmpty() || category.toggles.isNotEmpty() || category.custom != null) {
                TextButton(onClick = onReset, colors = appTextButtonColors()) { Text("Сбросить", fontSize = 13.sp) }
            }
            IconTouch(description = "Свернуть", onClick = onClose) {
                Text("✕", color = TextPrimary, fontSize = 16.sp)
            }
        }
        if (category.hasEnableSwitch) {
            SwitchRow("Эффект", enabled, onEnabledChange)
        }
        category.toggles.forEach { toggle ->
            SwitchRow(toggle.label, toggle.get(params)) { onParamsChange(toggle.set(params, it)) }
        }
        category.params.forEach { spec ->
            CompactSlider(spec, params, onParamsChange)
        }
        // Особое содержимое категории: редактор кривой ЧБ-части «Выбора цвета»
        if (category.custom == "curve") {
            val p = params as com.vhscam.app.effect.ColorSelectParams
            CurveEditor(points = p.curve, onChange = { onParamsChange(p.copy(curve = it)) }, modifier = Modifier.padding(end = PANEL_END_INSET))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp).dimWhileDragging(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(end = PANEL_END_INSET))
    }
}

/** Строка ползунка в две строки: название слева и значение справа, под ними ползунок на всю ширину панели. */
@Composable
private fun CompactSlider(spec: ParamSpec<Any>, params: Any, onChange: (Any) -> Unit) {
    val value = spec.get(params)
    val hold = rememberSliderHold(spec.label)
    // Справа отступ до 16dp, как слева (у панели справа 4dp — под «✕» в шапке)
    Column(modifier = Modifier.fillMaxWidth().padding(end = PANEL_END_INSET).dimWhileDragging(spec.label)) {
        Row(modifier = Modifier.fillMaxWidth().height(Dimens.SliderLabelRow), verticalAlignment = Alignment.CenterVertically) {
            Text(
                spec.label,
                color = TextPrimary,
                fontSize = LABEL_SIZE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(spec.format(value), color = TextSecondary, fontSize = LABEL_SIZE, textAlign = TextAlign.End, maxLines = 1)
        }
        Slider(
            value = value,
            onValueChange = { onChange(spec.set(params, it)) },
            valueRange = spec.range,
            steps = spec.steps,
            colors = appSliderColors(),
            interactionSource = hold,
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.SliderTouch),
        )
    }
}

/** Активный пресет: название с «×» (сброс к нейтральным) и ползунок «Сила пресета» 0–150%. */
@Composable
fun PresetStrengthRow(
    name: String,
    modified: Boolean,
    strength: Float,
    onStrengthChange: (Float) -> Unit,
    onClear: (() -> Unit)?,
    onOpen: () -> Unit,
    onPreview: ((Boolean) -> Unit)? = null,
) {
    val hold = rememberSliderHold("preset-strength")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dimWhileDragging("preset-strength")
            .clip(RoundedCornerShape(14.dp))
            .background(panelBackground())
            .absorbTouches()
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 136.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .previewPress(onClick = onOpen, onPreview = onPreview)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                name,
                color = Accent,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (modified) ModifiedDot()
        }
        if (onClear != null) IconTouch(description = "Снять пресет", onClick = onClear) { Text("×", color = TextPrimary, fontSize = 18.sp) }
        Slider(
            value = strength,
            onValueChange = onStrengthChange,
            valueRange = 0f..1.5f,
            colors = appSliderColors(),
            interactionSource = hold,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Text("${(strength * 100).roundToInt()}%", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.widthIn(min = 38.dp))
    }
}

/** Зона нажатия не меньше [size] (48dp) вокруг небольшой иконки. */
@Composable
fun IconTouch(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Таблетка-кнопка (формат, качество, «FX»): видимая часть ~30dp, зона нажатия 48dp по высоте. */
@Composable
fun Pill(
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp? = null,
    color: Color = if (enabled) TextPrimary else TextDisabled,
    background: Color = PanelRaised,
    content: (@Composable () -> Unit)? = null,
) {
    // Задана ширина — видимая подложка заполняет её целиком: таблетки одной группы выглядят одним размером
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(Dimens.PillHeight)
            .clip(RoundedCornerShape(Dimens.PillVisualHeight / 2))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(if (width != null) Modifier.fillMaxWidth() else Modifier)
                .height(Dimens.PillVisualHeight)
                .rotateWithDevice(maxHeight = Dimens.PillHeight - 2.dp)
                .clip(RoundedCornerShape(Dimens.PillVisualHeight / 2))
                .background(background)
                .padding(horizontal = Dimens.PillTextPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (content != null) content() else Text(text, color = color, fontSize = Dimens.PillText, maxLines = 1)
        }
    }
}
