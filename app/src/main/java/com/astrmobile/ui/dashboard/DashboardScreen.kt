package com.astrmobile.ui.dashboard

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.ContainerState
import com.astrmobile.service.ContainerService
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.KeyValueRow
import com.astrmobile.ui.components.SectionCard
import com.astrmobile.ui.components.StatusDot
import com.astrmobile.ui.components.StatusHeroCard
import com.astrmobile.core.container.UsageSnapshot
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import com.astrmobile.core.ui.Radius
import com.astrmobile.core.ui.Spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material.icons.rounded.ChevronRight

/**
 * 控制台。
 *
 * 只放三件事：现在是什么状态、开关、常用入口。
 * 模型和插件是独立的主页面（底部导航直达），这里不再重复列一遍——
 * 之前 9 条入口竖排加上引导、凭据，一屏根本放不下。
 */
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onOpenConsole: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: (Int) -> Unit,
    onOpenQr: () -> Unit,
    onOpenPersona: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenScreen: () -> Unit,
    onOpenCron: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenUsage: () -> Unit,
    onRepair: () -> Unit,
) {
    val state by viewModel.containerState.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val usageError by viewModel.usageError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 只在容器跑起来之后才有数据可查；60 秒节流在 ViewModel 里
    LaunchedEffect(state) {
        if (state is ContainerState.Running) viewModel.refreshUsage()
    }
    val (statusText, tone) = describe(state)
    val statusColor = when (tone) {
        Tone.Ok -> MaterialTheme.colorScheme.primary
        Tone.Warn -> MaterialTheme.colorScheme.secondary
        Tone.Bad -> MaterialTheme.colorScheme.error
        Tone.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    var showGuide by remember { mutableStateOf(false) }

    // 这里原来有一行「CONTROL / 控制台」大标题 + 设置图标。删掉的理由：
    // 底部 tab 已经标着「控制台」，再写一遍是重复；它占的 80dp 正是首屏
    // 最缺的。设置图标移到状态卡右上角，那块本来就是空的。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page, vertical = Spacing.md),
    ) {
        // ---------------------------------------------------------- 状态
        val running = state as? ContainerState.Running
        val restartingState = state as? ContainerState.Restarting
        val failedState = state as? ContainerState.Failed

        StatusHeroCard(
            status = statusText,
            accent = statusColor,
            pulsing = state is ContainerState.Running,
            detail = when {
                restartingState != null ->
                    "第 ${restartingState.attempt}/${restartingState.maxAttempts} 次重试，" +
                        "${restartingState.delaySeconds} 秒后开始"
                failedState != null -> failedState.reason
                running != null && !running.fullyReady -> "正在等待子服务就绪，通常十几秒"
                running != null -> "AstrBot 与 QQ 协议端都在后台运行"
                else -> null
            },
            chips = listOf(
                "AstrBot :6185" to running?.astrbotReady,
                "QQ :6099" to running?.napcatReady,
            ),
            trailing = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (state is ContainerState.Running || state is ContainerState.Starting) {
                    OutlinedButton(
                        onClick = {
                            viewModel.stopContainer()
                            ContainerService.stop(context)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(Radius.md),
                    ) {
                        Text("停止")
                    }
                    OutlinedButton(
                        onClick = { viewModel.restartContainer() },
                        modifier = Modifier
                            .weight(0.75f)
                            .height(48.dp),
                        shape = RoundedCornerShape(Radius.md),
                    ) {
                        Text("重启")
                    }
                } else {
                    Button(
                        onClick = { startWithService(context) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text("启动机器人", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(
                    onClick = {
                        if (running?.astrbotReady == true) {
                            onOpenWeb(6185)
                        } else {
                            Toast.makeText(context, "AstrBot 还在启动，等状态变成「已就绪」", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Icon(
                        Icons.Rounded.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("打开后台", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 试聊放在这里而不是快捷入口里：它是"配完立刻验一下"的动作，
                // 和「打开后台」是同一类，隔着三行图标去找反而绕。
                TextButton(
                    onClick = {
                        if (running?.astrbotReady == true) {
                            onOpenChat()
                        } else {
                            Toast.makeText(context, "AstrBot 还在启动，等状态变成「已就绪」", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Icon(
                        Icons.Rounded.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("试聊", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        UsageStrip(
            usage = usage,
            error = usageError,
            onRefresh = { scope.launch { viewModel.refreshUsage(force = true) } },
            onOpen = onOpenUsage,
        )

        Spacer(Modifier.height(Spacing.md))

        // ---------------------------------------------------------- 常用入口
        SectionCard(title = "常用") {
            // 3 列 × 3 行。原来是 5 列两行（5+4）：格子只有 60dp 宽，四个字
            // 的标题已经开始挤；而且第二行少一个，看着像没排完。9 个入口
            // 刚好三三得九。
            val tiles: List<Triple<ImageVector, String, () -> Unit>> = listOf(
                Triple(Icons.Rounded.HealthAndSafety, "自检", onOpenDiagnostics),
                Triple(Icons.Rounded.QrCodeScanner, "扫码", {
                    if (running?.napcatReady == true) {
                        onOpenQr()
                    } else {
                        Toast.makeText(context, "NapCat 还在启动，请稍等", Toast.LENGTH_SHORT).show()
                    }
                }),
                Triple(Icons.Rounded.Person, "人格", onOpenPersona),
                Triple(Icons.Rounded.MenuBook, "知识", onOpenKnowledge),
                Triple(Icons.Rounded.Schedule, "定时", onOpenCron),
                Triple(Icons.Rounded.ChatBubbleOutline, "对话", onOpenConversations),
                Triple(Icons.Rounded.CloudUpload, "备份", onOpenBackup),
                Triple(Icons.Rounded.PhoneAndroid, "看屏", onOpenScreen),
                Triple(Icons.Rounded.Terminal, "日志", onOpenConsole),
            )

            tiles.chunked(3).forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    row.forEach { (icon, title, action) ->
                        QuickTile(
                            icon = icon,
                            title = title,
                            modifier = Modifier.weight(1f),
                            onClick = action,
                        )
                    }
                    // 末行不满时补空位，避免最后一个格子独占整行宽度
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // ---------------------------------------------------------- 首次引导（折叠）
        SectionCard(
            title = "第一次用",
            trailing = {
                TextButton(onClick = { showGuide = !showGuide }) {
                    Text(if (showGuide) "收起" else "展开")
                }
            },
        ) {
            if (!showGuide) {
                Text(
                    text = "启动 → 扫码 → 配模型 → 群里 @ 它",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                GuideStep("1", "启动机器人", "等两个端口都变成「已就绪」。")
                GuideStep("2", "扫码登录 QQ", "用手机 QQ 扫，扫完在手机上点确认。")
                GuideStep("3", "配模型", "底部「模型」页填 API Key，保存即生效。")
                GuideStep("4", "写人格", "底部「插件」旁边没有，进「人格」写一段人设，保存后返回会自动重启。")
                Spacer(Modifier.height(Spacing.sm))
                TextButton(onClick = onRepair) {
                    Text("环境坏了？重新安装", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        HintText(
            "QQ 机器人使用非官方协议，有被风控或封号的概率，建议用小号。" +
                "NapCat 常驻会占 1–2GB 内存，低配机型容易被系统回收。",
        )

        Spacer(Modifier.height(Spacing.lg))
    }
}

/** 快捷入口：图标在上、文字在下的小方块，比原来一长串列表省一半高度。 */
@Composable
private fun QuickTile(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GuideStep(index: String, title: String, detail: String) {
    Row(modifier = Modifier.padding(vertical = Spacing.xs)) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )
        }
    }
}

/** 状态语义色：具体颜色交给主题，页面不再写死 0x 色值。 */
private enum class Tone { Ok, Warn, Bad, Idle }


/**
 * 今日用量。
 *
 * 首屏只放一行：金额 + token + 次数。模型明细、7 天趋势、按会话分布都在
 * 详情页——那些是"想知道为什么这么多钱"时才看的东西，不该占首屏。
 *
 * 金额是估算：接口只给合计 token，不拆输入/输出，按 3:1 折算。
 * 这句话放在详情页里说清楚，这里只给数字。
 */
@Composable
private fun UsageStrip(
    usage: UsageSnapshot?,
    error: String?,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = Spacing.lg,
                end = Spacing.sm,
                top = Spacing.sm,
                bottom = Spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "今日",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = when {
                        usage == null && error != null -> "读取失败"
                        usage == null -> "¥ …"
                        usage.isFreeOrEmpty -> "¥ 0.00"
                        else -> usage.todayCostText
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (usage == null && error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = when {
                        usage == null && error != null -> error
                        usage == null -> "读取中"
                        usage.isFreeOrEmpty -> "还没有调用记录"
                        else -> "${formatTokens(usage.todayTokens)} tokens · ${usage.todayCalls} 次"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }

            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新用量",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "查看用量详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = Spacing.xs),
            )
        }
    }
}

/** 12345 → 1.2 万；小于一万就直接给原数。 */
private fun formatTokens(tokens: Long): String = when {
    tokens >= 10_000 -> String.format("%.1f 万", tokens / 10_000.0)
    else -> tokens.toString()
}

private fun describe(state: ContainerState): Pair<String, Tone> = when (state) {
    is ContainerState.NotInstalled -> "环境未安装" to Tone.Warn
    is ContainerState.Stopped -> "已停止" to Tone.Idle
    is ContainerState.Starting -> "正在启动…" to Tone.Ok
    is ContainerState.Stopping -> "正在停止…" to Tone.Warn
    is ContainerState.Failed -> "运行异常" to Tone.Bad
    is ContainerState.Running -> when {
        state.fullyReady -> "运行中" to Tone.Ok
        else -> "容器已起，服务启动中" to Tone.Warn
    }
    is ContainerState.Restarting ->
        "异常退出，${state.delaySeconds}s 后自动重启（${state.attempt}/${state.maxAttempts}）" to Tone.Warn
}

private fun startWithService(context: Context) {
    // 只走 Service：它会在启动容器前读取用户配置的镜像源并持有唤醒锁
    ContainerService.start(context, alsoStartContainer = true)
}
