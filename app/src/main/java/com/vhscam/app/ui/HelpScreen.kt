package com.vhscam.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.effect.BuiltInPresets
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.CoresParams
import com.vhscam.app.effect.CoreKind
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.vhscam.app.effect.FrameFormat
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.EnableHelp
import com.vhscam.app.effect.ModeSpec
import com.vhscam.app.effect.ParamHelp
import com.vhscam.app.effect.ParamSpec
import com.vhscam.app.effect.ToggleSpec
import com.vhscam.app.effect.specFor
import com.vhscam.app.gl.OfflineRenderer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Справка по ползункам режима. Каждый эффект показан на программной тестовой картинке через тот же
 * офлайн-рендерер, что и фото: остальные эффекты нейтральны, рабочие настройки пользователя не трогаются.
 * Работает без разрешения на камеру.
 */
@Composable
fun HelpScreen(initialMode: EffectMode, onBack: () -> Unit, onOpenCameraInfo: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val testImage = remember { TestImage.create() }
    val renderer = remember { OfflineRenderer(context, testImage) }
    DisposableEffect(Unit) { onDispose { renderer.release() } }

    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val spec = specFor(mode)
    // У «Cores» справка показывает выбранное ядро: описание, категории, пример пресета
    var helpCore by rememberSaveable { mutableStateOf(CoreKind.DREAM) }
    val isCores = mode == EffectMode.CORES
    val group = if (isCores) helpCore.name else ""
    val adjust: (Any) -> Any = { p -> if (p is CoresParams) p.copy(core = helpCore) else p }
    val viewParams = adjust(spec.neutral)
    val categories = spec.categoriesOf(viewParams)

    var diceHelp by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTouch(description = "Назад", onClick = onBack) {
                Text("←", color = TextPrimary, fontSize = 22.sp)
            }
            Text("Справка", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            // Потолок устройства: какие размеры фото и видео камера отдаёт приложению
            TextButton(onClick = onOpenCameraInfo, colors = appTextButtonColors()) { Text("Камеры", fontSize = 13.sp) }
        }
        // Режимы — отдельной строкой: чипы и «Камеры» в заголовок не помещаются
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState())) {
            EffectMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m && !diceHelp,
                    onClick = { mode = m; diceHelp = false },
                    label = { Text(m.title) },
                    colors = appChipColors(),
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            FilterChip(
                selected = diceHelp,
                onClick = { diceHelp = true },
                label = { Text("Кубик") },
                colors = appChipColors(),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (isCores) {
            FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                CoreKind.shown.forEach { core ->
                    FilterChip(
                        selected = helpCore == core,
                        onClick = { helpCore = core },
                        label = { Text(core.title, fontSize = 13.sp) },
                        colors = appChipColors(),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }

        if (diceHelp) {
            Text(DICE_HELP, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.padding(12.dp))
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            item(key = "intro-$mode-$group") {
                Column {
                    Text(spec.introOf(viewParams), color = TextPrimary, fontSize = 15.sp)
                    Text(
                        "На картинках слева — без эффекта, справа — с эффектом; разделитель можно двигать пальцем. " +
                            "Ползунок под картинкой меняет только этот эффект и не трогает ваши настройки камеры. " +
                            "На камере долгое нажатие на превью временно показывает оригинал.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            categories.forEach { category ->
                item(key = "cat-$mode-$group-${category.title}") {
                    Text(
                        category.title,
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (category.hasEnableSwitch) {
                    item(key = "enable-$mode") { HelpCard("Эффект вкл/выкл", EnableHelp) }
                }
                category.toggles.forEach { toggle ->
                    item(key = "t-$mode-$group-${category.title}-${toggle.label}") {
                        ToggleHelpCard(mode, spec, toggle, testImage, renderer)
                    }
                }
                category.params.forEach { param ->
                    item(key = "p-$mode-$group-${category.title}-${param.label}") {
                        ParamHelpCard(mode, spec, param, testImage, renderer, stateKey = "$group-${category.title}", adjust = adjust)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamHelpCard(
    mode: EffectMode,
    spec: ModeSpec<Any>,
    param: ParamSpec<Any>,
    original: Bitmap,
    renderer: OfflineRenderer,
    stateKey: String = "",
    adjust: (Any) -> Any = { it },
) {
    // Демо параметров ядра уже выбирает своё ядро; общие категории «Cores» («Общее» первая, «Шум» последняя)
    // показываются на ядре, выбранном в справке
    val base = param.demoBase?.invoke(spec.neutral)?.let { raw ->
        val shared = spec.categoriesOf(raw).let { c -> param in c.first().params || param in c.last().params }
        if (shared) adjust(raw) else raw
    }
    HelpCard(param.label, param.help) {
        if (base == null) return@HelpCard
        var value by remember(mode, stateKey, param.label) { mutableFloatStateOf(param.get(base)) }
        var rendered by remember(mode, stateKey, param.label) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(mode, stateKey, param.label, value) {
            delay(20) // при перетаскивании ползунка рисуем только последнее значение
            val settings = spec.put(EffectSettings(mode = mode), spec.setEnabled(param.set(base, value), true))
            rendered = renderer.render(settings)
        }
        CompareImage(original, rendered)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = { value = it },
                valueRange = param.range,
                steps = param.steps,
                colors = appSliderColors(),
                modifier = Modifier.weight(1f),
            )
            Text(
                param.format(value),
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.width(56.dp).padding(start = 8.dp),
            )
        }
    }
}

/** Справка по переключателю (оверлей «VHS»): демо с выключателем под картинкой. */
@Composable
private fun ToggleHelpCard(
    mode: EffectMode,
    spec: ModeSpec<Any>,
    toggle: ToggleSpec<Any>,
    original: Bitmap,
    renderer: OfflineRenderer,
) {
    val base = toggle.demoBase?.invoke(spec.neutral)
    HelpCard(toggle.label, toggle.help) {
        if (base == null) return@HelpCard
        var on by remember(mode, toggle.label) { mutableStateOf(true) }
        var rendered by remember(mode, toggle.label) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(mode, toggle.label, on) {
            val settings = spec.put(EffectSettings(mode = mode), spec.setEnabled(toggle.set(base, on), true))
            rendered = renderer.render(settings)
        }
        CompareImage(original, rendered)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "Вкл" else "Выкл", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it })
        }
    }
}

@Composable
private fun HelpCard(title: String, help: ParamHelp, demo: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Text(help.what, color = TextPrimary, fontSize = 14.sp)
        Text("Мало — " + help.low, color = TextSecondary, fontSize = 13.sp)
        Text("Много — " + help.high, color = TextSecondary, fontSize = 13.sp)
        Text("Когда пригодится: " + help.whenUseful, color = TextSecondary, fontSize = 13.sp)
        Box(modifier = Modifier.padding(top = 6.dp)) {
            Column { demo() }
        }
    }
}

/** Картинка с перетаскиваемым разделителем: слева без эффекта, справа с эффектом. */
@Composable
private fun CompareImage(original: Bitmap, effected: Bitmap?) {
    val originalImage = remember(original) { original.asImageBitmap() }
    val effectedImage = remember(effected) { effected?.asImageBitmap() }
    var split by remember { mutableFloatStateOf(0.5f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(original.width.toFloat() / original.height)
            .clip(RoundedCornerShape(10.dp))
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { split = (it.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) {
                // Только горизонтальное перетаскивание: вертикальный жест остаётся прокрутке списка
                detectHorizontalDragGestures { change, _ ->
                    split = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        Image(originalImage, contentDescription = "Без эффекта", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        if (effectedImage != null) {
            Image(
                effectedImage,
                contentDescription = "С эффектом",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(left = split * size.width) { this@drawWithContent.drawContent() }
                    },
            )
        }
        val x = with(density) { (split * widthPx).toDp() }
        Box(
            modifier = Modifier
                .offset(x = x - 1.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = x - 14.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text("⇆", color = Color.Black, fontSize = 14.sp)
        }
        Label("без эффекта", Modifier.align(Alignment.TopStart))
        Label("с эффектом", Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text,
        color = TextPrimary,
        fontSize = 11.sp,
        modifier = modifier
            .padding(6.dp)
            .background(Scrim, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Справка по «Кубику» (режим редактора, не эффект — демо на картинке нет). */
private const val DICE_HELP = "«Кубик» (только редактор фото и видео) — случайная цепочка эффектов. Выберите число слоёв " +
    "(3, 5, 7 или 10) и нажмите «Бросить»: из всех режимов (Пиксель, Плёнка, VHS с ЭЛТ, каждое ядро Cores, Градиент — " +
    "готовый или из случайных точек, Газета, Выбор цвета; Гостинг — только на видео) выбираются случайные, " +
    "с случайными, но безопасными значениями ползунков, непрозрачностью 50–100% и в случайном порядке; один режим — " +
    "не больше двух раз. Если кадр получился чёрным, белым или однотонным, кубик сам перебрасывает до трёх раз. " +
    "Дальше это обычные слои: их можно менять, переставлять, скрывать, удалять и добавлять свои. Замок на слое " +
    "оставляет его при следующем броске. Seed броска показан в панели: нажмите на него, чтобы бросить по своему числу. " +
    "«Скопировать набор» — код всей цепочки слоёв (MYLAR1:), «Вставить набор» — цепочка из кода."
