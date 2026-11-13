package com.vhscam.app

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Отладочные подмены (только debug-сборка; в release — пустая заглушка в src/release).
 * `adb shell am start -n com.vhscam.app/.MainActivity --ei debug_orientation 90` — подменить датчик ориентации
 * (0 / 90 / 270; любое другое значение — снова датчик).
 * `--ez debug_motion true` — отладочный источник движения: кадр камеры плавно ездит из стороны в сторону
 * (эмулятор сцену почти не двигает, а «Гостинг» без движения не виден).
 * `--es debug_edit content://...` — открыть редактор с этим фото или видео (без системного выбора файла).
 */
object DebugHooks {
    var orientationOverride by mutableStateOf<Int?>(null)
        private set

    @Volatile var motion = false
        private set

    var editUri by mutableStateOf<String?>(null)
        private set

    /** Номер запроса на открытие редактора: тот же файл можно открыть повторно (новый интент — новый номер). */
    var editRequest by mutableStateOf(0)
        private set

    fun read(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_MOTION) == true) motion = intent.getBooleanExtra(EXTRA_MOTION, false)
        intent?.getStringExtra(EXTRA_EDIT)?.let {
            editUri = it
            editRequest++
        }
        if (intent?.hasExtra(EXTRA_ORIENTATION) != true) return
        orientationOverride = intent.getIntExtra(EXTRA_ORIENTATION, -1).takeIf { it == 0 || it == 90 || it == 270 }
    }

    private const val EXTRA_ORIENTATION = "debug_orientation"
    private const val EXTRA_MOTION = "debug_motion"
    private const val EXTRA_EDIT = "debug_edit"
}
