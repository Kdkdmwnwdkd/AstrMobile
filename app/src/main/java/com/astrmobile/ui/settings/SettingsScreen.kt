package com.astrmobile.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import com.astrmobile.ui.components.SettingSwitch
import com.astrmobile.ui.components.shareDiagnostics
import kotlinx.coroutines.launch
import com.astrmobile.core.ui.Spacing
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.produceState
import com.astrmobile.core.container.DashboardCredentials
import com.astrmobile.ui.components.copyText
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextOverflow
import com.astrmobile.core.container.AlertJournal
import com.astrmobile.core.container.AlertRecord
import com.astrmobile.ui.components.StatusDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenBehavior: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var aptMirror by remember(settings.aptMirror) { mutableStateOf(settings.aptMirror) }
    var pipIndex by remember(settings.pipIndex) { mutableStateOf(settings.pipIndex) }
    var githubProxy by remember(settings.githubProxy) { mutableStateOf(settings.githubProxy) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }

    // 告警历史：通知弹过就没了，这里是留痕
    var alertRefresh by remember { mutableIntStateOf(0) }
    var showAllAlerts by remember { mutableStateOf(false) }
    val alerts by produceState(
        initialValue = emptyList<AlertRecord>(),
        key1 = alertRefresh,
    ) {
        value = withContext(Dispatchers.IO) { AlertJournal.read(context.filesDir) }
    }

    // 登录信息从首页搬过来的：它属于"要进网页后台时才会用到"的东西，
    // 放在首屏占了一整张卡，却几乎没人天天看。
    val credentials by produceState<DashboardCredentials?>(null) {
        value = viewModel.readDashboardCredentials()
    }
    val napcatToken by produceState<String?>(null) {
        value = viewModel.readNapCatToken()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
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

        Spacer(Modifier.height(Spacing.md))

        SectionCard(title = "下载源（国内网络建议保持默认）") {
            OutlinedTextField(
                value = aptMirror,
                onValueChange = { aptMirror = it },
                label = { Text("APT 镜像（Ubuntu 软件包）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = pipIndex,
                onValueChange = { pipIndex = it },
                label = { Text("PyPI 镜像（Python 依赖）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = githubProxy,
                onValueChange = { githubProxy = it },
                label = { Text("GitHub 加速前缀") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(Spacing.md))
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

        Spacer(Modifier.height(Spacing.lg))

        // 机器人自己的行为（唤醒、回复方式、白名单）单独一页。
        // 这几项和"App 怎么运行"是两回事，混在一起会让人找不到。
        SectionCard(title = "机器人行为", subtitle = "唤醒前缀、回复方式、白名单") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenBehavior)
                    .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "打开设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "群里怎么唤醒它、回不回 @、谁能用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

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
            SettingSwitch(
                title = "掉线提醒",
                detail = "QQ 掉线超过 2 分钟、或容器异常退出时发通知；正常启停不会打扰",
                checked = settings.offlineAlert,
                onCheckedChange = { scope.launch { viewModel.setOfflineAlert(it) } },
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        SectionCard(title = "后台保活") {
            HintText(
                "机器人要在息屏后继续工作，需要系统允许本应用后台运行。" +
                    "点下面的按钮，把本应用加入电池优化白名单。",
            )
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = { requestIgnoreBatteryOptimization(context) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("允许后台运行")
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        SectionCard(
            title = "最近异常",
            subtitle = "掉线、运行失败会记在这里",
            // 空的时候也传 trailing（内部判断），否则 lambda 的类型推断会丢掉
            // @Composable 注解，编译期报 "Composable invocations can only happen…"
            trailing = {
                if (alerts.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { AlertJournal.clear(context.filesDir) }
                                alertRefresh++
                            }
                        },
                    ) { Text("清空") }
                }
            },
        ) {
            if (alerts.isEmpty()) {
                Text(
                    text = "没有记录。机器人一直正常运行时这里就是空的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val shown = if (showAllAlerts) alerts else alerts.take(4)
                shown.forEach { record ->
                    AlertRow(record)
                }
                if (alerts.size > 4) {
                    TextButton(onClick = { showAllAlerts = !showAllAlerts }) {
                        Text(
                            if (showAllAlerts) "收起" else "查看全部 ${alerts.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        if (credentials != null || napcatToken != null) {
            SectionCard(
                title = "登录信息",
                subtitle = "进网页版时才需要",
                trailing = {
                    TextButton(onClick = { showCredentials = !showCredentials }) {
                        Text(if (showCredentials) "收起" else "展开")
                    }
                },
            ) {
                if (!showCredentials) {
                    Text(
                        text = "后台账号密码、NapCat 口令，点「展开」查看。",
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
                                    copyText(
                                        context,
                                        "AstrBot 登录信息",
                                        "${creds.username}\n${creds.password}",
                                    )
                                },
                            ) { Text("复制") }
                        }
                    }
                    napcatToken?.let { token ->
                        Spacer(Modifier.height(Spacing.sm))
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

            Spacer(Modifier.height(Spacing.lg))
        }

        SectionCard(title = "维护") {
            HintText(
                "遇到问题先导出诊断包：里面有环境信息、日志尾部和脱敏后的配置" +
                    "（API Key、口令会替换成 ***），可以直接发出来。",
            )
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = {
                    scope.launch {
                        viewModel.exportDiagnostics()
                            .onSuccess { shareDiagnostics(context, it) }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    "导出失败：${it.message ?: it.javaClass.simpleName}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("导出诊断包")
            }
            Spacer(Modifier.height(Spacing.lg))
            HintText("清除环境会删除手机上的 Ubuntu 系统、AstrBot 与 NapCat 数据（含 QQ 登录态与对话记录），下次启动需要重新安装。")
            Spacer(Modifier.height(Spacing.md))
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

        Spacer(Modifier.height(Spacing.lg))

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

        Spacer(Modifier.height(Spacing.lg))

        SectionCard(title = "关于") {
            Text(
                text = "AstrMobile ${com.astrmobile.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            HintText(
                "Android 端的 AstrBot 启动器：proot 容器内运行完整 AstrBot 与 NapCat，" +
                    "原生界面负责安装、启停、保活与网页后台入口。",
            )
            Spacer(Modifier.height(Spacing.sm))
            HintText(
                "AstrBot 采用 AGPL-3.0；NapCatQQ 与 QQ 客户端版权归各自作者所有。" +
                    "本项目仅做本地集成，不提供任何账号或协议服务。",
            )
        }

        Spacer(Modifier.height(Spacing.section))
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
private fun FaqItem(question: String, answer: String) {
    Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
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

@Composable
private fun AlertRow(record: AlertRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        // 用状态点而不是图标：一行里已经有文字了，再加图标会更挤
        Box(modifier = Modifier.padding(top = 5.dp)) {
            StatusDot(
                color = if (record.critical) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                size = 7,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${formatAlertTime(record.timestamp)} · ${record.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 今天的只显示时分，更早的带上日期——列表里绝大多数是最近几小时。 */
private fun formatAlertTime(millis: Long): String {
    val target = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val sameDay = target.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)

    val pattern = if (sameDay) "'今天' HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
