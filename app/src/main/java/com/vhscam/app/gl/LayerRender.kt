package com.vhscam.app.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.EffectSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Слой редактора: режим со своими настройками (и силой пресета внутри [settings]), непрозрачность слоя
 * (смешивание результата слоя с тем, что под ним) и видимость. Слои применяются по порядку списка.
 */
data class EditLayer(
    val id: String,
    val settings: EffectSettings,
    val opacity: Float = 1f,
    val visible: Boolean = true,
    /** Замок: слой остаётся при следующем броске «Кубика». */
    val locked: Boolean = false,
) {
    /** Слой что-то делает с картинкой. */
    val active: Boolean get() = visible && settings.enabled && opacity > 0f

    companion object {
        const val MAX_LAYERS = 5
        // Цепочка из «Кубика» — до 10 слоёв (Dice.MAX_LAYERS)

        /** Режимы, доступные слою фото («Гостинг» на неподвижной картинке не виден). */
        val photoModes: List<EffectMode> = EffectMode.entries.filter { it != EffectMode.GHOST }
    }
}

/**
 * Цепочка слоёв в GL: каждый слой рисуется тем же [EffectRenderer] из результата предыдущего в свой буфер,
 * при непрозрачности меньше 1 — смешивается с тем, что под ним. Используется превью редактора (и рендером видео).
 * Вызывать только из GL-потока с текущим контекстом; текстуры и буферы — «строка 0 — верх».
 */
class LayerChain(private val assets: android.content.res.AssetManager) {
    val renderer = EffectRenderer(assets, GlInput.TEXTURE_2D)
    private val mixProgram = GlProgram(assets, "quad.vert", "layer_mix.frag", GlProgram.BASE_HEADER)
    private val targets = Array(3) { RenderTarget() }
    /** Видео: история кадров (смаз, шлейф, копии) у каждого слоя своя — по рендереру на слой. */
    private val perLayer = HashMap<String, EffectRenderer>()
    private val identity = uprightTexMatrix(0, false)

    /**
     * Рисует слои поверх [sourceTexture] ([w]x[h]) и возвращает текстуру результата (исходную, если слоёв нет).
     * @param timeSec время кадра (видео) — для эффектов, живущих во времени; [useHistory] — кадры идут подряд
     */
    fun render(
        sourceTexture: Int,
        w: Int,
        h: Int,
        layers: List<EditLayer>,
        seed: Int,
        timeSec: Float = 0f,
        useHistory: Boolean = false,
        frameDurationSec: Float = EffectRenderer.REFERENCE_FRAME_SEC,
        frameIndex: Int = seed,
    ): Int {
        // Слои, которых больше нет (новый бросок «Кубика», удаление), — их история и шейдеры освобождаются
        if (perLayer.isNotEmpty()) {
            val ids = layers.mapTo(HashSet()) { it.id }
            val gone = perLayer.keys.filter { it !in ids }
            gone.forEach { perLayer.remove(it)?.release() }
        }
        var prevTexture = sourceTexture
        var prevIndex = -1
        for (layer in layers) {
            if (!layer.active) continue
            val a = targets.indices.first { it != prevIndex }
            targets[a].ensure(w, h)
            val layerRenderer = if (useHistory) perLayer.getOrPut(layer.id) { EffectRenderer(assets, GlInput.TEXTURE_2D) } else renderer
            layerRenderer.noiseSeed = seed
            layerRenderer.draw(
                prevTexture, identity, w, h, w, h, crop = null, targetFbo = targets[a].fbo, settings = layer.settings,
                timeSec = timeSec, frameIndex = frameIndex, frameRotation = 0, useHistory = useHistory, frameDurationSec = frameDurationSec,
            )
            if (layer.opacity < 1f) {
                val b = targets.indices.first { it != prevIndex && it != a }
                targets[b].ensure(w, h)
                targets[b].bindAsTarget()
                mixProgram.use()
                bindTexture(0, GLES30.GL_TEXTURE_2D, prevTexture, mixProgram, "uBelow")
                bindTexture(1, GLES30.GL_TEXTURE_2D, targets[a].texture, mixProgram, "uLayer")
                GLES30.glUniform1f(mixProgram.uniform("uOpacity"), layer.opacity.coerceIn(0f, 1f))
                GlQuad.draw()
                prevIndex = b
            } else {
                prevIndex = a
            }
            prevTexture = targets[prevIndex].texture
        }
        return prevTexture
    }

    /** Перемотка или пауза на другом кадре: история кадров (смаз, шлейф, копии) у всех слоёв начинается заново. */
    fun resetHistory() {
        renderer.resetHistory()
        perLayer.values.forEach { it.resetHistory() }
    }

    fun release() {
        renderer.release()
        perLayer.values.forEach { it.release() }
        perLayer.clear()
        mixProgram.release()
        targets.forEach { it.release() }
    }
}

/**
 * Превью редактора: свой поток и EGL-контекст, исходная картинка загружена один раз; [render] прогоняет слои
 * и отдаёт результат как Bitmap размера исходной картинки. Запросы идут по очереди, отменённые пропускаются.
 */
class LayerPreviewRenderer(context: Context, source: Bitmap) {
    @Volatile private var source: Bitmap = source

    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("LayerGl").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var chain: LayerChain
    private val readTarget = RenderTarget()
    private var texture = 0

    init {
        handler.post {
            egl = EglCore()
            chain = LayerChain(assets)
            texture = uploadBitmap(this.source)
        }
    }

    /** Другая картинка того же назначения (кадр ролика на новой позиции): контекст и шейдеры остаются. */
    fun setSource(bitmap: Bitmap) {
        if (bitmap === source) return
        source = bitmap
        handler.post {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = uploadBitmap(bitmap)
        }
    }

    suspend fun render(layers: List<EditLayer>, seed: Int = 7): Bitmap = suspendCancellableCoroutine { cont ->
        handler.post {
            if (!cont.isActive) return@post
            val w = source.width
            val h = source.height
            val result = chain.render(texture, w, h, layers, seed)
            // Результат (или исходник, если слоёв нет) -> буфер для чтения
            readTarget.ensure(w, h)
            chain.renderer.draw(
                result, uprightTexMatrix(0, false), w, h, w, h, crop = null, targetFbo = readTarget.fbo,
                settings = EffectSettings().withEnabled(false), timeSec = 0f, frameIndex = 0, frameRotation = 0, useHistory = false,
            )
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readTarget.fbo)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            pixels.rewind()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixels)
            if (cont.isActive) cont.resume(bitmap) else bitmap.recycle()
        }
    }

    fun release() {
        handler.post {
            chain.release()
            readTarget.release()
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            egl.release()
            thread.quitSafely()
        }
    }
}
