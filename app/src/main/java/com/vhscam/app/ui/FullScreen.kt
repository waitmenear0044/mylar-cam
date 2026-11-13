package com.vhscam.app.ui

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Скрыть строку состояния и навигацию; по свайпу от края они показываются временно. */
fun hideSystemBars(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** Внутри листа или диалога (у них своё окно): тот же полноэкранный режим, иначе панели появляются вместе с окном. */
@Composable
fun HideSystemBarsInDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let(::hideSystemBars)
        onDispose {}
    }
}

/** Убрать затемнение экрана под окном диалога (лист, сквозь который должно быть видно превью). */
@Composable
fun ClearDialogDim() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        onDispose {}
    }
}
