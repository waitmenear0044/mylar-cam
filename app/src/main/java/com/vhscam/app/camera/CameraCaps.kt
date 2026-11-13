package com.vhscam.app.camera

import com.vhscam.app.AppInfo
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.opengl.GLES30
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import com.vhscam.app.effect.PhotoQuality
import com.vhscam.app.effect.VideoFps
import com.vhscam.app.effect.VideoResolution
import com.vhscam.app.gl.EglCore
import kotlin.math.abs

/**
 * Что камера реально отдаёт приложению: размеры фото (в т.ч. «медленные» большие из
 * getHighResolutionOutputSizes), разрешения видео, режим максимального разрешения сенсора.
 */
@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
object CameraCaps {
    private const val FULL_ASPECT = 4f / 3f

    fun cameraId(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

    /**
     * Размеры фото 4:3 (JPEG), доступные через CameraX: обычные и большие «медленные»
     * (PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE). Режим максимального разрешения сенсора
     * (SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) CameraX 1.6 выбирать не позволяет.
     */
    fun photoSizes(info: CameraInfo): List<Size> {
        val map = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val sizes = map.getOutputSizes(ImageFormat.JPEG).orEmpty().toList() + map.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().toList()
        return sizes.filter { isFourThree(it) }.distinct().sortedByDescending { it.width.toLong() * it.height }
    }

    /** Разрешения видео, которые поддерживают и камера, и кодек (по данным Recorder). */
    fun videoResolutions(info: CameraInfo): List<VideoResolution> {
        val supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        return VideoResolution.entries.filter { qualityOf(it) in supported }
    }

    /**
     * Частоты 24/30/60, которые держат и камера (диапазоны CameraX для этой связки use case'ов и эффекта —
     * [ranges]), и кодер H.264 при этом разрешении. Для каждой частоты — диапазон, который надо задать:
     * фиксированный [f, f], если есть, иначе диапазон с верхней границей f.
     */
    fun frameRates(info: CameraInfo, resolution: VideoResolution, ranges: Set<Range<Int>>): Map<VideoFps, Range<Int>> {
        val size = runCatching { Recorder.getVideoCapabilities(info).getResolution(qualityOf(resolution), DynamicRange.SDR) }.getOrNull()
        val result = LinkedHashMap<VideoFps, Range<Int>>()
        for (f in VideoFps.entries) {
            val range = ranges.firstOrNull { it.lower == f.fps && it.upper == f.fps }
                ?: ranges.filter { it.upper == f.fps }.maxByOrNull { it.lower }
                ?: continue
            if (size != null && !encoderSupports(size, f.fps)) continue
            result[f] = range
        }
        return result
    }

    /** Есть ли кодер H.264 (его использует Recorder для MP4), который тянет размер и частоту. */
    private fun encoderSupports(size: Size, fps: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.let { caps ->
                    // Recorder пишет 4:3: проверяем и размер профиля, и его 4:3-вариант
                    val w43 = size.height * 4 / 3
                    caps.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) ||
                        caps.areSizeAndRateSupported(w43 - w43 % 16, size.height, fps.toDouble())
                } == true
        }
    }.getOrDefault(true) // не смогли опросить кодеки — решает привязка с откатом

    fun qualityOf(resolution: VideoResolution): Quality = when (resolution) {
        VideoResolution.HD -> Quality.HD
        VideoResolution.FHD -> Quality.FHD
        VideoResolution.UHD -> Quality.UHD
    }

    private fun isFourThree(size: Size): Boolean {
        val a = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)
        return abs(a - FULL_ASPECT) < 0.02f
    }

    /**
     * Текст для экрана «Сведения о камерах»: все камеры, видимые приложению (включая физические
     * камеры логических), размеры фото и видео, режим максимального разрешения, флаг
     * ULTRA_HIGH_RESOLUTION_SENSOR, максимальный размер текстуры GL и память. Без разрешения на камеру.
     */
    fun report(context: Context, videoByCamera: Map<String, List<VideoResolution>>): String {
        val manager = context.getSystemService(CameraManager::class.java)
        val sb = StringBuilder()
        sb.appendLine(AppInfo.LABEL)
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("GL_MAX_TEXTURE_SIZE: ${glMaxTextureSize()}")
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        sb.appendLine(
            "Память: всего ${mem.totalMem / MB} МБ, свободно ${mem.availMem / MB} МБ, " +
                "Java-куча ${Runtime.getRuntime().maxMemory() / MB} МБ (largeHeap ${am.largeMemoryClass} МБ)",
        )
        val ids = manager.cameraIdList.toList()
        val physical = ids.flatMap { id ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.getCameraCharacteristics(id).physicalCameraIds.toList() else emptyList()
        }.distinct().filterNot { it in ids }
        for (id in ids + physical) {
            sb.appendLine()
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            if (chars == null) {
                sb.appendLine("Камера $id: характеристики недоступны")
                continue
            }
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> "задняя"
                CameraMetadata.LENS_FACING_FRONT -> "фронтальная"
                else -> "внешняя"
            }
            val kind = if (id in physical) "физическая" else "логическая"
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            val uhr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "%.1f".format(it) }
            sb.appendLine("Камера $id ($facing, $kind, f=$focal мм)")
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                sb.appendLine("  Фото JPEG: " + sizesLine(map.getOutputSizes(ImageFormat.JPEG).orEmpty().toList()))
                val high = map.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().toList()
                sb.appendLine("  Фото «медленные» большие: " + if (high.isEmpty()) "нет" else sizesLine(high))
                sb.appendLine("  Видео (поток): " + sizesLine(map.getOutputSizes(MediaRecorder::class.java).orEmpty().toList(), 6))
            }
            sb.appendLine("  ULTRA_HIGH_RESOLUTION_SENSOR: " + if (uhr) "да" else "нет")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                val maxJpeg = maxMap?.getOutputSizes(ImageFormat.JPEG).orEmpty().toList()
                sb.appendLine("  Режим макс. разрешения (JPEG): " + if (maxJpeg.isEmpty()) "нет" else sizesLine(maxJpeg, 3))
            }
            videoByCamera[id]?.let { sb.appendLine("  Видео в приложении: " + it.joinToString { r -> r.label }.ifEmpty { "—" }) }
        }
        return sb.toString()
    }

    private fun sizesLine(sizes: List<Size>, limit: Int = 5): String {
        val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
        val shown = sorted.take(limit).joinToString { "${it.width}×${it.height} (${PhotoQuality.label(it.width * it.height / 1_000_000f)})" }
        return if (sorted.size > limit) "$shown … всего ${sorted.size}" else shown
    }

    private fun glMaxTextureSize(): Int = runCatching {
        val egl = EglCore()
        try {
            IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        } finally {
            egl.release()
        }
    }.getOrDefault(-1)

    private const val MB = 1024L * 1024L
}
