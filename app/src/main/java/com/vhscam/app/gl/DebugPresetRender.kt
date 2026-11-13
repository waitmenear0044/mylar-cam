package com.vhscam.app.gl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.vhscam.app.effect.BuiltInPresets
import com.vhscam.app.effect.EffectSettings
import com.vhscam.app.effect.specFor
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Отладочная самопроверка пресетов (только debug-сборка): прогоняет все готовые пресеты через тот же
 * офлайн-рендер, что и фото, по картинкам из files/debug_look_in и пишет результат в files/debug_look_out.
 * Запуск: adb shell am start -n com.vhscam.app/.MainActivity --ez debug_render_presets true
 */
object DebugPresetRender {
    private const val TAG = "DebugPresetRender"

    fun isAllowed(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun runAsync(context: Context) {
        val app = context.applicationContext
        Thread({ run(app) }, "DebugPresetRender").start()
    }

    /**
     * Проверка ползунков режима: для каждого ползунка (у [spec] — «MODE» или «MODE:Категория») кадр с ползунком на
     * минимуме и на максимуме (остальное — значения по умолчанию режима), средняя разница кадров в sweep.csv.
     * Запуск: adb shell am start -n com.vhscam.app/.MainActivity --es debug_render_sweep NEWSPAPER
     */
    fun sweepAsync(context: Context, spec: String) {
        val app = context.applicationContext
        Thread({ sweep(app, spec) }, "DebugSweep").start()
    }

    private fun sweep(context: Context, arg: String) {
        val input = File(context.filesDir, "debug_look_in")
        val output = File(context.filesDir, "debug_sweep_out").apply { deleteRecursively(); mkdirs() }
        val mode = com.vhscam.app.effect.EffectMode.valueOf(arg.substringBefore(':'))
        val categoryFilter = arg.substringAfter(':', "")
        val spec = specFor(mode)
        val images = input.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sorted()
        val egl = EglCore()
        val csv = StringBuilder("image,category,param,min,max,mean_abs_diff\n")
        try {
            val renderer = EffectRenderer(context.assets, GlInput.TEXTURE_2D)
            val matrix = uprightTexMatrix(0, false)
            val base = spec.setEnabled(spec.defaults, true)
            for (file in images) {
                val src = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val scale = minOf(1f, 1024f / max(src.width, src.height))
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true) else src
                val tex = uploadBitmap(bmp)
                fun render(p: Any, seed: Int, tag: String): Bitmap {
                    val out = renderToBitmap(renderer, tex, matrix, bmp.width, bmp.height, spec.put(EffectSettings(mode = mode), p), frameIndex = seed)
                    File(output, "${file.nameWithoutExtension}__$tag.jpg").outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    return out
                }
                render(base, 3, "default").recycle()
                for ((ci, category) in spec.categoriesOf(base).withIndex()) {
                    if (categoryFilter.isNotEmpty() && category.title != categoryFilter && categoryFilter != ci.toString()) continue
                    for ((pi, param) in category.params.withIndex()) {
                        val pb = param.demoBase?.invoke(base) ?: base
                        val lo = render(param.set(pb, param.range.start), 3, "c${ci}_p${pi}_min")
                        val hi = render(param.set(pb, param.range.endInclusive), 3, "c${ci}_p${pi}_max")
                        csv.append("${file.name},${category.title},${param.label},${param.range.start},${param.range.endInclusive},${"%.2f".format(java.util.Locale.US, diff(lo, hi))}\n")
                        lo.recycle(); hi.recycle()
                    }
                }
                android.opengl.GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            renderer.release()
        } catch (e: Exception) {
            Log.e(TAG, "sweep failed", e)
            csv.append("error,$e\n")
        } finally {
            egl.release()
            File(output, "sweep.csv").writeText(csv.toString())
            File(output, "done.txt").writeText("ok")
        }
    }

    /** Средняя абсолютная разница двух кадров (0..255). */
    private fun diff(a: Bitmap, b: Bitmap): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }
        val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        var sum = 0L
        for (i in pa.indices) {
            val x = pa[i]; val y = pb[i]
            sum += kotlin.math.abs((x shr 16 and 255) - (y shr 16 and 255)) + kotlin.math.abs((x shr 8 and 255) - (y shr 8 and 255)) + kotlin.math.abs((x and 255) - (y and 255))
        }
        return sum.toDouble() / (pa.size * 3)
    }

    private fun run(context: Context) {
        val input = File(context.filesDir, "debug_look_in")
        val output = File(context.filesDir, "debug_look_out").apply { mkdirs() }
        // Синтетическая картинка справки — тоже вход (её же можно отдать старым сборкам для сравнения)
        File(input, "zz_testimage.png").takeIf { input.isDirectory && !it.exists() }?.outputStream()?.use {
            com.vhscam.app.ui.TestImage.create().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val images = input.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sorted()
        val egl = EglCore()
        try {
            val renderer = EffectRenderer(context.assets, GlInput.TEXTURE_2D)
            val matrix = uprightTexMatrix(0, false)
            for (file in images) {
                val src = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val scale = minOf(1f, 1024f / max(src.width, src.height))
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true) else src
                val tex = uploadBitmap(bmp)
                for (preset in BuiltInPresets.all) {
                    val spec = specFor(preset.mode)
                    val settings = spec.put(EffectSettings(mode = preset.mode), spec.setEnabled(preset.params, true))
                    val out = renderToBitmap(renderer, tex, matrix, bmp.width, bmp.height, settings, frameIndex = 3)
                    val name = "${file.nameWithoutExtension}__${preset.id.removePrefix("builtin:").replace(':', '_')}.jpg"
                    File(output, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    out.recycle()
                }
                android.opengl.GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
                Log.i(TAG, "rendered ${file.name}")
            }
            renderer.release()
            File(output, "done.txt").writeText("ok ${images.size}")
        } catch (e: Exception) {
            Log.e(TAG, "failed", e)
        } finally {
            egl.release()
        }
    }
}
