package com.vhscam.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.vhscam.app.AppPrefs

/**
 * Короткое нажатие — [onClick]; удержание ~350 мс (лёгкая вибрация, если включена) — [onPreview] (true), отпускание —
 * [onPreview] (false). Прокрутка списка пальцем без удержания работает как обычно: сдвиг до срабатывания удержания
 * отменяет и нажатие, и предпросмотр. Без [onPreview] удержание ничего не делает.
 */
fun Modifier.previewPress(onClick: () -> Unit, onPreview: ((Boolean) -> Unit)?): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val click by rememberUpdatedState(onClick)
    val preview by rememberUpdatedState(onPreview)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var cancelled = false
            val up = withTimeoutOrNull(PREVIEW_PRESS_MS) {
                waitForUpOrCancellation().also { if (it == null) cancelled = true }
            }
            if (cancelled) return@awaitEachGesture
            if (up != null) {
                up.consume()
                click()
                return@awaitEachGesture
            }
            val cb = preview
            if (cb == null) {
                waitForUpOrCancellation()
                return@awaitEachGesture
            }
            if (AppPrefs.get(context).vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            cb(true)
            try {
                // Пока палец держит — движения не отдаём прокрутке списка
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                cb(false)
            }
        }
    }
}

/**
 * Идёт предпросмотр долгим нажатием: панели и списки становятся почти прозрачными (как при перетаскивании ползунка),
 * над превью — плашка с [label].
 */
@Stable
class PresetPreviewState {
    var label by mutableStateOf<String?>(null)
        private set
    private var original: Any? = null
    private var restore: ((Any) -> Unit)? = null

    /** Временно применить [preview] (запомнив [current] — его вернёт [end]). */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> begin(label: String, current: T, preview: T, apply: (T) -> Unit) {
        if (original == null) {
            original = current
            restore = { apply(it as T) }
        }
        this.label = label
        apply(preview)
    }

    /** Вернуть то, что было до предпросмотра (точные значения). */
    fun end() {
        val o = original
        val r = restore
        original = null
        restore = null
        label = null
        if (o != null && r != null) r(o)
    }
}

/** Обработчик удержания для [previewPress]: начало — [begin] с этими значениями, отпускание — [PresetPreviewState.end]. */
fun <T : Any> PresetPreviewState?.handler(label: String, current: T, preview: () -> T, apply: (T) -> Unit): ((Boolean) -> Unit)? =
    this?.let { state -> { on: Boolean -> if (on) state.begin(label, current, preview(), apply) else state.end() } }

val LocalPresetPreview = compositionLocalOf<PresetPreviewState?> { null }

const val PREVIEW_PRESS_MS = 350L
