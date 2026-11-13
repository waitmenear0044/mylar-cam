package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.Matrix
import com.vhscam.app.effect.VhsParams
import com.vhscam.app.effect.VhsSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Режим «VHS» (по стадиям ntsc-rs). Сигнал считается в маленьком «буфере строк» W x L (L = число строк,
 * W ≈ 2 отсчёта на квадратный пиксель) в координатах итогового портретного кадра q (x вправо, y вниз):
 * всё «горизонтальное» идёт вдоль строк итогового кадра, а не сенсора. Проходы:
 * 1. signal — кадр -> строки в YIQ: дрожание строк, головки, трекинг, шум ленты;
 * 2. luma — lowpass яркости, звон, блочность, дропауты;
 * 3. chroma — широкое размытие и сдвиг цвета (буфер W/4 x L), цветные пятна;
 * 4. bright + blur H/V — свечение в буфере 1/4 x 1/4;
 * 5. composite — обратно в RGB, цвет записи, шлейф и чересстрочность (ping-pong с предыдущим кадром);
 * 6. output — растягивание строк на выход (плавно, без квадратных пикселей), строки, оверлей.
 * Фото: проходы 1–5 один раз по уменьшенной копии всего кадра ([prepareOffline]), тайлы — только проход 6,
 * поэтому стыков нет и вид совпадает с превью. Вызывать только из GL-потока.
 */
class VhsRenderer(assets: AssetManager, private val input: GlInput) {
    private val signalProgram = GlProgram(assets, "quad.vert", "vhs_signal.frag", input.header)
    private val lumaProgram = GlProgram(assets, "quad.vert", "vhs_luma.frag", GlProgram.BASE_HEADER)
    private val chromaProgram = GlProgram(assets, "quad.vert", "vhs_chroma.frag", GlProgram.BASE_HEADER)
    private val brightProgram = GlProgram(assets, "quad.vert", "vhs_bright.frag", GlProgram.BASE_HEADER)
    private val blurProgram = GlProgram(assets, "quad.vert", "film_blur.frag", GlProgram.BASE_HEADER)
    private val compositeProgram = GlProgram(assets, "quad.vert", "vhs_composite.frag", GlProgram.BASE_HEADER)
    private val outputProgram = GlProgram(assets, "quad.vert", "vhs_output.frag", GlProgram.BASE_HEADER)

    private val signal = RenderTarget()
    private val luma = RenderTarget()
    private val chroma = RenderTarget()
    private val bloomA = RenderTarget()
    private val bloomB = RenderTarget()
    private val history = arrayOf(RenderTarget(), RenderTarget())
    private var current = 0
    private var historyValid = false
    private val overlay = VhsOverlay()

    /** Для тайлов фото: видимая часть кадра в q, посчитанная в [prepareOffline]. */
    private val preparedVisible = floatArrayOf(0f, 0f, 1f, 1f)
    private var preparedNowMs = 0L

    /** Время для даты оверлея (готовое фото — дата съёмки); null — текущее время. */
    var fixedTimeMs: Long? = null

    fun resetHistory() {
        historyValid = false
    }

    /**
     * Фото тайлами: проходы 1–5 по уменьшенной копии всего кадра ([texMatrix]: uv полного кадра ->
     * uv копии). Буфер строк зависит только от числа строк, а не от разрешения снимка.
     * @param visible видимая часть (обрезка формата) в uv полного кадра (x0, y0, x1, y1), y вниз.
     */
    fun prepareOffline(
        inputTexture: Int,
        texMatrix: FloatArray,
        fullWidth: Int,
        fullHeight: Int,
        params: VhsParams,
        visible: FloatArray?,
        frameIndex: Int,
        timeSec: Float,
    ) {
        val p = VhsSpec.blend(params, params.strength.coerceIn(0f, 1f))
        val toBuffer = toBufferMatrix(0, mirror = false, live = false)
        val texQ = FloatArray(16).also { Matrix.multiplyMM(it, 0, texMatrix, 0, toBuffer, 0) }
        renderLines(inputTexture, texQ, fullWidth, fullHeight, p, timeSec, frameIndex, useHistory = false)
        (visible ?: floatArrayOf(0f, 0f, 1f, 1f)).copyInto(preparedVisible)
        preparedNowMs = fixedTimeMs ?: System.currentTimeMillis()
    }

    /**
     * @param frameRotation на сколько градусов CameraX повернёт выход после эффекта (0 — уже повёрнут).
     * @param mirror CameraX отразит выход после эффекта (фронтальная камера): строки и надписи
     *   считаются так, чтобы в итоговом кадре они были на месте и читались.
     * @param crop uv выхода -> uv этого кадра (обрезка формата после эффекта), для углов оверлея.
     * @param window тайл фото; буфер строк уже посчитан в [prepareOffline].
     */
    fun draw(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        targetFbo: Int,
        params: VhsParams,
        timeSec: Float,
        frameIndex: Int,
        frameRotation: Int,
        mirror: Boolean,
        useHistory: Boolean,
        crop: FloatArray? = null,
        window: FrameWindow? = null,
        historyScale: Float = 1f,
    ) {
        val p = VhsSpec.blend(params, params.strength.coerceIn(0f, 1f))
        val live = input == GlInput.CAMERA && window == null
        val rotation = if (live) frameRotation else 0
        val toBuffer = toBufferMatrix(rotation, mirror && live, live)
        val toQ = FloatArray(16).also { Matrix.invertM(it, 0, toBuffer, 0) }
        val fullW = window?.fullWidth ?: outWidth
        val fullH = window?.fullHeight ?: outHeight
        val rotated = rotation % 180 != 0
        val portraitW = if (rotated) fullH else fullW
        val portraitH = if (rotated) fullW else fullH

        val visible: FloatArray
        val nowMs: Long
        if (window == null) {
            val texQ = FloatArray(16).also { Matrix.multiplyMM(it, 0, texMatrix, 0, toBuffer, 0) }
            renderLines(inputTexture, texQ, portraitW, portraitH, p, timeSec, frameIndex, useHistory, historyScale)
            visible = visibleRect(crop, toQ)
            nowMs = fixedTimeMs ?: System.currentTimeMillis()
        } else {
            visible = preparedVisible
            nowMs = preparedNowMs
        }
        val lines = history[current]

        // Растягивание буфера строк на выход, строки и оверлей
        val blink = window != null || !useHistory || (nowMs / BLINK_MS) % 2 == 0L
        overlay.update(params, visible, portraitW, portraitH, lines.height, nowMs, blink)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        outputProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, lines.texture, outputProgram, "uImage")
        GLES30.glUniformMatrix4fv(outputProgram.uniform("uToQ"), 1, false, toQ, 0)
        setFrameWindow(outputProgram, window)
        GLES30.glUniform2f(outputProgram.uniform("uSize"), lines.width.toFloat(), lines.height.toFloat())
        // На строку меньше ~2 пикселей — тёмные промежутки дали бы рябь, плавно убираем их
        val pxPerLine = portraitH.toFloat() / lines.height
        val scan = p.scanlines * smoothStep(1.4f, 2.6f, pxPerLine)
        GLES30.glUniform1f(outputProgram.uniform("uScan"), scan)
        GLES30.glUniform1f(outputProgram.uniform("uScanGain"), 1f / (1f - 0.35f * scan))
        // ЭЛТ: маска в пикселях итогового кадра; тройка уже ~4–5 пикселей — маска плавно уходит (без муара)
        val maskOn = p.crtMask > 0f && p.crtMaskType > 0
        val crt = maskOn || p.crtCurve > 0f || p.crtClouds > 0f || p.crtWave > 0f
        GLES30.glUniform1f(outputProgram.uniform("uCrt"), if (crt) 1f else 0f)
        if (crt) {
            val cellPx = p.crtMaskSize.coerceIn(VhsParams.MIN_CRT_CELL, VhsParams.MAX_CRT_CELL) * min(portraitW, portraitH)
            GLES30.glUniform2f(outputProgram.uniform("uPortrait"), portraitW.toFloat(), portraitH.toFloat())
            GLES30.glUniform1f(outputProgram.uniform("uMask"), if (maskOn) p.crtMask * smoothStep(3.5f, 6f, cellPx) else 0f)
            GLES30.glUniform1i(outputProgram.uniform("uMaskType"), p.crtMaskType)
            GLES30.glUniform1f(outputProgram.uniform("uMaskCell"), cellPx)
            GLES30.glUniform1f(outputProgram.uniform("uCurve"), p.crtCurve)
            GLES30.glUniform1f(outputProgram.uniform("uClouds"), p.crtClouds)
            GLES30.glUniform1f(outputProgram.uniform("uCloudPhase"), timeSec * p.crtCloudSpeed * CLOUD_SPEED)
            GLES30.glUniform1f(outputProgram.uniform("uWave"), p.crtWave)
            GLES30.glUniform1f(outputProgram.uniform("uTime"), timeSec)
        }
        overlay.bind(outputProgram)
        GlQuad.draw()
    }

    private fun renderLines(
        inputTexture: Int,
        texQ: FloatArray,
        portraitW: Int,
        portraitH: Int,
        p: VhsParams,
        timeSec: Float,
        frameIndex: Int,
        useHistory: Boolean,
        historyScale: Float = 1f,
    ) {
        // Буфер строк: L строк по высоте итогового кадра, ~2 отсчёта на квадратный пиксель по ширине
        val l = p.lines.coerceIn(VhsParams.MIN_LINES, VhsParams.MAX_LINES).coerceAtMost(max(MIN_SIZE, portraitH))
        val w = (l * portraitW.toFloat() / portraitH * 2f).roundToInt().coerceIn(MIN_SIZE, max(MIN_SIZE, portraitW))
        val cw = max(MIN_SIZE / 2, w / 4)
        val bw = max(4, w / 4)
        val bh = max(4, l / 4)
        signal.ensure(w, l)
        luma.ensure(w, l)
        chroma.ensure(cw, l)
        bloomA.ensure(bw, bh)
        bloomB.ensure(bw, bh)
        val next = history[1 - current]
        val prev = history[current]
        if (next.ensure(w, l)) historyValid = false
        if (useHistory && prev.ensure(w, l)) historyValid = false
        val frame = frameIndex and 0xFFFFF
        // Квадратный пиксель кадра = столько отсчётов буфера по ширине
        val pxSamples = w.toFloat() / (l * portraitW.toFloat() / portraitH)

        // 1. Сигнал
        signal.bindAsTarget()
        signalProgram.use()
        bindTexture(0, input.target, inputTexture, signalProgram, "uInput")
        GLES30.glUniformMatrix4fv(signalProgram.uniform("uTexMatrix"), 1, false, texQ, 0)
        GLES30.glUniform2f(signalProgram.uniform("uSize"), w.toFloat(), l.toFloat())
        GLES30.glUniform1f(signalProgram.uniform("uTime"), timeSec)
        GLES30.glUniform1i(signalProgram.uniform("uFrame"), frame)
        GLES30.glUniform1f(signalProgram.uniform("uJitter"), p.jitter)
        GLES30.glUniform1f(signalProgram.uniform("uHeads"), p.headSwitch)
        GLES30.glUniform1f(signalProgram.uniform("uTracking"), p.tracking)
        GLES30.glUniform1f(signalProgram.uniform("uTapeNoise"), p.tapeNoise)
        GlQuad.draw()

        // 2. Яркость: lowpass (радиус — доля ширины кадра), звон, блочность, дропауты
        luma.bindAsTarget()
        lumaProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, signal.texture, lumaProgram, "uSignal")
        GLES30.glUniform2f(lumaProgram.uniform("uSize"), w.toFloat(), l.toFloat())
        GLES30.glUniform1f(lumaProgram.uniform("uSigma"), lumaSigma(p.lumaSharpness) * w)
        GLES30.glUniform1f(lumaProgram.uniform("uRing"), p.ringing)
        GLES30.glUniform1f(lumaProgram.uniform("uBlock"), p.blockiness)
        GLES30.glUniform2f(lumaProgram.uniform("uBlockSize"), BLOCK_LINES * pxSamples, BLOCK_LINES)
        val dropsPerFrame = 0.5f * p.dropouts + 1.5f * p.dropouts * p.dropouts * p.dropouts
        GLES30.glUniform1f(lumaProgram.uniform("uDropRate"), dropsPerFrame / l)
        GLES30.glUniform1i(lumaProgram.uniform("uFrame"), frame)
        GlQuad.draw()

        // 3. Цвет
        chroma.bindAsTarget()
        chromaProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, signal.texture, chromaProgram, "uSignal")
        GLES30.glUniform2f(chromaProgram.uniform("uSrcSize"), w.toFloat(), l.toFloat())
        GLES30.glUniform1f(chromaProgram.uniform("uSigma"), chromaSigma(p.chromaBlur) * w)
        GLES30.glUniform1f(chromaProgram.uniform("uShift"), p.chromaShift * CHROMA_SHIFT)
        GLES30.glUniform1f(chromaProgram.uniform("uNoise"), p.tapeNoise)
        GLES30.glUniform1i(chromaProgram.uniform("uFrame"), frame)
        GlQuad.draw()

        // 4. Свечение: пересвеченные места, широко по горизонтали, узко по вертикали
        if (p.bloom > 0f) {
            bloomA.bindAsTarget()
            brightProgram.use()
            bindTexture(0, GLES30.GL_TEXTURE_2D, luma.texture, brightProgram, "uSignal")
            GLES30.glUniform2f(brightProgram.uniform("uSrcTexel"), 1f / w, 1f / l)
            GlQuad.draw()
            blur(bloomA, bloomB, 2f / bw, 0f)
            blur(bloomB, bloomA, 3.5f / bw, 0f)
            blur(bloomA, bloomB, 0f, 0.7f / bh)
        }

        // 5. Сборка, цвет записи, шлейф и чересстрочность
        val withHistory = useHistory && historyValid
        next.bindAsTarget()
        compositeProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, luma.texture, compositeProgram, "uLuma")
        bindTexture(1, GLES30.GL_TEXTURE_2D, chroma.texture, compositeProgram, "uChroma")
        bindTexture(2, GLES30.GL_TEXTURE_2D, bloomB.texture, compositeProgram, "uBloom")
        // Без предыдущего кадра привязываем заведомо другую текстуру, не цель
        bindTexture(3, GLES30.GL_TEXTURE_2D, if (useHistory) prev.texture else signal.texture, compositeProgram, "uPrev")
        GLES30.glUniform2f(compositeProgram.uniform("uSize"), w.toFloat(), l.toFloat())
        GLES30.glUniform1f(compositeProgram.uniform("uVertBlend"), min(1f, p.chromaShift * 2.5f))
        GLES30.glUniform1f(compositeProgram.uniform("uBlock"), p.blockiness)
        GLES30.glUniform2f(compositeProgram.uniform("uBlockSize"), BLOCK_LINES * pxSamples, BLOCK_LINES)
        GLES30.glUniform1f(compositeProgram.uniform("uSaturation"), p.saturation)
        GLES30.glUniform1f(compositeProgram.uniform("uFade"), p.fadedBlacks)
        GLES30.glUniform1f(compositeProgram.uniform("uTint"), p.tint)
        GLES30.glUniform1f(compositeProgram.uniform("uContrast"), p.contrast)
        GLES30.glUniform1f(compositeProgram.uniform("uBloomK"), if (p.bloom > 0f) p.bloom * BLOOM_GAIN else 0f)
        GLES30.glUniform1f(compositeProgram.uniform("uTrail"), if (withHistory) p.trail else 0f)
        GLES30.glUniform1f(compositeProgram.uniform("uInterlace"), if (withHistory) p.interlace else 0f)
        GLES30.glUniform1f(compositeProgram.uniform("uHistoryScale"), historyScale)
        GLES30.glUniform1i(compositeProgram.uniform("uFrame"), frame)
        GlQuad.draw()
        current = 1 - current
        historyValid = useHistory
    }

    private fun blur(src: RenderTarget, dst: RenderTarget, stepX: Float, stepY: Float) {
        dst.bindAsTarget()
        blurProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, src.texture, blurProgram, "uSrc")
        GLES30.glUniform2f(blurProgram.uniform("uStep"), stepX, stepY)
        GlQuad.draw()
    }

    /** Видимая часть кадра (после обрезки формата) в q: углы обрезки, переведённые в q. */
    private fun visibleRect(crop: FloatArray?, toQ: FloatArray): FloatArray {
        val corners = if (crop == null) {
            listOf(0f to 0f, 1f to 1f)
        } else {
            val a = FloatArray(4).also { Matrix.multiplyMV(it, 0, crop, 0, floatArrayOf(0f, 0f, 0f, 1f), 0) }
            val b = FloatArray(4).also { Matrix.multiplyMV(it, 0, crop, 0, floatArrayOf(1f, 1f, 0f, 1f), 0) }
            listOf(a[0] to a[1], b[0] to b[1])
        }
        val q = corners.map { (x, y) ->
            FloatArray(4).also { Matrix.multiplyMV(it, 0, toQ, 0, floatArrayOf(x, y, 0f, 1f), 0) }
        }
        return floatArrayOf(
            min(q[0][0], q[1][0]).coerceIn(0f, 1f), min(q[0][1], q[1][1]).coerceIn(0f, 1f),
            max(q[0][0], q[1][0]).coerceIn(0f, 1f), max(q[0][1], q[1][1]).coerceIn(0f, 1f),
        )
    }

    fun release() {
        signalProgram.release()
        lumaProgram.release()
        chromaProgram.release()
        brightProgram.release()
        blurProgram.release()
        compositeProgram.release()
        outputProgram.release()
        signal.release()
        luma.release()
        chroma.release()
        bloomA.release()
        bloomB.release()
        history.forEach { it.release() }
        overlay.release()
    }

    companion object {
        private const val MIN_SIZE = 16
        /** Сдвиг цвета вправо при ползунке 1, доля ширины кадра (у ntsc-rs SP/LP/EP — 0.5–0.8%). */
        private const val CHROMA_SHIFT = 0.025f
        private const val BLOOM_GAIN = 1.6f
        /** Сдвиг облаков ЭЛТ в секунду при скорости 1 (в единицах шума). */
        private const val CLOUD_SPEED = 0.6f
        /** Высота блока сжатия в строках (ширина — столько же квадратных пикселей). */
        private const val BLOCK_LINES = 8f
        private const val BLINK_MS = 500L

        /**
         * Радиус lowpass яркости, доля ширины кадра: от ~0.9% (мыло) до ~0.08% (почти резко);
         * по умолчанию (0.45) ~0.43% — как у SP/LP-кассеты (полоса яркости ~2 МГц).
         */
        fun lumaSigma(sharpness: Float): Float {
            val soft = 1f - sharpness.coerceIn(0f, 1f)
            return 0.0008f + 0.0085f * soft * kotlin.math.sqrt(soft)
        }

        /** Радиус размытия цвета, доля ширины кадра: по умолчанию в ~7–8 раз шире яркости. */
        fun chromaSigma(blur: Float): Float = 0.004f + (0.065f - 0.004f) * blur.coerceIn(0f, 1f)

        /**
         * q (итоговый портретный кадр: x вправо, y вниз) -> uv кадра в цели.
         * Офлайн (фото, справка): строка 0 буфера — верх кадра, поворотов нет: uv = q.
         * Камера: строка 0 GL-поверхности — низ, а CameraX после эффекта повернёт выход на
         * [rotation] по часовой и при [mirror] отразит по горизонтали.
         */
        fun toBufferMatrix(rotation: Int, mirror: Boolean, live: Boolean): FloatArray {
            // p.x = ax*x + bx*y + cx;  p.y = ay*x + by*y + cy
            var ax: Float; var bx: Float; var cx: Float; var ay: Float; var by: Float; var cy: Float
            if (!live) {
                ax = 1f; bx = 0f; cx = 0f; ay = 0f; by = 1f; cy = 0f
            } else {
                when ((rotation % 360 + 360) % 360) {
                    90 -> { ax = 0f; bx = 1f; cx = 0f; ay = 1f; by = 0f; cy = 0f }     // p = (y, x)
                    180 -> { ax = -1f; bx = 0f; cx = 1f; ay = 0f; by = 1f; cy = 0f }   // p = (1-x, y)
                    270 -> { ax = 0f; bx = -1f; cx = 1f; ay = -1f; by = 0f; cy = 1f }  // p = (1-y, 1-x)
                    else -> { ax = 1f; bx = 0f; cx = 0f; ay = 0f; by = -1f; cy = 1f }  // p = (x, 1-y)
                }
            }
            if (mirror) {
                // x -> 1 - x
                cx += ax; ax = -ax
                cy += ay; ay = -ay
            }
            return floatArrayOf(
                ax, ay, 0f, 0f,
                bx, by, 0f, 0f,
                0f, 0f, 1f, 0f,
                cx, cy, 0f, 1f,
            )
        }
    }
}

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
