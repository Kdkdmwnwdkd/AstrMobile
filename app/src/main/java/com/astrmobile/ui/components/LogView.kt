package com.astrmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.core.ui.classifyLogLine

/**
 * 等宽字体的日志视图，自动跟随最新一行。
 * 只在"用户没有手动上滑"时自动滚动，避免打断排查。
 */
@Composable
fun LogView(
    lines: List<String>,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size, lines.lastOrNull(), autoScroll) {
        if (autoScroll && lines.isNotEmpty()) {
            // 用无动画滚动：日志高频追加时，动画会排队堆积导致明显卡顿
            listState.scrollToItem(lines.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (lines.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(
                        text = "暂无日志",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(lines) { _, line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
                color = classifyLogLine(line),
            )
        }
    }
}
