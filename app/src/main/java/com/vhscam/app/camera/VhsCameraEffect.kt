package com.vhscam.app.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer

/**
 * Эффект как CameraEffect: CameraX сама подаёт в него кадры камеры и забирает результат.
 * С целями PREVIEW or VIDEO_CAPTURE CameraX отдаёт процессору один вход и один выход
 * (в ориентации сенсора) и сама разносит обработанный кадр в превью и в запись,
 * так что эффект в файле тот же самый, что на экране.
 */
class VhsCameraEffect private constructor(
    private val processor: VhsSurfaceProcessor,
    targets: Int,
) : CameraEffect(
    targets,
    processor.executor,
    processor,
    Consumer { Log.e("VhsCameraEffect", "Effect error", it) },
) {
    constructor(context: Context, targets: Int = PREVIEW) : this(VhsSurfaceProcessor(context), targets)

    var settings by processor::settings

    var frameRotation by processor::frameRotation

    var frameMirror by processor::frameMirror

    var targetFps by processor::targetFps

    var bypass by processor::bypass

    var stamp by processor::stamp

    fun newSeed() = processor.newSeed()

    fun grabOutput(callback: (android.graphics.Bitmap) -> Unit) = processor.grabOutput(callback)

    fun grabFrame(maxSide: Int, callback: (android.graphics.Bitmap) -> Unit) = processor.grabFrame(maxSide, callback)

    val fps: Float get() = processor.fps

    val sceneLuma: Float get() = processor.sceneLuma

    fun release() = processor.release()
}
