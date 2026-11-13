package com.vhscam.app.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.vhscam.app.effect.VhsParams
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Оверлей камкордера для «VHS»: «● REC» с мигающей точкой и дата/время системным моноширинным шрифтом.
 * Текст рисуется через Canvas в небольшие текстуры (только когда меняется: раз в секунду или при мигании),
 * размер текстуры зависит от числа строк — надпись мягкая, как на записи, а не как у телефона.
 * Положение — в углах видимой части кадра (после обрезки формата). Вызывать только из GL-потока.
 */
class VhsOverlay {
    private val rec = Layer()
    private val date = Layer()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

    private class Layer {
        var texture = 0
        var key: String? = null
        var width = 1
        var height = 1
        val rect = floatArrayOf(OFF, OFF, -1f, -1f)
        fun off() {
            rect[0] = OFF; rect[1] = OFF; rect[2] = -1f; rect[3] = -1f
        }
    }

    /**
     * @param visible видимая часть кадра в q (x0, y0, x1, y1)
     * @param portraitW размеры полного итогового кадра в пикселях
     * @param lines число строк (размер текстуры текста)
     */
    fun update(
        p: VhsParams,
        visible: FloatArray,
        portraitW: Int,
        portraitH: Int,
        lines: Int,
        nowMs: Long,
        dotOn: Boolean,
    ) {
        val textPx = (lines * 0.075f).roundToInt().coerceIn(14, 56)
        val corner = p.recCorner.coerceIn(0, VhsParams.MAX_CORNER)
        if (p.showRec) {
            upload(rec, "rec:$textPx:$dotOn") { drawRec(textPx, dotOn) }
        }

        // Высота строки текста ~4.5% короткой стороны видимой части, отступ от края ~5%
        val vw = (visible[2] - visible[0]) * portraitW
        val vh = (visible[3] - visible[1]) * portraitH
        val ref = max(1f, min(vw, vh))
        val scale = 0.045f * ref / textPx
        val mx = 0.05f * ref / portraitW
        val my = 0.05f * ref / portraitH
        fun place(layer: Layer, right: Boolean, bottom: Boolean) {
            val w = layer.width * scale / portraitW
            val h = layer.height * scale / portraitH
            val x0 = if (right) visible[2] - mx - w else visible[0] + mx
            val y0 = if (bottom) visible[3] - my - h else visible[1] + my
            layer.rect[0] = x0; layer.rect[1] = y0; layer.rect[2] = x0 + w; layer.rect[3] = y0 + h
        }
        // Дата — общий штамп приложения (после эффектов); у «VHS» остался только «REC»
        if (p.showRec) place(rec, right = corner == 0 || corner == 2, bottom = corner < 2) else rec.off()
        date.off()
    }

    fun bind(program: GlProgram) {
        bindTexture(1, GLES30.GL_TEXTURE_2D, rec.texture, program, "uRecTex")
        bindTexture(2, GLES30.GL_TEXTURE_2D, date.texture, program, "uDateTex")
        GLES30.glUniform4fv(program.uniform("uRecRect"), 1, rec.rect, 0)
        GLES30.glUniform4fv(program.uniform("uDateRect"), 1, date.rect, 0)
    }

    private fun upload(layer: Layer, key: String, draw: () -> Bitmap) {
        if (layer.key == key && layer.texture != 0) return
        val bitmap = draw()
        if (layer.texture == 0) {
            layer.texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layer.texture)
        setTextureParams(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        layer.width = bitmap.width
        layer.height = bitmap.height
        layer.key = key
        bitmap.recycle()
    }

    private fun textPaint(textPx: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = textPx.toFloat()
        color = Color.WHITE
        // Тень, как у надписей видоискателя камкордера
        setShadowLayer(textPx * 0.1f, textPx * 0.07f, textPx * 0.07f, Color.argb(200, 0, 0, 0))
    }

    private fun drawRec(textPx: Int, dotOn: Boolean): Bitmap {
        val paint = textPaint(textPx)
        val pad = ceil(textPx * 0.3f).toInt()
        val dot = textPx * 0.62f
        val gap = textPx * 0.3f
        val textW = paint.measureText("REC")
        val bitmap = Bitmap.createBitmap(
            (pad * 2 + dot + gap + textW).roundToInt(), pad * 2 + ceil(textPx * 1.1f).toInt(), Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val baseline = pad - paint.fontMetrics.ascent * 0.95f
        if (dotOn) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 40, 32)
                setShadowLayer(textPx * 0.1f, textPx * 0.07f, textPx * 0.07f, Color.argb(200, 0, 0, 0))
            }
            canvas.drawCircle(pad + dot / 2, baseline - textPx * 0.36f, dot / 2, dotPaint)
        }
        canvas.drawText("REC", pad + dot + gap, baseline, paint)
        return bitmap
    }

    private fun drawLines(textPx: Int, text: List<String>, alignRight: Boolean): Bitmap {
        val paint = textPaint(textPx)
        val pad = ceil(textPx * 0.3f).toInt()
        val lineH = textPx * 1.15f
        val textW = text.maxOf { paint.measureText(it) }
        val bitmap = Bitmap.createBitmap(
            (pad * 2 + textW).roundToInt(), pad * 2 + ceil(lineH * text.size).toInt(), Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        text.forEachIndexed { i, s ->
            val x = if (alignRight) pad + textW - paint.measureText(s) else pad.toFloat()
            canvas.drawText(s, x, pad - paint.fontMetrics.ascent * 0.95f + i * lineH, paint)
        }
        return bitmap
    }

    fun release() {
        for (layer in listOf(rec, date)) {
            if (layer.texture != 0) GLES30.glDeleteTextures(1, intArrayOf(layer.texture), 0)
            layer.texture = 0
            layer.key = null
        }
    }

    private companion object {
        /** Прямоугольник «выключен»: x0 больше любого q.x. */
        const val OFF = 2f
    }
}
