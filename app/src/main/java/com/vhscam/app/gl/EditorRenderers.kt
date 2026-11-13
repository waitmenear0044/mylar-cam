package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import com.vhscam.app.effect.ColorSelectParams
import com.vhscam.app.effect.CurvePoint
import com.vhscam.app.effect.NewspaperParams
import com.vhscam.app.effect.curveLut
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Копия кадра с мип-уровнями: источник для больших размытий и для тона точек (уменьшение без ступенек). */
private class MipCopy(assets: AssetManager, private val input: GlInput) {
    private val copyProgram = GlProgram(assets, "quad.vert", "copy.frag", input.header)
    val target = RenderTarget()

    fun update(inputTexture: Int, texMatrix: FloatArray, fullWidth: Int, fullHeight: Int, maxSide: Int) {
        val k = min(1f, maxSide.toFloat() / max(fullWidth, fullHeight))
        val w = (fullWidth * k).roundToInt().coerceAtLeast(2)
        val h = (fullHeight * k).roundToInt().coerceAtLeast(2)
        target.ensure(w, h)
        target.bindAsTarget()
        copyProgram.use()
        bindTexture(0, input.target, inputTexture, copyProgram, "uInput")
        GLES30.glUniformMatrix4fv(copyProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GlQuad.draw()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.texture)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
    }

    fun release() {
        copyProgram.release()
        target.release()
    }
}

/** «Выбор цвета»: один попиксельный проход; кривая ЧБ-части — таблица 256x1 (пересоздаётся при смене точек). */
class ColorSelectRenderer(assets: AssetManager, private val input: GlInput) {
    private val program = GlProgram(assets, "quad.vert", "color_select.frag", input.header)
    private var lut = 0
    private var lutCurve: List<CurvePoint>? = null

    fun draw(inputTexture: Int, texMatrix: FloatArray, outWidth: Int, outHeight: Int, targetFbo: Int, params: ColorSelectParams, window: FrameWindow? = null) {
        if (lut == 0 || lutCurve != params.curve) {
            if (lut == 0) lut = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            val values = curveLut(params.curve, 256)
            val bytes = ByteBuffer.allocateDirect(256 * 4)
            for (v in values) {
                val b = (v * 255f).roundToInt().toByte()
                bytes.put(b).put(b).put(b).put(255.toByte())
            }
            bytes.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lut)
            setTextureParams(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 256, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            lutCurve = params.curve
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        program.use()
        bindTexture(0, input.target, inputTexture, program, "uInput")
        bindTexture(1, GLES30.GL_TEXTURE_2D, lut, program, "uCurve")
        GLES30.glUniformMatrix4fv(program.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        setFrameWindow(program, window)
        val s = List(ColorSelectParams.SLOTS) { params.slot(it) }
        GLES30.glUniform3f(program.uniform("uHue"), s[0].hue, s[1].hue, s[2].hue)
        GLES30.glUniform3f(program.uniform("uWidth"), s[0].width, s[1].width, s[2].width)
        GLES30.glUniform3f(program.uniform("uFeather"), s[0].feather, s[1].feather, s[2].feather)
        GLES30.glUniform3f(program.uniform("uOn"), if (s[0].on) 1f else 0f, if (s[1].on) 1f else 0f, if (s[2].on) 1f else 0f)
        GLES30.glUniform1f(program.uniform("uSatMin"), params.satMin.coerceIn(0f, 1f))
        GLES30.glUniform1f(program.uniform("uKeep"), if (params.keep) 1f else 0f)
        GlQuad.draw()
    }

    fun release() {
        program.release()
        if (lut != 0) GLES30.glDeleteTextures(1, intArrayOf(lut), 0)
        lut = 0
        lutCurve = null
    }
}

/**
 * «Газета»: тон точек берётся из сглаженной копии всего кадра ([prepare]), сам растр, бумага, пыль и заломы —
 * процедурные в одном проходе ([draw]).
 */
class NewspaperRenderer(assets: AssetManager, input: GlInput) {
    private val program = GlProgram(assets, "quad.vert", "newspaper.frag", GlProgram.BASE_HEADER)
    private val tone = MipCopy(assets, input)

    fun prepare(inputTexture: Int, texMatrix: FloatArray, fullWidth: Int, fullHeight: Int) {
        tone.update(inputTexture, texMatrix, fullWidth, fullHeight, TONE_MAX)
    }

    fun draw(outWidth: Int, outHeight: Int, targetFbo: Int, params: NewspaperParams, seed: Int, window: FrameWindow? = null) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        program.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, tone.target.texture, program, "uTone")
        setFrameWindow(program, window)
        GLES30.glUniform2f(program.uniform("uFullSize"), (window?.fullWidth ?: outWidth).toFloat(), (window?.fullHeight ?: outHeight).toFloat())
        GLES30.glUniform1i(program.uniform("uKind"), params.kind.coerceIn(0, 2))
        GLES30.glUniform1f(program.uniform("uCell"), params.cell.coerceIn(NewspaperParams.MIN_CELL, NewspaperParams.MAX_CELL))
        GLES30.glUniform1f(program.uniform("uGain"), params.gain)
        GLES30.glUniform1f(program.uniform("uOffset"), params.offset.coerceIn(0f, 1f))
        GLES30.glUniform1f(program.uniform("uAngle"), Math.toRadians(params.angle.toDouble()).toFloat())
        if (params.blueInk) GLES30.glUniform3f(program.uniform("uInk"), 0.07f, 0.10f, 0.24f) else GLES30.glUniform3f(program.uniform("uInk"), 0.08f, 0.08f, 0.08f)
        when (params.paper) {
            1 -> GLES30.glUniform3f(program.uniform("uPaper"), 0.80f, 0.80f, 0.78f)
            2 -> GLES30.glUniform3f(program.uniform("uPaper"), 0.93f, 0.87f, 0.70f)
            else -> GLES30.glUniform3f(program.uniform("uPaper"), 0.96f, 0.95f, 0.93f)
        }
        GLES30.glUniform1i(program.uniform("uSeed"), seed and 0xFFFF)
        GlQuad.draw()
    }

    fun release() {
        program.release()
        tone.release()
    }

    private companion object {
        const val TONE_MAX = 1024
    }
}
