package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import com.vhscam.app.effect.PixelParams
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Режим «Пиксель» (вид этапов 2–3): камера -> маленький буфер + цвет, затем растягивание без
 * сглаживания. Шейдеры и математика те же, что на этапах 2–3. Вызывать только из GL-потока.
 */
class PixelRenderer(assets: AssetManager, private val input: GlInput) {
    private val pixelProgram = GlProgram(assets, "quad.vert", "pixel.frag", input.header)
    private val upscaleProgram = GlProgram(assets, "quad.vert", "pixel_upscale.frag", GlProgram.BASE_HEADER)

    // Внутренний буфер выделяется под максимальную ширину; текущая ширина рисуется в его угол,
    // поэтому ползунок разрешения не требует пересоздания текстуры.
    private val lowRes = RenderTarget(GLES30.GL_NEAREST)

    /** Размер кадра в маленьком буфере, посчитанный последним проходом 1. */
    private var lowW = 1
    private var lowH = 1

    /**
     * Фото тайлами: маленький буфер (сетка крупных пикселей) считается один раз для всего кадра
     * по уменьшенной копии снимка; тайлы потом только растягивают его ([draw] с окном).
     */
    fun prepareLowRes(inputTexture: Int, texMatrix: FloatArray, fullWidth: Int, fullHeight: Int, params: PixelParams, timeSec: Float) {
        drawLowRes(inputTexture, texMatrix, fullWidth, fullHeight, params, timeSec)
    }

    /** @param window тайл фото в полном кадре; null — весь кадр (живой конвейер). */
    fun draw(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        targetFbo: Int,
        params: PixelParams,
        timeSec: Float,
        window: FrameWindow? = null,
    ) {
        if (window == null) drawLowRes(inputTexture, texMatrix, outWidth, outHeight, params, timeSec)

        // Проход 2: буфер -> выход, увеличение без сглаживания
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        upscaleProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, lowRes.texture, upscaleProgram, "uLowRes")
        setFrameWindow(upscaleProgram, window)
        GLES30.glUniform2f(
            upscaleProgram.uniform("uUvScale"),
            lowW.toFloat() / lowRes.width, lowH.toFloat() / lowRes.height,
        )
        GlQuad.draw()
    }

    private fun drawLowRes(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        params: PixelParams,
        timeSec: Float,
    ) {
        // «Ширина» из ползунка — число пикселей по короткой стороне кадра, то есть по ширине
        // портретного кадра. Выход процессора может быть и в ориентации сенсора (альбомный буфер,
        // который CameraX повернёт уже после эффекта), поэтому ориентируемся на короткую сторону.
        val short = params.resolutionWidth.coerceIn(PixelParams.MIN_WIDTH, PixelParams.MAX_WIDTH)
        val portrait = outWidth <= outHeight
        val longToShort = if (portrait) outHeight.toFloat() / outWidth else outWidth.toFloat() / outHeight
        val long = max(1, (short * longToShort).roundToInt())
        lowW = if (portrait) short else long
        lowH = if (portrait) long else short
        val maxLong = ceil(PixelParams.MAX_WIDTH * longToShort).toInt() + 1
        if (portrait) lowRes.ensure(PixelParams.MAX_WIDTH, maxLong) else lowRes.ensure(maxLong, PixelParams.MAX_WIDTH)

        // Проход 1: камера -> маленький буфер + цвет
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lowRes.fbo)
        GLES30.glViewport(0, 0, lowW, lowH)
        pixelProgram.use()
        bindTexture(0, input.target, inputTexture, pixelProgram, "uInput")
        GLES30.glUniformMatrix4fv(pixelProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform2f(pixelProgram.uniform("uLowSize"), lowW.toFloat(), lowH.toFloat())
        GLES30.glUniform1f(pixelProgram.uniform("uTime"), timeSec)
        GLES30.glUniform1f(pixelProgram.uniform("uSaturation"), params.saturation)
        GLES30.glUniform1f(pixelProgram.uniform("uBlackLevel"), params.blackLevel)
        GLES30.glUniform1f(pixelProgram.uniform("uTint"), params.tint)
        GlQuad.draw()
    }

    fun release() {
        pixelProgram.release()
        upscaleProgram.release()
        lowRes.release()
    }
}
