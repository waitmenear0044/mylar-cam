package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import com.vhscam.app.effect.FilmParams
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Режим «Плёнка». Проходы:
 * 1. base — спектральное расщепление цвета, полный размер выхода;
 * 2. bright + blur H/V — света для halation в буфере 1/4 размера;
 * 3. composite — мягкость, halation, цветопередача, смаз с предыдущим кадром (ping-pong);
 * 4. output — зерно и вывод в цель (экран/кодер или FBO фото).
 * Вызывать только из GL-потока.
 */
class FilmRenderer(assets: AssetManager, private val input: GlInput) {
    private val baseProgram = GlProgram(assets, "quad.vert", "film_base.frag", input.header)
    private val brightProgram = GlProgram(assets, "quad.vert", "film_bright.frag", GlProgram.BASE_HEADER)
    private val blurProgram = GlProgram(assets, "quad.vert", "film_blur.frag", GlProgram.BASE_HEADER)
    private val compositeProgram = GlProgram(assets, "quad.vert", "film_composite.frag", GlProgram.BASE_HEADER)
    private val outputProgram = GlProgram(assets, "quad.vert", "film_output.frag", GlProgram.BASE_HEADER)

    private val base = RenderTarget()
    private val haloA = RenderTarget()
    private val haloB = RenderTarget()
    private val history = arrayOf(RenderTarget(), RenderTarget())
    private var current = 0
    private var historyValid = false

    /** Сбросить «предыдущий кадр» (смена камеры, новый поток). */
    fun resetHistory() {
        historyValid = false
    }

    /** Halation для тайлов фото уже посчитан по уменьшенной копии всего кадра ([prepareHalo]). */
    private var preparedHalo = false

    /**
     * Фото тайлами: halation (широкое размытие светов) считается один раз по уменьшенной копии всего
     * кадра в буфер 1/4 размера полного кадра — как в живом конвейере, — а тайлы берут его оттуда.
     */
    fun prepareHalo(inputTexture: Int, texMatrix: FloatArray, fullWidth: Int, fullHeight: Int, params: FilmParams) {
        val m = params.strength.coerceIn(0f, 1f)
        val qw = max(1, fullWidth / 4)
        val qh = max(1, fullHeight / 4)
        base.ensure(qw, qh)
        drawBase(inputTexture, texMatrix, base, null, fullWidth, fullHeight, params, m, frameRotation = 0)
        haloA.ensure(qw, qh)
        haloB.ensure(qw, qh)
        drawHalo(qw, qh, srcWidth = qw, srcHeight = qh)
        preparedHalo = true
    }

    /**
     * @param frameRotation на сколько градусов CameraX повернёт выход после эффекта
     *   (0, если выход уже в ориентации итогового кадра). Нужен, чтобы равномерный сдвиг
     *   расщепления шёл по горизонтали итогового кадра, а не сенсора.
     * @param useHistory false для фото: предыдущего кадра нет, смаз выключен.
     * @param historyScale длительность кадра относительно 30 fps: доля предыдущего кадра a пересчитывается
     *   как a^scale, чтобы шлейф длился одинаковое время при любой частоте (при 30 fps — без изменений).
     * @param window тайл фото в полном кадре; null — рисуется весь кадр (живой конвейер).
     *   Цель тогда размером с окно, а кайма, зерно и свечение считаются в координатах полного кадра.
     */
    fun draw(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        targetFbo: Int,
        params: FilmParams,
        frameIndex: Int,
        frameRotation: Int,
        useHistory: Boolean,
        window: FrameWindow? = null,
        historyScale: Float = 1f,
    ) {
        val m = params.strength.coerceIn(0f, 1f)
        val fullW = window?.fullWidth ?: outWidth
        val fullH = window?.fullHeight ?: outHeight
        val short = min(fullW, fullH).toFloat()
        val tiled = window != null
        val withHistory = useHistory && !tiled

        if (base.ensure(outWidth, outHeight)) historyValid = false
        val qw = max(1, outWidth / 4)
        val qh = max(1, outHeight / 4)
        if (!tiled) {
            haloA.ensure(qw, qh)
            haloB.ensure(qw, qh)
        }
        val next = history[1 - current]
        val prev = history[current]
        if (next.ensure(outWidth, outHeight)) historyValid = false
        if (withHistory && prev.ensure(outWidth, outHeight)) historyValid = false

        // 1. Расщепление цвета
        drawBase(inputTexture, texMatrix, base, window, fullW, fullH, params, m, frameRotation)

        // 2. Света для halation: уменьшение + размытие по горизонтали и вертикали
        val halation = params.halation * m
        val haloReady = if (tiled) preparedHalo else halation > 0f
        if (!tiled && halation > 0f) drawHalo(qw, qh, srcWidth = outWidth, srcHeight = outHeight)

        // 3. Композиция в ping-pong буфер
        val motion = if (withHistory && historyValid) (params.motionBlur * MOTION_SCALE * m).pow(historyScale) else 0f
        next.bindAsTarget()
        compositeProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, base.texture, compositeProgram, "uBase")
        bindTexture(1, GLES30.GL_TEXTURE_2D, haloA.texture, compositeProgram, "uHalo")
        // В тайлах предыдущего кадра нет (смаз 0); привязываем заведомо другую текстуру, не цель
        bindTexture(2, GLES30.GL_TEXTURE_2D, if (withHistory) prev.texture else base.texture, compositeProgram, "uPrev")
        setFrameWindow(compositeProgram, window)
        GLES30.glUniform2f(compositeProgram.uniform("uTexel"), 1f / outWidth, 1f / outHeight)
        GLES30.glUniform1f(compositeProgram.uniform("uSoftness"), params.softness * 0.85f * m)
        GLES30.glUniform1f(compositeProgram.uniform("uHalation"), if (haloReady) halation else 0f)
        GLES30.glUniform1f(compositeProgram.uniform("uMotion"), motion)
        GLES30.glUniform1f(compositeProgram.uniform("uFilmColor"), params.filmColor * m)
        GLES30.glUniform1f(compositeProgram.uniform("uContrast"), params.contrast * 0.8f * m)
        GLES30.glUniform1f(compositeProgram.uniform("uFade"), params.fade * m)
        GLES30.glUniform1f(compositeProgram.uniform("uSaturation"), 1f + (params.saturation - 1f) * m)
        GLES30.glUniform1f(compositeProgram.uniform("uTemperature"), params.temperature * m)
        val shadowTint = hueTint(params.shadowHue)
        val highlightTint = hueTint(params.highlightHue)
        GLES30.glUniform3f(compositeProgram.uniform("uShadowTint"), shadowTint[0], shadowTint[1], shadowTint[2])
        GLES30.glUniform1f(compositeProgram.uniform("uShadowTone"), params.shadowTone * m)
        GLES30.glUniform3f(compositeProgram.uniform("uHighlightTint"), highlightTint[0], highlightTint[1], highlightTint[2])
        GLES30.glUniform1f(compositeProgram.uniform("uHighlightTone"), params.highlightTone * m)
        GlQuad.draw()
        if (!tiled) {
            current = 1 - current
            historyValid = useHistory
        }

        // 4. Зерно и вывод
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        outputProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, next.texture, outputProgram, "uImage")
        setFrameWindow(outputProgram, window)
        GLES30.glUniform2f(outputProgram.uniform("uOutSize"), fullW.toFloat(), fullH.toFloat())
        GLES30.glUniform1f(outputProgram.uniform("uShort"), short)
        GLES30.glUniform1f(outputProgram.uniform("uGrain"), params.grain * m)
        GLES30.glUniform1f(outputProgram.uniform("uGrainSize"), params.grainSize)
        GLES30.glUniform1i(outputProgram.uniform("uFrame"), frameIndex and 0xFFFFF)
        GlQuad.draw()
    }

    private fun drawBase(
        inputTexture: Int,
        texMatrix: FloatArray,
        target: RenderTarget,
        window: FrameWindow?,
        fullW: Int,
        fullH: Int,
        params: FilmParams,
        m: Float,
        frameRotation: Int,
    ) {
        val short = min(fullW, fullH).toFloat()
        target.bindAsTarget()
        baseProgram.use()
        bindTexture(0, input.target, inputTexture, baseProgram, "uInput")
        GLES30.glUniformMatrix4fv(baseProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform2f(baseProgram.uniform("uAspect"), fullW / short, fullH / short)
        setFrameWindow(baseProgram, window)
        // Горизонталь итогового кадра: при повороте на 90/270 это ось Y буфера
        val (dirX, dirY) = when (frameRotation) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        val split = params.splitStrength * SPLIT_SCALE * m
        GLES30.glUniform2f(baseProgram.uniform("uUniformDir"), dirX, dirY)
        GLES30.glUniform1f(baseProgram.uniform("uSplit"), split)
        GLES30.glUniform1f(baseProgram.uniform("uRadial"), params.radiality)
        GLES30.glUniform1i(baseProgram.uniform("uSamples"), splitSamples(params, split, short, fullW, fullH))
        GlQuad.draw()
    }

    /** Света из [base] в [haloA] (размер [qw]x[qh]) и размытие по горизонтали и вертикали. */
    private fun drawHalo(qw: Int, qh: Int, srcWidth: Int, srcHeight: Int) {
        haloA.bindAsTarget()
        brightProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, base.texture, brightProgram, "uSrc")
        GLES30.glUniform2f(brightProgram.uniform("uSrcTexel"), 1f / srcWidth, 1f / srcHeight)
        GlQuad.draw()
        blur(haloA, haloB, 1f / qw, 0f)
        blur(haloB, haloA, 0f, 1f / qh)
    }

    /**
     * Число выборок спектра: ползунок «Качество» — нижняя граница, а при большом разбросе
     * выборок становится столько, чтобы шаг между ними был не больше ~1.2 пикселя (иначе на
     * тонких светлых линиях видны отдельные «призраки»). Для камеры потолок ниже, чтобы держать fps.
     */
    private fun splitSamples(params: FilmParams, split: Float, short: Float, w: Int, h: Int): Int {
        val minSamples = params.splitSamples.coerceIn(FilmParams.MIN_SAMPLES, FilmParams.MAX_SAMPLES)
        // Наибольший сдвиг в кадре — в углу: |mix(0.5 * dir, p, radial)| <= 0.5 * (1 - r) + |p_угла| * r
        val r = params.radiality.coerceIn(0f, 1f)
        val corner = sqrt((w / short / 2f).pow(2) + (h / short / 2f).pow(2))
        val spanPx = (0.5f * (1f - r) + corner * r) * split * short
        val wanted = ceil(spanPx / 1.2f).toInt()
        val cap = if (input == GlInput.CAMERA) MAX_CAMERA_SAMPLES else MAX_OFFLINE_SAMPLES
        return max(minSamples, min(wanted, cap))
    }

    /** Цвет оттенка [hueDeg] на круге (насыщенность ~0.6), нормированный к яркости 1 — тонировка не темнит кадр. */
    private fun hueTint(hueDeg: Float): FloatArray {
        val h = ((hueDeg % 360f) + 360f) % 360f / 60f
        val x = 1f - abs(h % 2f - 1f)
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(1f, x, 0f)
            1 -> Triple(x, 1f, 0f)
            2 -> Triple(0f, 1f, x)
            3 -> Triple(0f, x, 1f)
            4 -> Triple(x, 0f, 1f)
            else -> Triple(1f, 0f, x)
        }
        // Смешиваем с белым (насыщенность 0.6) и нормируем по яркости
        val s = 0.6f
        val rgb = floatArrayOf(1f - s + s * r, 1f - s + s * g, 1f - s + s * b)
        val luma = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
        return floatArrayOf(rgb[0] / luma, rgb[1] / luma, rgb[2] / luma)
    }

    private fun blur(src: RenderTarget, dst: RenderTarget, texelX: Float, texelY: Float) {
        dst.bindAsTarget()
        blurProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, src.texture, blurProgram, "uSrc")
        GLES30.glUniform2f(blurProgram.uniform("uStep"), texelX * BLUR_SPREAD, texelY * BLUR_SPREAD)
        GlQuad.draw()
    }

    fun release() {
        baseProgram.release()
        brightProgram.release()
        blurProgram.release()
        compositeProgram.release()
        outputProgram.release()
        base.release()
        haloA.release()
        haloB.release()
        history.forEach { it.release() }
    }

    private companion object {
        /** Полный разброс спектра при силе 1, в единицах короткой стороны (на краю кадра ×~0.8). */
        const val SPLIT_SCALE = 0.03f
        /** Максимальная доля предыдущего кадра при «Смазе» = 1. */
        const val MOTION_SCALE = 0.7f
        /** Потолок выборок спектра в живом конвейере (превью и запись, ~27-30 fps) и офлайн (фото). */
        const val MAX_CAMERA_SAMPLES = 24
        const val MAX_OFFLINE_SAMPLES = 64
        /** Шаг выборок размытия halation в текселях буфера 1/4 (радиус ~ 4*2*4 = 32 px полного кадра). */
        const val BLUR_SPREAD = 2f
    }
}
