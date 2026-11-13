package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import com.vhscam.app.effect.GhostParams
import com.vhscam.app.effect.GradientParams
import com.vhscam.app.effect.GradientStop
import com.vhscam.app.effect.gradientLut
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** Режим «Градиент»: один попиксельный проход с картой градиента (LUT пересоздаётся, когда меняются точки). */
class GradientRenderer(assets: AssetManager, private val input: GlInput) {
    private val program = GlProgram(assets, "quad.vert", "gradient.frag", input.header)
    private var lut = 0
    private var lutStops: List<GradientStop>? = null

    /** @param window тайл фото в полном кадре; null — весь кадр. */
    fun draw(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        targetFbo: Int,
        params: GradientParams,
        frameIndex: Int,
        window: FrameWindow? = null,
    ) {
        if (lut == 0 || lutStops != params.stops) {
            if (lut == 0) lut = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            val bytes = gradientLut(params.stops, LUT_SIZE)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lut)
            setTextureParams(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, LUT_SIZE, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { position(0) },
            )
            lutStops = params.stops
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        program.use()
        bindTexture(0, input.target, inputTexture, program, "uInput")
        bindTexture(1, GLES30.GL_TEXTURE_2D, lut, program, "uLut")
        GLES30.glUniformMatrix4fv(program.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        setFrameWindow(program, window)
        GLES30.glUniform2f(program.uniform("uFullSize"), (window?.fullWidth ?: outWidth).toFloat(), (window?.fullHeight ?: outHeight).toFloat())
        GLES30.glUniform1f(program.uniform("uStrength"), params.strength.coerceIn(0f, 1f))
        GLES30.glUniform1f(program.uniform("uContrast"), params.contrast.coerceIn(-1f, 1f))
        GLES30.glUniform1f(program.uniform("uShift"), params.shift.coerceIn(0f, 1f))
        GLES30.glUniform1f(program.uniform("uInvert"), if (params.invert) 1f else 0f)
        GLES30.glUniform1i(program.uniform("uFrame"), frameIndex and 0xFFFF)
        GlQuad.draw()
    }

    fun release() {
        program.release()
        if (lut != 0) GLES30.glDeleteTextures(1, intArrayOf(lut), 0)
        lut = 0
        lutStops = null
    }

    private companion object {
        const val LUT_SIZE = 256
    }
}

/**
 * Режим «Гостинг»: история в уменьшенных буферах (длинная сторона не больше [HISTORY_MAX] — на 4K память не растёт),
 * обновляется по времени кадра. Без истории (фото, справка, миниатюры) рисует кадр как есть.
 * Буферы создаются при первом кадре режима и освобождаются при уходе из него ([releaseBuffers]).
 */
class GhostRenderer(assets: AssetManager, private val input: GlInput) {
    private val updateProgram = GlProgram(assets, "quad.vert", "ghost_update.frag", input.header)
    private val outputProgram = GlProgram(assets, "quad.vert", "ghost_output.frag", input.header)
    private val copyProgram = GlProgram(assets, "quad.vert", "copy.frag", input.header)
    private val history = arrayOf(RenderTarget(), RenderTarget())
    private val previous = RenderTarget()
    private var current = 0
    private var first = true
    private var lastTimeSec = Float.NaN
    private var sinceStampSec = 0f
    private var lastEcho: Boolean? = null

    fun resetHistory() {
        first = true
        lastTimeSec = Float.NaN
    }

    fun releaseBuffers() {
        history.forEach { it.release() }
        previous.release()
        resetHistory()
    }

    fun draw(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        targetFbo: Int,
        params: GhostParams,
        timeSec: Float,
        frameDurationSec: Float,
        useHistory: Boolean,
    ) {
        if (!useHistory) {
            copy(inputTexture, texMatrix, targetFbo, outWidth, outHeight)
            return
        }
        // Шаг времени — по меткам кадров; на разрывах (первый кадр, смена зерна, пауза) — номинальный кадр
        val dt = (timeSec - lastTimeSec).let { if (it.isNaN() || it <= 0f || it > 0.5f) frameDurationSec else it }
        lastTimeSec = timeSec
        val k = HISTORY_MAX.toFloat() / max(outWidth, outHeight)
        val hw = if (k < 1f) (outWidth * k).roundToInt().coerceAtLeast(2) else outWidth
        val hh = if (k < 1f) (outHeight * k).roundToInt().coerceAtLeast(2) else outHeight
        var fresh = first || lastEcho != params.echo
        for (t in history) if (t.ensure(hw, hh)) fresh = true
        if (previous.ensure(hw, hh)) fresh = true
        lastEcho = params.echo

        val seconds = params.seconds.coerceIn(GhostParams.MIN_SECONDS, GhostParams.MAX_SECONDS)
        // За «время жизни» след затухает примерно до 5%
        val decay = exp(-dt * 3f / seconds)
        sinceStampSec += dt
        val stamp = sinceStampSec >= STAMP_INTERVAL_SEC
        if (stamp) sinceStampSec = 0f

        val next = 1 - current
        history[next].bindAsTarget()
        updateProgram.use()
        bindTexture(0, input.target, inputTexture, updateProgram, "uInput")
        bindTexture(1, GLES30.GL_TEXTURE_2D, history[current].texture, updateProgram, "uHist")
        bindTexture(2, GLES30.GL_TEXTURE_2D, previous.texture, updateProgram, "uPrev")
        GLES30.glUniformMatrix4fv(updateProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform1i(updateProgram.uniform("uMode"), if (params.echo) 1 else 0)
        GLES30.glUniform1f(updateProgram.uniform("uDecay"), decay)
        // Порог движения: от 0.30 (только резкое) до 0.025 (почти любое изменение)
        val s = params.sensitivity.coerceIn(0f, 1f)
        GLES30.glUniform1f(updateProgram.uniform("uThreshold"), 0.30f * (1f - s) * (1f - s) + 0.025f)
        GLES30.glUniform1f(updateProgram.uniform("uStamp"), if (stamp) 1f else 0f)
        GLES30.glUniform1f(updateProgram.uniform("uFirst"), if (fresh) 1f else 0f)
        GlQuad.draw()
        current = next
        first = false

        // Текущий кадр -> «прошлый» для следующего раза
        copy(inputTexture, texMatrix, previous.fbo, hw, hh)

        // Форма шлейфа берёт средние по областям из мип-уровней истории
        val hist = history[current]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hist.texture)
        if (params.shape > 0) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        } else {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        outputProgram.use()
        bindTexture(0, input.target, inputTexture, outputProgram, "uInput")
        bindTexture(1, GLES30.GL_TEXTURE_2D, history[current].texture, outputProgram, "uHist")
        GLES30.glUniformMatrix4fv(outputProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform1f(outputProgram.uniform("uStrength"), params.strength.coerceIn(0f, 1f))
        GLES30.glUniform1f(outputProgram.uniform("uColorShift"), params.colorShift.coerceIn(0f, 1f))
        GLES30.glUniform1i(outputProgram.uniform("uShape"), params.shape)
        GLES30.glUniform1f(outputProgram.uniform("uShapeSize"), params.shapeSize.coerceIn(0f, 1f))
        GLES30.glUniform1f(outputProgram.uniform("uDegrade"), params.degrade.coerceIn(0f, 1f))
        GLES30.glUniform2f(outputProgram.uniform("uOutSize"), outWidth.toFloat(), outHeight.toFloat())
        GLES30.glUniform1f(outputProgram.uniform("uTime"), timeSec)
        GLES30.glUniform1f(outputProgram.uniform("uHistScale"), hw.toFloat() / outWidth)
        GlQuad.draw()
    }

    private fun copy(inputTexture: Int, texMatrix: FloatArray, fbo: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        copyProgram.use()
        bindTexture(0, input.target, inputTexture, copyProgram, "uInput")
        GLES30.glUniformMatrix4fv(copyProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GlQuad.draw()
    }

    fun release() {
        updateProgram.release()
        outputProgram.release()
        copyProgram.release()
        releaseBuffers()
    }

    private companion object {
        const val HISTORY_MAX = 1280
        /** Как часто движущийся объект оставляет копию. */
        const val STAMP_INTERVAL_SEC = 0.12f
    }
}
