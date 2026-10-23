package com.astrmobile.ui.backup

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.BackupRepository
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.InlineLoading

/**
 * 备份与恢复。
 *
 * 备份的是容器里的 `root/data`：配置、人格、插件、会话、知识库。
 * 换手机、重装、折腾坏了，都能从这里回去。
 */
@Composable
fun BackupScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var backups by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var fraction by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<File?>(null) }

    suspend fun reload() {
        backups = viewModel.listBackups()
    }

    LaunchedEffect(Unit) { reload() }

    // 从系统文件选择器挑备份包（用户可能把包存在网盘或下载目录）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val local = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "restore").apply { mkdirs() }
                    val target = File(dir, "import.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("读不到文件")
                    target
                }
            }
            local.fold(
                onSuccess = { pendingRestore = it },
                onFailure = { isError = true; message = it.message ?: "读取备份包失败" },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "备份与恢复",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "配置、人格、插件、会话、知识库",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        if (busy && fraction > 0f) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
        }

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(Spacing.md))
        }

        // ---------------------------------------------------------- 新建备份
        SectionCard(title = "新建备份") {
            HintText(
                "打包容器里的 data 目录。不含 Ubuntu 系统和 Python 环境——那些能重装，" +
                    "没必要让包大几百 MB。\n" +
                    "建议先停止容器再备份，运行中打包可能有文件正在写。",
            )
            Spacer(Modifier.height(Spacing.md))

            Button(
                onClick = {
                    busy = true
                    isError = false
                    message = null
                    fraction = 0f
                    scope.launch {
                        val result = viewModel.createBackup { done, total ->
                            if (total > 0) {
                                fraction = done.toFloat() / total
                                progress = "已处理 $done / $total 个文件"
                            }
                        }
                        busy = false
                        fraction = 0f
                        result.fold(
                            onSuccess = {
                                isError = false
                                message = "备份完成：${it.file.name}（${BackupRepository.humanSize(it.bytes)}）"
                                reload()
                            },
                            onFailure = {
                                isError = true
                                message = it.message ?: "备份失败"
                            },
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) {
                    InlineLoading(color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(progress.ifBlank { "正在打包…" }, fontSize = 13.sp)
                } else {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("立即备份")
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // ---------------------------------------------------------- 已有备份
        SectionCard(title = "已有备份（${backups.size}）") {
            if (backups.isEmpty()) {
                HintText("还没有备份。点上面的按钮生成一份。")
            } else {
                backups.forEach { file ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                            Text(
                                text = BackupRepository.humanSize(file.length()),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { share(context, file) }) {
                            Text("导出", fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            if (viewModel.deleteBackup(file)) {
                                message = "已删除 ${file.name}"
                                isError = false
                                scope.launch { reload() }
                            } else {
                                isError = true
                                message = "删除失败"
                            }
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // ---------------------------------------------------------- 恢复
        SectionCard(title = "从备份恢复") {
            HintText(
                "会覆盖当前的配置、插件和会话数据。" +
                    "恢复前系统会自动把现有数据留一份（data.previous），失败会还原。\n" +
                    "恢复后需要重启容器才生效。",
            )
            Spacer(Modifier.height(Spacing.md))

            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text("选择备份包")
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "备份包可以导出到网盘或发给自己。换手机时先装 App、" +
                "让它自己装好环境，再用这里的「从备份恢复」把数据搬回去。",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(Spacing.lg))
    }

    // ---------------------------------------------------------- 恢复确认
    pendingRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("确认恢复？") },
            text = {
                Text(
                    "将用「${file.name}」覆盖当前的配置、插件和会话数据。\n\n" +
                        "恢复前会先停止机器人，完成后自动重新启动。当前数据会自动留一份，恢复失败会还原。",
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    busy = true
                    isError = false
                    message = null
                    fraction = 0f
                    scope.launch {
                        val result = viewModel.restoreBackup(file) { done, total ->
                            if (total > 0) {
                                fraction = done.toFloat() / total
                                progress = "已恢复 $done / $total"
                            }
                        }
                        busy = false
                        fraction = 0f
                        result.fold(
                            onSuccess = {
                                isError = false
                                message = "恢复完成，容器已重新启动"
                            },
                            onFailure = {
                                isError = true
                                message = "恢复失败：${it.message}"
                            },
                        )
                    }
                }) { Text("恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
        )
    }
}

/** 通过系统面板把备份包发出去（存网盘、发给自己都行）。 */
private fun share(context: android.content.Context, file: File) {
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出备份"))
    }
}
