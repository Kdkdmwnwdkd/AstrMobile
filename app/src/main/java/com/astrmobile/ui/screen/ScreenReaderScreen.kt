package com.astrmobile.ui.screen

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.service.ScreenCaptureBus
import com.astrmobile.service.ScreenCaptureService
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import com.astrmobile.ui.components.copyText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 看屏幕。
 *
 * 模型是纯文本的（DeepSeek 等），发图它看不见，所以走"截屏 → 本地 OCR → 给文字"。
 * 只能识别屏幕上的文字，图片里的内容读不到，这点在页面上写清楚，别让人误会。
 */
@Composable
fun ScreenReaderScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 截屏结果是异步回来的，页面订阅一次
    LaunchedEffect(Unit) {
        ScreenCaptureBus.result.collect { result ->
            if (result == null) return@collect
            ScreenCaptureBus.publish(null)
            busy = true
            error = null
            result.fold(
                onSuccess = { bitmap ->
                    val recognized = runCatching { recognize(bitmap) }
                    bitmap.recycle()
                    busy = false
                    recognized.fold(
                        onSuccess = { text = it.ifBlank { "这一屏没有识别到文字（可能是图片或空白页）。" } },
                        onFailure = { error = "识别失败：${it.message}" },
                    )
                },
                onFailure = {
                    busy = false
                    error = "截屏失败：${it.message}"
                },
            )
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
        } else {
            error = "没有授权录屏，无法截屏"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "看屏幕",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "怎么用") {
            HintText(
                "点下面的按钮，系统会问你是否允许录屏。允许后截一张当前屏幕，" +
                    "在本地把文字提取出来——图片不会上传，也不保存。\n\n" +
                    "它是纯文本模型，看不到画面本身，只能读到屏幕上的字：聊天记录、网页、文档都行，" +
                    "纯图片和视频里的内容读不到。",
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val manager = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                if (manager == null) {
                    error = "这台设备不支持录屏接口"
                } else {
                    captureLauncher.launch(manager.createScreenCaptureIntent())
                }
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("正在识别…")
            } else {
                Text("截取当前屏幕")
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        text?.let { content ->
            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "识别到的文字",
                trailing = {
                    TextButton(
                        onClick = { copyText(context, "屏幕文字", content) },
                    ) { Text("复制") }
                },
            ) {
                SelectionContainer {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                HintText(
                    "复制之后可以直接发到群里问它，或者贴到「对话模型」的测试里。" +
                        "想让它自己接话，需要把这段文字发到群里——这一步要 AstrBot 的 API Key，还没接。",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { result ->
            runCatching { recognizer.close() }
            cont.resume(result.text)
        }
        .addOnFailureListener { error ->
            runCatching { recognizer.close() }
            cont.resumeWithException(error)
        }
}
