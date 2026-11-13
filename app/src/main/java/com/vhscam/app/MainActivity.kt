package com.vhscam.app

import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.vhscam.app.ui.AppColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vhscam.app.effect.EffectMode
import com.vhscam.app.effect.SettingsStore
import com.vhscam.app.gl.DebugPresetRender
import com.vhscam.app.ui.CameraInfoScreen
import com.vhscam.app.ui.CameraPermissionGate
import com.vhscam.app.ui.CameraScreen
import com.vhscam.app.ui.EditorScreen
import com.vhscam.app.ui.HelpScreen
import com.vhscam.app.ui.hideSystemBars

class MainActivity : ComponentActivity() {
    // Системный диалог (разрешения) возвращает панели — после него снова прячем
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(window)
    }

    // Кнопки громкости: если экран камеры обработал нажатие (спуск или зум), системная громкость не меняется
    private var volumeHandled = false

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handler = com.vhscam.app.ui.HardwareKeys.onVolume
            if (handler != null && handler(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP, event.repeatCount)) {
                volumeHandled = true
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && volumeHandled) {
            volumeHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DebugHooks.read(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Полноэкранный режим: системные панели скрыты (по свайпу от края показываются временно), интерфейс — под вырезом
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars(window)
        DebugHooks.read(intent)
        if (intent.getBooleanExtra("debug_render_presets", false) && DebugPresetRender.isAllowed(this)) {
            DebugPresetRender.runAsync(this)
        }
        intent.getStringExtra("debug_render_sweep")?.takeIf { DebugPresetRender.isAllowed(this) }?.let { DebugPresetRender.sweepAsync(this, it) }
        // Отладка совместимости: разбор кода пресета или набора слоёв, результат — в лог (тег DebugDecode)
        intent.getStringExtra("debug_decode")?.takeIf { DebugPresetRender.isAllowed(this) }?.let { code ->
            val preset = com.vhscam.app.effect.PresetRepository.decode(code)
            val reason = com.vhscam.app.effect.PresetRepository.rejection(code)
            val layers = com.vhscam.app.effect.LayerSetCode.decode(code, com.vhscam.app.effect.EffectSettings())
            android.util.Log.i(
                "DebugDecode",
                "preset=${preset?.mode}/${preset?.name} params=${preset?.params} rejection=$reason " +
                    "layers=${layers?.layers?.map { it.settings.mode.name + ":" + it.opacity }} skipped=${layers?.skipped} seed=${layers?.seed}",
            )
        }
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
              // Предпросмотр пресетов долгим нажатием: одно состояние на камеру и редактор
              androidx.compose.runtime.CompositionLocalProvider(
                  com.vhscam.app.ui.LocalPresetPreview provides androidx.compose.runtime.remember { com.vhscam.app.ui.PresetPreviewState() },
              ) {
                // Справка — отдельный экран поверх камеры; открывается и без разрешения на камеру
                var helpMode by rememberSaveable { mutableStateOf<EffectMode?>(null) }
                var cameraInfoOpen by rememberSaveable { mutableStateOf(false) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                var gradientEditorOpen by rememberSaveable { mutableStateOf(false) }
                // Фото из галереи на экране обработки; «Назад» возвращает в камеру
                var editUri by rememberSaveable { mutableStateOf<String?>(null) }
                // Отладка: редактор по URI из adb (в release всегда null)
                DebugHooks.editUri?.let { debugUri -> LaunchedEffect(debugUri, DebugHooks.editRequest) { com.vhscam.app.ui.EditorSession.clear(); editUri = debugUri } }
                val mode = helpMode
                val edit = editUri
                if (cameraInfoOpen) {
                    CameraInfoScreen(
                        onBack = { cameraInfoOpen = false },
                        onOpenHelp = {
                            cameraInfoOpen = false
                            if (helpMode == null) helpMode = SettingsStore(this).load().mode
                        },
                    )
                } else if (gradientEditorOpen) {
                    com.vhscam.app.ui.GradientEditorScreen(onBack = { gradientEditorOpen = false })
                } else if (settingsOpen) {
                    com.vhscam.app.ui.SettingsScreen(onBack = { settingsOpen = false })
                } else if (mode != null) {
                    HelpScreen(initialMode = mode, onBack = { helpMode = null }, onOpenCameraInfo = { cameraInfoOpen = true })
                } else if (edit != null) {
                    EditorScreen(uri = Uri.parse(edit), onBack = { editUri = null }, onOpenHelp = { helpMode = it }, onEditGradient = { gradientEditorOpen = true })
                } else {
                    CameraPermissionGate(onOpenHelp = { helpMode = SettingsStore(this).load().mode }) {
                        CameraScreen(onOpenHelp = { helpMode = it }, onOpenEditor = { com.vhscam.app.ui.EditorSession.clear(); editUri = it.toString() }, onOpenSettings = { settingsOpen = true }, onEditGradient = { gradientEditorOpen = true })
                    }
                }
              }
            }
        }
    }
}
