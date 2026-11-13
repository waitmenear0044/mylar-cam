package com.vhscam.app.videoimport

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.vhscam.app.AppPrefs
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
import com.vhscam.app.gl.stampTime
import com.vhscam.app.gl.uprightTexMatrix
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Готовый ролик из галереи: размеры в итоговой (повёрнутой) ориентации, длительность, дата съёмки, частота кадров. */
class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val takenMs: Long,
    val fps: Float,
) {
    companion object {
        private const val TAG = "VideoInfo"

        fun load(context: Context, uri: Uri): VideoInfo {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
                val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = (int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 360 + 360) % 360
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                check(w > 0 && h > 0) { "No video track" }
                val turned = rotation % 180 != 0
                // Дата съёмки из метаданных (UTC), иначе дата файла, иначе сейчас
                val taken = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { text ->
                    runCatching {
                        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text.take(15))?.time
                    }.getOrNull()?.takeIf { it > 86_400_000L * 365 }
                } ?: fileDate(context, uri) ?: System.currentTimeMillis()
                val fps = runCatching { frameRate(context, uri) }.getOrDefault(30f)
                Log.i(TAG, "video ${w}x$h rotation=$rotation duration=$duration fps=$fps taken=$taken")
                return VideoInfo(if (turned) h else w, if (turned) w else h, rotation, duration, taken, fps)
            } finally {
                retriever.release()
            }
        }

        private fun frameRate(context: Context, uri: Uri): Float {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        return f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat().coerceIn(1f, 240f)
                    }
                }
                return 30f
            } finally {
                extractor.release()
            }
        }

        private fun fileDate(context: Context, uri: Uri): Long? = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val taken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                if (taken >= 0 && !c.isNull(taken) && c.getLong(taken) > 0) return@use c.getLong(taken)
                val modified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (modified >= 0 && !c.isNull(modified) && c.getLong(modified) > 0) c.getLong(modified) * 1000 else null
            }
        }.getOrNull()

        /** Кадр ролика около [positionMs] в итоговой ориентации, длинная сторона не больше [maxSide]. */
        fun frameAt(context: Context, uri: Uri, positionMs: Long, maxSide: Int): Bitmap? {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                val k = min(1f, maxSide.toFloat() / max(frame.width, frame.height))
                if (k >= 1f) return frame.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== frame) frame.recycle() }
                return Bitmap.createScaledBitmap(frame, (frame.width * k).roundToInt().coerceAtLeast(2), (frame.height * k).roundToInt().coerceAtLeast(2), true)
                    .also { if (it !== frame) frame.recycle() }
            } finally {
                retriever.release()
            }
        }
    }
}

/** Разрешение результата: исходное или короткая сторона 1080 / 720 (никогда не больше исходного). */
enum class ExportSize(val label: String, val shortSide: Int) {
    SOURCE("Исходное", 0),
    P1080("1080p", 1080),
    P720("720p", 720),
}

class ExportCancelled : Exception("cancelled")

/**
 * Офлайн-рендер ролика слоями (не в реальном времени): MediaExtractor -> декодер в SurfaceTexture -> кадр в итоговой
 * ориентации и размере -> цепочка слоёв ([LayerChain], у каждого слоя свой рендерер и своя история) -> штамп ->
 * входная поверхность кодера -> MediaMuxer. Эффекты со временем считаются по метке времени кадра, история ведётся
 * последовательно по кадрам, шум живой с одним зерном на ролик. Звук копируется как есть. Поворот из метаданных
 * вшивается в пиксели (у результата поворот 0), зеркальность кадра не меняется.
 * Результат пишется во временный файл и попадает в Movies/<папка> только целиком: при отмене ничего не остаётся.
 */
class VideoExporter(private val context: Context) {
    private val appContext = context.applicationContext

    @Volatile var cancelled = false

    /**
     * @param cropAspect ширина/высота итогового кадра; null — как у ролика
     * @param onProgress 0..1
     * @return подпись сохранённого файла
     */
    fun export(
        uri: Uri,
        info: VideoInfo,
        layers: List<EditLayer>,
        size: ExportSize,
        cropAspect: Float?,
        stamp: StampSettings?,
        name: String,
        onProgress: (Float) -> Unit,
    ): String {
        val temp = File(appContext.cacheDir, "export_$name.mp4")
        try {
            // Декодер по умолчанию на части устройств не работает с поверхностью или падает на первом же кадре —
            // тогда пробуем остальные декодеры этого формата, какие есть на устройстве (список — из рантайма)
            var lastError: Exception? = null
            for (decoderName in decoderCandidates(uri)) {
                if (cancelled) throw ExportCancelled()
                try {
                    temp.delete()
                    render(uri, info, layers, size, cropAspect, stamp, temp, decoderName, onProgress)
                    lastError = null
                    break
                } catch (e: ExportCancelled) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Export with decoder ${decoderName ?: "default"} failed", e)
                    lastError = e
                }
            }
            lastError?.let { throw it }
            if (cancelled) throw ExportCancelled()
            return publish(temp, name)
        } finally {
            temp.delete()
        }
    }

    /** Декодеры для видеодорожки ролика: сначала выбор системы (null), затем остальные подходящие по формату. */
    private fun decoderCandidates(uri: Uri): List<String?> {
        val names = ArrayList<String?>()
        names += null
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(appContext, uri, null)
                val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@runCatching
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                val preferred = runCatching { list.findDecoderForFormat(format) }.getOrNull()
                list.codecInfos.filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                    .map { it.name }.filter { it != preferred }.forEach { names += it }
            } finally {
                extractor.release()
            }
        }
        return names
    }

    private fun render(
        uri: Uri,
        info: VideoInfo,
        layers: List<EditLayer>,
        size: ExportSize,
        cropAspect: Float?,
        stamp: StampSettings?,
        outFile: File,
        decoderName: String?,
        onProgress: (Float) -> Unit,
    ) {
        // Итоговый кадр: центральная часть нужных пропорций, уменьшенная до выбранного разрешения (чётные размеры)
        val srcAspect = info.width.toFloat() / info.height
        val aspect = cropAspect ?: srcAspect
        val (cropW, cropH) = if (aspect > srcAspect) info.width.toFloat() to info.width / aspect else info.height * aspect to info.height.toFloat()
        val scale = if (size.shortSide <= 0) 1f else min(1f, size.shortSide / min(cropW, cropH))
        val outW = ((cropW * scale).roundToInt() / 2 * 2).coerceAtLeast(16)
        val outH = ((cropH * scale).roundToInt() / 2 * 2).coerceAtLeast(16)
        val fps = info.fps.coerceIn(1f, 120f)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglCore? = null
        var frameThread: HandlerThread? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(appContext, uri, null)
            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i
            }
            check(videoTrack >= 0) { "No video track" }
            val inputFormat = extractor.getTrackFormat(videoTrack)
            // Поворот делаем сами в пикселях: декодеру о нём не говорим
            if (Build.VERSION.SDK_INT >= 23) inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else info.durationMs * 1000

            // Кодер: H.264 в поверхность; битрейт по площади кадра и частоте
            val bitrate = (outW.toLong() * outH * fps * 0.17f).toLong().coerceIn(3_000_000L, 80_000_000L).toInt()
            val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInput = enc.createInputSurface()
            enc.start()

            val gl = EglCore()
            egl = gl
            val encoderSurface = gl.createWindowSurface(encoderInput)
            gl.makeCurrent(encoderSurface)

            // Вход: кадры декодера во внешней текстуре; о новом кадре сообщает отдельный поток
            val oesTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            setTextureParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
            val frames0 = HandlerThread("ExportFrames").apply { start() }
            frameThread = frames0
            val frameLock = Object()
            var frameReady = false
            val st = SurfaceTexture(oesTexture)
            surfaceTexture = st
            st.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameReady = true
                    frameLock.notifyAll()
                }
            }, Handler(frames0.looper))
            decoderSurface = Surface(st)
            val dec = if (decoderName != null) MediaCodec.createByCodecName(decoderName) else MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            Log.i(TAG, "decoder ${dec.name}")
            decoder = dec
            dec.configure(inputFormat, decoderSurface, null, 0)
            dec.start()

            val oesProgram = GlProgram(appContext.assets, "quad.vert", "copy.frag", GlProgram.OES_HEADER)
            val flatProgram = GlProgram(appContext.assets, "quad.vert", "copy.frag", GlProgram.TEXTURE_2D_HEADER)
            val chain = LayerChain(appContext.assets)
            val stampLayer = StampLayer(appContext.assets)
            val frame = RenderTarget()
            frame.ensure(outW, outH)
            val seed = kotlin.random.Random.nextInt(1 shl 16)
            val stampMs = stamp?.takeIf { it.enabled }?.let { stampTime(it, info.takenMs) } ?: 0L

            // uv итогового кадра (y вниз) -> обрезка -> поворот -> uv кадра декодера (y вверх) -> матрица SurfaceTexture
            val cropMatrix = floatArrayOf(
                cropW / info.width, 0f, 0f, 0f,
                0f, cropH / info.height, 0f, 0f,
                0f, 0f, 1f, 0f,
                (1f - cropW / info.width) / 2f, (1f - cropH / info.height) / 2f, 0f, 1f,
            )
            val flipV = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
            val toDecoded = FloatArray(16)
            val toDecodedUp = FloatArray(16)
            val stMatrix = FloatArray(16)
            val texMatrix = FloatArray(16)
            val toFrame = outputToFrame(null, 0, false, live = true)

            val mux = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var muxVideo = -1
            var muxAudio = -1
            val audioFormat = if (audioTrack >= 0) extractor.getTrackFormat(audioTrack) else null

            extractor.selectTrack(videoTrack)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var frames = 0

            fun drainEncoder(endOfStream: Boolean) {
                while (true) {
                    val index = enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxVideo = mux.addTrack(enc.outputFormat)
                            if (audioFormat != null) muxAudio = runCatching { mux.addTrack(audioFormat) }.getOrDefault(-1)
                            mux.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val data = enc.getOutputBuffer(index)
                            if (data != null && bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted) {
                                mux.writeSampleData(muxVideo, data, bufferInfo)
                            }
                            enc.releaseOutputBuffer(index, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                                return
                            }
                        }
                    }
                    if (cancelled) return
                }
            }

            while (!decoderDone) {
                if (cancelled) throw ExportCancelled()
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(5_000L)
                    if (inIndex >= 0) {
                        val buffer = dec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, 5_000L)
                if (outIndex >= 0) {
                    val ptsUs = bufferInfo.presentationTimeUs
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = bufferInfo.size > 0
                    dec.releaseOutputBuffer(outIndex, render)
                    if (render) {
                        synchronized(frameLock) {
                            val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
                            while (!frameReady) {
                                val left = deadline - System.currentTimeMillis()
                                check(left > 0) { "Decoder frame timeout" }
                                frameLock.wait(left)
                            }
                            frameReady = false
                        }
                        st.updateTexImage()
                        st.getTransformMatrix(stMatrix)
                        // Поворот по метаданным — если декодер не повернул кадр сам (см. [rotationToApply])
                        Matrix.multiplyMM(toDecoded, 0, uprightTexMatrix(rotationToApply(stMatrix, info.rotation), false), 0, cropMatrix, 0)
                        Matrix.multiplyMM(toDecodedUp, 0, flipV, 0, toDecoded, 0)
                        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, toDecodedUp, 0)

                        // Кадр декодера -> текстура итогового размера и ориентации (строка 0 — верх)
                        frame.bindAsTarget()
                        oesProgram.use()
                        bindTexture(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, oesProgram, "uInput")
                        GLES30.glUniformMatrix4fv(oesProgram.uniform("uTexMatrix"), 1, false, texMatrix, 0)
                        GlQuad.draw()

                        val timeSec = ptsUs / 1_000_000f
                        val result = chain.render(
                            frame.texture, outW, outH, layers, seed, timeSec = timeSec, useHistory = true,
                            frameDurationSec = 1f / fps, frameIndex = (ptsUs * 30L / 1_000_000L).toInt() + seed,
                        )

                        // Результат -> поверхность кодера (у неё строка 0 — низ, поэтому переворот) и штамп поверх
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        GLES30.glViewport(0, 0, outW, outH)
                        flatProgram.use()
                        bindTexture(0, GLES30.GL_TEXTURE_2D, result, flatProgram, "uInput")
                        GLES30.glUniformMatrix4fv(flatProgram.uniform("uTexMatrix"), 1, false, flipV, 0)
                        GlQuad.draw()
                        if (stamp != null && stamp.enabled) stampLayer.draw(0, outW, outH, toFrame, outW, outH, stamp, stampMs)
                        gl.setPresentationTime(encoderSurface, ptsUs * 1000L)
                        gl.swapBuffers(encoderSurface)
                        frames++
                        if (durationUs > 0) onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 0.97f))
                        drainEncoder(false)
                    }
                    if (eos) decoderDone = true
                }
            }
            enc.signalEndOfInputStream()
            while (!encoderDone && !cancelled) drainEncoder(true)
            if (cancelled) throw ExportCancelled()
            check(frames > 0 && muxerStarted) { "No frames rendered" }

            // Звук — как есть: сжатые отсчёты копируются в результат без перекодирования
            if (muxAudio >= 0) {
                extractor.unselectTrack(videoTrack)
                extractor.selectTrack(audioTrack)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val max = audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0
                val buffer = ByteBuffer.allocateDirect(max(max, 1 shl 20))
                val audioInfo = MediaCodec.BufferInfo()
                while (!cancelled) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    audioInfo.set(0, sampleSize, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(muxAudio, buffer, audioInfo)
                    extractor.advance()
                }
                if (cancelled) throw ExportCancelled()
            }
            onProgress(1f)
            Log.i(TAG, "exported $frames frames ${outW}x$outH bitrate=$bitrate audio=${muxAudio >= 0}")

            chain.release()
            stampLayer.release()
            frame.release()
            oesProgram.release()
            flatProgram.release()
            gl.releaseSurface(encoderSurface)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { frameThread?.quitSafely() }
            runCatching { extractor.release() }
            runCatching { egl?.release() }
        }
    }

    /** Готовый файл -> Movies/<папка из настроек> через MediaStore (виден в галерее только целиком). */
    private fun publish(file: File, name: String): String {
        val album = AppPrefs.get(appContext).folder
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$album")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), album)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(dir, "$name.mp4").absolutePath)
            }
        }
        val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(target).use { out ->
                checkNotNull(out) { "No output stream" }
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
        return "Movies/$album/$name.mp4"
    }

    private companion object {
        const val TAG = "VideoExporter"
        const val FRAME_TIMEOUT_MS = 5000L
    }
}
