package com.vhscam.app.videoimport

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.vhscam.app.StampSettings
import com.vhscam.app.gl.EditLayer
import com.vhscam.app.gl.EglCore
import com.vhscam.app.gl.GlProgram
import com.vhscam.app.gl.GlQuad
import com.vhscam.app.gl.LayerChain
import com.vhscam.app.gl.RenderTarget
import com.vhscam.app.gl.StampLayer
import com.vhscam.app.gl.bindTexture
import com.vhscam.app.gl.outputToFrame
import com.vhscam.app.gl.setTextureParams
import com.vhscam.app.gl.uprightTexMatrix
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Воспроизведение ролика в редакторе с цепочкой слоёв в реальном времени.
 *
 * ExoPlayer декодирует картинку во внешнюю текстуру и сам ведёт звук (часы — по звуку), поэтому время и звук не
 * уплывают: если GL не успевает, лишние кадры пропускаются. Кадр: внешняя текстура -> поворот (как в сохранении) и
 * обрезка формата -> уменьшенная текстура (длинная сторона подбирается в рантайме) -> слои по времени кадра с
 * историей -> поверхность TextureView со штампом поверх.
 *
 * Методы управления — из главного потока; GL — в своём потоке.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class EditorVideoPlayer(context: Context, private val uri: Uri, private val info: VideoInfo) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("PlayerGl").apply { start() }
    private val gl = Handler(thread.looper)

    // Состояние для интерфейса (главный поток)
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    /** Цепочка не успевает: кадры пропускаются или разрешение превью снижено. */
    var simplified by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var looping by mutableStateOf(true)
        private set

    // Что рисовать (пишет главный поток, читает GL)
    @Volatile private var layers: List<EditLayer> = emptyList()
    @Volatile private var showOriginal = false
    @Volatile private var cropAspect: Float? = null
    @Volatile private var stamp: StampSettings? = null
    @Volatile private var stampMs = 0L
    @Volatile private var playingNow = false
    private val resetHistory = AtomicBoolean(true)

    // GL (только поток PlayerGl)
    private lateinit var egl: EglCore
    private var oesTexture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var decoderSurface: Surface
    private lateinit var oesProgram: GlProgram
    private lateinit var flatProgram: GlProgram
    private lateinit var chain: LayerChain
    private lateinit var stampLayer: StampLayer
    private val frame = RenderTarget()
    private var window: EGLSurface? = null
    private var windowW = 0
    private var windowH = 0
    private var hasFrame = false
    private var lastPtsUs = -1L
    private val pending = AtomicInteger(0)
    private val redrawQueued = AtomicBoolean(false)
    private val seed = kotlin.random.Random.nextInt(1 shl 16)
    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val flipV = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    private var released = false

    // Время кадра: ExoPlayer отдаёт кадр с меткой «когда показать», а время в ролике сообщает отдельно
    private val ptsByRelease = LinkedHashMap<Long, Long>()

    // Нагрузка: длинная сторона превью-текстуры и счётчики пропусков за последнюю секунду
    @Volatile private var longSide = 0
    private var maxLongSide = 0
    private val glDropped = AtomicInteger(0)
    private val glShown = AtomicInteger(0)
    private var slowSeconds = 0
    private var calmSeconds = 0
    private var lastDropped = 0
    private var lastRendered = 0

    private var player: ExoPlayer
    private val badDecoders = HashSet<String>()

    init {
        val ready = CountDownLatch(1)
        gl.post {
            egl = EglCore()
            oesTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            setTextureParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
            surfaceTexture = SurfaceTexture(oesTexture)
            surfaceTexture.setOnFrameAvailableListener({
                pending.incrementAndGet()
                gl.post(drawNewFrame)
            }, gl)
            decoderSurface = Surface(surfaceTexture)
            oesProgram = GlProgram(appContext.assets, "quad.vert", "copy.frag", GlProgram.OES_HEADER)
            flatProgram = GlProgram(appContext.assets, "quad.vert", "copy.frag", GlProgram.TEXTURE_2D_HEADER)
            chain = LayerChain(appContext.assets)
            stampLayer = StampLayer(appContext.assets)
            val maxTex = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0].takeIf { it > 0 } ?: 2048
            maxLongSide = minOf(START_LONG_SIDE, maxTex, max(info.width, info.height))
            longSide = maxLongSide
            ready.countDown()
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "GL setup timeout" }
        player = buildPlayer(0L, play = false)
        main.post { tick.run() }
    }

    private fun buildPlayer(startMs: Long, play: Boolean): ExoPlayer {
        // Декодер, на котором ролик уже не пошёл (бывает у эмуляторов и отдельных прошивок), больше не выбирается
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.name !in badDecoders }
        }
        val renderers = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        return ExoPlayer.Builder(appContext, renderers).build().apply {
            setVideoSurface(decoderSurface)
            setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                synchronized(ptsByRelease) {
                    ptsByRelease[releaseTimeNs] = presentationTimeUs
                    while (ptsByRelease.size > 64) ptsByRelease.remove(ptsByRelease.keys.first())
                }
            }
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    this@EditorVideoPlayer.isPlaying = playing
                    playingNow = playing
                    if (!playing) requestRedraw()
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    // Перемотка и повтор с начала: история эффектов начинается заново
                    resetHistory.set(true)
                }

                override fun onPlayerError(error: PlaybackException) = onError(error)
            })
            repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            if (startMs > 0) seekTo(startMs)
            playWhenReady = play
        }
    }

    private fun onError(error: PlaybackException) {
        val decoder = generateSequence(error as Throwable) { it.cause }.filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo?.name
        Log.w(TAG, "playback error (decoder=$decoder)", error)
        if (decoder != null && badDecoders.size < MAX_DECODER_RETRIES && badDecoders.add(decoder)) {
            val at = player.currentPosition
            val play = player.playWhenReady
            player.release()
            player = buildPlayer(at, play)
            return
        }
        failed = true
    }

    // ---------- Управление (главный поток) ----------

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() = player.pause()

    fun toggle() = if (player.isPlaying || player.playWhenReady) pause() else play()

    /** Перемотка. [dragging] — ползунок ещё тянут: ближайший опорный кадр (быстро), иначе — точный кадр. */
    fun seekTo(ms: Long, dragging: Boolean) {
        player.setSeekParameters(if (dragging) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        player.seekTo(ms.coerceIn(0L, info.durationMs))
        positionMs = ms
    }

    fun changeMuted(on: Boolean) {
        muted = on
        player.volume = if (on) 0f else 1f
    }

    fun changeLooping(on: Boolean) {
        looping = on
        player.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Что рисовать; на паузе кадр перерисовывается сразу. */
    fun update(layers: List<EditLayer>, cropAspect: Float?, stamp: StampSettings?, stampMs: Long) {
        val changedCrop = cropAspect != this.cropAspect
        this.layers = layers
        this.cropAspect = cropAspect
        this.stamp = stamp
        this.stampMs = stampMs
        if (changedCrop) resetHistory.set(true)
        requestRedraw()
    }

    /** Долгое нажатие: оригинал без слоёв (воспроизведение не останавливается). */
    fun setShowOriginal(on: Boolean) {
        showOriginal = on
        requestRedraw()
    }

    // Позиция для счётчика и проверка нагрузки — раз в 100 мс / раз в секунду
    private var tickCount = 0
    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            positionMs = player.currentPosition.coerceIn(0L, info.durationMs)
            if (++tickCount % 10 == 0) checkLoad()
            main.postDelayed(this, 100L)
        }
    }

    private fun checkLoad() {
        // Пропуски: кадры, которые ExoPlayer выбросил как опоздавшие, и кадры, которые GL не успел показать
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        val droppedNow = counters?.droppedBufferCount ?: 0
        val renderedNow = counters?.renderedOutputBufferCount ?: 0
        val dropped = (droppedNow - lastDropped).coerceAtLeast(0) + glDropped.getAndSet(0)
        val shown = (renderedNow - lastRendered).coerceAtLeast(0) + glShown.getAndSet(0)
        lastDropped = droppedNow
        lastRendered = renderedNow
        if (!player.isPlaying) {
            slowSeconds = 0
            return
        }
        val slow = dropped > 0 && dropped.toFloat() / max(1, dropped + shown) > SLOW_DROP_SHARE
        if (slow) {
            calmSeconds = 0
            slowSeconds++
            // Две секунды подряд не успевает — превью-текстура меньше (до нижней границы)
            if (slowSeconds >= 2 && longSide > MIN_LONG_SIDE) {
                longSide = max(MIN_LONG_SIDE, (longSide * 0.75f).roundToInt())
                resetHistory.set(true)
                slowSeconds = 0
                Log.i(TAG, "slow playback: preview long side -> $longSide")
            }
        } else {
            slowSeconds = 0
            calmSeconds++
        }
        simplified = longSide < maxLongSide || (slow || calmSeconds < 2) && dropped > 0
    }

    // ---------- Поверхность экрана (главный поток вызывает, GL выполняет) ----------

    fun attachWindow(texture: SurfaceTexture, width: Int, height: Int) {
        gl.post {
            if (released) return@post
            window?.let { egl.releaseSurface(it) }
            window = runCatching { egl.createWindowSurface(Surface(texture)) }.getOrNull()
            windowW = width
            windowH = height
            if (hasFrame) render(lastPtsUs, history = false)
        }
    }

    fun resizeWindow(width: Int, height: Int) {
        gl.post {
            windowW = width
            windowH = height
            if (hasFrame) render(lastPtsUs, history = false)
        }
    }

    fun detachWindow(texture: SurfaceTexture) {
        gl.post {
            window?.let { egl.releaseSurface(it) }
            window = null
            texture.release()
        }
    }

    // ---------- GL ----------

    private val drawNewFrame = Runnable {
        val n = pending.getAndSet(0)
        if (n == 0 || released) return@Runnable
        egl.makeCurrentOffscreen()
        // Накопилось несколько кадров — берём последний, остальные считаются пропущенными
        repeat(n) { surfaceTexture.updateTexImage() }
        if (n > 1) glDropped.addAndGet(n - 1)
        val releaseNs = surfaceTexture.timestamp
        val pts = synchronized(ptsByRelease) {
            ptsByRelease[releaseNs] ?: ptsByRelease.entries.minByOrNull { abs(it.key - releaseNs) }?.takeIf { abs(it.key - releaseNs) < 50_000_000L }?.value
        } ?: (if (lastPtsUs >= 0) lastPtsUs + (1_000_000f / info.fps.coerceAtLeast(1f)).toLong() else 0L)
        hasFrame = true
        // Время пошло назад или прыгнуло (перемотка, повтор) — история с чистого листа
        if (lastPtsUs >= 0 && (pts < lastPtsUs || pts - lastPtsUs > JUMP_US)) resetHistory.set(true)
        lastPtsUs = pts
        render(pts, history = playingNow)
        glShown.incrementAndGet()
    }

    private fun requestRedraw() {
        if (!redrawQueued.compareAndSet(false, true)) return
        gl.post {
            redrawQueued.set(false)
            // Во время воспроизведения новый кадр придёт сам
            if (!released && hasFrame && !playingNow) render(lastPtsUs, history = false)
        }
    }

    private fun render(ptsUs: Long, history: Boolean) {
        if (released) return
        egl.makeCurrentOffscreen()
        // Обрезка формата и поворот — как в сохранении ([VideoExporter])
        val srcAspect = info.width.toFloat() / info.height
        val aspect = cropAspect ?: srcAspect
        val (cropW, cropH) = if (aspect > srcAspect) info.width.toFloat() to info.width / aspect else info.height * aspect to info.height.toFloat()
        val k = min(1f, longSide / max(cropW, cropH))
        val w = ((cropW * k).roundToInt() / 2 * 2).coerceAtLeast(16)
        val h = ((cropH * k).roundToInt() / 2 * 2).coerceAtLeast(16)
        if (frame.ensure(w, h)) resetHistory.set(true)

        surfaceTexture.getTransformMatrix(stMatrix)
        val cropMatrix = floatArrayOf(
            cropW / info.width, 0f, 0f, 0f,
            0f, cropH / info.height, 0f, 0f,
            0f, 0f, 1f, 0f,
            (1f - cropW / info.width) / 2f, (1f - cropH / info.height) / 2f, 0f, 1f,
        )
        val toDecoded = FloatArray(16)
        Matrix.multiplyMM(toDecoded, 0, uprightTexMatrix(rotationToApply(stMatrix, info.rotation), false), 0, cropMatrix, 0)
        val toDecodedUp = FloatArray(16).also { Matrix.multiplyMM(it, 0, flipV, 0, toDecoded, 0) }
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, toDecodedUp, 0)

        frame.bindAsTarget()
        oesProgram.use()
        bindTexture(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, oesProgram, "uInput")
        GLES30.glUniformMatrix4fv(oesProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GlQuad.draw()

        if (resetHistory.getAndSet(false)) chain.resetHistory()
        val result = if (showOriginal) {
            frame.texture
        } else {
            chain.render(
                frame.texture, w, h, layers, seed, timeSec = ptsUs / 1_000_000f, useHistory = history,
                frameDurationSec = 1f / info.fps.coerceIn(1f, 240f), frameIndex = (ptsUs * 30L / 1_000_000L).toInt() + seed,
            )
        }

        val surface = window ?: return
        if (windowW <= 0 || windowH <= 0) return
        egl.makeCurrent(surface)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, windowW, windowH)
        flatProgram.use()
        bindTexture(0, GLES30.GL_TEXTURE_2D, result, flatProgram, "uInput")
        GLES30.glUniformMatrix4fv(flatProgram.uniform("uTexMatrix"), 1, false, flipV, 0)
        GlQuad.draw()
        val s = stamp
        if (s != null && s.enabled && !showOriginal) {
            stampLayer.draw(0, windowW, windowH, outputToFrame(null, 0, false, live = true), windowW, windowH, s, stampMs)
        }
        egl.swapBuffers(surface)
    }

    /** Закрыть: плеер, декодер, GL. Повторное открытие редактора создаёт всё заново. */
    fun release() {
        if (released) return
        released = true
        main.removeCallbacks(tick)
        player.release()
        gl.post {
            window?.let { egl.releaseSurface(it) }
            window = null
            chain.release()
            stampLayer.release()
            oesProgram.release()
            flatProgram.release()
            frame.release()
            decoderSurface.release()
            surfaceTexture.release()
            GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            egl.release()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "EditorVideoPlayer"
        /** Начальная длинная сторона превью-текстуры; при нехватке скорости уменьшается до [MIN_LONG_SIDE]. */
        const val START_LONG_SIDE = 720
        const val MIN_LONG_SIDE = 360
        /** Доля пропущенных кадров за секунду, при которой воспроизведение считается «не успевает». */
        const val SLOW_DROP_SHARE = 0.15f
        const val JUMP_US = 500_000L
        const val MAX_DECODER_RETRIES = 3
    }
}

/**
 * Поворот, который ещё нужно применить к кадру декодера. Декодер мог уже повернуть кадр по метаданным (тогда
 * это видно в матрице SurfaceTexture: она не сводится к обычному вертикальному отражению) — повторно не крутим.
 */
internal fun rotationToApply(stMatrix: FloatArray, rotation: Int): Int {
    val plain = stMatrix[0] > 0.01f && stMatrix[5] < -0.01f && abs(stMatrix[1]) < 0.01f && abs(stMatrix[4]) < 0.01f
    return if (plain) rotation else 0
}
