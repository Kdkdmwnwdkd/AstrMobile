package com.astrmobile.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.UsagePoint
import com.astrmobile.core.container.UsageSnapshot
import com.astrmobile.core.ui.Radius
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.ErrorState
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.LoadingState
import com.astrmobile.ui.components.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 用量详情。
 *
 * 首屏只回答"今天花了多少"，但决定要不要少聊两句的是"这周花了多少"。
 * 这个页面就是那第二个问题的答案。
 *
 * 金额仍然只对「今日」是准的——接口给了今日的按模型分布，没给区间内的。
 * 区间金额是拿今日均价折算的，界面上必须写明这一点，否则会被当成账单。
 */
@Composable
fun UsageScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val usage by viewModel.usageDetail.collectAsStateWithLifecycle()
    val busy by viewModel.usageDetailBusy.collectAsStateWithLifecycle()
    val error by viewModel.usageDetailError.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(7) }

    LaunchedEffect(days) { viewModel.refreshUsageDetail(days) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "用量",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            listOf(7 to "7 天", 30 to "30 天").forEach { (value, label) ->
                FilterChip(
                    selected = days == value,
                    onClick = { days = value },
                    label = { Text(label) },
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
        }

        val snapshot = usage
        when {
            snapshot == null && error != null -> ErrorState(
                message = error.orEmpty(),
                onRetry = { scope.launch { viewModel.refreshUsageDetail(days, force = true) } },
            )

            snapshot == null -> LoadingState(text = "读取用量…")

            else -> Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.page, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                SummaryCard(snapshot, days, busy)

                if (snapshot.trend.isNotEmpty()) {
                    SectionCard(
                        title = "每天用了多少",
                        subtitle = "横轴是日期，高度按 token 比例",
                    ) {
                        TrendChart(points = snapshot.trend, showLabels = days <= 7)
                    }
                }

                CostSection(viewModel, snapshot)

                if (snapshot.rangeByUmo.isNotEmpty()) {
                    SectionCard(title = "用在哪", subtitle = "按会话统计") {
                        val total = snapshot.rangeByUmo.sumOf { it.tokens }.coerceAtLeast(1L)
                        snapshot.rangeByUmo.take(6).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatTokens(item.tokens),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Text(
                                    text = "${item.tokens * 100 / total}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(40.dp),
                                )
                            }
                        }
                    }
                }

                if (snapshot.todayModels.isNotEmpty()) {
                    SectionCard(title = "今日按模型", subtitle = "金额按牌价折算") {
                        snapshot.todayModels.take(6).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.model,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = formatTokens(item.tokens),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Text(
                                    text = item.costYuan?.let(UsageSnapshot::formatYuan) ?: "未收录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (snapshot.hasUnpricedModel) {
                    SectionCard(
                        title = "这些模型没有牌价",
                        subtitle = "它们的用量不计入上面的金额",
                    ) {
                        snapshot.unpricedModels.take(6).forEach { model ->
                            Text(
                                text = "· $model",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        HintText(
                            "牌价表是内置的，认不出的名字不会瞎猜单价。想让它们算进去，" +
                                "把这个名字发出来，下一版加进去。",
                        )
                    }
                }

                HintText(
                    "金额是按模型牌价估算的，真实扣费以厂商后台为准。" +
                        "区间金额用今日均价折算——中间换过模型的话会有偏差。",
                )

                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun SummaryCard(snapshot: UsageSnapshot, days: Int, busy: Boolean) {
    SectionCard(title = "近 $days 天") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = snapshot.rangeCostYuan?.let(UsageSnapshot::formatYuan) ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = "${formatTokens(snapshot.rangeTokens)} tokens · ${snapshot.rangeCalls} 次调用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }

        if (snapshot.rangeCostYuan == null) {
            Spacer(Modifier.height(Spacing.sm))
            HintText(
                if (snapshot.rangeTokens == 0L) {
                    "这段时间还没有调用记录。"
                } else {
                    "今天还没有调用，没有单价可以折算金额。"
                },
            )
        } else {
            Spacer(Modifier.height(Spacing.xs))
            HintText("按今日均价折算")
        }

        if (busy) {
            Spacer(Modifier.height(Spacing.sm))
            HintText("正在刷新…")
        }
    }
}

/**
 * 柱状图。
 *
 * 没用 Canvas：这里就是一组等宽的矩形，用 Row + weight 就能表达，
 * 而且高度比例直接由 `fillMaxHeight(fraction)` 给出，不用自己算像素。
 * 30 天时不给日期标签——30 个 "10-17" 会糊成一片。
 */
@Composable
private fun TrendChart(points: List<UsagePoint>, showLabels: Boolean) {
    val max = points.maxOfOrNull { it.tokens } ?: 0L
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { point ->
            // 有数据的最矮也留 4%，否则 1 token 和 0 看起来一样
            val ratio = when {
                max <= 0L -> 0f
                point.tokens <= 0L -> 0.02f
                else -> (point.tokens.toFloat() / max).coerceIn(0.04f, 1f)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(ratio)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (point.tokens > 0L) barColor else emptyColor),
                )
                if (showLabels) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = dayLabel(point.timestampSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 服务端给的可能是秒也可能是毫秒，按量级判断，别把日期画成 55000 年。 */
private fun dayLabel(timestamp: Long): String {
    val millis = if (timestamp > 100_000_000_000L) timestamp else timestamp * 1000
    return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(millis))
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 10_000 -> String.format(Locale.US, "%.1f 万", tokens / 10_000.0)
    else -> tokens.toString()
}
