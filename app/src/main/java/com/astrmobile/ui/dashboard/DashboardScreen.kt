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
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SmartToy
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
import androidx.compose.runtime.produceState
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
import com.astrmobile.ui.components.copyText
import com.astrmobile.ui.components.StatusDot

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onOpenConsole: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: (Int) -> Unit,
    onOpenQr: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPlugins: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "CONTROL / 控制台",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "AstrMobile",
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

        Spacer(Modifier.height(18.dp))

        // ---------------------------------------------------------- 状态总览
        SectionCard(title = "容器状态") {
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

            Spacer(Modifier.height(12.dp))

            val running = state as? ContainerState.Running
            KeyValueRow(
                label = "AstrBot 后台",
                value = when {
                    running == null -> "http://127.0.0.1:6185"
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
                label = "QQ 扫码登录",
                value = when {
                    running == null -> "http://127.0.0.1:6099"
                    running.napcatReady -> "已就绪 :6099"
                    else -> "启动中…"
                },
                valueColor = if (running?.napcatReady == true) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state is ContainerState.Running || state is ContainerState.Starting) {
                    OutlinedButton(
                        onClick = {
                            viewModel.stopContainer()
                            ContainerService.stop(context)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("停止")
                    }
                    OutlinedButton(
                        onClick = { viewModel.restartContainer() },
                        modifier = Modifier
                            .weight(0.75f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("重启")
                    }
                } else {
                    Button(
                        onClick = { startWithService(context) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
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
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 后台登录
        val credentials by produceState<DashboardCredentials?>(null, state) {
            value = viewModel.readDashboardCredentials()
        }
        credentials?.let { creds ->
            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "AstrBot 后台登录",
                trailing = {
                    TextButton(
                        onClick = { copyText(context, "AstrBot 登录信息", "${creds.username}\n${creds.password}") },
                    ) {
                        Text("复制", style = MaterialTheme.typography.labelSmall)
                    }
                },
            ) {
                Text(
                    text = "账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = creds.username,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "密码（默认口令，登录后建议自行修改）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = creds.password,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- NapCat Token
        val napcatToken by produceState<String?>(null, state) {
            value = viewModel.readNapCatToken()
        }
        napcatToken?.let { token ->
            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "NapCat 网页版口令（兜底用）",
                trailing = {
                    TextButton(
                        onClick = { copyText(context, "NapCat Token", token) },
                    ) {
                        Text("复制", style = MaterialTheme.typography.labelSmall)
                    }
                },
            ) {
                Text(
                    text = token,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "只有走网页版兜底时才需要它。App 里的「QQ 扫码登录」会自动用它，" +
                        "你不用手动填。",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 快捷入口
        SectionCard(title = "快捷入口") {
            val running = state as? ContainerState.Running

            QuickEntry(
                icon = Icons.Rounded.CloudUpload,
                title = "备份与恢复",
                detail = "折腾坏了能回到之前的状态",
                onClick = onOpenBackup,
            )
            QuickEntry(
                icon = Icons.Rounded.HealthAndSafety,
                title = "自检",
                detail = "一眼看出哪步没通、该怎么修",
                onClick = onOpenDiagnostics,
            )
            QuickEntry(
                icon = Icons.Rounded.SmartToy,
                title = "对话模型",
                detail = "填 API Key，机器人才能开口说话",
                onClick = onOpenModels,
            )
            QuickEntry(
                icon = Icons.Rounded.Person,
                title = "人格设定",
                detail = "写清楚它是什么性格、怎么说话",
                onClick = onOpenPersona,
            )
            QuickEntry(
                icon = Icons.Rounded.Extension,
                title = "插件",
                detail = "表情包、人格、记忆、主动聊天都在这里装",
                onClick = onOpenPlugins,
            )
            QuickEntry(
                icon = Icons.Rounded.MenuBook,
                title = "知识库",
                detail = "上传资料，回答前先查再答",
                onClick = onOpenKnowledge,
            )
            QuickEntry(
                icon = Icons.Rounded.Language,
                title = "AstrBot 网页后台",
                detail = "装插件、设人格、看对话记录",
                onClick = {
                    if (running?.astrbotReady == true) {
                        onOpenWeb(6185)
                    } else {
                        Toast.makeText(context, "AstrBot 还在启动，请等状态变成「已就绪」", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            QuickEntry(
                icon = Icons.Rounded.QrCodeScanner,
                title = "QQ 扫码登录",
                detail = "二维码直接显示在 App 里，不用进网页",
                onClick = {
                    if (running?.napcatReady == true) {
                        onOpenQr()
                    } else {
                        Toast.makeText(context, "NapCat 还在启动，请稍等", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            QuickEntry(
                icon = Icons.Rounded.ChatBubbleOutline,
                title = "对话记录",
                detail = "看它到底回没回、回了什么",
                onClick = onOpenConversations,
            )
            QuickEntry(
                icon = Icons.Rounded.Terminal,
                title = "实时日志",
                detail = "排查安装与运行问题",
                onClick = onOpenConsole,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 使用指引
        SectionCard(title = "首次使用四步") {
            GuideStep("1", "启动容器", "点上面的「启动机器人」，等待两个端口都变为「已就绪」。")
            GuideStep("2", "扫码登录 QQ", "进入 NapCat 扫码页，用手机 QQ 扫码并确认登录。")
            GuideStep("3", "接上 AstrBot", "在 AstrBot 后台「机器人 → 通信接口」新增 aiocqhttp，地址 ws://127.0.0.1:6199/ws。")
            GuideStep("4", "配置大模型", "在「模型提供商」填入 API Key，之后群里 @机器人 即可对话。")

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRepair) {
                Text("环境异常？重新安装", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(14.dp))
        HintText(
            "提示：QQ 机器人使用非官方协议，存在被风控或封号的概率，请优先使用小号；" +
                "NapCat 与 QQ 客户端常驻后台会占用较多内存，低配机型可能被系统回收。",
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun QuickEntry(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuideStep(index: String, title: String, detail: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 3.dp),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )
        }
    }
}

private fun describe(state: ContainerState): Pair<String, Color> = when (state) {
    is ContainerState.NotInstalled -> "环境未安装" to Color(0xFFFFB454)
    is ContainerState.Stopped -> "已停止" to Color(0xFF9FB0AA)
    is ContainerState.Starting -> "正在启动…" to Color(0xFF3DDC97)
    is ContainerState.Stopping -> "正在停止…" to Color(0xFFFFB454)
    is ContainerState.Failed -> "运行异常" to Color(0xFFFF6B6B)
    is ContainerState.Running -> when {
        state.fullyReady -> "运行中" to Color(0xFF3DDC97)
        else -> "容器已起，服务启动中" to Color(0xFFFFB454)
    }
    is ContainerState.Restarting ->
        "异常退出，${state.delaySeconds}s 后自动重启（${state.attempt}/${state.maxAttempts}）" to
            Color(0xFFFFB454)
}

private fun startWithService(context: Context) {
    // 只走 Service：它会在启动容器前读取用户配置的镜像源并持有唤醒锁
    ContainerService.start(context, alsoStartContainer = true)
}
