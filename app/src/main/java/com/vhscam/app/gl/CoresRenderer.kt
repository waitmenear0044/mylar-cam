package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.Matrix
import com.vhscam.app.effect.CoreKind
import com.vhscam.app.effect.CoresParams
import com.vhscam.app.effect.CoresSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Режим «Cores». Circuit — один проход в полном размере (палитра, дизеринг, пороги, сбой), всплески
 * сбоя и замирание кадра решаются по времени ([burstAt]); для замирания хранится последний кадр.
 * Ядра Dream / Sun / Dark:
 * 1. small — уменьшенная копия всего кадра (1/4), 2 размытия тем же гауссианом, что halation «Плёнки»:
 *    soft (мягкость, дымка, штрихи) и wide (свечение, Orton, туман);
 * 2. ядро — цвет, дымка, свечение, засветы, туман, штрихи, виньетка в полном размере;
 * 3. зерно — тот же проход, что у «Плёнки» (film_output). Цифровой шум — общий финальный проход.
 * Фото тайлами: шаг 1 один раз по уменьшенной копии снимка ([prepareOffline]), тайлы — шаги 2–3
 * с окном в координатах полного кадра, поэтому швов нет.
 * Направления (верх кадра для тумана, угол штрихов, края засветок) — в координатах итогового
 * портретного кадра q, как у «VHS». Вызывать только из GL-потока.
 */
class CoresRenderer(assets: AssetManager, private val input: GlInput) {
    private val copyProgram = GlProgram(assets, "quad.vert", "copy.frag", input.header)
    private val blurProgram = GlProgram(assets, "quad.vert", "film_blur.frag", GlProgram.BASE_HEADER)
    private val dreamProgram = GlProgram(assets, "quad.vert", "cores_dream.frag", input.header)
    private val sunProgram = GlProgram(assets, "quad.vert", "cores_sun.frag", input.header)
    private val darkProgram = GlProgram(assets, "quad.vert", "cores_dark.frag", input.header)
    private val grainProgram = GlProgram(assets, "quad.vert", "film_output.frag", GlProgram.BASE_HEADER)
    private val circuitProgram = GlProgram(assets, "quad.vert", "cores_circuit.frag", input.header)
    private val frameCopyProgram = GlProgram(assets, "quad.vert", "copy.frag", GlProgram.TEXTURE_2D_HEADER)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val small = RenderTarget()
    private val tmp = RenderTarget()
    private val soft = RenderTarget()
    private val wide = RenderTarget()
    private val mid = RenderTarget()
    /** Последний кадр Circuit (живой конвейер): его повторяют, пока кадр «залип». */
    private val frame = RenderTarget()
    private var frameValid = false

    private var preparedTime = 0f

    fun resetHistory() {
        frameValid = false
    }

    /**
     * Фото тайлами: уменьшенная копия и размытия по всему кадру
     * ([texMatrix]: uv полного кадра -> uv копии).
     */
    fun prepareOffline(inputTexture: Int, texMatrix: FloatArray, fullWidth: Int, fullHeight: Int, params: CoresParams, timeSec: Float) {
        val p = CoresSpec.blend(params, params.strength.coerceIn(0f, 1f))
        prepareSmall(inputTexture, texMatrix, fullWidth, fullHeight)
        preparedTime = timeSec
    }

    /**
     * @param frameRotation / [mirror] — поворот и зеркало CameraX после эффекта (см. [VhsRenderer.toBufferMatrix]).
     * @param window тайл фото; уменьшенная копия уже посчитана в [prepareOffline].
     */
    fun draw(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        targetFbo: Int,
        params: CoresParams,
        timeSec: Float,
        frameIndex: Int,
        frameRotation: Int,
        mirror: Boolean,
        window: FrameWindow? = null,
    ) {
        val p = CoresSpec.blend(params, params.strength.coerceIn(0f, 1f))
        val live = input == GlInput.CAMERA && window == null
        val rotation = if (live) frameRotation else 0
        val toP = VhsRenderer.toBufferMatrix(rotation, mirror && live, live)
        val toQ = FloatArray(16).also { Matrix.invertM(it, 0, toP, 0) }
        val fullW = window?.fullWidth ?: outWidth
        val fullH = window?.fullHeight ?: outHeight
        val rotated = rotation % 180 != 0
        val aspectQ = if (rotated) fullH.toFloat() / fullW else fullW.toFloat() / fullH
        val time = if (window == null) timeSec else preparedTime
        if (p.core == CoreKind.CIRCUIT) {
            val portraitW = if (rotated) fullH else fullW
            val portraitH = if (rotated) fullW else fullH
            drawCircuit(inputTexture, texMatrix, toP, toQ, outWidth, outHeight, portraitW, portraitH, targetFbo, p, time, live, window)
            return
        }
        frameValid = false
        if (window == null) prepareSmall(inputTexture, texMatrix, outWidth, outHeight)

        val (grain, grainSize) = when (p.core) {
            CoreKind.DREAM -> p.dream.grain to 0.3f
            CoreKind.SUN -> p.sun.grain to 0.5f
            CoreKind.DARK -> p.dark.grain to 0.6f
            CoreKind.CIRCUIT -> 0f to 0f
        }
        // Без зерна ядро рисует прямо в цель
        val coreTarget = if (grain > 0f) mid.also { it.ensure(outWidth, outHeight) }.fbo else targetFbo
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coreTarget)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        val program = when (p.core) {
            CoreKind.SUN -> sunProgram
            CoreKind.DARK -> darkProgram
            else -> dreamProgram
        }
        program.use()
        bindTexture(0, input.target, inputTexture, program, "uInput")
        bindTexture(1, GLES30.GL_TEXTURE_2D, soft.texture, program, "uSoft")
        bindTexture(2, GLES30.GL_TEXTURE_2D, wide.texture, program, "uWide")
        GLES30.glUniformMatrix4fv(program.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix4fv(program.uniform("uToQ"), 1, false, toQ, 0)
        GLES30.glUniformMatrix4fv(program.uniform("uToP"), 1, false, toP, 0)
        GLES30.glUniform1f(program.uniform("uAspectQ"), aspectQ)
        GLES30.glUniform1f(program.uniform("uTime"), time)
        setFrameWindow(program, window)
        when (p.core) {
            CoreKind.SUN -> with(p.sun) {
                GLES30.glUniform1f(program.uniform("uExposure"), exposure)
                GLES30.glUniform1f(program.uniform("uGlow"), glow)
                GLES30.glUniform1f(program.uniform("uLeak"), lightLeak)
                GLES30.glUniform1f(program.uniform("uSoftness"), softness)
                GLES30.glUniform1f(program.uniform("uWarmth"), warmth)
                GLES30.glUniform1f(program.uniform("uSaturation"), saturation)
                GLES30.glUniform1f(program.uniform("uYellowGreen"), yellowGreen)
                GLES30.glUniform1f(program.uniform("uContrast"), contrast)
                GLES30.glUniform1f(program.uniform("uHaze"), haze)
            }
            CoreKind.DARK -> with(p.dark) {
                GLES30.glUniform1f(program.uniform("uTeal"), teal)
                GLES30.glUniform1f(program.uniform("uDarken"), darken)
                GLES30.glUniform1f(program.uniform("uLift"), liftedBlacks)
                GLES30.glUniform1f(program.uniform("uSaturation"), saturation)
                GLES30.glUniform1f(program.uniform("uFog"), fog)
                GLES30.glUniform1f(program.uniform("uSoftness"), softness)
                GLES30.glUniform1f(program.uniform("uVignette"), vignette)
                GLES30.glUniform1f(program.uniform("uGlow"), glow)
                // Направление в q: 0° — вправо, 90° — вверх; половина длины, длина до ~15% ширины кадра
                val a = streakAngle * PI.toFloat() / 180f
                val half = streakLength * MAX_STREAK / 2f
                GLES30.glUniform2f(program.uniform("uStreakDir"), cos(a) * half, -sin(a) * half * aspectQ)
                GLES30.glUniform1f(program.uniform("uStreakStrength"), streakStrength)
                GLES30.glUniform1f(program.uniform("uStreakOpacity"), streakOpacity)
            }
            else -> with(p.dream) {
                GLES30.glUniform1f(program.uniform("uPastel"), pastel)
                GLES30.glUniform1f(program.uniform("uShadowTone"), shadowTone)
                GLES30.glUniform1f(program.uniform("uHighlightTone"), highlightTone)
                GLES30.glUniform1f(program.uniform("uContrast"), contrast)
                GLES30.glUniform1f(program.uniform("uHaze"), haze)
                GLES30.glUniform1f(program.uniform("uSoftness"), softness)
                GLES30.glUniform1f(program.uniform("uGlow"), glow)
                GLES30.glUniform1f(program.uniform("uVignette"), vignette)
                GLES30.glUniform1f(program.uniform("uPosterize"), posterize)
            }
        }
        GlQuad.draw()
        if (grain <= 0f) return

        // Зерно — тот же проход, что у «Плёнки», в координатах полного кадра
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        grainProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, mid.texture, grainProgram, "uImage")
        setFrameWindow(grainProgram, window)
        GLES30.glUniform2f(grainProgram.uniform("uOutSize"), fullW.toFloat(), fullH.toFloat())
        GLES30.glUniform1f(grainProgram.uniform("uShort"), minOf(fullW, fullH).toFloat())
        GLES30.glUniform1f(grainProgram.uniform("uGrain"), grain)
        GLES30.glUniform1f(grainProgram.uniform("uGrainSize"), grainSize)
        GLES30.glUniform1i(grainProgram.uniform("uFrame"), frameIndex and 0xFFFFF)
        GlQuad.draw()
    }

    /**
     * Circuit. Во время всплеска с залипанием в живом конвейере повторяется прошлый кадр (замирание);
     * иначе кадр рисуется заново (в живом конвейере — через буфер, чтобы было что повторять).
     */
    private fun drawCircuit(
        inputTexture: Int,
        texMatrix: FloatArray,
        toP: FloatArray,
        toQ: FloatArray,
        outWidth: Int,
        outHeight: Int,
        portraitW: Int,
        portraitH: Int,
        targetFbo: Int,
        p: CoresParams,
        time: Float,
        live: Boolean,
        window: FrameWindow?,
    ) {
        val c = p.circuit
        val burst = burstAt(time, c.burstRate)
        val useFrame = live && c.lineStick > 0f && c.burstRate > 0f
        if (useFrame && frame.ensure(outWidth, outHeight)) frameValid = false
        val frozen = useFrame && frameValid && burst != null && unit(burst, 9) < c.lineStick * 0.5f
        if (!frozen) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (useFrame) frame.fbo else targetFbo)
            GLES30.glViewport(0, 0, outWidth, outHeight)
            val program = circuitProgram
            program.use()
            bindTexture(0, input.target, inputTexture, program, "uInput")
            val texQ = FloatArray(16).also { Matrix.multiplyMM(it, 0, texMatrix, 0, toP, 0) }
            GLES30.glUniformMatrix4fv(program.uniform("uTexQ"), 1, false, texQ, 0)
            GLES30.glUniformMatrix4fv(program.uniform("uToQ"), 1, false, toQ, 0)
            setFrameWindow(program, window)
            GLES30.glUniform2f(program.uniform("uPortrait"), portraitW.toFloat(), portraitH.toFloat())
            GLES30.glUniform1f(program.uniform("uTime"), time)
            GLES30.glUniform1f(program.uniform("uLevels"), c.levels.toFloat())
            GLES30.glUniform1f(program.uniform("uShift"), c.paletteShift)
            GLES30.glUniform1f(program.uniform("uSolarize"), c.solarize)
            GLES30.glUniform1f(program.uniform("uPalette"), c.paletteAmount)
            GLES30.glUniform1f(program.uniform("uDither"), c.dither)
            GLES30.glUniform1f(program.uniform("uDotSize"), c.ditherSize)
            GLES30.glUniform1i(program.uniform("uDitherType"), c.ditherType)
            GLES30.glUniform1f(program.uniform("uThreshold"), c.darkThreshold)
            GLES30.glUniform1f(program.uniform("uContrast"), c.contrast)
            GLES30.glUniform1f(program.uniform("uBandInvert"), c.bandInvert)
            GLES30.glUniform1f(program.uniform("uSwap"), c.channelSwap)
            GLES30.glUniform1f(program.uniform("uBlock"), c.blockShift)
            GLES30.glUniform1f(program.uniform("uLineStick"), c.lineStick)
            GLES30.glUniform1f(program.uniform("uSpeed"), c.animSpeed)
            GLES30.glUniform1f(program.uniform("uBurst"), if (burst != null) 1f else 0f)
            GLES30.glUniform1i(program.uniform("uBurstSeed"), (burst ?: 0L).toInt() and 0xFFFFF)
            GlQuad.draw()
            if (useFrame) frameValid = true
        }
        if (!useFrame) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        frameCopyProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, frame.texture, frameCopyProgram, "uInput")
        GLES30.glUniformMatrix4fv(frameCopyProgram.uniform("uTexMatrix"), 1, false, identity, 0)
        GlQuad.draw()
    }

    /** Уменьшенная копия всего кадра и два размытия: soft (~1% кадра) и wide (~3.5%). */
    private fun prepareSmall(inputTexture: Int, texMatrix: FloatArray, fullWidth: Int, fullHeight: Int) {
        val w = max(4, fullWidth / 4)
        val h = max(4, fullHeight / 4)
        small.ensure(w, h)
        tmp.ensure(w, h)
        soft.ensure(w, h)
        wide.ensure(w, h)
        small.bindAsTarget()
        copyProgram.use()
        bindTexture(0, input.target, inputTexture, copyProgram, "uInput")
        GLES30.glUniformMatrix4fv(copyProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GlQuad.draw()
        blur(small, tmp, 1f / w, 0f)
        blur(tmp, soft, 0f, 1f / h)
        blur(soft, tmp, 3f / w, 0f)
        blur(tmp, wide, 0f, 3f / h)
    }

    private fun blur(src: RenderTarget, dst: RenderTarget, stepX: Float, stepY: Float) {
        dst.bindAsTarget()
        blurProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, src.texture, blurProgram, "uSrc")
        GLES30.glUniform2f(blurProgram.uniform("uStep"), stepX, stepY)
        GlQuad.draw()
    }

    fun release() {
        copyProgram.release()
        blurProgram.release()
        dreamProgram.release()
        sunProgram.release()
        darkProgram.release()
        grainProgram.release()
        circuitProgram.release()
        frameCopyProgram.release()
        frame.release()
        small.release()
        tmp.release()
        soft.release()
        wide.release()
        mid.release()
    }

    companion object {
        /** Длина штрихов «Dark» при ползунке 1, доля ширины итогового кадра. */
        private const val MAX_STREAK = 0.15f
        /** Слотов всплеска в секунду: всплеск сбоя длится 1/5 с. */
        private const val BURST_SLOTS_PER_SEC = 5f
        /** Наибольший сдвиг блоков Circuit, доля ширины кадра (запас тайлов фото, см. [offlineMarginFraction]). */
        const val MAX_BLOCK_SHIFT = 0.1f

        /** Номер слота всплеска сбоя в момент [timeSec] или null, если сейчас всплеска нет. */
        fun burstAt(timeSec: Float, rate: Float): Long? {
            if (rate <= 0f) return null
            val slot = kotlin.math.floor(timeSec * BURST_SLOTS_PER_SEC).toLong()
            return if (unit(slot, 1) < rate * 0.35f) slot else null
        }

        /** Детерминированное псевдослучайное 0..1 от слота и соли (splitmix64). */
        private fun unit(slot: Long, salt: Int): Float {
            var z = slot * -0x61c8864680b583ebL + salt * 0x632BE59BD9B4E019L
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return ((z ushr 40).toFloat()) / (1L shl 24).toFloat()
        }

        /** Запас вокруг тайла фото (доля ширины кадра): Circuit во время сбоя берёт пиксели со сдвигом. */
        fun offlineMarginFraction(p: CoresParams): Float =
            if (p.core == CoreKind.CIRCUIT && p.circuit.blockShift > 0f && p.circuit.burstRate > 0f) MAX_BLOCK_SHIFT + 0.01f else 0f
    }
}
