package com.vhscam.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30

/** Тип входной текстуры: от него зависит заголовок шейдера (SAMPLER_TYPE) и GL-цель привязки. */
enum class GlInput(val header: String, val target: Int) {
    /** Кадры камеры из SurfaceTexture. */
    CAMERA(GlProgram.OES_HEADER, GLES11Ext.GL_TEXTURE_EXTERNAL_OES),
    /** Обычная текстура: фото, загруженное из Bitmap. */
    TEXTURE_2D(GlProgram.TEXTURE_2D_HEADER, GLES30.GL_TEXTURE_2D),
}

/** Полноэкранный прямоугольник, общий для всех проходов. */
object GlQuad {
    private val vertices = java.nio.ByteBuffer.allocateDirect(8 * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        .apply { position(0) }

    fun draw() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
    }
}

/** Текстура-буфер с framebuffer'ом для промежуточных проходов. */
class RenderTarget(private val filter: Int = GLES30.GL_LINEAR) {
    var fbo = 0
        private set
    var texture = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    /** Пересоздаёт буфер, если размер изменился. Возвращает true, если буфер новый (содержимое пустое). */
    fun ensure(w: Int, h: Int): Boolean {
        if (fbo != 0 && w == width && h == height) return false
        release()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        setTextureParams(GLES30.GL_TEXTURE_2D, filter)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: $status" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        width = w
        height = h
        return true
    }

    fun bindAsTarget() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        if (fbo == 0) return
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        fbo = 0
        texture = 0
        width = 0
        height = 0
    }
}

fun setTextureParams(target: Int, filter: Int) {
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
}

fun bindTexture(unit: Int, target: Int, texture: Int, program: GlProgram, uniform: String) {
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
    GLES30.glBindTexture(target, texture)
    GLES30.glUniform1i(program.uniform(uniform), unit)
}

/**
 * Окно (тайл) в полном кадре, в пикселях полного кадра: фото большого разрешения рисуется тайлами,
 * а шейдеры считают кайму, зерно, шум и свечение в координатах полного кадра — без швов между тайлами.
 */
data class FrameWindow(val x: Int, val y: Int, val width: Int, val height: Int, val fullWidth: Int, val fullHeight: Int)

/** uFrameOrigin/uFrameScale: uv полного кадра = origin + vUv * scale. Без окна — весь кадр. */
fun setFrameWindow(program: GlProgram, window: FrameWindow?) {
    if (window == null) {
        GLES30.glUniform2f(program.uniform("uFrameOrigin"), 0f, 0f)
        GLES30.glUniform2f(program.uniform("uFrameScale"), 1f, 1f)
    } else {
        GLES30.glUniform2f(program.uniform("uFrameOrigin"), window.x.toFloat() / window.fullWidth, window.y.toFloat() / window.fullHeight)
        GLES30.glUniform2f(program.uniform("uFrameScale"), window.width.toFloat() / window.fullWidth, window.height.toFloat() / window.fullHeight)
    }
}
