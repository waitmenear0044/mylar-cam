package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.Matrix
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.NoiseParams
import kotlin.math.min

/**
 * Рисует текущий режим эффекта из входной текстуры в цель. Не знает, куда идёт кадр
 * (экран, видеокодер или фото): одинаково для любого выхода. Новый режим = новый рендерер
 * и ветка в [drawMode]. Вызывать только из GL-потока.
 *
 * Эффект всегда считается в координатах полного кадра ([fullWidth] x [fullHeight], 3:4), а затем
 * общий финальный проход обрезает его до формата выхода ([crop]) и добавляет цифровой шум.
 * Без обрезки и шума финального прохода нет — режим рисует прямо в цель, как раньше.
 */
class EffectRenderer(assets: AssetManager, private val input: GlInput) {
    private val copyProgram = GlProgram(assets, "quad.vert", "copy.frag", input.header)
    private val postProgram = GlProgram(assets, "quad.vert", "post.frag", GlProgram.BASE_HEADER)
    // Рендереры режимов создаются при первом использовании: шейдеры компилируются только для режимов, которые
    // реально рисуются (холодный старт камеры, миниатюры пресетов, сохранение фото — каждый раз один режим, а не девять)
    private val releasers = ArrayList<() -> Unit>()
    private val pixelLazy = lazy { PixelRenderer(assets, input).also { r -> releasers += { r.release() } } }
    private val filmLazy = lazy { FilmRenderer(assets, input).also { r -> releasers += { r.release() } } }
    private val vhsLazy = lazy { VhsRenderer(assets, input).also { r -> r.fixedTimeMs = pendingOverlayTime; releasers += { r.release() } } }
    private val coresLazy = lazy { CoresRenderer(assets, input).also { r -> releasers += { r.release() } } }
    private val gradientLazy = lazy { GradientRenderer(assets, input).also { r -> releasers += { r.release() } } }
    private val ghostLazy = lazy { GhostRenderer(assets, input).also { r -> releasers += { r.release() } } }
    private val pixel get() = pixelLazy.value
    private val film get() = filmLazy.value
    private val vhs get() = vhsLazy.value
    private val cores get() = coresLazy.value
    private val gradient get() = gradientLazy.value
    private val ghost get() = ghostLazy.value
    private var pendingOverlayTime: Long? = null
    private val newspaper by lazy { NewspaperRenderer(assets, input).also { r -> releasers += { r.release() } } }
    private val colorSelect by lazy { ColorSelectRenderer(assets, input).also { r -> releasers += { r.release() } } }
    private val full = RenderTarget()
    private val probe = RenderTarget()
    private val probePixels = java.nio.ByteBuffer.allocateDirect(PROBE * PROBE * 4).order(java.nio.ByteOrder.nativeOrder())
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var lastMode: EffectMode? = null

    /** Зерно «неподвижных» случайных рисунков в видео (рисунки «Газеты»): одно на весь ролик. */
    var noiseSeed = 7


    /** Время для даты оверлея «VHS» (готовое фото — дата съёмки из EXIF); null — текущее. */
    var overlayTimeMs: Long?
        get() = pendingOverlayTime
        set(value) {
            pendingOverlayTime = value
            if (vhsLazy.isInitialized()) vhs.fixedTimeMs = value
        }

    /** Сбросить состояние между кадрами (предыдущий кадр для смаза) — при смене камеры. */
    fun resetHistory() {
        if (filmLazy.isInitialized()) film.resetHistory()
        if (vhsLazy.isInitialized()) vhs.resetHistory()
        if (coresLazy.isInitialized()) cores.resetHistory()
        if (ghostLazy.isInitialized()) ghost.resetHistory()
    }

    /**
     * @param texMatrix uv полного кадра -> uv входной текстуры
     * @param crop uv выхода -> uv полного кадра; null — выход и есть полный кадр
     * @param frameMirror CameraX отразит выход после эффекта (фронтальная камера)
     * @param frameIndex «тик» покадровых эффектов (шум, зерно, дрожание); в живом конвейере — от времени кадра
     * @param frameDurationSec длительность кадра: доля предыдущего кадра в смазе/шлейфе пересчитывается от неё
     */
    fun draw(
        inputTexture: Int,
        texMatrix: FloatArray,
        fullWidth: Int,
        fullHeight: Int,
        outWidth: Int,
        outHeight: Int,
        crop: FloatArray?,
        targetFbo: Int,
        settings: EffectSettings,
        timeSec: Float,
        frameIndex: Int,
        frameRotation: Int,
        useHistory: Boolean,
        frameMirror: Boolean = false,
        frameDurationSec: Float = REFERENCE_FRAME_SEC,
    ) {
        if (settings.mode != lastMode) {
            resetHistory()
            // Ушли из «Гостинга» — его буферы истории больше не нужны
            if (lastMode == EffectMode.GHOST && ghostLazy.isInitialized()) ghost.releaseBuffers()
            lastMode = settings.mode
        }
        val noise = if (settings.enabled) noiseOf(settings) else NoiseParams()
        if (crop == null && !noise.active) {
            drawMode(inputTexture, texMatrix, outWidth, outHeight, targetFbo, settings, timeSec, frameIndex, frameRotation, useHistory, frameMirror, null, frameDurationSec)
            return
        }

        full.ensure(fullWidth, fullHeight)
        drawMode(inputTexture, texMatrix, fullWidth, fullHeight, full.fbo, settings, timeSec, frameIndex, frameRotation, useHistory, frameMirror, crop, frameDurationSec)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        postProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, full.texture, postProgram, "uImage")
        GLES30.glUniformMatrix4fv(postProgram.uniform("uCrop"), 1, false, crop ?: identity, 0)
        setFrameWindow(postProgram, null)
        GLES30.glUniform2f(postProgram.uniform("uOutSize"), outWidth.toFloat(), outHeight.toFloat())
        // Короткая сторона полного кадра в пикселях выхода: масштаб выхода к полному кадру
        val scale = outWidth.toFloat() / cropSpanX(crop, fullWidth, fullHeight)
        GLES30.glUniform1f(postProgram.uniform("uFullShort"), min(fullWidth, fullHeight) * scale)
        GLES30.glUniform1f(postProgram.uniform("uLuma"), noise.luma)
        GLES30.glUniform1f(postProgram.uniform("uChroma"), noise.chroma)
        GLES30.glUniform1f(postProgram.uniform("uSize"), noise.size)
        GLES30.glUniform1f(postProgram.uniform("uShadows"), noise.shadows)
        GLES30.glUniform1i(postProgram.uniform("uFrame"), frameIndex and 0xFFFFF)
        GlQuad.draw()
    }

    /**
     * Фото тайлами, шаг 1: проходы, которым нужен весь кадр, считаются один раз по уменьшенной копии
     * снимка ([smallTexture], [texMatrix]: uv полного кадра -> uv копии): halation у «Плёнки»,
     * сетка крупных пикселей у «Пикселя», весь буфер строк у «VHS».
     * @param visible видимая часть после обрезки формата в uv полного кадра (x0, y0, x1, y1) — для оверлея «VHS»
     */
    fun prepareOffline(
        smallTexture: Int,
        texMatrix: FloatArray,
        fullWidth: Int,
        fullHeight: Int,
        settings: EffectSettings,
        visible: FloatArray? = null,
        frameIndex: Int = 0,
    ) {
        if (!settings.enabled) return
        when (settings.mode) {
            EffectMode.PIXEL -> pixel.prepareLowRes(smallTexture, texMatrix, fullWidth, fullHeight, settings.pixel, 0f)
            EffectMode.FILM -> film.prepareHalo(smallTexture, texMatrix, fullWidth, fullHeight, settings.film)
            EffectMode.VHS -> vhs.prepareOffline(
                smallTexture, texMatrix, fullWidth, fullHeight, settings.vhs, visible, frameIndex,
                timeSec = (frameIndex and 0x3FF) * 0.731f,
            )
            EffectMode.CORES -> cores.prepareOffline(
                smallTexture, texMatrix, fullWidth, fullHeight, settings.cores, timeSec = (frameIndex and 0x3FF) * 0.731f,
            )
            // Попиксельный «Градиент» и «Гостинг» (на фото — кадр как есть) проходов по всему кадру не требуют
            EffectMode.GRADIENT, EffectMode.GHOST, EffectMode.COLOR -> Unit
            EffectMode.NEWSPAPER -> newspaper.prepare(smallTexture, texMatrix, fullWidth, fullHeight)
        }
    }

    /**
     * Фото тайлами, шаг 2: окно [window] полного кадра рисуется в цель размером с окно.
     * [texMatrix] переводит uv полного кадра в uv текстуры [tileTexture] (кусок снимка с запасом).
     */
    fun drawTile(
        tileTexture: Int,
        texMatrix: FloatArray,
        window: FrameWindow,
        targetFbo: Int,
        settings: EffectSettings,
        frameIndex: Int,
    ) {
        val w = window.width
        val h = window.height
        if (!settings.enabled || settings.mode == EffectMode.GHOST) {
            // Без эффекта (и «Гостинг» на неподвижном фото): копия куска снимка; uv окна -> uv полного кадра -> uv текстуры
            val local = FloatArray(16)
            Matrix.multiplyMM(local, 0, texMatrix, 0, windowMatrix(window), 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
            GLES30.glViewport(0, 0, w, h)
            copyProgram.use()
            bindTexture(0, input.target, tileTexture, copyProgram, "uInput")
            GLES30.glUniformMatrix4fv(copyProgram.uniform("uTexMatrix"), 1, false, local, 0)
            GlQuad.draw()
            return
        }
        val noise = noiseOf(settings)
        val modeTarget = if (noise.active) full.also { it.ensure(w, h) }.fbo else targetFbo
        when (settings.mode) {
            EffectMode.PIXEL -> pixel.draw(tileTexture, texMatrix, w, h, modeTarget, settings.pixel, 0f, window)
            EffectMode.FILM -> film.draw(
                tileTexture, texMatrix, w, h, modeTarget, settings.film, frameIndex,
                frameRotation = 0, useHistory = false, window = window,
            )
            EffectMode.VHS -> vhs.draw(
                tileTexture, texMatrix, w, h, modeTarget, settings.vhs, 0f, frameIndex,
                frameRotation = 0, mirror = false, useHistory = false, window = window,
            )
            EffectMode.CORES -> cores.draw(
                tileTexture, texMatrix, w, h, modeTarget, settings.cores, 0f, frameIndex,
                frameRotation = 0, mirror = false, window = window,
            )
            EffectMode.GRADIENT -> gradient.draw(tileTexture, texMatrix, w, h, modeTarget, settings.gradient, frameIndex, window)
            EffectMode.GHOST -> Unit
            EffectMode.NEWSPAPER -> newspaper.draw(w, h, modeTarget, settings.newspaper, frameIndex, window)
            EffectMode.COLOR -> colorSelect.draw(tileTexture, texMatrix, w, h, modeTarget, settings.colorSelect, window)
        }
        if (!noise.active) return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, w, h)
        postProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, full.texture, postProgram, "uImage")
        GLES30.glUniformMatrix4fv(postProgram.uniform("uCrop"), 1, false, identity, 0)
        setFrameWindow(postProgram, window)
        GLES30.glUniform2f(postProgram.uniform("uOutSize"), window.fullWidth.toFloat(), window.fullHeight.toFloat())
        GLES30.glUniform1f(postProgram.uniform("uFullShort"), min(window.fullWidth, window.fullHeight).toFloat())
        GLES30.glUniform1f(postProgram.uniform("uLuma"), noise.luma)
        GLES30.glUniform1f(postProgram.uniform("uChroma"), noise.chroma)
        GLES30.glUniform1f(postProgram.uniform("uSize"), noise.size)
        GLES30.glUniform1f(postProgram.uniform("uShadows"), noise.shadows)
        GLES30.glUniform1i(postProgram.uniform("uFrame"), frameIndex and 0xFFFFF)
        GlQuad.draw()
    }

    /**
     * Дополнительный запас вокруг тайла фото, доля ширины полного кадра: режимы, которые берут пиксели
     * далеко от текущего (сдвиг блоков Circuit), иначе упёрлись бы в край куска снимка.
     */
    fun offlineMarginFraction(settings: EffectSettings): Float =
        if (settings.enabled && settings.mode == EffectMode.CORES) CoresRenderer.offlineMarginFraction(settings.cores) else 0f

    /** uv окна -> uv полного кадра. */
    private fun windowMatrix(wnd: FrameWindow): FloatArray {
        val sx = wnd.width.toFloat() / wnd.fullWidth
        val sy = wnd.height.toFloat() / wnd.fullHeight
        return floatArrayOf(
            sx, 0f, 0f, 0f,
            0f, sy, 0f, 0f,
            0f, 0f, 1f, 0f,
            wnd.x.toFloat() / wnd.fullWidth, wnd.y.toFloat() / wnd.fullHeight, 0f, 1f,
        )
    }

    /**
     * Средняя яркость исходного кадра 0..1 (без эффекта): кадр уменьшается в буфер 16x16 и читается.
     * Нужна для «Авто» экранной вспышки, где CameraX сам не решает, темно ли.
     */
    fun measureLuma(inputTexture: Int, texMatrix: FloatArray): Float {
        probe.ensure(PROBE, PROBE)
        probe.bindAsTarget()
        copyProgram.use()
        bindTexture(0, input.target, inputTexture, copyProgram, "uInput")
        GLES30.glUniformMatrix4fv(copyProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GlQuad.draw()
        probePixels.rewind()
        GLES30.glReadPixels(0, 0, PROBE, PROBE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, probePixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        var sum = 0f
        for (i in 0 until PROBE * PROBE) {
            val r = probePixels.get(i * 4).toInt() and 0xFF
            val g = probePixels.get(i * 4 + 1).toInt() and 0xFF
            val b = probePixels.get(i * 4 + 2).toInt() and 0xFF
            sum += 0.299f * r + 0.587f * g + 0.114f * b
        }
        return sum / (PROBE * PROBE * 255f)
    }

    /** Копия входного кадра без эффекта в Bitmap [w]x[h] (строка 0 — верх): замороженный кадр для миниатюр. */
    fun grab(inputTexture: Int, matrix: FloatArray, w: Int, h: Int): android.graphics.Bitmap {
        val target = RenderTarget()
        target.ensure(w, h)
        target.bindAsTarget()
        copyProgram.use()
        bindTexture(0, input.target, inputTexture, copyProgram, "uInput")
        GLES30.glUniformMatrix4fv(copyProgram.uniform("uTexMatrix"), 1, false, matrix, 0)
        GlQuad.draw()
        val pixels = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        target.release()
        pixels.rewind()
        return android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(pixels) }
    }

    /** Сколько пикселей полного кадра укладывается в ширину выхода (обрезка может быть повёрнута на 90°). */
    private fun cropSpanX(crop: FloatArray?, fullWidth: Int, fullHeight: Int): Float =
        if (crop == null) fullWidth.toFloat()
        else (kotlin.math.abs(crop[0]) * fullWidth + kotlin.math.abs(crop[1]) * fullHeight).coerceAtLeast(1f)

    private fun noiseOf(settings: EffectSettings): NoiseParams = when (settings.mode) {
        EffectMode.PIXEL -> settings.pixel.noise
        EffectMode.FILM -> settings.film.noise.let {
            // «Сила эффекта» плёнки масштабирует и шум
            val m = settings.film.strength.coerceIn(0f, 1f)
            it.copy(luma = it.luma * m, chroma = it.chroma * m)
        }
        EffectMode.VHS -> settings.vhs.noise.let {
            val m = settings.vhs.strength.coerceIn(0f, 1f)
            it.copy(luma = it.luma * m, chroma = it.chroma * m)
        }
        // У Dream и Dark свой ползунок шума в «Текстуре»: складывается с общей категорией «Шум»
        EffectMode.CORES -> settings.cores.noise.let {
            val m = settings.cores.strength.coerceIn(0f, 1f)
            val own = settings.cores.coreNoise
            it.copy(luma = maxOf(it.luma, own) * m, chroma = maxOf(it.chroma, own * 0.5f) * m)
        }
        EffectMode.GRADIENT -> settings.gradient.noise
        EffectMode.GHOST -> settings.ghost.noise
        EffectMode.NEWSPAPER, EffectMode.COLOR -> NoiseParams()
    }

    private fun drawMode(
        inputTexture: Int,
        texMatrix: FloatArray,
        w: Int,
        h: Int,
        targetFbo: Int,
        settings: EffectSettings,
        timeSec: Float,
        frameIndex: Int,
        frameRotation: Int,
        useHistory: Boolean,
        frameMirror: Boolean,
        crop: FloatArray?,
        frameDurationSec: Float,
    ) {
        if (!settings.enabled) {
            resetHistory()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
            GLES30.glViewport(0, 0, w, h)
            copyProgram.use()
            bindTexture(0, input.target, inputTexture, copyProgram, "uInput")
            GLES30.glUniformMatrix4fv(copyProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
            GlQuad.draw()
            return
        }
        when (settings.mode) {
            EffectMode.PIXEL -> pixel.draw(inputTexture, texMatrix, w, h, targetFbo, settings.pixel, timeSec)
            EffectMode.FILM -> film.draw(
                inputTexture, texMatrix, w, h, targetFbo, settings.film, frameIndex, frameRotation, useHistory,
                historyScale = frameDurationSec / REFERENCE_FRAME_SEC,
            )
            EffectMode.VHS -> vhs.draw(
                inputTexture, texMatrix, w, h, targetFbo, settings.vhs, timeSec, frameIndex, frameRotation,
                frameMirror, useHistory, crop, historyScale = frameDurationSec / REFERENCE_FRAME_SEC,
            )
            EffectMode.CORES -> cores.draw(
                inputTexture, texMatrix, w, h, targetFbo, settings.cores, timeSec, frameIndex, frameRotation, frameMirror,
            )
            EffectMode.GRADIENT -> gradient.draw(inputTexture, texMatrix, w, h, targetFbo, settings.gradient, frameIndex)
            EffectMode.GHOST -> ghost.draw(inputTexture, texMatrix, w, h, targetFbo, settings.ghost, timeSec, frameDurationSec, useHistory)
            // Редакторские режимы без тайлов (превью, видео): подготовка по самому кадру, затем тот же проход
            EffectMode.NEWSPAPER -> {
                newspaper.prepare(inputTexture, texMatrix, w, h)
                newspaper.draw(w, h, targetFbo, settings.newspaper, if (useHistory) noiseSeed else frameIndex)
            }
            EffectMode.COLOR -> colorSelect.draw(inputTexture, texMatrix, w, h, targetFbo, settings.colorSelect)
        }
    }

    fun release() {
        copyProgram.release()
        postProgram.release()
        releasers.forEach { it() }
        releasers.clear()
        full.release()
        probe.release()
    }

    companion object {
        private const val PROBE = 16
        /** Длительность кадра, под которую подобраны смаз и шлейф (30 fps). */
        const val REFERENCE_FRAME_SEC = 1f / 30f
    }
}
