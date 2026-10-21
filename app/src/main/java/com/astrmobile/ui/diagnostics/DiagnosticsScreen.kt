package com.astrmobile.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.CheckItem
import com.astrmobile.core.container.CheckLevel
import com.astrmobile.ui.components.SectionCard

private val OkColor = Color(0xFF167A57)
private val WarnColor = Color(0xFFB26A00)
private val FailColor = Color(0xFFB3261E)

/**
 * 运行状态自检。
 *
 * 存在的理由：前面所有故障都是「看起来在跑，但某一步没通」，
 * 而用户只能看到一堆日志。这里把每个环节单独判一次，直接指出该修哪。
 */
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<CheckItem>>(emptyList()) }
    var summary by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        val (result, text) = viewModel.diagnose()
        items = result
        summary = text
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自检",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "看现在能不能用、卡在哪一步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { reloadKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "重新检查")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- 总结
        val failed = items.count { it.level == CheckLevel.FAIL }
        val warned = items.count { it.level == CheckLevel.WARN }
        val accent = when {
            failed > 0 -> FailColor
            warned > 0 -> WarnColor
            else -> OkColor
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在逐项检查…", style = MaterialTheme.typography.titleSmall)
                    }
                } else {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        lineHeight = 22.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 逐项
        SectionCard(title = "检查项") {
            if (loading && items.isEmpty()) {
                Text(
                    "稍等…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                items.forEach { item ->
                    CheckRow(item)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = { reloadKey++ },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(if (loading) "检查中…" else "重新检查")
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "自检只读本地状态，不会修改任何配置。改动之后要重新点一次才会更新结论。",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val color = when (item.level) {
        CheckLevel.OK -> OkColor
        CheckLevel.WARN -> WarnColor
        CheckLevel.FAIL -> FailColor
    }
    val icon = when (item.level) {
        CheckLevel.OK -> Icons.Rounded.CheckCircle
        CheckLevel.WARN -> Icons.Rounded.WarningAmber
        CheckLevel.FAIL -> Icons.Rounded.Cancel
    }

    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 1.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                item.action?.let { action ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "→ $action",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = color,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}
