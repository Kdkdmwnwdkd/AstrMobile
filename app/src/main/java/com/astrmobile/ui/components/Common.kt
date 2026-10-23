package com.astrmobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.core.ui.Radius
import com.astrmobile.core.ui.Spacing
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Int = 10) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Box(
        modifier = Modifier
            .size(size.dp)
            .alpha(if (pulsing) alpha else 1f)
            .clip(RoundedCornerShape(percent = 50))
            .background(color),
    )
}

/**
 * 分组卡片。
 *
 * 观感上做了三件事，目的是"更像产品"而不是"更好看"：
 * - 圆角从 16 提到 22，深色底上圆角越大越软
 * - 加一条 1dp 低对比描边：深色底几乎没有阴影，卡片边界只能靠描边立住
 * - 标题区改成「标题 + 可选副标题」，每张卡片都有明确的一句话说明
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(Radius.lg),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 卡片标题用正文字体而不是等宽小字：等宽 12sp 在深底上最难认
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(Spacing.md))
            content()
        }
    }
}

/**
 * 首屏状态卡。
 *
 * 和普通 SectionCard 的区别：它承担"一眼看懂现在什么情况"的职责，
 * 所以用大字号状态 + 从强调色淡出的渐变，让第一屏有明确主次。
 *
 * 结构上收敛过一次：原来标题区有一行「运行状态」的小标签、状态字下面
 * 还有一层说明、再下面是胶囊和操作。一屏四张卡挤在一起时，那行标签
 * 纯属占地方——「已停止」三个大字已经说清楚了。现在状态点直接贴在字
 * 左边，右上角留给页面的主图标（设置之类）。
 */
@Composable
fun StatusHeroCard(
    status: String,
    accent: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    pulsing: Boolean = false,
    chips: List<Pair<String, Boolean?>> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(Radius.xl),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                    ),
                )
                .padding(Spacing.xl),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = accent, pulsing = pulsing, size = 8)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (detail != null) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 和状态文字左对齐：点 8 + 间距 8
                            modifier = Modifier.padding(start = Spacing.lg),
                        )
                    }
                }
                trailing?.invoke()
            }

            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    chips.forEach { (text, ready) -> StatusChip(text = text, ready = ready) }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            actions()
        }
    }
}

/** 状态胶囊：用于"某个子服务是否就绪"这种二值状态。 */
@Composable
fun StatusChip(text: String, ready: Boolean?) {
    val color = when (ready) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.secondary
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = color, size = 6)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空列表/未配置时的占位。统一它，是为了让"没有内容"的页面也不像坏了。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.section, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/**
 * 行内转圈。
 *
 * 之前 17 处 CircularProgressIndicator 各写各的尺寸（12/14/16/18/24）
 * 和线宽（1.5/2），同一个 App 里转圈的大小和粗细都不一样。统一到这里。
 */
@Composable
fun InlineLoading(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = 2.dp,
        color = color,
    )
}

/** 整块加载。首屏还没拿到数据时用它，而不是留一片空白让人以为卡死了。 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    text: String = "加载中…",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 整块错误。
 *
 * 原则是「说清发生了什么 + 给出下一步」。只有一句"失败"等于没提示，
 * 用户唯一能做的是杀掉 App 重开。
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.section, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        ) {
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.lg))
            OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(Radius.md)) {
                Text("重试")
            }
        }
    }
}

/**
 * 开关项：左边标题 + 说明，右边开关。
 *
 * 原来只存在于设置页里（private），这次行为配置页也要用，提到公共组件。
 * 顺手把说明文字的字号从 13sp 收到 bodySmall——13sp 是之前每页自己定的
 * 那一批，没有走 typography。
 */
@Composable
fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 20.sp,
    )
}

@Composable
fun HorizontalSpacer(width: Int) = Spacer(Modifier.width(width.dp))
