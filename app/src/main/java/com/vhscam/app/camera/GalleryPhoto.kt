package com.vhscam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/**
 * Готовое фото из галереи для экрана обработки: исходные байты (полноразмерная обработка читает их
 * по кускам), EXIF-ориентация (поворот и зеркало применяются в пикселях до эффекта) и дата съёмки
 * для оверлея «VHS» (EXIF, иначе дата файла).
 */
class GalleryPhoto private constructor(
    val bytes: ByteArray,
    /** Поворот по часовой и зеркало по горизонтали итогового кадра — как у снимка камеры. */
    val rotation: Int,
    val mirror: Boolean,
    /** Размер в вертикальной (итоговой) ориентации. */
    val width: Int,
    val height: Int,
    val takenMs: Long,
) {
    /** Уменьшенная копия в итоговой ориентации для быстрого превью (длинная сторона <= [maxSide]). */
    fun previewBitmap(maxSide: Int): Bitmap {
        val source = SourceImage.from(bytes, maxSide)
        try {
            val small = source.decodeDownsampled(maxSide)
            val scale = minOf(1f, maxSide.toFloat() / max(small.width, small.height))
            val m = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
            if (upright !== small) small.recycle()
            return upright
        } finally {
            source.close()
        }
    }

    companion object {
        private const val TAG = "GalleryPhoto"

        fun load(context: Context, uri: Uri): GalleryPhoto {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open $uri")
            val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
            // EXIF -> поворот по часовой, затем зеркало по горизонтали (как uprightTexMatrix)
            val (rotation, mirror) = when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
                ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
                ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
                ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
                ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
                ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
                else -> 0 to false
            }
            val source = SourceImage.from(bytes, maxSideFallback = 4096)
            val (w, h) = if (rotation % 180 != 0) source.height to source.width else source.width to source.height
            source.close()
            val taken = exifDate(exif) ?: fileDate(context, uri) ?: System.currentTimeMillis()
            Log.i(TAG, "loaded ${w}x$h orientation=$orientation rotation=$rotation mirror=$mirror taken=$taken")
            return GalleryPhoto(bytes, rotation, mirror, w, h, taken)
        }

        private fun exifDate(exif: ExifInterface?): Long? {
            val text = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif?.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
            return runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text)?.time }.getOrNull()
        }

        /** Дата файла: «дата съёмки» MediaStore / Photo Picker, иначе дата изменения. */
        private fun fileDate(context: Context, uri: Uri): Long? = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val taken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                if (taken >= 0 && !c.isNull(taken) && c.getLong(taken) > 0) return@use c.getLong(taken)
                val modified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (modified >= 0 && !c.isNull(modified) && c.getLong(modified) > 0) return@use c.getLong(modified) * 1000
                val docModified = c.getColumnIndex("last_modified")
                if (docModified >= 0 && !c.isNull(docModified)) c.getLong(docModified) else null
            }
        }.getOrNull()
    }
}
