package com.vhscam.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.camera.CameraCaps
import com.vhscam.app.effect.VideoResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Сведения о камерах»: что устройство отдаёт приложению (размеры фото и видео, режим максимального
 * разрешения, ULTRA_HIGH_RESOLUTION_SENSOR, GL_MAX_TEXTURE_SIZE, память). Для скриншота потолка
 * устройства; работает без разрешения на камеру.
 */
@Composable
fun CameraInfoScreen(onBack: () -> Unit, onOpenHelp: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var report by remember { mutableStateOf("Собираю сведения…") }
    LaunchedEffect(Unit) {
        // Разрешения видео — по данным CameraX Recorder (камера + кодек) для камер, видимых CameraX
        val video = runCatching {
            val provider = ProcessCameraProvider.awaitInstance(context)
            provider.availableCameraInfos.associate { info -> CameraCaps.cameraId(info) to CameraCaps.videoResolutions(info) }
        }.getOrDefault(emptyMap<String, List<VideoResolution>>())
        report = withContext(Dispatchers.Default) {
            runCatching { CameraCaps.report(context, video) }.getOrElse { "Ошибка: ${it.message}" }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTouch(description = "Назад", onClick = onBack) {
                Text("←", color = TextPrimary, fontSize = 22.sp)
            }
            Text("Сведения о камерах", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(colors = appTextButtonColors(), onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Сведения о камерах", report))
            }) { Text("Копировать") }
        }
        TextButton(onClick = onOpenHelp, colors = appTextButtonColors(), modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("Справка по эффектам")
        }
        Text(
            report,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
