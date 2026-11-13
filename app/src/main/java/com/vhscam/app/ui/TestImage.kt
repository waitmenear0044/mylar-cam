package com.vhscam.app.ui

import com.vhscam.app.AppInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Тестовая картинка для справки, нарисованная программно: светлый фон с тёмными предметами
 * (видна кайма), цветные полосы, серый градиент, текст и тёмный угол со светящейся лампой (halation).
 */
object TestImage {
    const val WIDTH = 960
    const val HEIGHT = 720

    fun create(): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Светлый «бумажный» фон
        p.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFFEDE9DF.toInt(), 0xFFD8D3C6.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), p)
        p.shader = null

        // Цветные полосы
        val bars = intArrayOf(
            0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
            0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
        )
        val barW = 52f
        bars.forEachIndexed { i, color ->
            p.color = color
            c.drawRect(40f + i * barW, 40f, 40f + (i + 1) * barW, 300f, p)
        }

        // Серый градиент от чёрного к белому
        p.shader = LinearGradient(40f, 0f, 920f, 0f, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(40f, 330f, 920f, 390f, p)
        p.shader = null

        // Тёмные предметы на светлом фоне: толстый «крючок», тонкие линии, рама окна
        p.style = Paint.Style.STROKE
        p.color = 0xFF1A1A1E.toInt()
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 22f
        val hook = Path().apply {
            moveTo(470f, 300f)
            lineTo(470f, 110f)
            quadTo(470f, 50f, 530f, 50f)
            quadTo(590f, 50f, 590f, 110f)
        }
        c.drawPath(hook, p)
        p.strokeWidth = 3f
        for (i in 0..4) c.drawLine(640f + i * 16f, 40f, 660f + i * 16f, 300f, p)
        p.strokeWidth = 10f
        c.drawRect(RectF(760f, 50f, 920f, 290f), p)
        p.strokeWidth = 6f
        c.drawLine(840f, 50f, 840f, 290f, p)
        c.drawLine(760f, 170f, 920f, 170f, p)
        p.style = Paint.Style.FILL

        // Текст
        p.color = 0xFF202024.toInt()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = 64f
        c.drawText("${AppInfo.NAME} 1999", 40f, 490f, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = 28f
        c.drawText("мелкий текст: проверка резкости и каймы", 40f, 540f, p)

        // Тёмный угол с лампой: проверка halation и свечения
        p.color = 0xFF1E2228.toInt()
        c.drawRect(560f, 420f, 920f, 690f, p)
        p.shader = RadialGradient(740f, 555f, 90f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFF3C4.toInt(), 0x00FFE082), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(740f, 555f, 90f, p)
        p.shader = null
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(740f, 555f, 26f, p)
        // Тонкие светлые линии на тёмном: «призраки» расщепления видны сразу
        p.strokeWidth = 2f
        for (i in 0..3) c.drawLine(590f + i * 14f, 440f, 590f + i * 14f, 670f, p)

        // Тени в левом нижнем углу (зерно и выцветание видны в тёмных тонах)
        p.shader = LinearGradient(40f, 0f, 520f, 0f, 0xFF2A2A2E.toInt(), 0xFF8A8A88.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(40f, 580f, 520f, 690f, p)
        p.shader = null
        return bmp
    }
}
