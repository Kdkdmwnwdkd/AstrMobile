package com.astrmobile.ui.onboarding

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.Bootstrapper
import com.astrmobile.service.ContainerService
import com.astrmobile.ui.components.KeyValueRow
import com.astrmobile.ui.components.LogView
import com.astrmobile.ui.components.SectionCard
import com.astrmobile.ui.components.StatusDot
import com.astrmobile.ui.components.copyLogsToClipboard
import com.astrmobile.ui.components.shareLogFile
import com.astrmobile.core.ui.Spacing

@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onFinished: () -> Unit,
) {
    val stage by viewModel.installStage.collectAsStateWithLifecycle()
    val logs by viewModel.installLogs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.section),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(MaterialTheme.colorScheme.primary, size = 12)
            Spacer(Modifier.fillMaxWidth(0f))
            Text(
                text = "SETUP / 首次初始化",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = "AstrMobile",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "在你的手机上运行完整的 AstrBot，并把 QQ 变成 AI 节点。全程不需要电脑、不需要命令行。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 21.sp,
        )

        Spacer(Modifier.height(Spacing.xxl))

        SectionCard(title = "将要完成的三件事") {
            StepRow("01", "下载并解压 Ubuntu 系统", "约 100MB，解压后占用约 1.5GB 空间")
            StepRow("02", "安装 AstrBot 与 NapCat", "需要联网下载，慢时可切换镜像源")
            StepRow("03", "扫码登录 QQ 并配置模型", "在网页后台完成，App 内一键进入")
        }

        Spacer(Modifier.height(Spacing.lg))

        DeviceCheckCard()

        Spacer(Modifier.height(Spacing.lg))

        when (val current = stage) {
            is Bootstrapper.Stage.Running -> {
                SectionCard(title = "安装进度") {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = current.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    LinearProgressIndicator(
                        progress = { current.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "${(current.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.height(Spacing.md))
                LogSection(lines = logs, context = context, height = 220)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "提示：安装期间请保持屏幕常亮并保持 App 在前台，避免系统中断下载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                TextButton(
                    onClick = { viewModel.cancelInstall() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "取消安装",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is Bootstrapper.Stage.Failed -> {
                SectionCard(title = "安装未完成") {
                    Text(
                        text = current.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = "可以重试：已下载的部分会从断点继续，已解压的环境不会重复解压。若反复失败，请换个网络，或用下面的方式导入系统包。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                LogSection(lines = logs, context = context, height = 200)
                Spacer(Modifier.height(Spacing.lg))
                Button(
                    onClick = { viewModel.install() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("重新安装", fontWeight = FontWeight.SemiBold)
                }
                ImportRootfsButton(viewModel)
            }

            is Bootstrapper.Stage.Done -> {
                SectionCard(title = "环境已就绪") {
                    Text(
                        text = "容器、AstrBot 与 NapCat 已安装完成。接下来进入控制台启动服务。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
                Button(
                    onClick = onFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("进入控制台", fontWeight = FontWeight.SemiBold)
                }
            }

            else -> {
                Button(
                    onClick = {
                        // 先拉起前台服务，保证安装期间不被系统回收
                        ContainerService.start(context, alsoStartContainer = false)
                        viewModel.install()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("开始安装", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(Spacing.md))
                ImportRootfsButton(viewModel)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "仅支持 Android 8.0 及以上的 arm64 设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(Spacing.section))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "AstrBot 为 AGPL-3.0 开源项目",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "v${com.astrmobile.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportRootfsButton(viewModel: MainViewModel) {
    // 用一个约束宽松的 MIME：不少文件管理器把 .tar.xz 报成 application/octet-stream，
    // 按精确类型过滤会让用户在列表里根本看不到自己刚传进来的文件。
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importRootfs(uri) }

    TextButton(
        onClick = { launcher.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Rounded.FileOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text("从文件导入系统包")
    }
}

@Composable
private fun StepRow(index: String, title: String, detail: String) {
    Row(modifier = Modifier.padding(vertical = Spacing.sm)) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCheckCard() {
    val context = LocalContext.current
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    val supported = abi == "arm64-v8a"
    val freeGb = context.filesDir.usableSpace / (1024.0 * 1024 * 1024)

    SectionCard(title = "设备检查") {
        KeyValueRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
        KeyValueRow("系统", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        KeyValueRow(
            label = "架构",
            value = abi,
            valueColor = if (supported) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        KeyValueRow("可用空间", "%.1f GB".format(freeGb))

        if (!supported) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "此设备架构不是 arm64-v8a，容器无法运行，请换用 arm64 手机。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LogSection(lines: List<String>, context: Context, height: Int = 220) {
    SectionCard(
        title = "安装日志（${lines.size} 行）",
        trailing = {
            Row {
                IconButton(
                    onClick = { copyLogsToClipboard(context, lines) },
                    enabled = lines.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "复制日志",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { shareLogFile(context) }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "导出日志",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    ) {
        LogView(lines = lines, modifier = Modifier.height(height.dp))
    }
}
