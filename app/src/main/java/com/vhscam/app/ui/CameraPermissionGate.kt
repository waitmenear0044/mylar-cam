package com.vhscam.app.ui

import com.vhscam.app.AppInfo
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Разрешения. При первом запуске — экран-объяснение и один запрос камеры и микрофона (на Android 8–9 ещё
 * и памяти: туда сохраняются фото и видео; с Android 10 MediaStore разрешений не требует, а фото из галереи
 * выбираются системным Photo Picker без READ_MEDIA_*). Первый запуск запоминается.
 * Без камеры — экран «Нет доступа к камере»; без микрофона всё работает, видео пишется без звука.
 */
@Composable
fun CameraPermissionGate(onOpenHelp: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun isGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }
    var onboarded by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false)) }
    // Пересчитать «можно ли спросить ещё раз» после каждого ответа
    var answers by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = isGranted()
        onboarded = true
        answers++
        // Микрофон уже спросили здесь — при первой записи повторно не спрашиваем
        prefs.edit().putBoolean(KEY_ONBOARDED, true).putBoolean(KEY_AUDIO_ASKED, true).apply()
    }

    fun request() = launcher.launch(firstLaunchPermissions())

    // Пользователь мог выдать разрешение в настройках и вернуться
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isGranted() }

    when {
        granted -> content()
        !onboarded -> WelcomeScreen(onContinue = ::request, onOpenHelp = onOpenHelp)
        else -> {
            // После отказа «больше не спрашивать» система диалог не покажет — остаются настройки
            val canAskAgain = answers >= 0 && activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            PermissionDeniedScreen(
                canAskAgain = canAskAgain,
                onAskAgain = { launcher.launch(arrayOf(Manifest.permission.CAMERA)) },
                onOpenSettings = { activity?.openAppSettings() },
                onOpenHelp = onOpenHelp,
            )
        }
    }
}

private fun firstLaunchPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

@Composable
private fun WelcomeScreen(onContinue: () -> Unit, onOpenHelp: () -> Unit) {
    CenteredScrollColumn(padding = 28.dp) {
        Text(AppInfo.NAME, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            "Камера с ретро-эффектами: эффект видно прямо в превью, и он сразу вшивается в фото и видео.",
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Камера — чтобы показывать превью, снимать фото и видео.", color = TextPrimary, fontSize = 15.sp)
            Text(
                "Микрофон — чтобы в видео был звук. Можно отказать: тогда видео пишется без звука.",
                color = TextPrimary,
                fontSize = 15.sp,
            )
            Text(
                "Фото и видео сохраняются в «Pictures/VHS» и «Movies/VHS». Доступ ко всей галерее не нужен: " +
                    "фото для обработки выбираются системным окном выбора.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }
        Button(onClick = onContinue, colors = appButtonColors()) { Text("Продолжить") }
        TextButton(onClick = onOpenHelp, colors = appTextButtonColors()) { Text("Справка по эффектам") }
    }
}

@Composable
private fun PermissionDeniedScreen(
    canAskAgain: Boolean,
    onAskAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    CenteredScrollColumn(padding = 32.dp) {
        Text("Нет доступа к камере", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        Text(
            if (canAskAgain) {
                "${AppInfo.NAME} — это камера: без разрешения на камеру показывать нечего."
            } else {
                "Система больше не показывает запрос. Откройте настройки приложения → Разрешения → Камера → «Разрешить»."
            },
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (canAskAgain) {
            Button(onClick = onAskAgain, colors = appButtonColors()) { Text("Разрешить") }
            OutlinedButton(onClick = onOpenSettings, colors = appTextButtonColors()) { Text("Открыть настройки") }
        } else {
            Button(onClick = onOpenSettings, colors = appButtonColors()) { Text("Открыть настройки") }
        }
        // Справка не зависит от камеры: эффекты показаны на встроенной тестовой картинке
        TextButton(onClick = onOpenHelp, colors = appTextButtonColors()) { Text("Справка по эффектам") }
    }
}

private fun Activity.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}

private const val PREFS = "vhs"
private const val KEY_ONBOARDED = "onboarded"
/** Тот же ключ, что у CameraScreen: микрофон уже спрашивали. */
private const val KEY_AUDIO_ASKED = "audio_permission_asked"

/** Колонка по центру экрана; если не помещается (альбомная ориентация, маленький экран) — прокручивается. */
@Composable
private fun CenteredScrollColumn(padding: Dp, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(ScreenBackground).safeDrawingPadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}
