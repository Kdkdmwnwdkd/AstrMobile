package com.astrmobile.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.astrmobile.ui.components.LogView
import com.astrmobile.ui.components.SectionCard
import com.astrmobile.ui.components.StatusDot

@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onFinished: () -> Unit,
) {
    val stage by viewModel.installStage.collectAsStateWithLifecycle()
    val logs by viewModel.installLogs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
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

        Spacer(Modifier.height(18.dp))
        Text(
            text = "AstrMobile",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在你的手机上运行完整的 AstrBot，并把 QQ 变成 AI 节点。全程不需要电脑、不需要命令行。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 21.sp,
        )

        Spacer(Modifier.height(24.dp))

        SectionCard(title = "将要完成的三件事") {
            StepRow("01", "搭建 Ubuntu 容器环境", "约需 3-8 分钟，占用 400MB 空间")
            StepRow("02", "安装 AstrBot 与 NapCat", "需要联网下载，慢时可切换镜像源")
            StepRow("03", "扫码登录 QQ 并配置模型", "在网页后台完成，App 内一键进入")
        }

        Spacer(Modifier.height(16.dp))

        when (val current = stage) {
            is Bootstrapper.Stage.Running -> {
                SectionCard(title = "安装进度") {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = current.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { current.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${(current.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.height(12.dp))
                LogView(lines = logs, modifier = Modifier.height(220.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "提示：安装期间请保持屏幕常亮并保持 App 在前台，避免系统中断下载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is Bootstrapper.Stage.Failed -> {
                SectionCard(title = "安装未完成") {
                    Text(
                        text = current.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "可以重试：已解压的环境不会重复下载，只会继续未完成的步骤。若反复失败，请到「设置」切换镜像源后重试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LogView(lines = logs, modifier = Modifier.height(200.dp))
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.install() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("重新安装", fontWeight = FontWeight.SemiBold)
                }
            }

            is Bootstrapper.Stage.Done -> {
                SectionCard(title = "环境已就绪") {
                    Text(
                        text = "容器、AstrBot 与 NapCat 已安装完成。接下来进入控制台启动服务。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(14.dp))
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
                val context = LocalContext.current
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
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "仅支持 Android 8.0 及以上的 arm64 设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(32.dp))
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
private fun StepRow(index: String, title: String, detail: String) {
    Row(modifier = Modifier.padding(vertical = 7.dp)) {
        Text(
            text = index,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
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
