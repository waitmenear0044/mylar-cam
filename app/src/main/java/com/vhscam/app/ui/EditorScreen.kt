package com.vhscam.app.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.LocalView
import com.vhscam.app.videoimport.ExportCancelled
import com.vhscam.app.videoimport.ExportSize
import com.vhscam.app.videoimport.VideoExporter
import com.vhscam.app.videoimport.VideoInfo
import android.graphics.Rect
import android.net.Uri
import android.opengl.GLES30
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vhscam.app.videoimport.EditorVideoPlayer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.AppPrefs
import com.vhscam.app.R
import com.vhscam.app.StampSettings
import com.vhscam.app.camera.GalleryPhoto
import com.vhscam.app.camera.PhotoProcessor
import com.vhscam.app.camera.SourceImage
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.FrameFormat
import com.vhscam.app.effect.PresetManager
import com.vhscam.app.effect.SettingsStore
import com.vhscam.app.effect.specFor
import com.vhscam.app.gl.EditLayer
import com.vhscam.app.effect.LayerSetCode
import com.vhscam.app.effect.Dice
import com.vhscam.app.effect.DiceSet
import com.vhscam.app.effect.DiceSetStore
import com.vhscam.app.gl.EglCore
import com.vhscam.app.gl.LayerPreviewRenderer
import com.vhscam.app.gl.drawStamp
import com.vhscam.app.gl.stampTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Обработка готового фото из галереи слоями. Каждый слой — режим со своими настройками, пресетами и силой пресета
 * (как в камере, плюс редакторские «Газета», «Выбор цвета», «Макро») и непрозрачностью; слои применяются по порядку.
 * Превью «заперто», как в камере: панели лежат поверх его нижней части. Долгое нажатие по превью показывает
 * оригинал; короткое — пипетка («Выбор цвета») или точка фокуса («Макро») активного слоя.
 * «Сохранить» — полноразмерный тайловый проход по слоям ([PhotoProcessor.processLayers]).
 */
@Composable
fun EditorScreen(uri: Uri, onBack: () -> Unit, onOpenHelp: (EffectMode) -> Unit, onEditGradient: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Видео из галереи открывается в том же редакторе: превью — кадр на выбранной позиции, сохранение — офлайн-рендер
    // Тип — от провайдера; если он не сообщил (file://, часть файловых менеджеров) — по расширению
    val isVideo = remember(uri) {
        val type = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
        type?.startsWith("video") == true
    }
    var video by remember { mutableStateOf<VideoInfo?>(null) }
    var exporter by remember { mutableStateOf<VideoExporter?>(null) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportDialog by remember { mutableStateOf(false) }
    // Закрытие редактора: состояние сеанса (слои, «Кубик») не переживает закрытие — следующее открытие чистое
    val close = {
        EditorSession.clear()
        onBack()
    }
    BackHandler(onBack = { if (exporter != null) exporter?.cancelled = true else close() })
    val store = remember { SettingsStore(context) }
    val presets = remember { PresetManager(context) }
    val prefs = remember { AppPrefs.get(context) }
    val effectUi = remember { EffectUiState() }

    // Редактор стартует чистым: значения по умолчанию (не настройки камеры), эффект не выбран, пока не выбран режим
    val modes = if (isVideo) EffectMode.entries else EditLayer.photoModes
    val base = remember { EffectSettings() }
    // Слои переживают уход в справку и в редактор градиентов (экран при этом пересоздаётся) — до закрытия редактора
    var layers by remember {
        mutableStateOf(EditorSession.layers.takeIf { EditorSession.uri == uri && it.isNotEmpty() } ?: listOf(EditLayer(UUID.randomUUID().toString(), base)))
    }
    // Выбран ли эффект: до первого выбора режима (пресета, броска, набора) показывается исходный кадр
    var chosen by remember { mutableStateOf(EditorSession.uri == uri && EditorSession.chosen) }
    var activeId by remember { mutableStateOf(EditorSession.activeId.takeIf { id -> layers.any { it.id == id } } ?: layers.first().id) }
    LaunchedEffect(layers, activeId) {
        EditorSession.uri = uri
        EditorSession.layers = layers
        EditorSession.activeId = activeId
    }
    LaunchedEffect(chosen) { EditorSession.chosen = chosen }
    val active = layers.firstOrNull { it.id == activeId } ?: layers.first()
    // Новый активный слой — управление развёрнуто (кроме открытого «Кубика»: под ним категории не нужны)
    var diceShown by remember { mutableStateOf(false) }
    LaunchedEffect(activeId, chosen) { effectUi.collapsed = diceShown || !chosen }
    val settings = active.settings
    fun update(s: EffectSettings) {
        layers = layers.map { if (it.id == active.id) it.copy(settings = s) else it }
        chosen = true
    }

    val snackbar = remember { SnackbarHostState() }
    fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    // Загрузка фото и уменьшенной копии для превью (лимит — по текстуре этого GPU)
    var photo by remember { mutableStateOf<GalleryPhoto?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        if (isVideo) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { VideoInfo.load(context, uri).let { it to VideoInfo.frameAt(context, uri, 0L, VIDEO_PREVIEW_SIDE) } }
            }
            loaded.onSuccess { (info, frame) ->
                video = info
                preview = frame
                if (frame == null) loadError = "Не удалось прочитать кадр ролика"
            }.onFailure {
                Log.e("EditorScreen", "Cannot load video $uri", it)
                loadError = "Не удалось открыть видео"
            }
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val p = GalleryPhoto.load(context, uri)
                p to p.previewBitmap(minOf(PREVIEW_MAX_SIDE, maxTextureSize()))
            }
        }
        result.onSuccess { (p, b) ->
            photo = p
            preview = b
        }.onFailure {
            Log.e("EditorScreen", "Cannot load $uri", it)
            loadError = "Не удалось открыть фото"
        }
    }
    // Видео: плеер с цепочкой слоёв (воспроизведение, пауза, перемотка, звук). Создаётся, когда ролик прочитан,
    // освобождается при закрытии редактора
    val player = remember(video) { video?.let { info -> runCatching { EditorVideoPlayer(context, uri, info) }.onFailure { Log.e("EditorScreen", "player", it) }.getOrNull() } }
    DisposableEffect(player) { onDispose { player?.release() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Кадр на позиции паузы — для пипетки и миниатюр пресетов (подгружается с небольшой задержкой)
    val pausedAt = if (player != null && !player.isPlaying) player.positionMs else -1L
    LaunchedEffect(pausedAt) {
        if (!isVideo || video == null || pausedAt < 0) return@LaunchedEffect
        delay(300)
        withContext(Dispatchers.IO) { runCatching { VideoInfo.frameAt(context, uri, pausedAt, VIDEO_PREVIEW_SIDE) }.getOrNull() }?.let { preview = it }
    }
    val source = preview
    // У видео превью рисует плеер; статичный рендер слоёв по картинке — только у фото
    val renderer = remember(uri, source == null) { if (isVideo) null else source?.let { LayerPreviewRenderer(context, it) } }
    DisposableEffect(renderer) { onDispose { renderer?.release() } }
    LaunchedEffect(renderer, source) { source?.let { renderer?.setSource(it) } }

    // Формат: «Исходный» или формат из списка в ориентации фото (у альбомного фото 3:4 -> 4:3)
    var format by rememberSaveable { mutableStateOf<FrameFormat?>(null) }
    val landscape = (photo?.width ?: video?.width ?: 1) > (photo?.height ?: video?.height ?: 1)
    val cropAspect: Float? = format?.let { if (landscape) it.height.toFloat() / it.width else it.aspect }

    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    val stamp = prefs.stamp
    val takenMs = photo?.takenMs ?: video?.takenMs ?: System.currentTimeMillis()
    val renderLayers = if (chosen) layers.map { it.copy(settings = it.settings.effective()) } else emptyList()
    LaunchedEffect(renderer, source, renderLayers, cropAspect, stamp, takenMs) {
        val r = renderer ?: return@LaunchedEffect
        val src = source ?: return@LaunchedEffect
        delay(30) // при перетаскивании ползунка рисуем только последнее значение
        val full = r.render(renderLayers)
        val crop = cropRect(src.width, src.height, cropAspect)
        val bitmap = if (crop != null) Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height()).also { if (it !== full) full.recycle() } else full
        // Штамп даты — поверх готового кадра; по умолчанию дата съёмки файла
        drawStamp(bitmap, stamp, stampTime(stamp, takenMs))
        rendered = bitmap
    }
    LaunchedEffect(player, renderLayers, cropAspect, stamp, takenMs) {
        player?.update(renderLayers, cropAspect, stamp.takeIf { it.enabled }, stampTime(stamp, takenMs))
    }
    LaunchedEffect(player, showOriginal) { player?.setShowOriginal(showOriginal) }
    val originalCropped = remember(source, cropAspect) {
        source?.let { s -> cropRect(s.width, s.height, cropAspect)?.let { Bitmap.createBitmap(s, it.left, it.top, it.width(), it.height()) } ?: s }
    }
    // Миниатюры пресетов считаются на маленькой копии фото
    val thumbSource = remember(source) {
        source?.let { s ->
            val k = THUMB_SIDE.toFloat() / max(s.width, s.height)
            Bitmap.createScaledBitmap(s, (s.width * k).roundToInt().coerceAtLeast(2), (s.height * k).roundToInt().coerceAtLeast(2), true)
        }
    }

    var openCategory by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var presetsOpen by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var addLayer by remember { mutableStateOf(false) }
    // «Кубик»: панель, число слоёв, seed, идёт ли бросок
    var diceOpen by remember { mutableStateOf(false) }
    LaunchedEffect(diceOpen) { diceShown = diceOpen }
    var diceCount by remember { mutableStateOf(EditorSession.diceCount) }
    var diceSeed by remember { mutableStateOf(EditorSession.diceSeed) }
    var fromDice by remember { mutableStateOf(EditorSession.fromDice && EditorSession.uri == uri) }
    var rolling by remember { mutableStateOf(false) }
    var seedDialog by remember { mutableStateOf(false) }
    /** Сброс к исходному фото (видео): слои, эффекты и результат «Кубика» убираются, «Отменить» возвращает всё как было. */
    fun resetToOriginal() {
        val before = listOf(layers, activeId, chosen, fromDice, diceSeed)
        val fresh = EditLayer(UUID.randomUUID().toString(), base)
        layers = listOf(fresh)
        activeId = fresh.id
        chosen = false
        fromDice = false
        diceSeed = null
        presetsOpen = false
        layersOpen = false
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Возвращено исходное", actionLabel = "Отменить", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                @Suppress("UNCHECKED_CAST")
                layers = before[0] as List<EditLayer>
                activeId = before[1] as String
                chosen = before[2] as Boolean
                fromDice = before[3] as Boolean
                diceSeed = before[4] as Long?
            }
        }
    }

    // Сохранённые наборы слоёв «Кубика»
    val diceSets = remember { DiceSetStore.get(context) }
    var saveDialog by remember { mutableStateOf(false) }
    var savedActions by remember { mutableStateOf<DiceSet?>(null) }
    var renameSet by remember { mutableStateOf<DiceSet?>(null) }
    /** Набор слоёв из кода (вставка или сохранённый): слои удалённых режимов и «Гостинг» на фото пропускаются. */
    fun applySet(code: String, name: String?) {
        val decoded = LayerSetCode.decode(code, base)
        when {
            decoded == null -> message("Набор не читается")
            decoded.layers.isEmpty() -> message("В наборе нет доступных слоёв (режим удалён)")
            else -> {
                val usable = decoded.layers.filter { isVideo || it.settings.mode != EffectMode.GHOST }
                layers = usable.ifEmpty { decoded.layers }
                activeId = layers.first().id
                fromDice = true
                chosen = true
                diceSeed = decoded.seed
                decoded.count?.let { diceCount = it }
                val skipped = decoded.skipped + (decoded.layers.size - usable.size)
                val what = if (name != null) "Набор «$name» применён" else "Набор слоёв вставлен"
                message(if (skipped > 0) "$what, пропущено слоёв: $skipped (режим удалён или только для видео)" else what)
            }
        }
    }
    LaunchedEffect(diceCount, diceSeed, fromDice) {
        EditorSession.diceCount = diceCount
        EditorSession.diceSeed = diceSeed
        EditorSession.fromDice = fromDice
    }
    /**
     * Бросок: N случайных слоёв (слои с замком остаются). Результат проверяется на маленькой копии кадра:
     * средняя яркость вне 0.10–0.90 или почти нет контраста — переброс (до трёх раз), потом — как есть.
     */
    fun roll(seed: Long) {
        val src = source ?: return
        if (rolling) return
        rolling = true
        val current = layers
        scope.launch {
            val k = 256f / maxOf(src.width, src.height)
            val small = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).roundToInt().coerceAtLeast(2), (src.height * k).roundToInt().coerceAtLeast(2), true) else src
            val checker = LayerPreviewRenderer(context, small)
            try {
                var result = current
                for (attempt in 0..3) {
                    result = Dice.roll(seed, attempt, diceCount, isVideo, base, current)
                    val bmp = checker.render(result.map { it.copy(settings = it.settings.effective()) })
                    val alive = withContext(Dispatchers.Default) { Dice.looksAlive(bmp) }
                    bmp.recycle()
                    if (alive) break
                    Log.i("EditorScreen", "dice: seed $seed attempt $attempt looks dead, rerolling")
                }
                layers = result
                activeId = result.first().id
                diceSeed = seed
                fromDice = true
                chosen = true
            } finally {
                checker.release()
                rolling = false
            }
        }
    }
    var stampSheet by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Диалоги, листы и обработка ставят воспроизведение на паузу
    LaunchedEffect(player, exportDialog, stampSheet, addLayer, exporter != null) {
        if (exportDialog || stampSheet || addLayer || exporter != null) player?.pause()
    }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun exportVideo(size: ExportSize) {
        val info = video ?: return
        if (exporter != null) return
        // Уведомление о готовности: на Android 13+ нужно разрешение; без него просто не показываем
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val job = VideoExporter(context)
        exporter = job
        exportProgress = 0f
        val snapshot = renderLayers
        val aspect = cropAspect
        val name = "VID_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { job.export(uri, info, snapshot, size, aspect, stamp.takeIf { it.enabled }, name) { exportProgress = it } }
            }
            exporter = null
            result.onSuccess { label ->
                message("Сохранено: $label")
                notifyExportDone(context, label)
            }.onFailure {
                if (it is ExportCancelled) {
                    message("Обработка отменена, файл не сохранён")
                } else {
                    Log.e("EditorScreen", "Video export failed", it)
                    message("Не удалось обработать видео")
                }
            }
        }
    }

    fun save() {
        if (isVideo) {
            if (video != null && exporter == null) exportDialog = true
            return
        }
        val p = photo ?: return
        if (saving) return
        saving = true
        val snapshot = if (chosen) layers else emptyList()
        val aspect = cropAspect
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { processFull(context, p, snapshot, aspect, stamp.takeIf { it.enabled }) }
            }
            saving = false
            result.onSuccess { (label, reduced) ->
                message(if (reduced) "Сохранено (уменьшено из-за памяти): $label" else "Сохранено: $label")
            }.onFailure {
                Log.e("EditorScreen", "Save failed", it)
                message("Не удалось сохранить фото")
            }
        }
    }

    // Пипетка берёт цвет до эффекта активного слоя: результат слоёв под ним (у первого слоя — сама картинка)
    val activeIndex = layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val below = renderLayers.take(activeIndex)
    var belowRendered by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderer, source, below, settings.mode) {
        belowRendered = null
        if (settings.mode != EffectMode.COLOR || below.none { it.active }) return@LaunchedEffect
        renderer?.let { r -> belowRendered = r.render(below) }
    }
    val pickSource = belowRendered ?: source
    // Какой из трёх цветов: открытая категория «Цвет N», иначе первый
    val pickSlot = run {
        val spec = specFor(settings.mode)
        val title = openCategory?.second?.let { spec.categoriesOf(spec.get(settings)).getOrNull(it)?.title }
        title?.removePrefix("Цвет ")?.toIntOrNull()?.minus(1)?.takeIf { it in 0 until 3 } ?: 0
    }
    /** Пиксель картинки пипетки под точкой (u, v) видимой части кадра. */
    fun sourcePixel(u: Float, v: Float, src: Bitmap): Pair<Int, Int> {
        val rect = cropRect(src.width, src.height, cropAspect) ?: Rect(0, 0, src.width, src.height)
        val x = (rect.left + u * rect.width()).roundToInt().coerceIn(0, src.width - 1)
        val y = (rect.top + v * rect.height()).roundToInt().coerceIn(0, src.height - 1)
        return x to y
    }
    // Нажатие или отпускание пальца с лупой: пипетка «Выбора цвета» активного слоя (усреднение 5x5)
    val onImageTap by rememberUpdatedState { u: Float, v: Float ->
        val src = pickSource
        if (src != null && settings.mode == EffectMode.COLOR) {
            val (x, y) = sourcePixel(u, v, src)
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(averageColor(src, x, y), it) }
            val slot = pickSlot
            val p = settings.colorSelect
            update(settings.copy(colorSelect = p.withSlot(slot, p.slot(slot).copy(on = true, hue = hsv[0]))))
            message("Цвет ${slot + 1}: оттенок ${hsv[0].roundToInt()}°")
        }
    }
    // Лупа: пока палец ведёт по картинке в режиме «Выбор цвета»
    var loupe by remember { mutableStateOf<Offset?>(null) }
    var previewBox by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val pickMode by rememberUpdatedState(settings.mode == EffectMode.COLOR && pickSource != null)

    // Верх: назад, слои, штамп, формат, сохранить
    val header = @Composable {
        Row(modifier = Modifier.fillMaxWidth().height(Dimens.IconButton), verticalAlignment = Alignment.CenterVertically) {
            IconTouch(description = "Назад", onClick = close) { Text("←", color = TextPrimary, fontSize = 22.sp) }
            Box(
                modifier = Modifier
                    .size(Dimens.IconButton)
                    .clip(CircleShape)
                    .clickable {
                        layersOpen = !layersOpen
                        presetsOpen = false
                    }
                    .semantics { contentDescription = "Слои: ${if (chosen) layers.size else 0}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_layers), contentDescription = null, tint = if (layersOpen) Accent else TextPrimary, modifier = Modifier.size(Dimens.Icon))
                Text(
                    (if (chosen) layers.size else 0).toString(), color = Accent, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp),
                )
            }
            // «К исходному»: все слои и эффекты убираются; пару секунд можно отменить
            IconTouch(description = "К исходному", onClick = ::resetToOriginal, enabled = chosen) {
                Icon(
                    painterResource(R.drawable.ic_reset), contentDescription = null,
                    tint = if (chosen) TextPrimary else TextDisabled,
                    modifier = Modifier.size(Dimens.Icon).rotateWithDevice(),
                )
            }
            StampToggle(on = stamp.enabled, onToggle = { prefs.stamp = stamp.copy(enabled = !stamp.enabled) }, onLongPress = { stampSheet = true })
            Box(Modifier.weight(1f))
            FormatPill(format, landscape) { format = it }
            // Та же таблетка 32dp, что у формата (по сетке), а не стандартная кнопка 40dp
            Pill(
                text = "Сохранить",
                description = "Сохранить",
                onClick = ::save,
                enabled = (photo != null || video != null) && !saving && exporter == null,
                background = PanelSelected,
                modifier = Modifier.padding(start = Dimens.Grid, end = Dimens.HalfGrid),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                else Text("Сохранить", color = TextPrimary, fontSize = Dimens.PillText, maxLines = 1)
            }
        }
    }

    // Превью: короткое нажатие — пипетка или точка фокуса, долгое — оригинал
    val previewArea = @Composable { modifier: Modifier ->
        val shown = if (showOriginal) originalCropped else rendered ?: originalCropped
        val videoAspect = video?.let { cropAspect ?: (it.width.toFloat() / it.height) }
        val shownAspect = videoAspect ?: shown?.let { it.width.toFloat() / it.height } ?: 1f
        val aspectNow by rememberUpdatedState(shownAspect)
        Box(
            modifier = modifier.onSizeChanged { previewBox = it }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (pickMode) {
                        // Пипетка с лупой: ведём палец, отпускание подтверждает выбор
                        val frame0 = fitRect(size.width.toFloat(), size.height.toFloat(), aspectNow)
                        if (!frame0.isEmpty && frame0.contains(down.position)) {
                            loupe = down.position
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                last = change.position
                                loupe = last
                                change.consume()
                            }
                            loupe = null
                            val frame = fitRect(size.width.toFloat(), size.height.toFloat(), aspectNow)
                            val p = Offset(last.x.coerceIn(frame.left, frame.right - 1f), last.y.coerceIn(frame.top, frame.bottom - 1f))
                            if (!frame.isEmpty) onImageTap((p.x - frame.left) / frame.width, (p.y - frame.top) / frame.height)
                            return@awaitEachGesture
                        }
                    }
                    val up = withTimeoutOrNull(LONG_PRESS_MS) { waitForUpOrCancellation() }
                    if (up == null) {
                        showOriginal = true
                        waitForUpOrCancellation()
                        showOriginal = false
                    } else if ((up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                        val frame = fitRect(size.width.toFloat(), size.height.toFloat(), aspectNow)
                        val p: Offset = up.position
                        if (!frame.isEmpty && frame.contains(p)) onImageTap((p.x - frame.left) / frame.width, (p.y - frame.top) / frame.height)
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (player != null && videoAspect != null) {
                VideoSurface(player, Modifier.aspectRatio(videoAspect))
                if (player.simplified) {
                    Text(
                        "упрощённое воспроизведение",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(Dimens.HalfGrid)
                            .background(Scrim, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (player.failed) Text("Не удалось воспроизвести ролик", color = ErrorRed)
            } else {
                shown?.let {
                    Image(it.asImageBitmap(), contentDescription = "Превью", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            if (photo == null && video == null && loadError == null) CircularProgressIndicator(color = Accent)
            loadError?.let { Text(it, color = ErrorRed) }
            if (showOriginal) {
                Text(
                    "оригинал",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(Scrim, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            // Лупа пипетки над пальцем
            val finger = loupe
            val src = pickSource
            if (finger != null && src != null) {
                val frame = fitRect(previewBox.width.toFloat(), previewBox.height.toFloat(), shownAspect)
                if (!frame.isEmpty) {
                    val u = ((finger.x - frame.left) / frame.width).coerceIn(0f, 1f)
                    val v = ((finger.y - frame.top) / frame.height).coerceIn(0f, 1f)
                    val (px, py) = sourcePixel(u, v, src)
                    val slot = settings.colorSelect.slot(pickSlot)
                    ColorLoupe(
                        bitmap = src, px = px, py = py, finger = finger, picked = averageColor(src, px, py),
                        current = if (slot.on) android.graphics.Color.HSVToColor(floatArrayOf(slot.hue, 0.85f, 1f)) else null,
                    )
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.TopCenter).padding(top = Dimens.Grid * 4))
        }
    }

    // Прокрутка списков (режимы, пресеты, текстуры, градиенты) запоминается отдельно для каждого слоя
    CompositionLocalProvider(LocalScrollScope provides "editor:$activeId") {
    Box(modifier = Modifier.fillMaxSize().background(ScreenBackground)) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(modifier = Modifier.padding(horizontal = 4.dp)) { header() }
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val areaHeight = maxHeight
                previewArea(Modifier.fillMaxSize())
                if ((presetsOpen && !effectUi.collapsed) || layersOpen) {
                    Box(
                        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            presetsOpen = false
                            layersOpen = false
                        },
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(ControlsInsets.only(WindowInsetsSides.Horizontal))
                        .heightIn(max = areaHeight)
                        .verticalScroll(rememberRememberedScroll("editor-panel"))
                        .padding(bottom = Dimens.HalfGrid),
                ) {
                    when {
                        layersOpen -> LayersPanel(
                            layers = layers,
                            activeId = active.id,
                            onSelect = { activeId = it.id },
                            onChange = { changed -> layers = layers.map { if (it.id == changed.id) changed else it } },
                            onDelete = { removed ->
                                if (layers.size > 1) {
                                    layers = layers.filterNot { it.id == removed.id }
                                    if (activeId == removed.id) activeId = layers.first().id
                                }
                            },
                            onMove = { from, to -> layers = layers.toMutableList().also { it.add(to, it.removeAt(from)) } },
                            onAdd = { addLayer = true },
                            onClose = { layersOpen = false },
                            maxLayers = if (fromDice) Dice.MAX_LAYERS else EditLayer.MAX_LAYERS,
                            showLocks = fromDice || diceOpen,
                        )
                        diceOpen -> DicePanel(
                            count = diceCount,
                            onCount = { diceCount = it },
                            rolling = rolling,
                            seed = diceSeed,
                            onRoll = { roll(kotlin.random.Random.nextLong(1, 1_000_000_000L)) },
                            onSeed = { seedDialog = true },
                            canSave = fromDice,
                            onSave = { saveDialog = true },
                            saved = diceSets.sets,
                            onApplySaved = { set -> applySet(set.code, set.name) },
                            onSavedActions = { savedActions = it },
                            onCopyChain = {
                                context.copyText(LayerSetCode.encode(layers, diceSeed, diceCount))
                                message("Код набора слоёв скопирован")
                            },
                            onPasteChain = {
                                val code = context.clipboardText()
                                if (code == null || LayerSetCode.decode(code, base) == null) message("В буфере нет кода набора слоёв") else applySet(code, null)
                            },
                        )
                        else -> CollapsibleEffectControls(effectUi) { if (presetsOpen) PresetDropdown(
                            manager = presets,
                            settings = settings,
                            onSettingsChange = ::update,
                            onDismiss = { presetsOpen = false },
                            thumbSource = thumbSource,
                            maxHeight = areaHeight * 0.7f,
                            onOpenHelp = { presetsOpen = false; onOpenHelp(settings.mode) },
                        ) else EffectOverlayPanels(
                            settings = settings,
                            presets = presets,
                            onUpdate = ::update,
                            openCategory = openCategory,
                            onOpenCategory = { openCategory = it },
                            onOpenPresets = { presetsOpen = true },
                            ui = effectUi,
                            onEditGradient = onEditGradient,
                        ) }
                    }
                }
            }
            // Видео: воспроизведение/пауза, время, позиция, звук, повтор
            val info = video
            if (info != null && player != null) PlayerControls(player, info.durationMs)
            // Режим активного слоя и его категории
            EffectModeBar(
                settings = settings,
                onUpdate = ::update,
                openCategory = openCategory,
                onOpenCategory = {
                    openCategory = it
                    presetsOpen = false
                    layersOpen = false
                },
                presetsOpen = presetsOpen,
                onTogglePresets = {
                    presetsOpen = !presetsOpen
                    layersOpen = false
                },
                ui = effectUi,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(ControlsInsets).padding(top = 6.dp, bottom = 8.dp),
                modes = modes,
                noneSelected = !chosen,
                extra = ModeBarExtra(R.drawable.ic_mode_random, "Кубик", diceOpen) {
                    diceOpen = !diceOpen
                    // Категории режима слоя под «Кубиком» не нужны: полоса гаснет (место остаётся)
                    effectUi.collapsed = diceOpen || !chosen
                    presetsOpen = false
                    layersOpen = false
                },
            )
        }
        // Идёт офлайн-рендер ролика: прогресс и отмена поверх всего экрана
        exporter?.let { job ->
            Box(
                Modifier.fillMaxSize().background(Scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(Dimens.Grid * 4).clip(RoundedCornerShape(Dimens.SheetCorner)).background(PanelRaised).padding(Dimens.Grid * 3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.Grid * 2),
                ) {
                    Text(if (job.cancelled) "Отмена…" else "Обработка видео: ${(exportProgress * 100).roundToInt()}%", color = TextPrimary, fontSize = 16.sp)
                    LinearProgressIndicator(progress = { exportProgress }, color = Accent, trackColor = PanelSelected, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { job.cancelled = true }, enabled = !job.cancelled, colors = appTextButtonColors()) { Text("Отмена") }
                }
            }
        }
    }
    }
    // Экран не гаснет, пока идёт обработка
    val view = LocalView.current
    DisposableEffect(exporter != null) {
        view.keepScreenOn = exporter != null
        onDispose { view.keepScreenOn = false }
    }

    if (exportDialog) {
        var size by remember { mutableStateOf(ExportSize.P1080) }
        TurnedDialog(
            title = "Сохранить видео",
            onDismiss = { exportDialog = false },
            buttons = {
                TextButton(onClick = { exportDialog = false }, colors = appTextButtonColors()) { Text("Отмена") }
                TextButton(onClick = { exportDialog = false; exportVideo(size) }, colors = appTextButtonColors()) { Text("Начать") }
            },
        ) {
            Text("Разрешение результата", color = TextSecondary, fontSize = 13.sp)
            ExportSize.entries.forEach { option -> ChoiceRow(option.label, selected = option == size) { size = option } }
            Text(
                "Обработка идёт не в реальном времени и может занять время: длинные ролики и тяжёлые режимы — несколько минут. " +
                    "Разрешение само не снижается. Звук копируется без изменений.",
                color = TextSecondary, fontSize = 13.sp,
            )
        }
    }

    if (addLayer) {
        AddLayerDialog(
            modes = modes,
            onPick = { mode ->
                addLayer = false
                if (layers.size < (if (fromDice) Dice.MAX_LAYERS else EditLayer.MAX_LAYERS)) {
                    val layer = EditLayer(UUID.randomUUID().toString(), base.copy(mode = mode).withEnabled(true))
                    layers = if (chosen) layers + layer else listOf(layer)
                    activeId = layer.id
                    chosen = true
                }
            },
            onDismiss = { addLayer = false },
        )
    }
    if (saveDialog) {
        NameInputDialog(
            title = "Сохранить набор слоёв",
            initial = remember { diceSets.nextName() },
            onConfirm = { name ->
                saveDialog = false
                val set = diceSets.add(name, diceCount, layers, diceSeed)
                message("Набор «${set.name}» сохранён")
            },
            onDismiss = { saveDialog = false },
        )
    }
    savedActions?.let { set ->
        TurnedDialog(
            title = set.name,
            onDismiss = { savedActions = null },
            buttons = { TextButton(onClick = { savedActions = null }, colors = appTextButtonColors()) { Text("Отмена") } },
        ) {
            ActionRow("Применить") { savedActions = null; applySet(set.code, set.name) }
            ActionRow("Переименовать") { savedActions = null; renameSet = set }
            ActionRow("Скопировать код") {
                savedActions = null
                context.copyText(set.code)
                message("Код набора «${set.name}» скопирован")
            }
            ActionRow("Удалить") { savedActions = null; diceSets.delete(set); message("Набор «${set.name}» удалён") }
        }
    }
    renameSet?.let { set ->
        NameInputDialog(
            title = "Переименовать набор",
            initial = set.name,
            onConfirm = { name -> diceSets.rename(set, name); renameSet = null },
            onDismiss = { renameSet = null },
        )
    }
    if (seedDialog) {
        NameInputDialog(
            title = "Бросок по seed",
            initial = diceSeed?.toString() ?: "",
            label = "Seed (число)",
            onConfirm = { text ->
                seedDialog = false
                text.trim().toLongOrNull()?.let { roll(it) } ?: message("Seed — это целое число")
            },
            onDismiss = { seedDialog = false },
        )
    }
    if (stampSheet) {
        StampSheet(
            stamp = stamp,
            autoMs = takenMs,
            autoLabel = "Дата файла",
            onChange = { prefs.stamp = it },
            onDismiss = { stampSheet = false },
        )
    }
}

/** Ряд плеера: ячейки 48dp по сетке, значки поворачиваются вместе с телефоном. */
@Composable
private fun PlayerControls(player: EditorVideoPlayer, durationMs: Long) {
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val shownMs = dragMs ?: player.positionMs
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.IconButton).windowInsetsPadding(ControlsInsets.only(WindowInsetsSides.Horizontal)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (player.isPlaying) "Пауза" else "Воспроизвести", true) { player.toggle() }
        Text(
            "${timeLabel(shownMs)} / ${timeLabel(durationMs)}", color = TextSecondary, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.padding(end = Dimens.HalfGrid),
        )
        Slider(
            value = shownMs.coerceIn(0L, durationMs).toFloat(),
            onValueChange = {
                dragMs = it.toLong()
                player.seekTo(it.toLong(), dragging = true)
            },
            onValueChangeFinished = {
                dragMs?.let { player.seekTo(it, dragging = false) }
                dragMs = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            colors = appSliderColors(),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Позиция в ролике" },
        )
        PlayerButton(if (player.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on, if (player.muted) "Звук выключен" else "Звук включён", !player.muted) {
            player.changeMuted(!player.muted)
        }
        PlayerButton(R.drawable.ic_loop, if (player.looping) "Повтор включён" else "Повтор выключен", player.looping) { player.changeLooping(!player.looping) }
    }
}

@Composable
private fun PlayerButton(icon: Int, description: String, on: Boolean, onClick: () -> Unit) {
    IconTouch(description = description, onClick = onClick) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (on) TextPrimary else TextSecondary,
            modifier = Modifier.size(Dimens.Icon).rotateWithDevice(),
        )
    }
}

/** Поверхность плеера: TextureView, в которую GL-поток плеера рисует готовые кадры. */
@Composable
private fun VideoSurface(player: EditorVideoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                isOpaque = false
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) =
                        player.attachWindow(texture, width, height)

                    override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) =
                        player.resizeWindow(width, height)

                    override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                        // Поверхность освобождает GL-поток после того, как отпустит её EGL
                        player.detachWindow(texture)
                        return false
                    }

                    override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier,
    )
}

private fun timeLabel(ms: Long): String = String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60)

/** Системное уведомление «ролик готов» (если уведомления разрешены). */
private fun notifyExportDone(context: android.content.Context, label: String) {
    runCatching {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(android.app.NotificationChannel("export", "Обработка видео", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val notification = android.app.Notification.Builder(context, "export")
            .setSmallIcon(R.drawable.ic_capture_video)
            .setContentTitle("Видео готово")
            .setContentText(label)
            .setAutoCancel(true)
            .build()
        manager.notify(1, notification)
    }
}

/** Слои текущего файла редактора: чтобы не терялись, пока открыта справка или редактор градиентов. */
internal object EditorSession {
    var uri: Uri? = null
    var layers: List<EditLayer> = emptyList()
    var activeId: String = ""
    /** Цепочка из «Кубика» (предел слоёв 10), seed последнего броска и выбранное число слоёв. */
    var fromDice = false
    var diceSeed: Long? = null
    var diceCount = 5
    var chosen = false

    /** Сеанс окончен (редактор закрыт или открывается другой файл): следующее открытие — с чистого листа. */
    fun clear() {
        uri = null
        layers = emptyList()
        activeId = ""
        fromDice = false
        diceSeed = null
        diceCount = 5
        chosen = false
    }
}

/** Штамп даты в шапке: нажатие включает и выключает, долгое нажатие — настройки штампа. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StampToggle(on: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Dimens.IconButton)
            .clip(CircleShape)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .semantics { contentDescription = if (on) "Штамп даты включён" else "Штамп даты выключен" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_stamp), contentDescription = null, tint = if (on) Accent else TextPrimary, modifier = Modifier.size(Dimens.Icon))
    }
}

/** Выбор формата: «Исходный» или формат в ориентации кадра. */
@Composable
internal fun FormatPill(format: FrameFormat?, landscape: Boolean, onSelect: (FrameFormat?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(f: FrameFormat?) = when {
        f == null -> "Исходный"
        landscape -> "${f.height}:${f.width}"
        else -> f.label
    }
    Pill(text = label(format), description = "Формат ${label(format)}", onClick = { open = true })
    if (open) {
        TurnedDialog(
            title = "Формат кадра",
            onDismiss = { open = false },
            buttons = { TextButton(onClick = { open = false }, colors = appTextButtonColors()) { Text("Отмена") } },
        ) {
            (listOf<FrameFormat?>(null) + FrameFormat.entries).forEach { f ->
                ChoiceRow(label(f), selected = f == format) {
                    open = false
                    onSelect(f)
                }
            }
        }
    }
}

/** Наибольшая центральная область пропорций [aspect] (ширина/высота) внутри w x h; null — без обрезки. */
internal fun cropRect(w: Int, h: Int, aspect: Float?): Rect? {
    if (aspect == null) return null
    val full = w.toFloat() / h
    val (cw, ch) = if (aspect > full) w to (w / aspect).roundToInt() else (h * aspect).roundToInt() to h
    val x = (w - cw) / 2
    val y = (h - ch) / 2
    return Rect(x, y, x + cw.coerceAtLeast(1), y + ch.coerceAtLeast(1))
}

/** Полноразмерная обработка слоями с откатом по памяти (100% -> 71% -> 50%). Возвращает подпись файла и «уменьшено». */
private fun processFull(
    context: android.content.Context,
    photo: GalleryPhoto,
    layers: List<EditLayer>,
    cropAspect: Float?,
    stamp: StampSettings?,
): Pair<String, Boolean> {
    val processor = PhotoProcessor(context)
    val name = "IMG_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    var lastError: Throwable? = null
    for (scale in floatArrayOf(1f, 0.71f, 0.5f)) {
        try {
            val result = processor.processLayers(
                open = { SourceImage.from(photo.bytes, maxSideFallback = 4096) },
                rotationDegrees = photo.rotation, mirror = photo.mirror, layers = layers,
                cropAspect = cropAspect ?: (photo.width.toFloat() / photo.height),
                name = name, scale = scale, stamp = stamp, stampAutoMs = photo.takenMs,
            )
            result.thumbnail.recycle()
            return result.label to (scale < 1f)
        } catch (e: OutOfMemoryError) {
            lastError = e
        } catch (e: IllegalStateException) {
            lastError = e
        }
        System.gc()
    }
    throw lastError ?: IllegalStateException("Photo processing failed")
}

/** GL_MAX_TEXTURE_SIZE этого устройства (запасной вариант — 2048, минимум ES 3.0). */
internal fun maxTextureSize(): Int = runCatching {
    val egl = EglCore()
    try {
        IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0].takeIf { it > 0 } ?: 2048
    } finally {
        egl.release()
    }
}.getOrDefault(2048)

private const val PREVIEW_MAX_SIDE = 1600
private const val VIDEO_PREVIEW_SIDE = 1280
private const val THUMB_SIDE = 160
private const val LONG_PRESS_MS = 350L
