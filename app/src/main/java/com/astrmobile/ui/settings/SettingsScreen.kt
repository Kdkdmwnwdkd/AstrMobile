package com.astrmobile.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var aptMirror by remember(settings.aptMirror) { mutableStateOf(settings.aptMirror) }
    var pipIndex by remember(settings.pipIndex) { mutableStateOf(settings.pipIndex) }
    var githubProxy by remember(settings.githubProxy) { mutableStateOf(settings.githubProxy) }
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "下载源（国内网络建议保持默认）") {
            OutlinedTextField(
                value = aptMirror,
                onValueChange = { aptMirror = it },
                label = { Text("APT 镜像（Ubuntu 软件包）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pipIndex,
                onValueChange = { pipIndex = it },
                label = { Text("PyPI 镜像（Python 依赖）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = githubProxy,
                onValueChange = { githubProxy = it },
                label = { Text("GitHub 加速前缀") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        viewModel.applySettings(
                            aptMirror = aptMirror,
                            pipIndex = pipIndex,
                            githubProxy = githubProxy,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("保存下载源")
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "运行行为") {
            SettingSwitch(
                title = "开机自动启动",
                detail = "重启手机后自动拉起机器人（需已完成初始化）",
                checked = settings.autoStartOnBoot,
                onCheckedChange = { scope.launch { viewModel.setAutoStart(it) } },
            )
            SettingSwitch(
                title = "保持唤醒",
                detail = "容器运行时持有唤醒锁，避免息屏被系统冻结",
                checked = settings.keepAwake,
                onCheckedChange = { scope.launch { viewModel.setKeepAwake(it) } },
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "后台保活") {
            HintText(
                "机器人要在息屏后继续工作，需要系统允许本应用后台运行。" +
                    "点下面的按钮，把本应用加入电池优化白名单。",
            )
            Spacer(Modifier.height(12.dp))
            val context = LocalContext.current
            Button(
                onClick = { requestIgnoreBatteryOptimization(context) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("允许后台运行")
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "维护") {
            HintText("清除环境会删除手机上的 Ubuntu 系统、AstrBot 与 NapCat 数据（含 QQ 登录态与对话记录），下次启动需要重新安装。")
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("清除并重装环境")
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "常见问题") {
            FaqItem(
                "安装卡在某个步骤",
                "点控制台右上角「复制」或「导出」把日志发出来。安装是可断点重试的：已解压的环境不会重复下载。",
            )
            FaqItem(
                "apt 或 pip 下载很慢",
                "到上面「下载源」换成更近的镜像（如中科大、阿里云），保存后重新安装。",
            )
            FaqItem(
                "NapCat 安装失败",
                "AstrBot 本身仍可正常使用，只是 QQ 接不进来。可先完成模型配置，稍后重试安装。",
            )
            FaqItem(
                "息屏后机器人掉线",
                "设置里保持「保持唤醒」开启，并在系统设置中允许本应用后台运行、关闭电池优化。",
            )
            FaqItem(
                "QQ 账号被风控",
                "个人号接入属于非官方协议，建议使用小号，避免高频发言。",
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "关于") {
            Text(
                text = "AstrMobile ${com.astrmobile.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            HintText(
                "Android 端的 AstrBot 启动器：proot 容器内运行完整 AstrBot 与 NapCat，" +
                    "原生界面负责安装、启停、保活与网页后台入口。",
            )
            Spacer(Modifier.height(8.dp))
            HintText(
                "AstrBot 采用 AGPL-3.0；NapCatQQ 与 QQ 客户端版权归各自作者所有。" +
                    "本项目仅做本地集成，不提供任何账号或协议服务。",
            )
        }

        Spacer(Modifier.height(30.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("确认清除环境？") },
            text = { Text("此操作不可撤销，会删除容器与全部机器人数据。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetEnvironment()
                        onBack()
                    },
                ) {
                    Text("确认清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = question,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = answer,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
    }
}

/** 拉起系统的电池优化白名单页面；部分 ROM 不支持直达就退回到列表页。 */
private fun requestIgnoreBatteryOptimization(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    val launched = runCatching { context.startActivity(direct) }.isSuccess
    if (!launched) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
