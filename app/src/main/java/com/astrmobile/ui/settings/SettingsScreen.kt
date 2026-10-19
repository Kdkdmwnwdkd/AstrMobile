package com.astrmobile.ui.settings

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
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
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "SETTINGS / 设置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
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
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
