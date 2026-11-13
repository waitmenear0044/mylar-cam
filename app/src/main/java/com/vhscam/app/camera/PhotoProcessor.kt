package com.vhscam.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.FrameFormat
import com.vhscam.app.gl.EffectRenderer
import com.vhscam.app.gl.EglCore
import com.vhscam.app.gl.FrameWindow
import com.vhscam.app.gl.GlInput
import com.vhscam.app.gl.RenderTarget
import com.vhscam.app.gl.uploadBitmap
import com.vhscam.app.gl.uprightTexMatrix
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Фото с вшитым эффектом: снимок ImageCapture в высоком разрешении прогоняется через тот же
 * [EffectRenderer], что и превью, во внеэкранном EGL-контексте. Поворот и зеркало делаются
 * в пикселях (матрицей текстурных координат), EXIF не используется.
 * Большие снимки обрабатываются тайлами. Вызывать с фонового потока: работа занимает секунды.
 */
class PhotoProcessor(context: Context) {
    private val appContext = context.applicationContext

    data class Result(val uri: Uri, val label: String, val thumbnail: Bitmap, val megapixels: Float = 0f)

    /**
     * Снимок камеры или один эффект: обработка ([render]), штамп даты поверх готового кадра и сохранение.
     */
    fun process(
        source: SourceImage,
        rotationDegrees: Int,
        mirror: Boolean,
        settings: EffectSettings,
        format: FrameFormat,
        name: String,
        scale: Float = 1f,
        ownAspect: Boolean = false,
        cropAspect: Float? = null,
        overlayTimeMs: Long? = null,
        fullAspect: Float = FULL_ASPECT,
        stamp: com.vhscam.app.StampSettings? = null,
        stampAutoMs: Long = System.currentTimeMillis(),
    ): Result {
        val output = render(source, rotationDegrees, mirror, settings, format, scale, ownAspect, cropAspect, overlayTimeMs, fullAspect)
        try {
            return finish(output, name, stamp, stampAutoMs)
        } finally {
            output.recycle()
        }
    }

    /**
     * Слои редактора в полном разрешении: каждый слой — отдельный тайловый проход по результату предыдущего.
     * Промежуточный результат большого фото уходит во временный файл без потерь (PNG) и читается по кускам,
     * так что в памяти одновременно только один полный кадр. Обрезка формата — на последнем слое, штамп — в конце.
     * Шум у каждого сохранения свой (зерно берётся заново в каждом проходе).
     * @param open открывает исходное фото заново (нужно при повторе в меньшем размере)
     */
    fun processLayers(
        open: () -> SourceImage,
        rotationDegrees: Int,
        mirror: Boolean,
        layers: List<com.vhscam.app.gl.EditLayer>,
        cropAspect: Float,
        name: String,
        scale: Float = 1f,
        stamp: com.vhscam.app.StampSettings? = null,
        stampAutoMs: Long = System.currentTimeMillis(),
    ): Result {
        val active = layers.filter { it.active }
        // Без слоёв — один проход «как есть» (с обрезкой и штампом)
        val passes = active.ifEmpty { listOf(com.vhscam.app.gl.EditLayer("none", EffectSettings().withEnabled(false))) }
        var source = open()
        var rotation = rotationDegrees
        var flip = mirror
        try {
            for ((i, layer) in passes.withIndex()) {
                val last = i == passes.lastIndex
                val rotated = rotation % 180 != 0
                val ownAspect = (if (rotated) source.height else source.width).toFloat() / (if (rotated) source.width else source.height)
                val output = render(
                    source, rotation, flip, layer.settings.effective(), FrameFormat.R3_4,
                    scale = if (i == 0) scale else 1f, ownAspect = true,
                    cropAspect = if (last) cropAspect else ownAspect, opacity = layer.opacity,
                )
                source.close()
                if (last) {
                    try {
                        return finish(output, name, stamp, stampAutoMs)
                    } finally {
                        output.recycle()
                    }
                }
                // Результат слоя — вход следующего: уже повёрнут и отражён как надо
                source = if (output.width.toLong() * output.height > IN_MEMORY_PIXELS) {
                    SourceImage.viaFile(output, File(appContext.cacheDir, "layer_$i.png"))
                } else {
                    SourceImage.from(output)
                }
                rotation = 0
                flip = false
            }
            error("No layers processed")
        } finally {
            runCatching { source.close() }
        }
    }

    /** Штамп даты поверх готового кадра (после эффектов), сохранение и миниатюра. */
    private fun finish(output: Bitmap, name: String, stamp: com.vhscam.app.StampSettings?, stampAutoMs: Long): Result {
        stamp?.let { com.vhscam.app.gl.drawStamp(output, it, com.vhscam.app.gl.stampTime(it, stampAutoMs)) }
        val result = save(output, name)
        val thumbH = 160
        val thumb = Bitmap.createScaledBitmap(output, thumbH * output.width / output.height, thumbH, true)
        return result.copy(thumbnail = thumb, megapixels = output.width * output.height / 1_000_000f)
    }

    /**
     * Обработка снимка в полном разрешении тайлами.
     *
     * Эффект считается по полному кадру 3:4 (как превью), затем кадр обрезается по центру до [format].
     * Если снимок уже обрезан (CameraX применил ViewPort), вокруг него достраивается «виртуальный»
     * полный кадр 3:4, чтобы размер зерна, кайма и вид эффекта не менялись.
     *
     * Снимок читается кусками ([SourceImage]), каждый тайл рисуется отдельно и сразу копируется
     * в итоговый Bitmap: в памяти одновременно только итоговый кадр и несколько небольших буферов.
     * Широкие размытия (halation) и сетка «Пикселя» считаются один раз по уменьшенной копии.
     *
     * @param scale < 1 — обработка в уменьшенном размере (повтор после нехватки памяти).
     * @param ownAspect фото из галереи: полный кадр — само фото в своих пропорциях (без достраивания до 3:4).
     * @param cropAspect ширина/высота итогового кадра; null — по [format]. Если формат шире фото — максимально
     *   возможное кадрирование, без растяжения.
     * @param overlayTimeMs время для даты оверлея «VHS» (дата съёмки готового фото); null — текущее.
     * @param fullAspect пропорции полного кадра (ширина/высота) в ориентации итогового снимка:
     *   3:4 у вертикального, 4:3 у снятого в альбомной ориентации.
     */
    fun render(
        source: SourceImage,
        rotationDegrees: Int,
        mirror: Boolean,
        settings: EffectSettings,
        format: FrameFormat,
        scale: Float = 1f,
        ownAspect: Boolean = false,
        cropAspect: Float? = null,
        overlayTimeMs: Long? = null,
        fullAspect: Float = FULL_ASPECT,
        opacity: Float = 1f,
    ): Bitmap {
        val rotated = rotationDegrees % 180 != 0
        val uprightW = if (rotated) source.height else source.width
        val uprightH = if (rotated) source.width else source.height

        // Полный кадр (3:4 или 4:3) в координатах повёрнутого снимка и место снимка в нём
        val srcAspect = uprightW.toFloat() / uprightH
        val (fullW0, fullH0) = when {
            ownAspect || abs(srcAspect - fullAspect) < 0.01f -> uprightW to uprightH
            srcAspect > fullAspect -> uprightW to (uprightW / fullAspect).roundToInt()
            else -> (uprightH * fullAspect).roundToInt() to uprightH
        }
        val sx = uprightW.toFloat() / fullW0
        val sy = uprightH.toFloat() / fullH0
        // uv полного кадра -> uv вертикального снимка -> uv снимка в ориентации сенсора
        val placement = floatArrayOf(
            1f / sx, 0f, 0f, 0f,
            0f, 1f / sy, 0f, 0f,
            0f, 0f, 1f, 0f,
            -(1f - sx) / 2f / sx, -(1f - sy) / 2f / sy, 0f, 1f,
        )
        val toSource = FloatArray(16)
        Matrix.multiplyMM(toSource, 0, uprightTexMatrix(rotationDegrees, mirror), 0, placement, 0)

        // Размер обработки (при повторе после нехватки памяти — меньше) и обрезка до формата
        val fullW = (fullW0 * scale).roundToInt()
        val fullH = (fullH0 * scale).roundToInt()
        val target = cropAspect ?: format.aspect
        val fullAspect = fullW.toFloat() / fullH
        val (cropW, cropH) = if (target > fullAspect) fullW to (fullW / target).roundToInt() else (fullH * target).roundToInt() to fullH
        val cropX = (fullW - cropW) / 2
        val cropY = (fullH - cropH) / 2

        val output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val egl = EglCore()
        var done = false
        try {
            val maxTexture = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            val renderer = EffectRenderer(appContext.assets, GlInput.TEXTURE_2D)
            renderer.overlayTimeMs = overlayTimeMs
            val frameIndex = (System.nanoTime() and 0xFFFF).toInt()

            // Шаг 1: проходы по всему кадру — по уменьшенной копии (длинная сторона ~1/4 полного кадра)
            val smallBitmap = source.decodeDownsampled(minOf(maxTexture, max(512, max(fullW, fullH) / 4)))
            val smallTexture = uploadBitmap(smallBitmap)
            smallBitmap.recycle()
            val visible = floatArrayOf(
                cropX.toFloat() / fullW, cropY.toFloat() / fullH,
                (cropX + cropW).toFloat() / fullW, (cropY + cropH).toFloat() / fullH,
            )
            renderer.prepareOffline(smallTexture, toSource, fullW, fullH, settings, visible, frameIndex)

            // Шаг 2: тайлы по области обрезки
            // Запас вокруг тайла: кайма (до ~1.5% короткой стороны в каждую сторону) и мягкость
            val margin = maxOf(0.016f * min(fullW, fullH), renderer.offlineMarginFraction(settings) * fullW).roundToInt() + 4
            // Кусок снимка = тайл + запас с двух сторон дважды (окно и сдвиг каймы): должен влезть
            // в текстуру этого GPU (GL_MAX_TEXTURE_SIZE берётся в рантайме)
            val tile = minOf(TILE, maxTexture - 4 * margin - 8).coerceAtLeast(MIN_TILE)
            val target2 = RenderTarget()
            val canvas = Canvas(output)
            val layerPaint = android.graphics.Paint().apply { alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt() }
            var pixels = ByteBuffer.allocateDirect(4)
            var y = 0
            while (y < cropH) {
                var x = 0
                val th = min(tile, cropH - y)
                while (x < cropW) {
                    val tw = min(tile, cropW - x)
                    // Окно с запасом в координатах полного кадра
                    val wx = max(0, cropX + x - margin)
                    val wy = max(0, cropY + y - margin)
                    val wr = min(fullW, cropX + x + tw + margin)
                    val wb = min(fullH, cropY + y + th + margin)
                    val window = FrameWindow(wx, wy, wr - wx, wb - wy, fullW, fullH)

                    // Кусок снимка под окно (ещё с запасом на сдвиг каймы)
                    val region = sourceRegion(toSource, window, margin, source.width, source.height)
                    val regionBitmap = source.decodeRegion(region)
                    val regionTexture = uploadBitmap(regionBitmap)
                    regionBitmap.recycle()
                    val texMatrix = FloatArray(16)
                    Matrix.multiplyMM(texMatrix, 0, regionMatrix(region, source.width, source.height), 0, toSource, 0)

                    target2.ensure(window.width, window.height)
                    // Непрозрачность слоя меньше 1: сначала кусок как есть, поверх него — результат слоя с альфой
                    for (effectPass in if (opacity < 1f) listOf(false, true) else listOf(true)) {
                    renderer.drawTile(regionTexture, texMatrix, window, target2.fbo, if (effectPass) settings else settings.withEnabled(false), frameIndex)

                    // Внутренняя часть окна (без запаса) -> в итоговый кадр
                    val need = tw * th * 4
                    if (pixels.capacity() < need) pixels = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
                    pixels.clear()
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target2.fbo)
                    GLES30.glReadPixels(cropX + x - wx, cropY + y - wy, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    pixels.rewind()
                    val tileBitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    tileBitmap.copyPixelsFromBuffer(pixels)
                    canvas.drawBitmap(tileBitmap, x.toFloat(), y.toFloat(), if (effectPass && opacity < 1f) layerPaint else null)
                    tileBitmap.recycle()
                    }
                    GLES30.glDeleteTextures(1, intArrayOf(regionTexture), 0)
                    x += tw
                }
                y += th
            }
            target2.release()
            GLES30.glDeleteTextures(1, intArrayOf(smallTexture), 0)
            renderer.release()
            checkGl()

            done = true
            return output
        } finally {
            if (!done) output.recycle()
            egl.release()
        }
    }

    /** Сохранить уже готовый кадр (эффект и штамп на нём есть): «Гостинг» сохраняет то, что на экране. */
    fun saveReady(bitmap: Bitmap, name: String): Result {
        val result = save(bitmap, name)
        val thumbH = 160
        val thumb = Bitmap.createScaledBitmap(bitmap, thumbH * bitmap.width / bitmap.height, thumbH, true)
        return result.copy(thumbnail = thumb, megapixels = bitmap.width * bitmap.height / 1_000_000f)
    }

    /** Область снимка (в пикселях, ориентация сенсора), покрывающая окно полного кадра с запасом. */
    private fun sourceRegion(toSource: FloatArray, window: FrameWindow, margin: Int, srcW: Int, srcH: Int): Rect {
        val fx0 = (window.x - margin).toFloat() / window.fullWidth
        val fy0 = (window.y - margin).toFloat() / window.fullHeight
        val fx1 = (window.x + window.width + margin).toFloat() / window.fullWidth
        val fy1 = (window.y + window.height + margin).toFloat() / window.fullHeight
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val v = FloatArray(4)
        for ((u, w) in listOf(fx0 to fy0, fx1 to fy0, fx0 to fy1, fx1 to fy1)) {
            Matrix.multiplyMV(v, 0, toSource, 0, floatArrayOf(u, w, 0f, 1f), 0)
            minX = min(minX, v[0]); maxX = max(maxX, v[0]); minY = min(minY, v[1]); maxY = max(maxY, v[1])
        }
        val left = (floor(minX * srcW).toInt() - 1).coerceIn(0, srcW - 1)
        val top = (floor(minY * srcH).toInt() - 1).coerceIn(0, srcH - 1)
        val right = (ceil(maxX * srcW).toInt() + 1).coerceIn(left + 1, srcW)
        val bottom = (ceil(maxY * srcH).toInt() + 1).coerceIn(top + 1, srcH)
        return Rect(left, top, right, bottom)
    }

    /** uv снимка -> uv текстуры куска [region]. */
    private fun regionMatrix(region: Rect, srcW: Int, srcH: Int): FloatArray {
        val sx = srcW.toFloat() / region.width()
        val sy = srcH.toFloat() / region.height()
        return floatArrayOf(
            sx, 0f, 0f, 0f,
            0f, sy, 0f, 0f,
            0f, 0f, 1f, 0f,
            -region.left.toFloat() / region.width(), -region.top.toFloat() / region.height(), 0f, 1f,
        )
    }

    private fun checkGl() {
        val error = GLES30.glGetError()
        if (error == GLES30.GL_OUT_OF_MEMORY) throw OutOfMemoryError("GL out of memory")
    }

    private fun save(bitmap: Bitmap, name: String): Result {
        val fileName = "$name.jpg"
        val prefs = com.vhscam.app.AppPrefs.get(appContext)
        val album = prefs.folder
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                // Android 8–9: RELATIVE_PATH ещё нет, путь задаётся напрямую (нужно WRITE_EXTERNAL_STORAGE)
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), album)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, fileName).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "No output stream" }
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, prefs.jpegQuality.value, out)) { "JPEG encode failed" }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Result(uri, "Pictures/$album/$fileName", bitmap)
    }

    private companion object {
        const val FULL_ASPECT = 3f / 4f
        /** Промежуточный результат слоя больше этого числа пикселей уходит на диск (16 Мп ~ 64 МБ в памяти). */
        const val IN_MEMORY_PIXELS = 16_000_000L
        /** Сторона тайла: кусок с запасом помещается в текстуру на любом GPU. */
        const val TILE = 1536
        const val MIN_TILE = 256
    }
}
