package com.vhscam.app.gl

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.vhscam.app.StampFont
import com.vhscam.app.StampSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Штамп даты: один рисовальщик для превью, видео (текстура поверх итогового кадра) и фото (Canvas по готовому кадру).
 * Размеры — в долях короткой стороны итогового кадра, поэтому превью и полноразмерный результат совпадают.
 */

/** Отступ штампа от краёв кадра, доля короткой стороны. */
private const val STAMP_MARGIN = 0.045f

/** Текст штампа на момент [timeMs] (текущее время, дата файла или своя дата — решает вызывающий). */
fun stampText(s: StampSettings, timeMs: Long): String {
    val date = Date(timeMs)
    val text = SimpleDateFormat(s.format.pattern, Locale.US).format(date).uppercase(Locale.US)
    return if (s.showTime) text + "  " + SimpleDateFormat("HH:mm", Locale.US).format(date) else text
}

/** Время для штампа: своя дата из настроек, иначе [autoMs] (сейчас — у камеры, дата файла — у галереи). */
fun stampTime(s: StampSettings, autoMs: Long): Long = if (s.custom && s.customMs > 0L) s.customMs else autoMs

/** Надпись штампа с цифрами высотой [textPx] на прозрачном фоне (непрозрачность применяется при наложении). */
fun stampBitmap(s: StampSettings, text: String, textPx: Int): Bitmap {
    val h = textPx.coerceAtLeast(6).toFloat()
    val pad = ceil(h * 0.12f).toInt() + 1
    return if (s.font == StampFont.SEGMENT) {
        val width = text.sumOf { segmentAdvance(it, h).toDouble() }.toFloat()
        val bitmap = Bitmap.createBitmap(ceil(width).toInt() + pad * 2, ceil(h).toInt() + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color or 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = h * 0.115f
            strokeCap = Paint.Cap.BUTT
        }
        var x = pad.toFloat()
        for (ch in text) {
            drawSegmentChar(canvas, paint, ch, x, pad.toFloat(), h)
            x += segmentAdvance(ch, h)
        }
        bitmap
    } else {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color or 0xFF000000.toInt()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            // Высота заглавных букв и цифр ~0.72 размера шрифта
            textSize = h / 0.72f
        }
        val bitmap = Bitmap.createBitmap(ceil(paint.measureText(text)).toInt() + pad * 2, ceil(h).toInt() + pad * 2, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, pad.toFloat(), pad + h, paint)
        bitmap
    }
}

/** Место штампа в кадре [frameW]x[frameH] для надписи [bw]x[bh]: левый верхний угол в пикселях. */
private fun stampOrigin(s: StampSettings, frameW: Int, frameH: Int, bw: Int, bh: Int): Pair<Int, Int> {
    val m = (STAMP_MARGIN * min(frameW, frameH)).roundToInt()
    val right = s.corner == 0 || s.corner == 2
    val bottom = s.corner < 2
    val x = if (right) frameW - m - bw else m
    val y = if (bottom) frameH - m - bh else m
    return x.coerceAtLeast(0) to y.coerceAtLeast(0)
}

private fun stampTextPx(s: StampSettings, frameW: Int, frameH: Int): Int = (s.size * min(frameW, frameH)).roundToInt().coerceAtLeast(6)

/** Фото: штамп поверх готового кадра (после эффектов), в выбранном углу итогового кадра. */
fun drawStamp(frame: Bitmap, s: StampSettings, timeMs: Long) {
    if (!s.enabled) return
    val bitmap = stampBitmap(s, stampText(s, timeMs), stampTextPx(s, frame.width, frame.height))
    val (x, y) = stampOrigin(s, frame.width, frame.height, bitmap.width, bitmap.height)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (s.opacity * 255).roundToInt().coerceIn(0, 255) }
    Canvas(frame).drawBitmap(bitmap, x.toFloat(), y.toFloat(), paint)
    bitmap.recycle()
}

/**
 * Превью и видео: штамп отдельным проходом поверх уже нарисованного кадра в цели. Надпись — текстура ровно того
 * размера, в котором рисуется (чёткая), пересоздаётся при смене текста или настроек. Учитывает поворот и зеркало,
 * которые CameraX применит к выходу после эффекта: в файле и на экране штамп стоит прямо и не зеркальный.
 * Вызывать только из GL-потока.
 */
class StampLayer(assets: AssetManager) {
    private val program = GlProgram(assets, "quad.vert", "stamp.frag", GlProgram.BASE_HEADER)
    private var texture = 0
    private var key: String? = null
    private var texW = 1
    private var texH = 1

    /**
     * @param toFrame uv выхода -> uv итогового видимого кадра (x вправо, y вниз, 0..1), см. [outputToFrame]
     * @param frameW размеры итогового видимого кадра в пикселях
     */
    fun draw(targetFbo: Int, outW: Int, outH: Int, toFrame: FloatArray, frameW: Int, frameH: Int, s: StampSettings, timeMs: Long) {
        if (!s.enabled) return
        val textPx = stampTextPx(s, frameW, frameH)
        // Текст меняется раз в минуту: ключ — минута, а не строка (форматирование даты не на каждом кадре)
        val newKey = "${timeMs / 60_000L}|$textPx|${s.font}|${s.color}|${s.format}|${s.showTime}"
        if (newKey != key || texture == 0) {
            val bitmap = stampBitmap(s, stampText(s, timeMs), textPx)
            if (texture == 0) texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            setTextureParams(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            texW = bitmap.width
            texH = bitmap.height
            bitmap.recycle()
            key = newKey
        }
        val (x, y) = stampOrigin(s, frameW, frameH, texW, texH)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, texture, program, "uTex")
        GLES30.glUniformMatrix4fv(program.uniform("uToQ"), 1, false, toFrame, 0)
        GLES30.glUniform4f(
            program.uniform("uRect"),
            x.toFloat() / frameW, y.toFloat() / frameH, (x + texW).toFloat() / frameW, (y + texH).toFloat() / frameH,
        )
        GLES30.glUniform1f(program.uniform("uAlpha"), s.opacity.coerceIn(0f, 1f))
        GlQuad.draw()
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        key = null
    }
}

/**
 * uv выхода -> uv итогового видимого кадра (x вправо, y вниз). [crop] — uv выхода -> uv полного кадра (null — выход и
 * есть полный кадр); [rotation] и [mirror] — как полный кадр повёрнут и отражён относительно итогового (то же, что
 * получает рендерер эффекта); [live] — поверхность камеры (строка 0 — низ), иначе буфер «строка 0 — верх».
 */
fun outputToFrame(crop: FloatArray?, rotation: Int, mirror: Boolean, live: Boolean): FloatArray {
    val toQ = FloatArray(16).also { Matrix.invertM(it, 0, VhsRenderer.toBufferMatrix(if (live) rotation else 0, mirror && live, live), 0) }
    val m = if (crop == null) toQ else FloatArray(16).also { Matrix.multiplyMM(it, 0, toQ, 0, crop, 0) }
    // Видимая часть в q — образ квадрата выхода; растягиваем её на 0..1
    val a = FloatArray(4).also { Matrix.multiplyMV(it, 0, m, 0, floatArrayOf(0f, 0f, 0f, 1f), 0) }
    val b = FloatArray(4).also { Matrix.multiplyMV(it, 0, m, 0, floatArrayOf(1f, 1f, 0f, 1f), 0) }
    val x0 = min(a[0], b[0]); val y0 = min(a[1], b[1])
    val sx = 1f / max(1e-6f, kotlin.math.abs(a[0] - b[0])); val sy = 1f / max(1e-6f, kotlin.math.abs(a[1] - b[1]))
    val fit = floatArrayOf(
        sx, 0f, 0f, 0f,
        0f, sy, 0f, 0f,
        0f, 0f, 1f, 0f,
        -x0 * sx, -y0 * sy, 0f, 1f,
    )
    return FloatArray(16).also { Matrix.multiplyMM(it, 0, fit, 0, m, 0) }
}

/** Итоговый кадр повёрнут на 90°/270° относительно выхода (ширина и высота меняются местами). */
fun frameTurned(toFrame: FloatArray): Boolean = kotlin.math.abs(toFrame[1]) > kotlin.math.abs(toFrame[0])

// --- Сегментный шрифт (14 сегментов: цифры выглядят как у семисегментного индикатора, буквы месяцев читаются) ---

private const val A = 1; private const val B = 2; private const val C = 4; private const val D = 8
private const val E = 16; private const val F = 32; private const val G1 = 64; private const val G2 = 128
private const val H = 256; private const val I = 512; private const val J = 1024; private const val K = 2048
private const val L = 4096; private const val M = 8192

private val SEGMENTS: Map<Char, Int> = mapOf(
    '0' to (A or B or C or D or E or F), '1' to (B or C), '2' to (A or B or D or E or G1 or G2),
    '3' to (A or B or C or D or G1 or G2), '4' to (B or C or F or G1 or G2), '5' to (A or C or D or F or G1 or G2),
    '6' to (A or C or D or E or F or G1 or G2), '7' to (A or B or C), '8' to (A or B or C or D or E or F or G1 or G2),
    '9' to (A or B or C or D or F or G1 or G2),
    'A' to (A or B or C or E or F or G1 or G2), 'B' to (A or B or C or D or I or L or G2), 'C' to (A or D or E or F),
    'D' to (A or B or C or D or I or L), 'E' to (A or D or E or F or G1 or G2), 'F' to (A or E or F or G1 or G2),
    'G' to (A or C or D or E or F or G2), 'J' to (B or C or D or E), 'L' to (D or E or F),
    'M' to (B or C or E or F or H or J), 'N' to (B or C or E or F or H or M), 'O' to (A or B or C or D or E or F),
    'P' to (A or B or E or F or G1 or G2), 'R' to (A or B or E or F or G1 or G2 or M),
    'S' to (A or C or D or F or G1 or G2), 'T' to (A or I or L), 'U' to (B or C or D or E or F),
    'V' to (E or F or K or J), 'Y' to (H or J or L),
)

private fun segmentAdvance(ch: Char, h: Float): Float = when (ch) {
    ' ' -> h * 0.38f
    '.', ':' -> h * 0.3f
    else -> h * 0.74f
}

private fun drawSegmentChar(canvas: Canvas, paint: Paint, ch: Char, left: Float, top: Float, h: Float) {
    val sw = paint.strokeWidth
    if (ch == '.' || ch == ':') {
        val fill = Paint(paint).apply { style = Paint.Style.FILL }
        val cx = left + h * 0.1f
        if (ch == '.') {
            canvas.drawRect(cx - sw / 2, top + h - sw, cx + sw / 2, top + h, fill)
        } else {
            canvas.drawRect(cx - sw / 2, top + h * 0.3f - sw / 2, cx + sw / 2, top + h * 0.3f + sw / 2, fill)
            canvas.drawRect(cx - sw / 2, top + h * 0.7f - sw / 2, cx + sw / 2, top + h * 0.7f + sw / 2, fill)
        }
        return
    }
    val mask = SEGMENTS[ch] ?: return
    val x0 = left + sw / 2
    val x1 = left + h * 0.56f - sw / 2
    val xm = (x0 + x1) / 2
    val y0 = top + sw / 2
    val y1 = top + h - sw / 2
    val ym = (y0 + y1) / 2
    val gap = h * 0.045f
    fun seg(bit: Int, ax: Float, ay: Float, bx: Float, by: Float) {
        if (mask and bit == 0) return
        val len = max(1e-3f, hypot(bx - ax, by - ay))
        val ux = (bx - ax) / len * gap
        val uy = (by - ay) / len * gap
        canvas.drawLine(ax + ux, ay + uy, bx - ux, by - uy, paint)
    }
    seg(A, x0, y0, x1, y0); seg(B, x1, y0, x1, ym); seg(C, x1, ym, x1, y1); seg(D, x0, y1, x1, y1)
    seg(E, x0, ym, x0, y1); seg(F, x0, y0, x0, ym); seg(G1, x0, ym, xm, ym); seg(G2, xm, ym, x1, ym)
    seg(H, x0, y0, xm, ym); seg(I, xm, y0, xm, ym); seg(J, x1, y0, xm, ym)
    seg(K, xm, ym, x0, y1); seg(L, xm, ym, xm, y1); seg(M, xm, ym, x1, y1)
}
