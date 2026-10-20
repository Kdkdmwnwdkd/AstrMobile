package com.astrmobile.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** 把日志放进剪贴板：长按选择在 LazyColumn 里不可靠，整段复制更实用。 */
fun copyLogsToClipboard(context: Context, lines: List<String>) {
    if (lines.isEmpty()) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val text = lines.joinToString("\n")
    clipboard.setPrimaryClip(ClipData.newPlainText("AstrMobile 日志", text))
}

/**
 * 通过系统分享面板导出日志文件。
 * 同时把安装日志拼进去（如果存在），因为安装失败时最有价值的是那一段。
 */
fun shareLogFile(context: Context) {
    val containerLog = File(context.filesDir, "runtime/logs/container.log")
    val installLog = File(context.filesDir, "runtime/logs/install.log")

    val target = when {
        containerLog.exists() && containerLog.length() > 0 -> containerLog
        installLog.exists() && installLog.length() > 0 -> installLog
        else -> null
    } ?: return

    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AstrMobile 运行日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出日志"))
    }
}
