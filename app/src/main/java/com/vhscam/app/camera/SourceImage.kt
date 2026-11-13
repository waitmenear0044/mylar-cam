package com.vhscam.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import kotlin.math.max

/**
 * Снимок, который можно читать по кускам: большие JPEG (50–200 Мп) не разжимаются целиком в память,
 * а декодируются областями под каждый тайл. Координаты — в пикселях снимка в ориентации сенсора,
 * уже с учётом cropRect (если CameraX его задал).
 */
interface SourceImage {
    val width: Int
    val height: Int

    /** Кусок [region] (в координатах снимка) в полном разрешении. */
    fun decodeRegion(region: Rect): Bitmap

    /** Весь снимок, уменьшенный так, чтобы длинная сторона была не меньше [minLongSide] (степень двойки). */
    fun decodeDownsampled(minLongSide: Int): Bitmap

    fun close() {}

    companion object {
        /** Из ImageProxy: JPEG читается по кускам, остальные форматы — через toBitmap(). */
        fun from(image: ImageProxy): SourceImage {
            val crop = image.cropRect
            if (image.format == ImageFormat.JPEG) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val decoder = regionDecoder(bytes)
                val full = Rect(0, 0, decoder.width, decoder.height)
                val effective = if (!crop.isEmpty && full.contains(crop)) crop else full
                return JpegSource(decoder, effective)
            }
            val bitmap = image.toBitmap()
            val full = Rect(0, 0, bitmap.width, bitmap.height)
            val effective = if (!crop.isEmpty && full.contains(crop)) crop else full
            return BitmapSource(bitmap, effective)
        }

        /** Готовая картинка в памяти (результат предыдущего слоя); [close] освобождает её. */
        fun from(bitmap: Bitmap): SourceImage = BitmapSource(bitmap, Rect(0, 0, bitmap.width, bitmap.height))

        /**
         * Картинка -> временный файл без потерь (PNG) и чтение по кускам: промежуточный результат между слоями
         * не занимает память. [bitmap] освобождается; файл удаляется в [close].
         */
        fun viaFile(bitmap: Bitmap, file: java.io.File): SourceImage {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encode failed" } }
            bitmap.recycle()
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            val inner = JpegSource(decoder, Rect(0, 0, decoder.width, decoder.height))
            return object : SourceImage by inner {
                override fun close() {
                    inner.close()
                    file.delete()
                }
            }
        }

        /**
         * Готовое фото (JPEG, PNG, WebP, HEIF — что умеет BitmapRegionDecoder) читается по кускам, как снимок
         * камеры. Если декодер по кускам формат не берёт — целиком, уменьшенным до [maxSideFallback].
         */
        fun from(bytes: ByteArray, maxSideFallback: Int): SourceImage {
            runCatching { regionDecoder(bytes) }.getOrNull()?.let { decoder ->
                return JpegSource(decoder, Rect(0, 0, decoder.width, decoder.height))
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSideFallback) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Unsupported image")
            return BitmapSource(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        }
    }
}

private class JpegSource(private val decoder: BitmapRegionDecoder, private val crop: Rect) : SourceImage {
    override val width = crop.width()
    override val height = crop.height()

    override fun decodeRegion(region: Rect): Bitmap {
        val r = Rect(region).apply { offset(crop.left, crop.top) }
        return decoder.decodeRegion(r, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    override fun decodeDownsampled(minLongSide: Int): Bitmap {
        var sample = 1
        while (max(width, height) / (sample * 2) >= minLongSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return decoder.decodeRegion(crop, options)
    }

    override fun close() = decoder.recycle()
}

private class BitmapSource(private val bitmap: Bitmap, private val crop: Rect) : SourceImage {
    override val width = crop.width()
    override val height = crop.height()

    override fun decodeRegion(region: Rect): Bitmap =
        Bitmap.createBitmap(bitmap, crop.left + region.left, crop.top + region.top, region.width(), region.height())

    override fun decodeDownsampled(minLongSide: Int): Bitmap {
        val scale = minOf(1f, minLongSide.toFloat() / max(width, height))
        val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, width, height)
        if (scale >= 1f) return cropped
        return Bitmap.createScaledBitmap(cropped, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it !== cropped) cropped.recycle() }
    }

    override fun close() = bitmap.recycle()
}

/**
 * Декодер по кускам из байтов. Вариант без isShareable появился только в Android 12 (API 31), а minSdk — 26:
 * берём старый (есть с API 10; isShareable с API 21 игнорируется), он работает на всех версиях одинаково.
 */
@Suppress("DEPRECATION")
private fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder =
    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: error("BitmapRegionDecoder: unsupported image")
