package com.vhscam.app.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.gl.EffectRenderer
import com.vhscam.app.gl.EglCore
import com.vhscam.app.gl.GlInput
import com.vhscam.app.gl.setTextureParams
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Получает кадры камеры от CameraX (вход), прогоняет через [EffectRenderer] и рисует во все
 * выходные поверхности, которые даёт CameraX (общий выход для превью и записи).
 * Все методы SurfaceProcessor CameraX вызывает на [executor], то есть в нашем GL-потоке.
 */
class VhsSurfaceProcessor(context: Context) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {
    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("VhsGl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    /** Подменяется из UI в любой момент, читается GL-потоком на каждом кадре. */
    @Volatile var settings = EffectSettings()

    /** Временно показать оригинал без эффекта (сравнение долгим нажатием на превью). */
    @Volatile var bypass = false

    /**
     * На сколько градусов CameraX повернёт наш выход после эффекта (поворот сенсора при
     * портретном экране). Нужен эффектам, у которых есть «горизонталь итогового кадра».
     */
    @Volatile var frameRotation = 0

    /** CameraX отразит выход после эффекта (фронтальная камера): нужно «VHS» для строк и надписей. */
    @Volatile var frameMirror = false

    /** Выбранная частота кадров: от неё длительность кадра для смаза/шлейфа. */
    @Volatile var targetFps = 30

    /** Средняя яркость сцены 0..1 без эффекта (обновляется раз в ~0.5 с) — для «Авто» экранной вспышки. */
    @Volatile var sceneLuma = 0.5f
        private set

    /** Штамп даты поверх итогового кадра (после эффектов); null или выключенный — нет. */
    @Volatile var stamp: com.vhscam.app.StampSettings? = null

    /**
     * Зерно генератора шума: у превью своё, на каждый ролик берётся новое ([newSeed] перед стартом записи) —
     * рисунок зерна, шума ленты, дропаутов и полосы трекинга у двух роликов разный. В файле не хранится.
     */
    @Volatile private var seed = kotlin.random.Random.nextInt(1 shl 20)

    fun newSeed() {
        seed = kotlin.random.Random.nextInt(1 shl 20)
    }

    @Volatile private var grabRequest: Pair<Int, (android.graphics.Bitmap) -> Unit>? = null

    /**
     * Замороженный кадр камеры без эффекта в ориентации итогового кадра (длинная сторона ~[maxSide]) — для миниатюр
     * пресетов. Берётся из ближайшего кадра, превью не останавливается. [callback] вызывается в GL-потоке.
     */
    fun grabFrame(maxSide: Int, callback: (android.graphics.Bitmap) -> Unit) {
        grabRequest = maxSide to callback
    }

    @Volatile private var outputGrab: ((android.graphics.Bitmap) -> Unit)? = null

    /**
     * Готовый кадр «как на экране» — с эффектом и штампом, в ориентации итогового кадра и размере выхода.
     * Для режимов, которые живут только во времени («Гостинг»): фото сохраняет то, что видно. [callback] — в GL-потоке.
     */
    fun grabOutput(callback: (android.graphics.Bitmap) -> Unit) {
        outputGrab = callback
    }

    /** Реально отрисованные кадры в секунду (обновляется раз в секунду). */
    @Volatile var fps = 0f
        private set

    private lateinit var egl: EglCore
    private lateinit var renderer: EffectRenderer
    private lateinit var stampLayer: com.vhscam.app.gl.StampLayer
    private var inputTexture = 0
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val textureMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val cameraXMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val loggedOutputs = HashSet<SurfaceOutput>()
    private var inputCount = 0
    private var releaseRequested = false
    private var released = false
    private var firstTimestampNs = 0L
    private var frameCount = 0
    private var fpsWindowStartNs = 0L
    private var fpsFrames = 0

    init {
        handler.post {
            egl = EglCore()
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            inputTexture = tex[0]
            GLES30.glBindTexture(GlInput.CAMERA.target, inputTexture)
            setTextureParams(GlInput.CAMERA.target, GLES30.GL_LINEAR)
            renderer = EffectRenderer(assets, GlInput.CAMERA)
            stampLayer = com.vhscam.app.gl.StampLayer(assets)
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (releaseRequested) {
            request.willNotProvideSurface()
            return
        }
        inputCount++
        Log.i(TAG, "input surface ${request.resolution}")
        renderer.resetHistory()
        val surfaceTexture = SurfaceTexture(inputTexture)
        surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(surfaceTexture)
        request.provideSurface(surface, executor) {
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
            surface.release()
            inputCount--
            releaseIfDone()
        }
        surfaceTexture.setOnFrameAvailableListener(this, handler)
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (releaseRequested) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(executor) {
            outputs.remove(surfaceOutput)?.let { egl.releaseSurface(it) }
            loggedOutputs.remove(surfaceOutput)
            surfaceOutput.close()
        }
        Log.i(TAG, "output surface ${surfaceOutput.size} targets=${surfaceOutput.targets}")
        outputs[surfaceOutput] = egl.createWindowSurface(surface)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (releaseRequested) return
        try {
            egl.makeCurrentOffscreen()
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureMatrix)
            // Отладочный источник движения (только debug-сборка): кадр плавно ездит из стороны в сторону
            if (com.vhscam.app.DebugHooks.motion) {
                val t = System.nanoTime() / 1e9
                android.opengl.Matrix.translateM(textureMatrix, 0, (0.12 * Math.sin(t * 2.2)).toFloat(), (0.05 * Math.sin(t * 1.3)).toFloat(), 0f)
            }
            val currentSettings = if (bypass) settings.withEnabled(false) else settings
            val rotation = frameRotation
            // Покадровые эффекты привязаны ко времени кадра камеры, а не к числу кадров: шум, зерно,
            // дрожание, дропауты меняются EFFECT_TICKS_PER_SEC раз в секунду при любой частоте
            // (при 30 fps — каждый кадр, как раньше; при 60 — раз в два кадра).
            val stamp = surfaceTexture.timestamp.takeIf { it > 0L } ?: System.nanoTime()
            if (firstTimestampNs == 0L || stamp < firstTimestampNs) firstTimestampNs = stamp
            val elapsedMs = (stamp - firstTimestampNs) / 1_000_000L
            val currentSeed = seed
            val timeSec = (elapsedMs % 3_600_000L) / 1000f + (currentSeed % 997) * 0.37f
            val tick = Math.round(elapsedMs * EFFECT_TICKS_PER_SEC / 1000.0).toInt() + currentSeed
            val currentStamp = this.stamp?.takeIf { it.enabled }
            val nowMs = System.currentTimeMillis()
            val frameDurationSec = 1f / targetFps.coerceAtLeast(1)
            frameCount++
            // Неподвижные случайные рисунки (рисунки эффектов): одни на превью, новые на каждый ролик
            renderer.noiseSeed = currentSeed
            for ((output, eglSurface) in outputs) {
                egl.makeCurrent(eglSurface)
                val w = output.size.width
                val h = output.size.height
                // Дополнительное преобразование CameraX (без матрицы SurfaceTexture): обрезка ViewPort при выбранном
                // формате, иногда вместе с поворотом на 90°/180°/270° (зависит от устройства и формата). Эффект
                // считаем по полному входному кадру, а обрезку (и этот поворот) делает финальный проход рендерера —
                // вид эффекта не зависит от формата.
                output.updateTransformMatrix(cameraXMatrix, identity)
                val crop = cropOf(cameraXMatrix)
                val texMatrix: FloatArray
                val fullW: Int
                val fullH: Int
                val outRotation: Int
                var outMirror = frameMirror
                if (crop == null) {
                    output.updateTransformMatrix(outputMatrix, textureMatrix)
                    texMatrix = outputMatrix
                    fullW = w
                    fullH = h
                    // Выход в ориентации сенсора (альбомный) повернётся после эффекта; уже повёрнутый — нет
                    outRotation = if (fullW > fullH) rotation else 0
                } else {
                    texMatrix = textureMatrix
                    // Ось x выхода идёт вдоль u полного кадра (без поворота) или вдоль v (поворот на 90°/270°)
                    val turned = abs(crop[1]) > abs(crop[0])
                    fullW = (if (turned) h / abs(crop[4]) else w / abs(crop[0])).roundToInt()
                    fullH = (if (turned) w / abs(crop[1]) else h / abs(crop[5])).roundToInt()
                    // Полный кадр повёрнут относительно выхода на угол матрицы, выход CameraX повернёт ещё на rotation.
                    // Если в матрице есть отражение (фронтальная камера: CameraX отражает уже в ней), направление
                    // поворота меняется, а отражения CameraX после эффекта нет (проверено перебором на всех поворотах)
                    val turn = matrixRotation(crop)
                    val reflected = crop[0] * crop[5] - crop[4] * crop[1] < 0f
                    val total = rotation + turn
                    outRotation = ((if (reflected) -total else total) % 360 + 360) % 360
                    outMirror = frameMirror != reflected
                }
                if (output !in loggedOutputs) {
                    loggedOutputs += output
                    Log.i(TAG, "output ${w}x$h full ${fullW}x$fullH crop=${crop != null} matrix=${crop?.let(::matrixRotation)} rotation=$rotation -> $outRotation mirror=$outMirror")
                }
                renderer.draw(
                    inputTexture, texMatrix, fullW, fullH, w, h, crop, 0, currentSettings, timeSec,
                    tick, outRotation, useHistory = true, frameMirror = outMirror,
                    frameDurationSec = frameDurationSec,
                )
                if (currentStamp != null || grabRequest != null || outputGrab != null) {
                    val toFrame = com.vhscam.app.gl.outputToFrame(crop, outRotation, outMirror, live = true)
                    val turned = com.vhscam.app.gl.frameTurned(toFrame)
                    val fw = if (turned) h else w
                    val fh = if (turned) w else h
                    if (currentStamp != null) {
                        stampLayer.draw(0, w, h, toFrame, fw, fh, currentStamp, com.vhscam.app.gl.stampTime(currentStamp, nowMs))
                    }
                    grabRequest?.let { (maxSide, callback) ->
                        grabRequest = null
                        // uv итогового кадра -> uv выхода -> uv текстуры камеры
                        val outToTex = FloatArray(16)
                        if (crop == null) outputMatrix.copyInto(outToTex) else android.opengl.Matrix.multiplyMM(outToTex, 0, textureMatrix, 0, crop, 0)
                        val fromFrame = FloatArray(16).also { android.opengl.Matrix.invertM(it, 0, toFrame, 0) }
                        val m = FloatArray(16).also { android.opengl.Matrix.multiplyMM(it, 0, outToTex, 0, fromFrame, 0) }
                        val k = maxSide.toFloat() / maxOf(fw, fh)
                        val bitmap = renderer.grab(inputTexture, m, (fw * k).roundToInt().coerceAtLeast(2), (fh * k).roundToInt().coerceAtLeast(2))
                        egl.makeCurrent(eglSurface)
                        callback(bitmap)
                    }
                    outputGrab?.let { callback ->
                        outputGrab = null
                        // Только что нарисованный выход -> Bitmap, затем поворот и зеркало до итогового кадра
                        val pixels = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
                        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                        pixels.rewind()
                        val raw = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        raw.copyPixelsFromBuffer(pixels)
                        val m = android.graphics.Matrix().apply {
                            setValues(
                                floatArrayOf(
                                    toFrame[0] * fw / w, toFrame[4] * fw / h, toFrame[12] * fw,
                                    toFrame[1] * fh / w, toFrame[5] * fh / h, toFrame[13] * fh,
                                    0f, 0f, 1f,
                                ),
                            )
                        }
                        val upright = android.graphics.Bitmap.createBitmap(raw, 0, 0, w, h, m, false)
                        if (upright !== raw) raw.recycle()
                        callback(upright)
                    }
                }
                egl.setPresentationTime(eglSurface, surfaceTexture.timestamp)
                egl.swapBuffers(eglSurface)
            }
            if (frameCount % LUMA_EVERY_FRAMES == 0) {
                sceneLuma = renderer.measureLuma(inputTexture, textureMatrix)
            }
            countFrame()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Frame rendering failed", e)
        }
    }

    /** Освобождает GL, когда CameraX закроет вход. Перед вызовом камеру нужно отвязать (unbind). */
    fun release() {
        handler.post {
            if (releaseRequested) return@post
            releaseRequested = true
            releaseIfDone()
        }
    }

    private fun releaseIfDone() {
        if (!releaseRequested || inputCount > 0 || released) return
        released = true
        for ((output, eglSurface) in outputs) {
            egl.releaseSurface(eglSurface)
            output.close()
        }
        outputs.clear()
        renderer.release()
        stampLayer.release()
        GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        egl.release()
        thread.quitSafely()
    }

    /**
     * Обрезка из преобразования CameraX: масштаб и сдвиг, возможно с поворотом на угол, кратный 90°.
     * null — обрезки нет (единичная матрица) или преобразование сложнее (тогда работает прежний путь без обрезки).
     */
    private fun cropOf(m: FloatArray): FloatArray? {
        val scaleOnly = abs(m[1]) < 1e-4f && abs(m[4]) < 1e-4f
        val turnedOnly = abs(m[0]) < 1e-4f && abs(m[5]) < 1e-4f
        val isIdentity = scaleOnly && abs(m[0] - 1f) < 1e-4f && abs(m[5] - 1f) < 1e-4f &&
            abs(m[12]) < 1e-4f && abs(m[13]) < 1e-4f
        return if ((!scaleOnly && !turnedOnly) || isIdentity) null else m
    }

    /** Угол поворота в матрице обрезки (0, 90, 180, 270) в той же мере, что frameRotation. */
    private fun matrixRotation(m: FloatArray): Int {
        val deg = Math.toDegrees(kotlin.math.atan2(m[1].toDouble(), m[0].toDouble())).roundToInt()
        return (((deg + 45).floorDiv(90) * 90) % 360 + 360) % 360
    }

    private fun countFrame() {
        val now = System.nanoTime()
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = now
        fpsFrames++
        val elapsed = now - fpsWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            fps = fpsFrames * 1e9f / elapsed
            fpsFrames = 0
            fpsWindowStartNs = now
        }
    }

    private companion object {
        const val TAG = "VhsSurfaceProcessor"
        const val LUMA_EVERY_FRAMES = 15
        /** Скорость смены покадровых эффектов (шум, зерно, дрожание): как у прежних 30 fps. */
        const val EFFECT_TICKS_PER_SEC = 30.0
    }
}
