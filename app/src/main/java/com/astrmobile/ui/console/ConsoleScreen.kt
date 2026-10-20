package com.astrmobile.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.ui.components.LogView
import com.astrmobile.ui.components.copyLogsToClipboard
import com.astrmobile.ui.components.shareLogFile

@Composable
fun ConsoleScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "CONSOLE / 实时日志",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${logs.size} 行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            IconButton(onClick = { copyLogsToClipboard(context, logs) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制日志")
            }
            IconButton(onClick = { shareLogFile(context) }) {
                Icon(Icons.Rounded.Share, contentDescription = "导出日志")
            }
            IconButton(onClick = { viewModel.clearLogs() }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            LogView(
                lines = logs,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 12.dp),
            )
        }
    }
}
