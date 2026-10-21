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
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.astrmobile.core.container.DashboardCredentials
import com.astrmobile.service.ContainerService
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.KeyValueRow
import com.astrmobile.ui.components.SectionCard
import com.astrmobile.ui.components.StatusDot
import com.astrmobile.ui.components.copyText

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
    onRepair: () -> Unit,
) {
    val state by viewModel.containerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val (statusText, statusColor) = describe(state)

    var showCredentials by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "CONTROL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "控制台",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------------------------------------------------------- 状态
        val running = state as? ContainerState.Running
        SectionCard(title = "运行状态") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state is ContainerState.Starting || state is ContainerState.Stopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    StatusDot(
                        color = statusColor,
                        pulsing = state is ContainerState.Running,
                        size = 12,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(10.dp))

            KeyValueRow(
                label = "AstrBot",
                value = when {
                    running == null -> "6185"
                    running.astrbotReady -> "已就绪 :6185"
                    else -> "启动中…"
                },
                valueColor = if (running?.astrbotReady == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            KeyValueRow(
                label = "QQ 协议端",
                value = when {
                    running == null -> "6099"
                    running.napcatReady -> "已就绪 :6099"
                    else -> "启动中…"
                },
                valueColor = if (running?.napcatReady == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state is ContainerState.Running || state is ContainerState.Starting) {
                    OutlinedButton(
                        onClick = {
                            viewModel.stopContainer()
                            ContainerService.stop(context)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("停止")
                    }
                    OutlinedButton(
                        onClick = { viewModel.restartContainer() },
                        modifier = Modifier
                            .weight(0.75f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("重启")
                    }
                } else {
                    Button(
                        onClick = { startWithService(context) },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("启动机器人", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenConsole) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("实时日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    Spacer(Modifier.width(6.dp))
                    Text("打开后台", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- 常用入口
        SectionCard(title = "常用") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(
                    icon = Icons.Rounded.HealthAndSafety,
                    title = "自检",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenDiagnostics,
                )
                QuickTile(
                    icon = Icons.Rounded.QrCodeScanner,
                    title = "扫码登录",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (running?.napcatReady == true) {
                            onOpenQr()
                        } else {
                            Toast.makeText(context, "NapCat 还在启动，请稍等", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                QuickTile(
                    icon = Icons.Rounded.Person,
                    title = "人格",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenPersona,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(
                    icon = Icons.Rounded.MenuBook,
                    title = "知识库",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenKnowledge,
                )
                QuickTile(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    title = "对话记录",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenConversations,
                )
                QuickTile(
                    icon = Icons.Rounded.CloudUpload,
                    title = "备份",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenBackup,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- 登录信息（折叠）
        val credentials by produceState<DashboardCredentials?>(null, state) {
            value = viewModel.readDashboardCredentials()
        }
        val napcatToken by produceState<String?>(null, state) {
            value = viewModel.readNapCatToken()
        }

        if (credentials != null || napcatToken != null) {
            SectionCard(
                title = "登录信息",
                trailing = {
                    TextButton(onClick = { showCredentials = !showCredentials }) {
                        Text(if (showCredentials) "收起" else "展开")
                    }
                },
            ) {
                if (!showCredentials) {
                    Text(
                        text = "后台账号密码、NapCat 口令，需要进网页版时再用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    credentials?.let { creds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${creds.username} / ${creds.password}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                            )
                            TextButton(
                                onClick = {
                                    copyText(context, "AstrBot 登录信息", "${creds.username}\n${creds.password}")
                                },
                            ) { Text("复制") }
                        }
                    }
                    napcatToken?.let { token ->
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "NapCat: $token",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                            )
                            TextButton(onClick = { copyText(context, "NapCat Token", token) }) {
                                Text("复制")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

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
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRepair) {
                    Text("环境坏了？重新安装", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HintText(
            "QQ 机器人使用非官方协议，有被风控或封号的概率，建议用小号。" +
                "NapCat 常驻会占 1–2GB 内存，低配机型容易被系统回收。",
        )

        Spacer(Modifier.height(16.dp))
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
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GuideStep(index: String, title: String, detail: String) {
    Row(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
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

private fun describe(state: ContainerState): Pair<String, Color> = when (state) {
    is ContainerState.NotInstalled -> "环境未安装" to Color(0xFFFFC46B)
    is ContainerState.Stopped -> "已停止" to Color(0xFFBAC7C2)
    is ContainerState.Starting -> "正在启动…" to Color(0xFF4FE3A4)
    is ContainerState.Stopping -> "正在停止…" to Color(0xFFFFC46B)
    is ContainerState.Failed -> "运行异常" to Color(0xFFFF7B72)
    is ContainerState.Running -> when {
        state.fullyReady -> "运行中" to Color(0xFF4FE3A4)
        else -> "容器已起，服务启动中" to Color(0xFFFFC46B)
    }
    is ContainerState.Restarting ->
        "异常退出，${state.delaySeconds}s 后自动重启（${state.attempt}/${state.maxAttempts}）" to
            Color(0xFFFFC46B)
}

private fun startWithService(context: Context) {
    // 只走 Service：它会在启动容器前读取用户配置的镜像源并持有唤醒锁
    ContainerService.start(context, alsoStartContainer = true)
}
