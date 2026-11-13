package com.vhscam.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vhscam.app.AppPrefs
import com.vhscam.app.DebugHooks
import com.vhscam.app.VolumeAction
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Job
import com.vhscam.app.R
import com.vhscam.app.camera.VhsCameraController
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.FlashSetting
import com.vhscam.app.effect.FrameFormat
import com.vhscam.app.effect.PhotoQuality
import com.vhscam.app.effect.PresetManager
import com.vhscam.app.effect.VideoResolution
import com.vhscam.app.effect.specFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Экран камеры (всегда портретный): сверху плашка (вспышка, формат, качество, FX, переворот, fps), превью
 * выбранного формата (ничем не перекрыто, кроме временных индикаторов), под ним настройки эффекта, «Фото/Видео»,
 * зум и ряд миниатюры, спуска и пресетов. При повороте телефона раскладка не меняется: на месте поворачиваются
 * иконки и подписи, а фото и видео сохраняются в ориентации телефона (во время записи всё заморожено).
 */
@Composable
fun CameraScreen(onOpenHelp: (EffectMode) -> Unit, onOpenEditor: (Uri) -> Unit, onOpenSettings: () -> Unit, onEditGradient: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember { VhsCameraController(context, lifecycleOwner) }
    val presets = remember { PresetManager(context) }
    val appPrefs = remember { AppPrefs.get(context) }
    val effectUi = remember { EffectUiState() }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val settings = controller.settings
    val spec = specFor(settings.mode)
    val modeParams = spec.get(settings)

    LaunchedEffect(Unit) { controller.start() }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    // Ориентация телефона по датчику: ориентация фото/видео и поворот иконок; во время записи заморожены
    val deviceOrientation = rememberDeviceOrientation(DebugHooks.orientationOverride)
    LaunchedEffect(deviceOrientation, controller.isRecording) { controller.updateDeviceOrientation(deviceOrientation) }
    val uiRotation = rememberUiRotation(deviceOrientation, frozen = controller.isRecording)
    val activity = LocalActivity.current

    val snackbar = remember { SnackbarHostState() }
    fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    LaunchedEffect(Unit) {
        for (text in controller.messages) message(text)
    }

    // Вспышка-затвор: белый кадр ~90 мс
    val flash = remember { Animatable(0f) }

    // Микрофон спрашиваем один раз, при первом нажатии на запись. Отказ — пишем без звука.
    val prefs = remember { context.getSharedPreferences("vhs", Context.MODE_PRIVATE) }
    var pendingPhoto by remember { mutableStateOf(false) }

    fun shootPhoto() {
        // Затворное мигание — только если не будет ни лампы, ни экранной вспышки,
        // иначе белый кадр на экране сливается с настоящей вспышкой
        if (controller.takePhoto()) {
            scope.launch {
                flash.snapTo(1f)
                flash.animateTo(0f, tween(90))
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (pendingPhoto) {
            pendingPhoto = false
            shootPhoto()
            return@rememberLauncherForActivityResult
        }
        prefs.edit().putBoolean(KEY_AUDIO_ASKED, true).apply()
        controller.startRecording()
        if (!controller.hasAudioPermission()) message(NO_AUDIO_MESSAGE)
    }

    fun needsLegacyStorage() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun performShutter() {
        if (controller.photoMode) {
            if (needsLegacyStorage()) {
                pendingPhoto = true
                permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            } else {
                shootPhoto()
            }
            return
        }
        if (controller.isRecording) {
            controller.stopRecording()
            return
        }
        val missing = buildList {
            if (!controller.hasAudioPermission() && !prefs.getBoolean(KEY_AUDIO_ASKED, false)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (needsLegacyStorage()) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            controller.startRecording()
            if (!controller.hasAudioPermission()) message(NO_AUDIO_MESSAGE)
        }
    }

    // Таймер: спуск запускает обратный отсчёт (и для фото, и перед стартом записи); повторное нажатие отменяет
    var countdown by remember { mutableStateOf<Int?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    fun onShutterClick() {
        if (appPrefs.vibration) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (countdown != null) {
            countdownJob?.cancel()
            countdown = null
            return
        }
        val starts = controller.photoMode || !controller.isRecording
        val seconds = appPrefs.timerSec
        if (seconds <= 0 || !starts) {
            performShutter()
            return
        }
        countdownJob = scope.launch {
            for (left in seconds downTo 1) {
                countdown = left
                delay(1000)
            }
            countdown = null
            performShutter()
        }
    }

    var openCategory by rememberSaveable { mutableStateOf<Pair<String, Int>?>(null) }
    var presetsOpen by remember { mutableStateOf(false) }
    // Выпадающий список пресетов: миниатюры считаются на кадре, замороженном в момент открытия
    var thumbSource by remember { mutableStateOf<Bitmap?>(null) }
    fun togglePresets() {
        presetsOpen = !presetsOpen
        if (presetsOpen) {
            thumbSource = null
            controller.grabFrame(THUMB_SIDE) { thumbSource = it }
        }
    }
    var stampSheet by remember { mutableStateOf(false) }
    val tilt = rememberTilt(appPrefs.level)
    var showingOriginal by remember { mutableStateOf(false) }

    // Фото из галереи: системный Photo Picker без READ_MEDIA_*; где его нет, androidx сам откатывается
    // на ACTION_OPEN_DOCUMENT
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onOpenEditor(uri)
    }

    // Тап-фокус: точка, состояние и «ключ» последнего касания; всё гаснет через пару секунд без касаний
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusState by remember { mutableStateOf(FocusState.PENDING) }
    var focusKey by remember { mutableIntStateOf(0) }
    var focusTouch by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusKey, focusTouch) {
        if (focusPoint == null) return@LaunchedEffect
        delay(FOCUS_INDICATOR_MS)
        focusPoint = null
    }
    // Смена камеры сбрасывает зум и экспозицию — убираем и индикатор
    LaunchedEffect(controller.useFrontCamera) { focusPoint = null }

    // Шкала зума: раскрывается тапом/перетаскиванием по таблетке и щипком, сворачивается через ~3 с без касаний
    val zoomSupported = controller.maxZoom > controller.minZoom + 0.01f
    var zoomExpanded by remember { mutableStateOf(false) }
    var zoomTouch by remember { mutableIntStateOf(0) }
    var zoomHeld by remember { mutableStateOf(false) }
    LaunchedEffect(zoomExpanded, zoomTouch, zoomHeld) {
        if (!zoomExpanded || zoomHeld) return@LaunchedEffect
        delay(ZOOM_COLLAPSE_MS)
        zoomExpanded = false
    }
    fun zoomInteract() {
        zoomExpanded = true
        zoomTouch++
    }

    // Кнопки громкости: спуск (автоповтор при удержании не считается новым нажатием), зум или ничего
    val onVolume by rememberUpdatedState<(Boolean, Int) -> Boolean>({ up, repeat ->
        when (appPrefs.volumeAction) {
            VolumeAction.SHUTTER -> {
                if (repeat == 0) onShutterClick()
                true
            }
            VolumeAction.ZOOM -> {
                controller.zoomBy(if (up) VOLUME_ZOOM_STEP else 1f / VOLUME_ZOOM_STEP)
                if (zoomSupported) zoomInteract()
                true
            }
            VolumeAction.NONE -> false
        }
    })
    DisposableEffect(Unit) {
        HardwareKeys.onVolume = { up, repeat -> onVolume(up, repeat) }
        onDispose { HardwareKeys.onVolume = null }
    }

    // Экранная вспышка: белый экран и максимальная яркость, пока CameraX делает снимок
    LaunchedEffect(controller.screenFlashActive) {
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply {
            screenBrightness = if (controller.screenFlashActive) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    // --- Части экрана (одни и те же в обеих раскладках) ---

    val previewArea = @Composable { modifier: Modifier ->
        // Превью: всё отведённое место, кадр выбранного формата целиком (ContentScale.Fit).
        // Тап — фокус и экспозиция, долгое нажатие — оригинал, два пальца — зум
        Box(
            modifier = modifier
                .onSizeChanged { previewSize = it }
                .pointerInput(Unit) {
                    cameraGestures(
                        longPressMs = LONG_PRESS_MS,
                        onTap = { tap ->
                            // Касание -> доля видимого кадра: превью вписано в область целиком (Fit), по центру
                            if (controller.surfaceRequest == null) return@cameraGestures
                            val aspect = controller.frameFormat.aspect
                            val boxW = size.width.toFloat()
                            val boxH = size.height.toFloat()
                            val (shownW, shownH) = if (boxW / boxH > aspect) boxH * aspect to boxH else boxW to boxW / aspect
                            val u = (tap.x - (boxW - shownW) / 2f) / shownW
                            val v = (tap.y - (boxH - shownH) / 2f) / shownH
                            if (u !in 0f..1f || v !in 0f..1f) return@cameraGestures
                            val key = focusKey + 1
                            focusKey = key
                            focusPoint = tap
                            focusState = FocusState.PENDING
                            controller.focusAt(u, v) { ok ->
                                if (focusKey == key) focusState = if (ok) FocusState.OK else FocusState.FAILED
                            }
                        },
                        onLongPress = { pressed ->
                            if (pressed && !controller.isRecording) {
                                showingOriginal = true
                                controller.showOriginal(true)
                            } else if (!pressed && showingOriginal) {
                                controller.showOriginal(false)
                                showingOriginal = false
                            }
                        },
                        onZoom = {
                            controller.zoomBy(it)
                            if (controller.maxZoom > controller.minZoom + 0.01f) zoomInteract()
                        },
                    )
                },
        ) {
            controller.surfaceRequest?.let { request ->
                CameraXViewfinder(
                    surfaceRequest = request,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            // Сетка и уровень — поверх кадра (в файл не попадают)
            FrameGuides(controller.frameFormat.aspect, appPrefs.grid, tilt)
            // Временные индикаторы: кольцо фокуса и ползунок экспозиции
            focusPoint?.let { point ->
                FocusRing(point, focusState, focusKey)
                controller.exposureRange?.let { range ->
                    ExposureSlider(
                        anchor = point,
                        boxWidth = previewSize.width.toFloat(),
                        boxHeight = previewSize.height.toFloat(),
                        index = controller.exposureIndex,
                        range = range,
                        step = controller.exposureStep,
                        onChange = controller::setExposure,
                        onInteract = { focusTouch++ },
                    )
                }
            }
            if (controller.isRecording) {
                RecordingBadge(
                    nanos = controller.recordedNanos,
                    withAudio = controller.recordingWithAudio,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
            countdown?.let { CountdownOverlay(it) }
            if (flash.value > 0f) {
                Box(modifier = Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
            }
            if (controller.isTakingPhoto) ProcessingBadge(Modifier.align(Alignment.Center))
            // Панели поверх нижней части превью: на его размер и положение не влияют
            if (presetsOpen && !effectUi.collapsed) {
                Box(
                    Modifier.fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { presetsOpen = false },
                )
            }
            if (previewSize.height > 0) {
                val areaHeight = with(density) { previewSize.height.toDp() }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(ControlsInsets.only(WindowInsetsSides.Horizontal))
                        .heightIn(max = areaHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = Dimens.HalfGrid),
                ) {
                    CollapsibleEffectControls(effectUi) {
                    if (presetsOpen) {
                        PresetDropdown(
                            manager = presets,
                            settings = settings,
                            onSettingsChange = controller::updateSettings,
                            onDismiss = { presetsOpen = false },
                            thumbSource = thumbSource,
                            maxHeight = minOf(LocalConfiguration.current.screenHeightDp.dp * DROPDOWN_MAX_FRACTION, areaHeight - Dimens.Grid),
                            // Справка закрывает камеру, поэтому во время записи недоступна
                            onOpenHelp = if (controller.isRecording) null else { { presetsOpen = false; onOpenHelp(settings.mode) } },
                        )
                    } else {
                        EffectOverlayPanels(
                            settings = settings,
                            presets = presets,
                            onUpdate = controller::updateSettings,
                            openCategory = openCategory,
                            onOpenCategory = { openCategory = it },
                            onOpenPresets = ::togglePresets,
                            ui = effectUi,
                            // Редактор закрывает камеру, поэтому во время записи недоступен
                            onEditGradient = if (controller.isRecording) null else onEditGradient,
                        )
                    }
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.TopCenter).padding(top = Dimens.Grid * 5))
        }
    }

    val toggleEffect = { controller.updateSettings(spec.put(settings, spec.setEnabled(modeParams, !settings.enabled))) }

    CompositionLocalProvider(LocalUiRotation provides uiRotation) {
        Box(modifier = Modifier.fillMaxSize().background(ScreenBackground)) {
            // Превью «заперто»: верхняя полоса и блок управления постоянной высоты, поэтому размер и положение превью
            // зависят только от формата кадра. Панели настроек и список пресетов лежат поверх его нижней части
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.HalfGrid)) {
                    TopBar(controller, onToggleEffect = toggleEffect, onOpenSettings = onOpenSettings, onOpenStamp = { stampSheet = true })
                }
                previewArea(Modifier.weight(1f).fillMaxWidth())
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Не в зоне системных жестов: «назад» у боковых краёв и «домой» у нижнего — отступы из рантайма
                        .windowInsetsPadding(ControlsInsets)
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EffectModeBar(
                        settings = settings,
                        onUpdate = controller::updateSettings,
                        openCategory = openCategory,
                        onOpenCategory = {
                            openCategory = it
                            presetsOpen = false
                        },
                        presetsOpen = presetsOpen,
                        onTogglePresets = ::togglePresets,
                        ui = effectUi,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Ряд зума (место занято всегда — ничего не прыгает): таблетка или раскрытая шкала по центру,
                    // слева метка таймера, справа fps
                    Box(modifier = Modifier.fillMaxWidth().height(Dimens.ZoomRow), contentAlignment = Alignment.Center) {
                        if (zoomSupported && zoomExpanded) {
                            ZoomScale(
                                ratio = controller.zoomRatio,
                                min = controller.minZoom,
                                max = controller.maxZoom,
                                marks = controller.zoomMarks,
                                onZoom = controller::setZoom,
                                onInteract = { pressed ->
                                    zoomHeld = pressed
                                    zoomTouch++
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            if (zoomSupported) {
                                ZoomPill(
                                    ratio = controller.zoomRatio,
                                    min = controller.minZoom,
                                    max = controller.maxZoom,
                                    onExpand = ::zoomInteract,
                                    onZoom = {
                                        controller.setZoom(it)
                                        zoomTouch++
                                    },
                                )
                            }
                            if (appPrefs.timerSec > 0) {
                                Box(Modifier.align(Alignment.CenterStart).size(Dimens.IconButton), contentAlignment = Alignment.Center) {
                                    TimerBadge(appPrefs.timerSec)
                                }
                            }
                            Box(Modifier.align(Alignment.CenterEnd).size(Dimens.IconButton), contentAlignment = Alignment.Center) {
                                FpsLabel(controller, showingOriginal)
                            }
                        }
                    }
                    // Блок спуска: три ячейки — миниатюра и галерея | спуск по центру | «Фото/Видео»; группы по центрам своих ячеек
                    Row(modifier = Modifier.fillMaxWidth().height(Dimens.ShutterRow), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            // Пара ячеек 2 × 48dp — той же ширины, что капсула «Фото/Видео» справа: спуск ровно посередине
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(Dimens.IconButton), contentAlignment = Alignment.Center) {
                                    controller.lastMedia?.let { media ->
                                        LastMediaButton(media = media, onClick = { context.openMedia(media.uri, media.mimeType) { message(it) } })
                                    }
                                }
                                // Обработка готового фото — рядом с миниатюрой
                                GalleryButton(
                                    enabled = !controller.isRecording,
                                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                                )
                            }
                        }
                        ShutterButton(
                            photoMode = controller.photoMode,
                            recording = controller.isRecording,
                            busy = controller.isTakingPhoto,
                            onClick = ::onShutterClick,
                        )
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CaptureToggle(photoMode = controller.photoMode, enabled = !controller.isRecording) { controller.updatePhotoMode(it) }
                        }
                    }
                }
            }

            if (controller.screenFlashActive) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White))
            }
        }

        if (stampSheet) {
            StampSheet(
                stamp = controller.stamp,
                autoMs = System.currentTimeMillis(),
                autoLabel = "Текущая",
                onChange = { controller.stamp = it },
                onDismiss = { stampSheet = false },
            )
        }
    }
}

/**
 * Верхняя полоса по сетке: вспышка, формат (со значком ориентации съёмки), качество, «FX», штамп даты, переворот
 * камеры, настройки. Кнопки-иконки 48×48, обе таблетки одной ширины — по самому длинному из возможных значений;
 * элементы распределены равномерно и стоят на одной оси (на узком экране, где не помещаются, полоса прокручивается).
 * Иконки и подписи поворачиваются вместе с телефоном.
 */
@Composable
private fun TopBar(
    controller: VhsCameraController,
    onToggleEffect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStamp: () -> Unit,
) {
    var frameMenu by remember { mutableStateOf(false) }
    val landscape = controller.landscape
    val flashEnabled = controller.flashAvailable
    val flashIcon = when {
        !controller.photoMode -> if (controller.torch && flashEnabled) FlashIcon.TORCH_ON else FlashIcon.TORCH_OFF
        controller.flash == FlashSetting.OFF -> FlashIcon.OFF
        controller.flash == FlashSetting.AUTO -> FlashIcon.AUTO
        else -> FlashIcon.ON
    }

    // Ширина таблетки формата: самое длинное из значений, которые могут в ней оказаться (со значком 16dp)
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = Dimens.PillText)
    val density = LocalDensity.current
    val pillWidth = remember(density) {
        FrameFormat.entries.flatMap { listOf(it.labelFor(false), it.labelFor(true)) }
            .maxOf { with(density) { measurer.measure(it, style).size.width.toDp() } } + FORMAT_GLYPH_ROOM + Dimens.PillTextPadding * 2
    }

    // В полосе только частое: вспышка, формат, FX, штамп, переворот, настройки — помещается в 360dp без прокрутки.
    // Качество и частота кадров — в диалоге «Кадр» под таблеткой формата
    Row(
        modifier = Modifier.fillMaxWidth().height(Dimens.IconButton),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
            IconTouch(
                enabled = flashEnabled,
                description = if (controller.photoMode) "Вспышка: ${controller.flash.label}" else "Фонарик",
                onClick = { if (controller.photoMode) controller.cycleFlash() else controller.toggleTorch() },
            ) {
                val on = flashIcon == FlashIcon.ON || flashIcon == FlashIcon.AUTO || flashIcon == FlashIcon.TORCH_ON
                Box(Modifier.rotateWithDevice()) {
                    FlashGlyph(flashIcon, if (!flashEnabled) TextDisabled else if (on) Accent else TextPrimary)
                }
            }
            // Формат — в ориентации съёмки (телефон боком: 3:4 -> 4:3), значок телефона — как сохранится снимок
            Pill(
                text = controller.frameFormat.labelFor(landscape),
                description = "Кадр: формат ${controller.frameFormat.labelFor(landscape)}, ${qualityLabel(controller)}",
                enabled = !controller.isTakingPhoto,
                onClick = { frameMenu = true },
                width = pillWidth,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.HalfGrid)) {
                    OrientationGlyph(landscape, TextSecondary, size = 16.dp)
                    Text(
                        controller.frameFormat.labelFor(landscape),
                        color = if (controller.isRecording) TextDisabled else TextPrimary,
                        fontSize = Dimens.PillText,
                        maxLines = 1,
                    )
                }
            }
            FxButton(on = controller.settings.enabled, onClick = onToggleEffect)
            // Штамп даты: нажатие включает и выключает, долгое нажатие открывает его настройки
            StampButton(
                on = controller.stamp.enabled,
                onToggle = { controller.stamp = controller.stamp.copy(enabled = !controller.stamp.enabled) },
                onLongPress = onOpenStamp,
            )
            IconTouch(
                // Во время записи камеру не переключаем: см. VhsCameraController.flipCamera
                enabled = controller.canFlip && !controller.isRecording,
                description = "Переключить камеру",
                onClick = controller::flipCamera,
            ) {
                Box(Modifier.rotateWithDevice()) {
                    FlipGlyph(if (!controller.canFlip || controller.isRecording) TextDisabled else TextPrimary)
                }
            }
            // Настройки закрывают камеру, поэтому во время записи недоступны: серая иконка, нажатие ничего не делает
            IconTouch(enabled = !controller.isRecording, description = if (controller.isRecording) "Настройки недоступны во время записи" else "Настройки", onClick = onOpenSettings) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = null,
                    tint = if (controller.isRecording) TextDisabled else TextPrimary,
                    modifier = Modifier.size(Dimens.Icon).rotateWithDevice(),
                )
            }
    }

    if (frameMenu) {
        FrameDialog(controller, landscape, onDismiss = { frameMenu = false })
    }
}

/** Счётчик кадров в секунду: опрашивает процессор сам, поэтому раз в полсекунды перерисовывается только он. */
@Composable
private fun FpsLabel(controller: VhsCameraController, showingOriginal: Boolean) {
    var fps by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            fps = controller.effect.fps
            delay(500)
        }
    }
    Text(
        text = if (showingOriginal) "ориг." else String.format(Locale.US, "%.0f", fps),
        // Акцент — меньше 90% выбранной частоты
        color = if (!showingOriginal && fps >= 1f && fps < controller.videoFps.fps * 0.9f) Accent else TextSecondary,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.rotateWithDevice().semantics { contentDescription = "Кадров в секунду" },
    )
}

/** Подпись таблетки качества: в фото — размер («12 Мп»), в видео — разрешение и частота («1080p·30»). */
private fun qualityLabel(controller: VhsCameraController): String =
    if (controller.photoMode) controller.photoLabel().substringAfter("· ")
    else "${controller.videoResolution.label}·${controller.videoFps.fps}"

/**
 * «Кадр»: формат и качество. Качество в фото — размеры, которые камера реально отдаёт приложению (плюс «Макс»),
 * в видео — 720p / 1080p / 4K и частота кадров (только то, что держат камера, разрешение и кодер).
 * Во время записи и обработки фото не меняется. Диалог поворачивается вместе с телефоном.
 */
@Composable
private fun FrameDialog(controller: VhsCameraController, landscape: Boolean, onDismiss: () -> Unit) {
    TurnedDialog(
        title = "Кадр",
        onDismiss = onDismiss,
        buttons = { TextButton(onClick = onDismiss, colors = appTextButtonColors()) { Text("Закрыть") } },
    ) {
        if (controller.isRecording) {
            Text("Во время записи формат и качество не меняются", color = TextSecondary, fontSize = 13.sp)
            return@TurnedDialog
        }
        Text("Формат", color = Accent, fontSize = 13.sp)
        FrameFormat.entries.forEach { format ->
            ChoiceRow(format.labelFor(landscape), selected = format == controller.frameFormat) {
                onDismiss()
                controller.updateFrameFormat(format)
            }
        }
        HorizontalDivider(color = MicroDivider)
        Text(if (controller.photoMode) "Качество фото" else "Качество видео", color = Accent, fontSize = 13.sp)
        if (controller.photoMode) {
            val current = controller.photoQuality
            ChoiceRow("Макс", selected = current.isMax) { onDismiss(); controller.updatePhotoQuality(PhotoQuality.MAX) }
            // По одному пункту на каждый размер в мегапикселях (самый большой из одинаковых)
            controller.availablePhotoSizes
                .distinctBy { PhotoQuality.label(it.width * it.height / 1_000_000f) }
                .forEach { size ->
                    val q = PhotoQuality(size.width, size.height)
                    ChoiceRow("${PhotoQuality.label(q.megapixels)}  (${size.width}×${size.height})", selected = q == current) {
                        onDismiss()
                        controller.updatePhotoQuality(q)
                    }
                }
        } else {
            // Реальный размер записи: CameraX может выбрать меньше заказанного
            controller.boundVideoSize?.let { size ->
                Text("Пишется: ${minOf(size.width, size.height)}×${maxOf(size.width, size.height)}", color = TextSecondary, fontSize = 13.sp)
            }
            controller.availableVideoResolutions.forEach { r ->
                ChoiceRow(r.label + if (r == VideoResolution.UHD) "  (может тормозить)" else "", selected = r == controller.videoResolution) {
                    onDismiss()
                    controller.updateVideoResolution(r)
                }
            }
            HorizontalDivider(color = MicroDivider)
            controller.availableFps.forEach { f ->
                ChoiceRow(f.label, selected = f == controller.videoFps) {
                    onDismiss()
                    controller.updateVideoFps(f)
                }
            }
        }
    }
}

/** «Обработка…» поверх превью, пока снимок в полном разрешении проходит через эффект. */
@Composable
private fun ProcessingBadge(modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Scrim)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
        Text("Обработка…", color = TextPrimary, fontSize = 14.sp)
    }
}

/** Штамп даты — кнопка 48×48: акцентная, когда штамп включён; долгое нажатие — настройки штампа. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StampButton(on: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Dimens.IconButton)
            .clip(CircleShape)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .semantics { contentDescription = if (on) "Штамп даты включён" else "Штамп даты выключен" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_stamp),
            contentDescription = null,
            tint = if (on) Accent else TextPrimary,
            modifier = Modifier.size(Dimens.Icon).rotateWithDevice(),
        )
    }
}

/** «FX» — кнопка 48×48: подсвечена, когда эффект включён; перечёркнута, когда выключен. */
@Composable
private fun FxButton(on: Boolean, onClick: () -> Unit) {
    IconTouch(description = if (on) "Эффект включён" else "Эффект выключен", onClick = onClick) {
        Box(
            modifier = Modifier
                .size(Dimens.IconHighlight)
                .rotateWithDevice()
                .clip(RoundedCornerShape(14.dp))
                .background(if (on) PanelSelected else PanelRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "FX",
                color = if (on) Accent else TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = if (on) null else TextDecoration.LineThrough,
            )
        }
    }
}

/**
 * Переключатель «Фото/Видео» — одна капсула 2 × 48dp: подложка-ползунок плавно (~150 мс) переезжает под выбранную
 * иконку, выбранная — акцентная, вторая приглушена. Нажатие по любой половине переключает режим.
 */
@Composable
private fun CaptureToggle(photoMode: Boolean, enabled: Boolean, onSelect: (photo: Boolean) -> Unit) {
    val thumbX by animateDpAsState(if (photoMode) 0.dp else Dimens.CaptureToggleCell, tween(TOGGLE_MS), label = "captureThumb")
    val photoTint by animateColorAsState(if (photoMode) Accent else if (enabled) TextSecondary else TextDisabled, tween(TOGGLE_MS), label = "photoTint")
    val videoTint by animateColorAsState(if (!photoMode) Accent else if (enabled) TextSecondary else TextDisabled, tween(TOGGLE_MS), label = "videoTint")
    Box(
        modifier = Modifier
            .size(width = Dimens.CaptureToggleCell * 2, height = Dimens.CaptureToggleCell)
            .clip(RoundedCornerShape(Dimens.CaptureToggleCell / 2))
            .background(PanelBackground),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .size(Dimens.CaptureToggleCell)
                .padding(Dimens.HalfGrid)
                .clip(RoundedCornerShape(Dimens.CaptureToggleCell / 2))
                .background(PanelSelected)
                .border(1.5.dp, Accent, RoundedCornerShape(Dimens.CaptureToggleCell / 2)),
        )
        Row {
            listOf(true to R.drawable.ic_capture_photo, false to R.drawable.ic_capture_video).forEach { (photo, icon) ->
                Box(
                    modifier = Modifier
                        .size(Dimens.CaptureToggleCell)
                        .clip(RoundedCornerShape(Dimens.CaptureToggleCell / 2))
                        .clickable(enabled = enabled && photo != photoMode) { onSelect(photo) }
                        .semantics {
                            contentDescription = if (photo) "Фото" else "Видео"
                            selected = photo == photoMode
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = if (photo) photoTint else videoTint,
                        modifier = Modifier.size(Dimens.Icon).rotateWithDevice(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(photoMode: Boolean, recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val innerSize by animateDpAsState(if (recording) 28.dp else 56.dp, label = "inner")
    val innerColor by animateColorAsState(
        when {
            photoMode -> if (busy) TextSecondary else TextPrimary
            recording -> TextPrimary
            else -> RecordRed
        },
        label = "color",
    )
    val ringColor by animateColorAsState(if (recording) RecordRed else Accent, label = "ring")
    Box(
        modifier = Modifier
            .size(Dimens.Shutter)
            .clip(CircleShape)
            .background(if (recording) RecordRed.copy(alpha = 0.85f) else Color(0x33000000))
            .border(4.dp, ringColor, CircleShape)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics {
                contentDescription = when {
                    photoMode -> "Сделать снимок"
                    recording -> "Остановить запись"
                    else -> "Начать запись"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .rotateWithDevice()
                .size(innerSize)
                .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                .background(innerColor),
        )
    }
}

@Composable
internal fun LastMediaButton(media: VhsCameraController.LastMedia, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // Видимая часть 40dp в ячейке 48dp — как у остальных кнопок ряда
            .size(Dimens.IconHighlight)
            .rotateWithDevice()
            .clip(RoundedCornerShape(10.dp))
            .background(PanelRaised)
            .border(2.dp, TextPrimary, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Открыть последний снимок или ролик" },
        contentAlignment = Alignment.Center,
    ) {
        media.thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (media.mimeType.startsWith("video")) {
            Text("▶", color = TextPrimary, fontSize = 20.sp)
        }
    }
}

@Composable
private fun RecordingBadge(nanos: Long, withAudio: Boolean, modifier: Modifier = Modifier) {
    val totalSec = nanos / 1_000_000_000L
    Row(
        modifier = modifier
            .background(RecordRed, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(TextPrimary))
        Text(
            String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60),
            color = TextPrimary,
            fontSize = 16.sp,
        )
        if (!withAudio) Text("без звука", color = TextPrimary, fontSize = 12.sp)
    }
}

/** «Фото из галереи»: открыть готовое фото для обработки эффектами. */
@Composable
private fun GalleryButton(enabled: Boolean, onClick: () -> Unit) {
    IconTouch(description = "Фото или видео из галереи", enabled = enabled, onClick = onClick) {
        Box(
            modifier = Modifier.size(Dimens.IconHighlight).clip(CircleShape).background(PanelRaised),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.rotateWithDevice()) { GalleryGlyph(if (enabled) TextPrimary else TextDisabled) }
        }
    }
}

private fun Context.hasPermission(permission: String) =
    checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

internal fun Context.openMedia(uri: Uri, mimeType: String, onError: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onError("Нет приложения для просмотра")
    }
}

private const val CONTROLS_MAX_FRACTION = 0.72f
private const val TOGGLE_MS = 150
/** Место под значок ориентации внутри таблетки формата. */
private val FORMAT_GLYPH_ROOM = 20.dp

/** Блок управления: по бокам — зона жеста «назад» (но не меньше 10dp), снизу — зона жеста «домой». */
internal val ControlsInsets: WindowInsets
    @Composable get() = WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)
        .union(WindowInsets(left = 10.dp, right = 10.dp))
        .union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
private const val LONG_PRESS_MS = 350L
/** Миниатюры пресетов: длинная сторона замороженного кадра, px. */
private const val THUMB_SIDE = 160
/** Выпадающий список пресетов — не выше этой доли экрана. */
private const val DROPDOWN_MAX_FRACTION = 0.45f
private const val VOLUME_ZOOM_STEP = 1.06f
private const val FOCUS_INDICATOR_MS = 3000L
private const val ZOOM_COLLAPSE_MS = 3000L
private const val KEY_AUDIO_ASKED = "audio_permission_asked"
private const val NO_AUDIO_MESSAGE = "Запись без звука: нет доступа к микрофону (можно включить в настройках)"
