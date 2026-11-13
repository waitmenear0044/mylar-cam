package com.vhscam.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.ViewPort
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.SessionConfig
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.FlashSetting
import com.vhscam.app.effect.FrameFormat
import com.vhscam.app.effect.PhotoQuality
import com.vhscam.app.effect.VideoFps
import com.vhscam.app.effect.VideoResolution
import com.vhscam.app.effect.SettingsStore
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Камера, запись и фото. Превью и VideoCapture привязаны вместе с одним [VhsCameraEffect]
 * (цели PREVIEW | VIDEO_CAPTURE), поэтому в файл попадает ровно тот кадр, что на экране.
 * Фото снимается отдельным ImageCapture в высоком разрешении и прогоняется через тот же
 * рендерер офлайн ([PhotoProcessor]). Работает на главном потоке.
 */
@Stable
class VhsCameraController(context: Context, private val lifecycleOwner: LifecycleOwner) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val store = SettingsStore(appContext)
    private val appPrefs = com.vhscam.app.AppPrefs.get(appContext)
    private val photoExecutor = Executors.newSingleThreadExecutor()
    private val photoProcessor = PhotoProcessor(appContext)

    val effect = VhsCameraEffect(appContext, CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE)

    /** Текущий режим и параметры всех режимов; сохраняются между запусками. */
    var settings by mutableStateOf(store.load())
        private set
    var photoMode by mutableStateOf(
        when (appPrefs.startMode) {
            com.vhscam.app.StartMode.PHOTO -> true
            com.vhscam.app.StartMode.VIDEO -> false
            com.vhscam.app.StartMode.LAST -> store.photoMode
        },
    )
        private set

    var surfaceRequest by mutableStateOf<SurfaceRequest?>(null)
        private set
    var useFrontCamera by mutableStateOf(false)
        private set
    var canFlip by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
        private set
    var recordingWithAudio by mutableStateOf(false)
        private set
    var recordedNanos by mutableLongStateOf(0L)
        private set
    var isTakingPhoto by mutableStateOf(false)
        private set

    /** Формат итогового кадра; меняется только вне записи (требует перепривязки). */
    var frameFormat by mutableStateOf(store.frameFormat)
        private set
    /** Вспышка для фото (Выкл/Авто/Вкл) и фонарик для видео; запоминаются. */
    var flash by mutableStateOf(store.flash)
        private set
    var torch by mutableStateOf(store.torch)
        private set
    var hasFlashUnit by mutableStateOf(false)
        private set

    /** Качество фото и разрешение видео; запоминаются, меняются только вне записи. */
    var photoQuality by mutableStateOf(store.photoQuality)
        private set
    var videoResolution by mutableStateOf(store.videoResolution)
        private set
    /** Размеры фото 4:3 и разрешения видео, которые текущая камера реально отдаёт приложению. */
    var availablePhotoSizes by mutableStateOf<List<Size>>(emptyList())
        private set
    var availableVideoResolutions by mutableStateOf(listOf(VideoResolution.HD))
        private set
    /** Частота кадров превью и видео; запоминается, меняется только вне записи. */
    var videoFps by mutableStateOf(store.videoFps)
        private set
    /** Частоты, которые держат текущая камера, разрешение видео и кодек. */
    var availableFps by mutableStateOf(listOf(VideoFps.FPS30))
        private set
    /** Диапазон CameraX для каждой доступной частоты; пусто — камера не сообщила, частоту выбирает CameraX. */
    private var fpsRanges: Map<VideoFps, Range<Int>> = emptyMap()
    /** Размер, с которым ImageCapture действительно привязан (для подписи «Макс · 12 Мп»). */
    var boundPhotoSize by mutableStateOf<Size?>(null)
        private set
    /** Размер, в котором реально пишется видео (CameraX может выбрать меньше заказанного). */
    var boundVideoSize by mutableStateOf<Size?>(null)
        private set
    /**
     * Зум: текущее значение, диапазон камеры и быстрые кнопки (строятся в рантайме из zoomState
     * и физических камер). Сохраняется при перепривязке и смене режима эффекта, сбрасывается
     * на 1× при переключении передней/задней камеры.
     */
    var zoomRatio by mutableFloatStateOf(1f)
        private set
    var minZoom by mutableFloatStateOf(1f)
        private set
    var maxZoom by mutableFloatStateOf(1f)
        private set
    /** Крупные риски шкалы зума: 1× и собственный зум физических камер (к ним примагничивается палец). */
    var zoomMarks by mutableStateOf(listOf(1f))
        private set
    private var zoomObserved: Pair<androidx.lifecycle.LiveData<androidx.camera.core.ZoomState>, androidx.lifecycle.Observer<androidx.camera.core.ZoomState>>? = null
    /** Компенсация экспозиции: индекс и диапазон камеры; null — камера компенсацию не поддерживает. */
    var exposureIndex by mutableIntStateOf(0)
        private set
    var exposureRange by mutableStateOf<IntRange?>(null)
        private set
    var exposureStep by mutableFloatStateOf(0f)
        private set

    /**
     * Ориентация съёмки по датчику (Surface.ROTATION_*): в ней сохраняются фото и видео, вдоль её горизонтали
     * идут строки и оверлей эффекта. Экран при этом всегда портретный. Во время записи не меняется
     * (ориентация файла фиксируется в момент старта записи).
     */
    var captureRotation by mutableIntStateOf(Surface.ROTATION_0)
        private set
    /** Снимаем в альбомной ориентации: формат перевёрнут (3:4 -> 4:3), полный кадр 4:3. */
    val landscape: Boolean
        get() = captureRotation == Surface.ROTATION_90 || captureRotation == Surface.ROTATION_270

    /** Белый экран на максимальной яркости во время снимка с экранной вспышкой. */
    var screenFlashActive by mutableStateOf(false)
        private set

    /** Вспышка доступна: в фото — у задней с лампой или у фронтальной (экранная), в видео — только фонарик задней. */
    val flashAvailable: Boolean
        get() = if (photoMode) hasFlashUnit || useFrontCamera else hasFlashUnit && !useFrontCamera

    data class LastMedia(val uri: Uri, val mimeType: String, val thumbnail: Bitmap?)

    var lastMedia by mutableStateOf<LastMedia?>(null)
        private set

    /** Короткие сообщения для пользователя (показываются снекбаром). */
    val messages = Channel<String>(Channel.BUFFERED)

    private var provider: ProcessCameraProvider? = null
    /** Для текущего снимка решено светить экраном (только фронтальная камера). */
    private var screenFlashThisShot = false
    private var camera: Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    private val applyBitrateLater = Runnable { applyBitrate() }
    private val checkPreviewFpsLater = Runnable { checkPreviewFps() }
    // Во время записи: если кадры заметно не успевают (тяжёлый режим, нагрев) — одно предупреждение за запись.
    // Разрешение и частоту сами не снижаем
    private var slowSinceMs = 0L
    private var slowWarned = false
    private val watchRecordingFps = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val now = android.os.SystemClock.elapsedRealtime()
            val measured = effect.fps
            if (measured > 0f && measured < videoFps.fps * SLOW_FPS_SHARE) {
                if (slowSinceMs == 0L) slowSinceMs = now
                if (!slowWarned && now - slowSinceMs >= SLOW_FPS_WARN_MS) {
                    slowWarned = true
                    messages.trySend("Запись идёт ${measured.roundToInt()} из ${videoFps.fps} fps — телефон не успевает (тяжёлый режим или нагрев). Разрешение не снижается; можно выбрать 720p или режим полегче")
                }
            } else {
                slowSinceMs = 0L
            }
            handler.postDelayed(this, 1000L)
        }
    }
    private val thermalListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        android.os.PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && isRecording) {
                messages.trySend("Телефон сильно нагрелся — кадры могут пропадать. Разрешение не снижается")
            }
        }
    } else {
        null
    }
    private var recording: Recording? = null
    private var lastFileLabel = ""

    // 4:3 — кадр как у VHS; одинаковый у превью, записи и фото
    private val preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build(),
        )
        .build()
        .apply { setSurfaceProvider { request -> surfaceRequest = request } }

    private var videoBitrate = bitrateFor(settings.effective(), videoResolution, videoFps)
    private var videoCapture = buildVideoCapture(videoBitrate)

    // Экранная вспышка для фронтальной камеры: белый экран на максимальной яркости
    private val screenFlashImpl = object : ImageCapture.ScreenFlash {
        override fun apply(expirationTimeMillis: Long, listener: ImageCapture.ScreenFlashListener) {
            mainExecutor.execute {
                // Белый экран — только для фронтальной камеры и только если для этого снимка
                // решено светить экраном. Иначе ничего не показываем и сразу отпускаем CameraX.
                val allowed = useFrontCamera && screenFlashThisShot
                Log.i(FLASH_TAG, "screenFlash.apply camera=${cameraName()} allowed=$allowed overlay=$allowed")
                if (!allowed) {
                    listener.onCompleted()
                    return@execute
                }
                screenFlashActive = true
                // Даём экрану разгореться, затем CameraX делает снимок
                handler.postDelayed({ listener.onCompleted() }, SCREEN_FLASH_WARMUP_MS)
            }
        }

        override fun clear() {
            mainExecutor.execute { screenFlashActive = false }
        }
    }

    private var imageCapture = buildImageCapture(photoQuality)

    // Свернули приложение во время записи — останавливаем явно, чтобы файл закрылся корректно,
    // а не дожидаемся, пока Recorder сам завершит запись с ошибкой «камера неактивна».
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = stopRecording()
    }

    init {
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(android.os.PowerManager::class.java)
                ?.addThermalStatusListener(mainExecutor, thermalListener as android.os.PowerManager.OnThermalStatusChangedListener)
        }
        effect.settings = settings.effective()
        effect.targetFps = videoFps.fps
        effect.stamp = appPrefs.stamp
    }

    /** Штамп даты: общий для режимов, запоминается, сразу виден на превью и попадает в фото и видео. */
    var stamp: com.vhscam.app.StampSettings
        get() = appPrefs.stamp
        set(value) {
            appPrefs.stamp = value
            effect.stamp = value
        }

    /** Замороженный кадр камеры без эффекта (для миниатюр пресетов); [callback] — на главном потоке. */
    fun grabFrame(maxSide: Int, callback: (Bitmap) -> Unit) {
        effect.grabFrame(maxSide) { bitmap -> mainExecutor.execute { callback(bitmap) } }
    }

    suspend fun start() {
        val p = ProcessCameraProvider.awaitInstance(appContext)
        val hasBack = p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        if (!hasBack) useFrontCamera = true
        canFlip = hasBack && hasFront
        provider = p
        bind()
    }

    fun updateSettings(newSettings: EffectSettings) {
        settings = newSettings
        // В рендер идут итоговые значения с учётом силы пресета; ползунки показывают базовые
        effect.settings = newSettings.effective()
        store.save(newSettings)
        // Битрейт зависит от режима и силы шума; применяем с задержкой, чтобы не перепривязывать
        // камеру на каждом движении ползунка
        handler.removeCallbacks(applyBitrateLater)
        handler.postDelayed(applyBitrateLater, BITRATE_APPLY_DELAY_MS)
    }

    fun updateFrameFormat(format: FrameFormat) {
        if (isRecording || format == frameFormat) return
        frameFormat = format
        store.frameFormat = format
        bind()
    }

    /** Фото: Выкл → Авто → Вкл. */
    fun cycleFlash() {
        flash = flash.next()
        store.flash = flash
        applyFlash()
    }

    /** Видео: фонарик вкл/выкл (можно и во время записи). */
    fun toggleTorch() {
        torch = !torch
        store.torch = torch
        applyTorch()
    }

    /**
     * Режим вспышки ImageCapture для текущей камеры. Задняя: лампа (Авто/Вкл), экранный режим — никогда.
     * Фронтальная: экранная вспышка при «Вкл»; при «Авто» — только в тёмной сцене (CameraX в экранном
     * режиме сам не решает, нужна ли вспышка, поэтому смотрим на среднюю яркость кадра).
     */
    private fun flashModeForCurrentCamera(): Int = when {
        flash == FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
        useFrontCamera -> when (flash) {
            FlashSetting.ON -> ImageCapture.FLASH_MODE_SCREEN
            else -> if (effect.sceneLuma < AUTO_SCREEN_FLASH_LUMA) ImageCapture.FLASH_MODE_SCREEN else ImageCapture.FLASH_MODE_OFF
        }
        !hasFlashUnit -> ImageCapture.FLASH_MODE_OFF
        flash == FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        else -> ImageCapture.FLASH_MODE_ON
    }

    private fun applyFlash() {
        imageCapture.flashMode = flashModeForCurrentCamera()
    }

    private fun cameraName() = if (useFrontCamera) "front" else "back"

    private fun modeName(mode: Int) = when (mode) {
        ImageCapture.FLASH_MODE_OFF -> "OFF"
        ImageCapture.FLASH_MODE_AUTO -> "AUTO"
        ImageCapture.FLASH_MODE_ON -> "ON"
        ImageCapture.FLASH_MODE_SCREEN -> "SCREEN"
        else -> mode.toString()
    }

    private fun applyTorch() {
        camera?.cameraControl?.enableTorch(!photoMode && torch && hasFlashUnit && !useFrontCamera)
    }

    /**
     * Показать оригинал без эффекта, пока палец на превью. Во время записи не работает:
     * превью и запись идут через один выход эффекта, и оригинал попал бы в файл.
     */
    fun showOriginal(show: Boolean) {
        effect.bypass = show && !isRecording
    }

    /** Фото и видео привязываются по-разному (см. [sessionConfig]), поэтому переключение перепривязывает камеру. */
    fun updatePhotoMode(photo: Boolean) {
        if (isRecording || photo == photoMode) return
        photoMode = photo
        store.photoMode = photo
        bind()
    }

    /**
     * Поворот телефона по датчику ([degrees] по часовой: 0, 90, 270). Перепривязка не нужна: область кадра на сенсоре
     * та же (превью не меняется), меняется только ориентация файла и «горизонталь» эффекта. Во время записи
     * не применяется — интерфейс передаст положение снова после остановки.
     */
    fun updateDeviceOrientation(degrees: Int) {
        if (isRecording || recording != null) return
        val rotation = when (degrees) {
            90 -> Surface.ROTATION_270
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (rotation == captureRotation) return
        captureRotation = rotation
        applyCaptureRotation()
    }

    /** Ориентация файлов фото и видео и поворот эффекта относительно сенсора — по [captureRotation]. */
    private fun applyCaptureRotation() {
        imageCapture.targetRotation = captureRotation
        videoCapture.targetRotation = captureRotation
        camera?.let { effect.frameRotation = it.cameraInfo.getSensorRotationDegrees(captureRotation) }
    }

    /**
     * Во время записи не переключаем: CameraX умеет продолжать запись после перепривязки
     * (persistent recording), но вместе с эффектом (StreamSharing) падает, если у камер разные
     * размеры кадра (IllegalArgumentException в VirtualCameraAdapter, CameraX 1.6.2). Это обычное
     * дело — у фронтальной часто меньше максимальное разрешение, — поэтому кнопка на время записи заблокирована.
     */
    fun flipCamera() {
        if (!canFlip || isRecording) return
        useFrontCamera = !useFrontCamera
        // Зум и экспозиция у другой камеры свои: начинаем с 1× и без компенсации
        zoomRatio = 1f
        exposureIndex = 0
        bind()
    }

    /**
     * Привязка с откатом: если камера не тянет выбранный размер фото или разрешение видео вместе
     * с превью и эффектом, качество снижается на шаг (сначала фото, потом видео) и пробуем снова.
     */
    private fun bind() {
        val p = provider ?: return
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // Возможности камеры — только из рантайма; если опрос не удался, не падаем: фото «Макс»
        // (CameraX выберет сам), видео 720p с запасным вариантом в QualitySelector
        val info = runCatching { p.getCameraInfo(selector) }.getOrNull()
        availablePhotoSizes = info?.let { runCatching { CameraCaps.photoSizes(it) }.getOrNull() }.orEmpty()
        availableVideoResolutions = info?.let { runCatching { CameraCaps.videoResolutions(it) }.getOrNull() }
            .orEmpty().ifEmpty { listOf(VideoResolution.HD) }
        if (availablePhotoSizes.isEmpty()) {
            Log.w(TAG, "No photo sizes reported for ${cameraName()} camera, using CameraX default")
            if (!photoQuality.isMax) {
                messages.trySend("Камера не сообщила размеры фото — выбрано «Макс»")
                setPhotoQualityInternal(PhotoQuality.MAX)
            }
        }
        // Выбранного разрешения видео у этой камеры может не быть — берём ближайшее меньшее
        if (videoResolution !in availableVideoResolutions) {
            val fallback = availableVideoResolutions.lastOrNull { it.ordinal < videoResolution.ordinal }
                ?: availableVideoResolutions.first()
            setVideoResolutionInternal(fallback)
        }
        var photoSteps = availablePhotoSizes.size
        var videoSteps = availableVideoResolutions.size
        var fpsSteps = VideoFps.entries.size
        handler.removeCallbacks(checkPreviewFpsLater)
        while (true) {
            if (info != null) refreshFrameRates(info)
            if (bindOnce(p, selector) != null) {
                boundPhotoSize = if (photoMode) imageCapture.resolutionInfo?.resolution else null
                boundVideoSize = if (photoMode) null else videoCapture.resolutionInfo?.resolution
                // CameraX молча уменьшает поток, если сессия не держит частоту в заказанном размере:
                // при 24/60 fps возвращаемся к 30, при 30 — честно сообщаем реальный размер
                if (!photoMode && !videoSizeMatches()) {
                    val actual = boundVideoSize
                    if (videoFps != VideoFps.FPS30 && VideoFps.FPS30 in availableFps && fpsSteps-- > 0) {
                        messages.trySend("${videoResolution.label} ${videoFps.label} камера отдаёт только ${actual?.width}×${actual?.height} — выбрано 30 fps")
                        setVideoFpsInternal(VideoFps.FPS30)
                        continue
                    }
                    messages.trySend("Камера отдаёт видео ${actual?.width}×${actual?.height} вместо ${videoResolution.label}")
                }
                Log.i(TAG, "bound photo=$boundPhotoSize (${photoQuality.encode()}) video=$videoResolution fps=$videoFps range=${fpsRanges[videoFps]} available=$availableFps")
                Log.i(TAG, "bound sizes: video=${videoCapture.resolutionInfo?.resolution} preview=${preview.resolutionInfo?.resolution} photo=${imageCapture.resolutionInfo?.resolution}")
                // Держит ли превью выбранную частоту — смотрим по счётчику через несколько секунд
                if (lowerFps() != null) handler.postDelayed(checkPreviewFpsLater, FPS_CHECK_DELAY_MS)
                return
            }
            val lowerPhoto = nextLowerPhoto()
            val lowerFps = lowerFps()
            when {
                lowerPhoto != null && photoSteps-- > 0 -> {
                    messages.trySend("Камера не тянет ${photoLabel()} вместе с видео — выбрано ${PhotoQuality.label(lowerPhoto.megapixels)}")
                    setPhotoQualityInternal(lowerPhoto)
                }
                lowerFps != null && fpsSteps-- > 0 -> {
                    messages.trySend("Камера не тянет ${videoResolution.label} ${videoFps.label} — выбрано ${lowerFps.label}")
                    setVideoFpsInternal(lowerFps)
                }
                videoResolution != VideoResolution.HD && videoSteps-- > 0 -> {
                    val lower = availableVideoResolutions.lastOrNull { it.ordinal < videoResolution.ordinal } ?: VideoResolution.HD
                    messages.trySend("Камера не тянет видео ${videoResolution.label} — выбрано ${lower.label}")
                    setVideoResolutionInternal(lower)
                }
                else -> {
                    messages.trySend("Не удалось запустить камеру")
                    return
                }
            }
        }
    }

    /**
     * Сессия: превью, видео, фото и эффект. Формат кадра: CameraX обрезает превью и запись через ViewPort,
     * а эффект при этом считается по полному кадру 3:4 (см. VhsSurfaceProcessor). Для 3:4 ViewPort не нужен.
     * Частота кадров задаётся сессии целиком — превью, запись и кодер идут на одной частоте.
     */
    private fun sessionConfig(frameRate: Range<Int>?): SessionConfig {
        // Фото в полном разрешении в одной сессии с видео ограничивает размер видеопотока (CameraX тогда
        // молча берёт меньший), поэтому в режиме видео ImageCapture не привязываем, а в режиме фото
        // VideoCapture остаётся: эффект (StreamSharing) рассчитан на превью + видео
        val useCases = if (photoMode) listOf(preview, videoCapture, imageCapture) else listOf(preview, videoCapture)
        val builder = SessionConfig.Builder(useCases).addEffect(effect)
        if (!frameFormat.isFull) {
            builder.setViewPort(ViewPort.Builder(frameFormat.rational, Surface.ROTATION_0).build())
        }
        if (frameRate != null) builder.setFrameRateRange(frameRate)
        return builder.build()
    }

    /**
     * Какие частоты доступны для текущей камеры и разрешения видео (диапазоны CameraX именно для этой
     * сессии + кодер). Если выбранной нет — берём ближайшую меньшую (или ближайшую вообще).
     */
    private fun refreshFrameRates(info: CameraInfo) {
        val ranges = runCatching { info.getSupportedFrameRateRanges(sessionConfig(null)) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { info.supportedFrameRateRanges }.getOrDefault(emptySet())
        fpsRanges = runCatching { CameraCaps.frameRates(info, videoResolution, ranges) }.getOrDefault(emptyMap())
        Log.i(TAG, "frame rate ranges ${cameraName()} $videoResolution: $ranges -> ${fpsRanges.keys}")
        if (fpsRanges.isEmpty()) Log.w(TAG, "No frame rate ranges for ${cameraName()} camera, CameraX default")
        availableFps = fpsRanges.keys.toList().ifEmpty { listOf(VideoFps.FPS30) }
        if (videoFps !in availableFps) {
            val fallback = availableFps.lastOrNull { it.fps < videoFps.fps } ?: availableFps.first()
            messages.trySend("${videoResolution.label} ${videoFps.label} здесь недоступно — выбрано ${fallback.label}")
            setVideoFpsInternal(fallback)
        }
    }

    private fun lowerFps(): VideoFps? = availableFps.lastOrNull { it.fps < videoFps.fps }

    /** Короткая сторона видео не меньше ~90% заказанного качества (720 / 1080 / 2160). */
    private fun videoSizeMatches(): Boolean {
        val size = boundVideoSize ?: return true
        val wanted = when (videoResolution) {
            VideoResolution.HD -> 720
            VideoResolution.FHD -> 1080
            VideoResolution.UHD -> 2160
        }
        return minOf(size.width, size.height) >= wanted * 0.9f
    }

    /** Превью заметно не держит выбранную частоту (меньше 80%) — шаг вниз, как с качеством. */
    private fun checkPreviewFps() {
        val lower = lowerFps() ?: return
        val measured = effect.fps
        if (isRecording || measured <= 0f || measured >= videoFps.fps * 0.8f) return
        Log.w(TAG, "preview holds $measured of ${videoFps.fps} fps, stepping down")
        messages.trySend("Превью не держит ${videoFps.label} — выбрано ${lower.label}")
        setVideoFpsInternal(lower)
        bind()
    }

    private fun bindOnce(p: ProcessCameraProvider, selector: CameraSelector) = run {
        // Камера не сообщила диапазоны — частоту не задаём (CameraX возьмёт свою, обычно 30)
        val config = sessionConfig(fpsRanges[videoFps])
        // Экранный режим, оставшийся от фронтальной камеры, CameraX не даёт привязать к задней
        // («Not a front camera despite setting FLASH_MODE_SCREEN»): сбрасываем до привязки,
        // нужный режим выставляется после неё.
        imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
        // Превью — по экрану (всегда портрет), файлы — по ориентации телефона
        preview.targetRotation = Surface.ROTATION_0
        videoCapture.targetRotation = captureRotation
        imageCapture.targetRotation = captureRotation
        try {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, config).also { bound ->
                camera = bound
                // «Горизонталь» эффекта — горизонталь итогового файла: угол между сенсором и ориентацией съёмки
                effect.frameRotation = bound.cameraInfo.getSensorRotationDegrees(captureRotation)
                // Превью и запись фронтальной камеры CameraX отражает после эффекта (MIRROR_MODE_ON_FRONT_ONLY)
                effect.frameMirror = bound.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
                hasFlashUnit = bound.cameraInfo.hasFlashUnit()
                applyFlash()
                Log.i(FLASH_TAG, "bind camera=${cameraName()} hasFlashUnit=$hasFlashUnit setting=$flash imageCaptureMode=${modeName(imageCapture.flashMode)}")
                applyTorch()
                applyZoomAndExposure(bound)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            camera = null
            hasFlashUnit = false
            null
        }
    }

    /**
     * После привязки: диапазон зума и кнопки из рантайма, компенсация экспозиции, если поддерживается;
     * прежние значения (зум, экспозиция) возвращаются — новая привязка их сбрасывает.
     */
    private fun applyZoomAndExposure(bound: Camera) {
        val info = bound.cameraInfo
        // Диапазон зума камера сообщает, когда откроется (до этого в zoomState значение по умолчанию):
        // подписываемся и при каждом обновлении пересобираем кнопки и возвращаем выбранный зум
        zoomObserved?.let { (data, observer) -> data.removeObserver(observer) }
        val wanted = zoomRatio
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            val rangeChanged = state.minZoomRatio != minZoom || state.maxZoomRatio != maxZoom
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
            if (rangeChanged) {
                zoomMarks = zoomMarks(info, minZoom, maxZoom)
                Log.i(TAG, "zoom $minZoom..$maxZoom marks=$zoomMarks")
                if (camera === bound && maxZoom > minZoom) {
                    zoomRatio = wanted.coerceIn(minZoom, maxZoom)
                    bound.cameraControl.setZoomRatio(zoomRatio)
                }
            }
        }
        info.zoomState.observe(lifecycleOwner, observer)
        zoomObserved = info.zoomState to observer
        val exposure = info.exposureState
        if (exposure.isExposureCompensationSupported) {
            val range = exposure.exposureCompensationRange
            exposureRange = range.lower..range.upper
            exposureStep = exposure.exposureCompensationStep.toFloat()
            exposureIndex = exposureIndex.coerceIn(range.lower, range.upper)
            bound.cameraControl.setExposureCompensationIndex(exposureIndex)
        } else {
            exposureRange = null
            exposureIndex = 0
        }
        Log.i(TAG, "exposure=$exposureRange step=$exposureStep")
    }

    /**
     * Крупные риски шкалы зума: 1× и собственный зум физических камер (ширик, телевик). Ничего не зашито:
     * всё из zoomState и intrinsicZoomRatio камер; близкие значения схлопываются.
     */
    private fun zoomMarks(info: CameraInfo, min: Float, max: Float): List<Float> {
        if (max <= min + 0.01f) return listOf(min)
        val candidates = mutableListOf(1f)
        runCatching { info.physicalCameraInfos }.getOrDefault(emptySet()).forEach {
            val r = runCatching { it.intrinsicZoomRatio }.getOrDefault(CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN)
            if (r != CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN && r > 0f) candidates += r
        }
        val result = mutableListOf<Float>()
        for (r in candidates.map { roundZoom(it) }.filter { it in min - 0.01f..max + 0.01f }.sorted()) {
            if (result.none { kotlin.math.abs(it - r) / r < 0.08f }) result += r
        }
        return result.ifEmpty { listOf(1f) }
    }

    private fun roundZoom(r: Float): Float = if (r < 1f) (r * 10f).roundToInt() / 10f else (r * 2f).roundToInt() / 2f

    /** Плавный зум (щипок): умножить текущий на [factor]. Работает и во время записи. */
    fun zoomBy(factor: Float) = setZoom(zoomRatio * factor)

    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val r = ratio.coerceIn(minZoom, maxZoom)
        if (r == zoomRatio) return
        zoomRatio = r
        cam.cameraControl.setZoomRatio(r)
    }

    fun setExposure(index: Int) {
        val range = exposureRange ?: return
        val cam = camera ?: return
        val i = index.coerceIn(range.first, range.last)
        if (i == exposureIndex) return
        exposureIndex = i
        cam.cameraControl.setExposureCompensationIndex(i)
    }

    /**
     * Фокус и экспозиция в точке превью. [u], [v] — точка касания в долях видимого кадра на экране (уже без полей
     * вокруг кадра). Переводим в координаты сенсора сами, не через матрицу буфера превью (после эффекта она
     * зависит от устройства и формата): снимаем обрезку формата (кадр — центральная часть полного 3:4),
     * зеркало фронтальной камеры и поворот между сенсором и портретным экраном.
     * Через 4 с камера возвращается к непрерывному автофокусу. [onResult]: поймал или нет (главный поток).
     */
    fun focusAt(u: Float, v: Float, onResult: (Boolean) -> Unit) {
        val cam = camera ?: return
        // Экран всегда портретный: кадр на экране = кадр сенсора, повёрнутый на угол сенсора
        val rotation = cam.cameraInfo.sensorRotationDegrees
        val mirror = cam.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
        val fullAspect = FULL_FRAME_ASPECT
        val frameAspect = frameFormat.aspect
        var x = u.coerceIn(0f, 1f)
        var y = v.coerceIn(0f, 1f)
        // Видимый кадр — центральная часть полного кадра
        if (frameAspect > fullAspect) y = 0.5f + (y - 0.5f) * fullAspect / frameAspect
        else x = 0.5f + (x - 0.5f) * frameAspect / fullAspect
        // Фронтальная показана зеркально
        if (mirror) x = 1f - x
        // Итоговый кадр = кадр сенсора, повёрнутый по часовой на rotation: поворачиваем обратно
        val (s, t) = when (rotation) {
            90 -> y to 1f - x
            180 -> 1f - x to 1f - y
            270 -> 1f - y to x
            else -> x to y
        }
        // Пропорции полного кадра в ориентации сенсора: CameraX сам впишет их в область кадра сенсора
        val sensorAspect = if (rotation % 180 != 0) 1f / fullAspect else fullAspect
        val point = SurfaceOrientedMeteringPointFactory(sensorAspect, 1f).createPoint(s * sensorAspect, t)
        Log.i(TAG, "focus tap ($u, $v) rotation=$rotation mirror=$mirror -> sensor ($s, $t)")
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).setAutoCancelDuration(FOCUS_AUTO_CANCEL_S, TimeUnit.SECONDS).build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
            onResult(ok)
        }, mainExecutor)
    }

    fun updatePhotoQuality(quality: PhotoQuality) {
        if (isRecording || isTakingPhoto || quality == photoQuality) return
        setPhotoQualityInternal(quality)
        bind()
    }

    fun updateVideoResolution(resolution: VideoResolution) {
        if (isRecording || resolution == videoResolution) return
        setVideoResolutionInternal(resolution)
        bind()
    }

    private fun setPhotoQualityInternal(quality: PhotoQuality) {
        photoQuality = quality
        store.photoQuality = quality
        imageCapture = buildImageCapture(quality)
    }

    fun updateVideoFps(fps: VideoFps) {
        if (isRecording || fps == videoFps) return
        setVideoFpsInternal(fps)
        bind()
    }

    private fun setVideoResolutionInternal(resolution: VideoResolution) {
        videoResolution = resolution
        store.videoResolution = resolution
        videoBitrate = bitrateFor(settings.effective(), resolution, videoFps)
        videoCapture = buildVideoCapture(videoBitrate)
    }

    private fun setVideoFpsInternal(fps: VideoFps) {
        videoFps = fps
        store.videoFps = fps
        effect.targetFps = fps.fps
        videoBitrate = bitrateFor(settings.effective(), videoResolution, fps)
        videoCapture = buildVideoCapture(videoBitrate)
    }

    /** Следующий меньший доступный размер фото (для отката). */
    private fun nextLowerPhoto(): PhotoQuality? {
        val current = boundOrRequestedPixels()
        return availablePhotoSizes.firstOrNull { it.width.toLong() * it.height < current }
            ?.let { PhotoQuality(it.width, it.height) }
    }

    private fun boundOrRequestedPixels(): Long =
        if (photoQuality.isMax) availablePhotoSizes.firstOrNull()?.let { it.width.toLong() * it.height } ?: Long.MAX_VALUE
        else photoQuality.width.toLong() * photoQuality.height

    fun photoLabel(): String {
        val size = boundPhotoSize
        val mp = size?.let { PhotoQuality.label(it.width * it.height / 1_000_000f) }
        return when {
            photoQuality.isMax -> if (mp != null) "Макс · $mp" else "Макс"
            else -> PhotoQuality.label(photoQuality.megapixels)
        }
    }

    /**
     * Фото: «Макс» — самый большой 4:3, включая «медленные» большие размеры
     * (PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE); иначе ближайший к выбранному.
     */
    private fun buildImageCapture(quality: PhotoQuality): ImageCapture {
        val strategy = if (quality.isMax) {
            ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
        } else {
            ResolutionStrategy(Size(quality.width, quality.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        }
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .setResolutionStrategy(strategy)
                    .build(),
            )
            .build()
            .apply { screenFlash = screenFlashImpl }
    }

    /** Битрейт видео зависит от режима, шума и разрешения; меняется только между записями (требует перепривязки). */
    private fun applyBitrate() {
        val wanted = bitrateFor(settings.effective(), videoResolution, videoFps)
        if (wanted == videoBitrate || isRecording) return
        videoBitrate = wanted
        videoCapture = buildVideoCapture(wanted)
        bind()
    }

    private fun buildVideoCapture(bitrate: Int): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(CameraCaps.qualityOf(videoResolution), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
            )
            .setAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetVideoEncodingBitRate(bitrate)
            .build()
        // Фронтальная в файле зеркальная, как в превью: снимаешь себя и видишь ровно то, что запишется
        return VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
    }

    fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // withAudioEnabled вызывается только при выданном разрешении
    fun startRecording() {
        // isRecording остаётся true, пока предыдущий файл дописывается (до события Finalize)
        if (recording != null || isRecording) return
        effect.bypass = false
        // Шум у каждого ролика свой: новое зерно генератора на запись
        effect.newSeed()
        val album = appPrefs.folder
        val name = "VID_" + timestamp()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$album")
            } else {
                // Android 8–9: RELATIVE_PATH ещё нет, путь задаётся напрямую (нужно WRITE_EXTERNAL_STORAGE)
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), album)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(dir, "$name.mp4").absolutePath)
            }
        }
        val options = MediaStoreOutputOptions
            .Builder(appContext.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        val withAudio = hasAudioPermission()
        lastFileLabel = "Movies/$album/$name.mp4"
        var pending = videoCapture.output.prepareRecording(appContext, options)
        if (withAudio) pending = pending.withAudioEnabled()
        recordingWithAudio = withAudio
        recordedNanos = 0L
        recording = pending.start(mainExecutor, ::onRecordEvent)
        isRecording = true
        slowSinceMs = 0L
        slowWarned = false
        handler.removeCallbacks(watchRecordingFps)
        handler.postDelayed(watchRecordingFps, 3000L)
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        recordedNanos = event.recordingStats.recordedDurationNanos
        if (event !is VideoRecordEvent.Finalize) return
        isRecording = false
        recording = null
        val uri = event.outputResults.outputUri
        // При части ошибок (камера закрылась, кончилось место) файл всё равно сохраняется
        if (uri != Uri.EMPTY) {
            lastMedia = LastMedia(uri, "video/mp4", loadVideoThumbnail(uri))
            messages.trySend("Сохранено: $lastFileLabel")
            if (event.hasError()) Log.w(TAG, "Saved with error ${event.error}", event.cause)
        } else {
            Log.e(TAG, "Recording failed: ${event.error}", event.cause)
            messages.trySend("Ошибка записи (код ${event.error}), файл не сохранён")
        }
        // Режим или шум могли поменять во время записи — теперь можно применить новый битрейт
        applyBitrate()
    }

    private fun loadVideoThumbnail(uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { appContext.contentResolver.loadThumbnail(uri, android.util.Size(240, 320), null) }.getOrNull()
        } else {
            null
        }

    /**
     * Снимок с текущим режимом и параметрами (на момент нажатия). Режим вспышки выставляется заново
     * прямо перед снимком. Возвращает true, если интерфейсу можно показать затворное мигание:
     * только когда ни лампа, ни экран не будут светить (иначе мигание сливается со вспышкой).
     */
    fun takePhoto(): Boolean {
        if (isTakingPhoto || isRecording || camera == null || !photoMode) return false
        isTakingPhoto = true
        // «Гостинг» существует только во времени: фото — это кадр превью как есть (с копиями и штампом)
        if (settings.mode == EffectMode.GHOST && settings.enabled) {
            val name = "IMG_" + timestamp()
            effect.grabOutput { bitmap ->
                photoExecutor.execute {
                    val result = runCatching { photoProcessor.saveReady(bitmap, name) }
                    bitmap.recycle()
                    mainExecutor.execute {
                        isTakingPhoto = false
                        result.onSuccess { saved ->
                            lastMedia = LastMedia(saved.uri, "image/jpeg", saved.thumbnail)
                            messages.trySend("Сохранено как на экране (${PhotoQuality.label(saved.megapixels)}): ${saved.label}")
                        }.onFailure {
                            Log.e(TAG, "Ghost photo failed", it)
                            messages.trySend("Не удалось сохранить фото")
                        }
                    }
                }
            }
            return true
        }
        val flashMode = flashModeForCurrentCamera()
        imageCapture.flashMode = flashMode
        screenFlashThisShot = flashMode == ImageCapture.FLASH_MODE_SCREEN
        if (!useFrontCamera && imageCapture.flashMode == ImageCapture.FLASH_MODE_SCREEN) {
            // Не должно случаться: на задней камере экранный режим запрещён
            Log.e(FLASH_TAG, "SCREEN flash mode on back camera, forcing OFF")
            imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
            screenFlashThisShot = false
        }
        val blink = imageCapture.flashMode == ImageCapture.FLASH_MODE_OFF
        Log.i(
            FLASH_TAG,
            "capture camera=${cameraName()} hasFlashUnit=$hasFlashUnit setting=$flash " +
                "imageCaptureMode=${modeName(imageCapture.flashMode)} sceneLuma=${"%.2f".format(effect.sceneLuma)} " +
                "screenOverlay=$screenFlashThisShot shutterBlink=$blink",
        )
        val snapshot = settings.effective()
        val format = frameFormat
        val shotLandscape = landscape
        val mirror = useFrontCamera
        val shotStamp = appPrefs.stamp.takeIf { it.enabled }
        val shotTimeMs = System.currentTimeMillis()
        val name = "IMG_" + timestamp()
        val callback = object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching {
                    val rotation = image.imageInfo.rotationDegrees
                    val source = image.use { SourceImage.from(it) }
                    try {
                        processWithMemoryFallback(source, rotation, mirror, snapshot, format, shotLandscape, name, shotStamp, shotTimeMs)
                    } finally {
                        source.close()
                    }
                }
                mainExecutor.execute {
                    isTakingPhoto = false
                    result.onSuccess { (saved, reduced) ->
                        lastMedia = LastMedia(saved.uri, "image/jpeg", saved.thumbnail)
                        messages.trySend(
                            if (reduced) "Не хватило памяти — сохранено в ${PhotoQuality.label(saved.megapixels)}: ${saved.label}"
                            else "Сохранено (${PhotoQuality.label(saved.megapixels)}): ${saved.label}",
                        )
                    }.onFailure {
                        Log.e(TAG, "Photo processing failed", it)
                        messages.trySend("Не удалось сохранить фото")
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed", exception)
                mainExecutor.execute {
                    isTakingPhoto = false
                    messages.trySend("Не удалось сделать снимок")
                }
            }
        }
        try {
            imageCapture.takePicture(photoExecutor, callback)
        } catch (e: RuntimeException) {
            Log.e(TAG, "takePicture failed", e)
            isTakingPhoto = false
            messages.trySend("Не удалось сделать снимок")
            return false
        }
        return blink
    }

    /**
     * Обработка в полном разрешении; если не хватило памяти (Java, нативной или GL) — повтор
     * в меньшем размере. Возвращает результат и признак «размер уменьшен».
     */
    private fun processWithMemoryFallback(
        source: SourceImage,
        rotation: Int,
        mirror: Boolean,
        settings: EffectSettings,
        format: FrameFormat,
        shotLandscape: Boolean,
        name: String,
        stamp: com.vhscam.app.StampSettings?,
        stampTimeMs: Long,
    ): Pair<PhotoProcessor.Result, Boolean> {
        var lastError: Throwable? = null
        for (scale in MEMORY_FALLBACK_SCALES) {
            try {
                // Снимок в альбомной ориентации: полный кадр 4:3 и перевёрнутый формат
                return photoProcessor.process(
                    source, rotation, mirror, settings, format, name, scale,
                    cropAspect = format.aspectFor(shotLandscape),
                    fullAspect = if (shotLandscape) 1f / FULL_FRAME_ASPECT else FULL_FRAME_ASPECT,
                    stamp = stamp, stampAutoMs = stampTimeMs,
                ) to (scale < 1f)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Photo processing out of memory at scale $scale", e)
                lastError = e
            } catch (e: IllegalStateException) {
                // Неполный framebuffer — обычно нехватка видеопамяти под буфер
                Log.w(TAG, "Photo processing GL failure at scale $scale", e)
                lastError = e
            }
            System.gc()
        }
        throw lastError ?: IllegalStateException("Photo processing failed")
    }

    /** Снимок с учётом cropRect, если CameraX его задал (при ViewPort), — иначе целиком. */
    private fun ImageProxy.toBitmapCropped(): Bitmap {
        val full = toBitmap()
        val r = cropRect
        val isSubRect = r.left >= 0 && r.top >= 0 && r.right <= full.width && r.bottom <= full.height &&
            (r.width() < full.width || r.height() < full.height) && r.width() > 0 && r.height() > 0
        if (!isSubRect) return full
        return Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height()).also { full.recycle() }
    }

    fun release() {
        handler.removeCallbacks(applyBitrateLater)
        handler.removeCallbacks(watchRecordingFps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(android.os.PowerManager::class.java)
                ?.removeThermalStatusListener(thermalListener as android.os.PowerManager.OnThermalStatusChangedListener)
        }
        camera?.cameraControl?.enableTorch(false)
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        stopRecording()
        provider?.unbindAll()
        effect.release()
        photoExecutor.shutdown()
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    private companion object {
        const val TAG = "VhsCameraController"
        /** Шаги уменьшения при нехватке памяти на обработку фото. */
        val MEMORY_FALLBACK_SCALES = floatArrayOf(1f, 0.71f, 0.5f)

        const val FLASH_TAG = "VhsFlash"
        const val SCREEN_FLASH_WARMUP_MS = 250L
        /** «Авто» экранной вспышки срабатывает, если средняя яркость кадра ниже этого. */
        const val AUTO_SCREEN_FLASH_LUMA = 0.25f
        const val BITRATE_APPLY_DELAY_MS = 800L
        const val FPS_CHECK_DELAY_MS = 4000L
        /** Запись «не успевает»: меньше этой доли выбранной частоты дольше [SLOW_FPS_WARN_MS]. */
        const val SLOW_FPS_SHARE = 0.75f
        const val SLOW_FPS_WARN_MS = 5000L
        /** Через столько секунд после тапа камера возвращается к непрерывному автофокусу. */
        const val FOCUS_AUTO_CANCEL_S = 4L
        /** Полный кадр эффекта (портрет 3:4, в альбомной ориентации 4:3): формат кадра — его центральная часть. */
        const val FULL_FRAME_ASPECT = 3f / 4f

        // ~720p 4:3 при 30 fps. «Пиксель»: крупные пиксели хорошо сжимаются, 5 Мбит/с хватает
        // (~37 МБ в минуту со звуком). «Плёнка»: зерно кодер иначе съедает, нужно ~14 Мбит/с.
        // Сильный цифровой шум (>0.3) кодер размазывает сильнее всего — тогда 18 Мбит/с.
        // Для 1080p и 4K — пропорционально больше; сильный шум ещё ×1.3 (кодер размазывает его сильнее всего).
        // «VHS»: картинка мягкая, но строки и шум ленты на гладких местах кодер сглаживает — 10 Мбит/с в 720p.
        // Частота: 60 fps ×1.5, 24 fps ×0.85 (больше или меньше кадров на тот же поток).
        fun bitrateFor(s: EffectSettings, resolution: VideoResolution, fps: VideoFps): Int {
            val ownNoise = if (s.mode == EffectMode.CORES) s.cores.coreNoise else 0f
            val strongNoise = s.enabled && maxOf(s.noise.luma, s.noise.chroma, ownNoise) > 0.3f
            val base = when (s.mode) {
                EffectMode.PIXEL -> when (resolution) {
                    VideoResolution.HD -> 5_000_000
                    VideoResolution.FHD -> 10_000_000
                    VideoResolution.UHD -> 30_000_000
                }
                EffectMode.FILM -> when (resolution) {
                    VideoResolution.HD -> 14_000_000
                    VideoResolution.FHD -> 24_000_000
                    VideoResolution.UHD -> 60_000_000
                }
                EffectMode.VHS -> when (resolution) {
                    VideoResolution.HD -> 10_000_000
                    VideoResolution.FHD -> 18_000_000
                    VideoResolution.UHD -> 45_000_000
                }
                // «Cores», «Градиент» (дизеринг) и «Гостинг»: мелкие детали кодер съедает, как у «Плёнки»
                EffectMode.CORES, EffectMode.GRADIENT, EffectMode.GHOST, EffectMode.NEWSPAPER, EffectMode.COLOR -> when (resolution) {
                    VideoResolution.HD -> 14_000_000
                    VideoResolution.FHD -> 24_000_000
                    VideoResolution.UHD -> 60_000_000
                }
            }
            val rateScale = when (fps) {
                VideoFps.FPS24 -> 0.85f
                VideoFps.FPS30 -> 1f
                VideoFps.FPS60 -> 1.5f
            }
            val scaled = (base * rateScale).toInt()
            return if (strongNoise) minOf((scaled * 1.3f).toInt(), 100_000_000) else scaled
        }
    }
}
