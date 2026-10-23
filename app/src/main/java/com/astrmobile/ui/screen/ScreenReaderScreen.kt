package com.astrmobile.ui.screen

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.astrmobile.core.data.VisionConfig
import com.astrmobile.service.ScreenCaptureBus
import com.astrmobile.service.ScreenCaptureService
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import com.astrmobile.ui.components.copyText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.InlineLoading

private const val DEFAULT_PROMPT =
    "这是一张安卓手机的屏幕截图。用中文简洁说明：现在是什么界面、在讲什么。" +
        "画面里有文字的话，把关键信息摘出来（聊天内容、标题、数字），不要逐字复述整屏。"

private enum class Mode { Vision, Ocr }

/**
 * 看屏幕。
 *
 * 两条路：
 * - 看图：截图压缩后直接交给多模态模型（deepseek-flash 这类），能看懂画面，
 *   代价是图片要发往模型服务商。
 * - 提取文字：用系统自带的 ML Kit 在本地 OCR，不上传任何东西，但只认得字。
 *
 * 两条路都由用户自己点，不默认上传。
 */
@Composable
fun ScreenReaderScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("处理中…") }
    var answer by remember { mutableStateOf<String?>(null) }
    var answerTitle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<VisionConfig?>(null) }
    var prompt by remember { mutableStateOf(DEFAULT_PROMPT) }
    var pending by remember { mutableStateOf(Mode.Vision) }

    LaunchedEffect(Unit) {
        target = viewModel.visionTarget()
    }

    // 截屏结果是异步回来的，页面订阅一次
    LaunchedEffect(Unit) {
        ScreenCaptureBus.result.collect { result ->
            if (result == null) return@collect
            ScreenCaptureBus.publish(null)
            result.fold(
                onSuccess = { bitmap ->
                    busy = true
                    busyLabel = if (pending == Mode.Vision) "正在让模型看…" else "正在提取文字…"
                    error = null
                    answer = null

                    if (pending == Mode.Vision) {
                        val jpeg = withContext(Dispatchers.Default) { compress(bitmap) }
                        bitmap.recycle()
                        val result2 = viewModel.describeScreen(jpeg, prompt.ifBlank { DEFAULT_PROMPT })
                        busy = false
                        result2.fold(
                            onSuccess = {
                                answerTitle = "模型看到的内容"
                                answer = it
                            },
                            onFailure = { error = it.message ?: "请求失败" },
                        )
                    } else {
                        val recognized = runCatching { recognize(bitmap) }
                        bitmap.recycle()
                        busy = false
                        recognized.fold(
                            onSuccess = {
                                answerTitle = "识别到的文字"
                                answer = it.ifBlank { "这一屏没有识别到文字（可能是图片或空白页）。" }
                            },
                            onFailure = { error = "识别失败：${it.message}" },
                        )
                    }
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

    fun startCapture(mode: Mode) {
        pending = mode
        error = null
        val manager = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (manager == null) {
            error = "这台设备不支持录屏接口"
        } else {
            captureLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
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

        Spacer(Modifier.height(Spacing.md))

        SectionCard(title = "当前模型") {
            val current = target
            if (current == null) {
                Text(
                    text = "还没有可用的模型凭据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(Spacing.sm))
                HintText(
                    "去「模型」页把 Key 重新保存一次，App 会记住一份（只存在本机、加密存放），" +
                        "之后这里就能直接把截图交给模型看。",
                )
            } else {
                Text(
                    text = current.model,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = hostOf(current.apiBase),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        SectionCard(title = "问它什么") {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(Spacing.sm))
            HintText("默认是「描述这一屏」。想让它只读文字，就直接写「把屏幕上的文字抄下来」。")
        }

        Spacer(Modifier.height(Spacing.lg))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(
                onClick = { startCapture(Mode.Vision) },
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text("看图")
            }
            OutlinedButton(
                onClick = { startCapture(Mode.Ocr) },
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("只提取文字")
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        HintText(
            "「看图」会把截图作为图片发给你配置的服务商（上面那个地址），用于理解画面内容；" +
                "「只提取文字」完全在手机本地完成，图片不出设备，但只认得字。",
        )

        if (busy) {
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineLoading()
                Spacer(Modifier.width(Spacing.md))
                Text(busyLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }

        error?.let {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 21.sp,
            )
        }

        answer?.let { content ->
            Spacer(Modifier.height(Spacing.lg))
            SectionCard(
                title = answerTitle,
                trailing = {
                    TextButton(onClick = { copyText(context, answerTitle, content) }) { Text("复制") }
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
            }
            Spacer(Modifier.height(Spacing.md))
            HintText(
                "复制之后可以直接贴到群里问它，「对话模型」页的测试框也行。",
            )
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

/** `https://api.deepseek.com/v1` → `api.deepseek.com`，界面上只显示主机名。 */
private fun hostOf(apiBase: String): String =
    apiBase.removePrefix("https://").removePrefix("http://").substringBefore('/')

/**
 * 压到长边 1280、JPEG 82。
 *
 * 长边再大对读屏幕没帮助，只会让请求体变大、变慢；1280 已经能看清正文。
 */
private fun compress(
    bitmap: Bitmap,
    maxEdge: Int = 1280,
    quality: Int = 82,
): ByteArray {
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longest > maxEdge) {
        val ratio = maxEdge.toFloat() / longest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(bitmap, width, height, true)
    } else {
        null
    }

    val output = ByteArrayOutputStream()
    (scaled ?: bitmap).compress(Bitmap.CompressFormat.JPEG, quality, output)
    if (scaled != null && scaled !== bitmap) scaled.recycle()
    return output.toByteArray()
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
