package com.astrmobile.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.ContextConfig
import com.astrmobile.core.container.ContextSettings
import com.astrmobile.core.container.CostDiagnosis
import com.astrmobile.core.container.CostHint
import com.astrmobile.core.container.UsageSnapshot
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.InlineLoading
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 「钱为什么花得快」+「怎么省」。
 *
 * 放在用量页而不是设置页，是因为这两个问题总是一起出现：用户看到金额，
 * 下一句一定是"怎么会这么多"。答案不在账单里，在上下文长度里。
 */
@Composable
fun CostSection(viewModel: MainViewModel, snapshot: UsageSnapshot) {
    val hint = remember(snapshot.todayTokens, snapshot.todayCalls, snapshot.todayCostYuan) {
        CostDiagnosis.analyze(snapshot)
    }

    // 正常的时候不打扰：每次都弹一张"一切正常"的卡片，只会把真正的问题埋掉
    if (hint != null && hint.level > CostDiagnosis.LEVEL_OK) {
        CostHintCard(hint)
        Spacer(Modifier.height(Spacing.md))
    }

    ContextSettingsCard(viewModel)
}

@Composable
private fun CostHintCard(hint: CostHint) {
    val severe = hint.level >= CostDiagnosis.LEVEL_BAD
    SectionCard(
        title = if (severe) "钱主要花在这里" else "一个可能的原因",
    ) {
        Text(
            text = hint.headline,
            style = MaterialTheme.typography.titleSmall,
            color = if (severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = hint.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSettingsCard(viewModel: MainViewModel) {
    val saved by viewModel.contextSettings.collectAsStateWithLifecycle()
    val busy by viewModel.contextBusy.collectAsStateWithLifecycle()
    val error by viewModel.contextError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var turns by remember { mutableStateOf<Int?>(null) }
    var strategy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadContextSettings() }

    // 服务端读回来之后才填初值；用户已动过就不覆盖他的选择
    LaunchedEffect(saved) {
        val current = saved ?: return@LaunchedEffect
        if (turns == null) turns = if (current.unlimited) 0 else current.maxTurns
        if (strategy == null) strategy = current.overflowStrategy
    }

    val currentTurns = turns ?: 0
    val currentStrategy = strategy ?: ContextSettings.STRATEGY_LLM_COMPRESS

    SectionCard(
        title = "省一点钱",
        subtitle = "决定每次请求带多少历史",
    ) {
        Text(
            text = "保留最近几轮",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ContextSettings.TURN_OPTIONS.forEach { option ->
                FilterChip(
                    selected = currentTurns == option,
                    onClick = {
                        turns = option
                        message = null
                    },
                    label = { Text(ContextSettings.turnLabel(option), fontSize = 13.sp) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "超出之后怎么办",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            StrategyChip(
                selected = currentStrategy == ContextSettings.STRATEGY_TRUNCATE,
                title = "直接丢掉最老的",
                subtitle = "不花钱，代价是它记不住很早以前说过什么",
                onClick = {
                    strategy = ContextSettings.STRATEGY_TRUNCATE
                    message = null
                },
            )
            StrategyChip(
                selected = currentStrategy == ContextSettings.STRATEGY_LLM_COMPRESS,
                title = "让模型总结旧对话",
                subtitle = "语义保留得好，但每次总结本身就是一次付费调用",
                onClick = {
                    strategy = ContextSettings.STRATEGY_LLM_COMPRESS
                    message = null
                },
            )
        }

        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = {
                    val recommended = ContextConfig.recommended()
                    turns = recommended.maxTurns
                    strategy = recommended.overflowStrategy
                    message = null
                },
                label = { Text("用推荐值", fontSize = 12.sp) },
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    scope.launch {
                        message = null
                        viewModel.saveContextSettings(
                            ContextSettings(
                                maxTurns = currentTurns,
                                overflowStrategy = currentStrategy,
                            ),
                        ).fold(
                            onSuccess = { message = it },
                            onFailure = { message = it.message ?: "保存失败" },
                        )
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (busy) {
                    InlineLoading(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("保存", fontSize = 14.sp)
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 18.sp,
            )
        }

        error?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 18.sp,
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        HintText(
            "AstrBot 默认不限轮数：每次回复都把整段历史重新发给模型，" +
                "聊得越久单次越贵。限制到 10 轮之后，同样的聊天量通常能降一个数量级。" +
                "限制的是「记得多久」，不是「能聊多久」——超出部分直接不带上，不会报错。",
        )
    }
}

@Composable
private fun StrategyChip(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
            )
        }
    }
}
