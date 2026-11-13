package com.vhscam.app.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import com.vhscam.app.effect.EffectSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Загружает Bitmap в текстуру. Вызывать с текущим EGL-контекстом.
 */
fun uploadBitmap(bitmap: Bitmap): Int {
    val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    setTextureParams(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    return tex
}

/**
 * Рисует эффект из текстуры в буфер [w]x[h] и возвращает результат как Bitmap (ряд 0 = верх).
 * Вызывать с текущим EGL-контекстом.
 */
fun renderToBitmap(
    renderer: EffectRenderer,
    texture: Int,
    texMatrix: FloatArray,
    w: Int,
    h: Int,
    settings: EffectSettings,
    frameIndex: Int,
    crop: android.graphics.Rect? = null,
): Bitmap {
    // Кадрирование после эффекта (эффект считается по всему кадру, оверлей — в углах видимой части)
    val outW = crop?.width() ?: w
    val outH = crop?.height() ?: h
    val cropMatrix = crop?.let {
        floatArrayOf(
            it.width().toFloat() / w, 0f, 0f, 0f,
            0f, it.height().toFloat() / h, 0f, 0f,
            0f, 0f, 1f, 0f,
            it.left.toFloat() / w, it.top.toFloat() / h, 0f, 1f,
        )
    }
    val target = RenderTarget()
    target.ensure(outW, outH)
    renderer.draw(
        texture, texMatrix, w, h, outW, outH, crop = cropMatrix, targetFbo = target.fbo, settings = settings,
        timeSec = 0f, frameIndex = frameIndex, frameRotation = 0, useHistory = false,
    )
    val pixels = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo)
    GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    target.release()
    val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    pixels.rewind()
    bitmap.copyPixelsFromBuffer(pixels)
    return bitmap
}

/**
 * Матрица (u,v) выхода -> (s,t) исходной текстуры, загруженной из Bitmap «как есть» (t=0 — верх).
 * v=0 выхода — верх кадра (так ряд 0 glReadPixels становится верхом Bitmap). Исходник поворачивается
 * по часовой на [rotation] и при [mirror] отражается по горизонтали итогового кадра.
 */
fun uprightTexMatrix(rotation: Int, mirror: Boolean): FloatArray {
    // s = a*u + b*v + c,  t = d*u + e*v + f
    var a: Float; var b: Float; var c: Float; var d: Float; var e: Float; var f: Float
    when ((rotation % 360 + 360) % 360) {
        90 -> { a = 0f; b = 1f; c = 0f; d = -1f; e = 0f; f = 1f }   // source(y, 1-x)
        180 -> { a = -1f; b = 0f; c = 1f; d = 0f; e = -1f; f = 1f } // source(1-x, 1-y)
        270 -> { a = 0f; b = -1f; c = 1f; d = 1f; e = 0f; f = 0f }  // source(1-y, x)
        else -> { a = 1f; b = 0f; c = 0f; d = 0f; e = 1f; f = 0f }  // source(x, y)
    }
    if (mirror) {
        // x -> 1 - x
        c += a; a = -a
        f += d; d = -d
    }
    return floatArrayOf(
        a, d, 0f, 0f,
        b, e, 0f, 0f,
        0f, 0f, 1f, 0f,
        c, f, 0f, 1f,
    )
}

/**
 * Постоянный внеэкранный рендерер для справки: свой поток и EGL-контекст, одна загруженная
 * исходная картинка. Запросы выполняются по очереди; отменённые до начала пропускаются.
 */
class OfflineRenderer(context: Context, private val source: Bitmap) {
    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("OfflineGl").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var renderer: EffectRenderer
    private var texture = 0
    private val matrix = uprightTexMatrix(0, false)

    init {
        handler.post {
            egl = EglCore()
            renderer = EffectRenderer(assets, GlInput.TEXTURE_2D)
            texture = uploadBitmap(source)
        }
    }

    /** Время для даты оверлея «VHS» (готовое фото — дата съёмки); null — текущее. */
    @Volatile var overlayTimeMs: Long? = null

    /** @param crop видимая часть (кадрирование формата) в пикселях исходной картинки; null — вся. */
    suspend fun render(settings: EffectSettings, crop: android.graphics.Rect? = null): Bitmap = suspendCancellableCoroutine { cont ->
        handler.post {
            if (!cont.isActive) return@post
            renderer.overlayTimeMs = overlayTimeMs
            val bitmap = renderToBitmap(renderer, texture, matrix, source.width, source.height, settings, frameIndex = 7, crop = crop)
            if (cont.isActive) cont.resume(bitmap)
        }
    }

    fun release() {
        handler.post {
            renderer.release()
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            egl.release()
            thread.quitSafely()
        }
    }
}
