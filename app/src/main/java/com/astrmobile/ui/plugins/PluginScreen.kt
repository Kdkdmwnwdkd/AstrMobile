package com.astrmobile.ui.plugins

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.InstalledPlugin
import com.astrmobile.core.container.MarketPlugin
import kotlinx.coroutines.launch

/**
 * 插件市场。
 *
 * 表情包、情感记忆、主动聊天这些都是插件，不是 App 内建功能。
 * 电脑版在后台的「插件管理」里装，这里把同一件事搬进 App。
 */
@Composable
fun PluginScreen(
    viewModel: MainViewModel,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onOpenConfig: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var market by remember { mutableStateOf<List<MarketPlugin>?>(null) }
    var installed by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var failed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var progressText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var needRestart by remember { mutableStateOf(false) }

    // 装完/删完插件，用户多半直接返回，不会去点"重启"。
    // 离开页面时替他重启，避免"装了但没生效"。连续装多个只会重启一次。
    DisposableEffect(Unit) {
        onDispose {
            if (needRestart) onRestart()
        }
    }

    fun refreshInstalled() {
        scope.launch {
            installed = viewModel.listInstalledPlugins()
            // 目录里有，不代表加载成功；失败的要从运行时接口单独问
            failed = viewModel.loadFailedPlugins()
        }
    }

    LaunchedEffect(Unit) {
        refreshInstalled()
        viewModel.loadPluginMarket().fold(
            onSuccess = { market = it },
            onFailure = { loadError = it.message ?: "加载失败" },
        )
    }

    val filtered = remember(market, query) {
        val all = market.orEmpty()
        val q = query.trim()
        when {
            q.isEmpty() -> all.take(80)
            else -> all.filter { item ->
                item.displayName.contains(q, ignoreCase = true) ||
                    item.id.contains(q, ignoreCase = true) ||
                    item.description.contains(q, ignoreCase = true) ||
                    item.tags.any { it.contains(q, ignoreCase = true) }
            }.take(80)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------------------------------------------------------- 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "插件",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = market?.let { "市场 ${it.size} 个 · 已装 ${installed.size} 个" }
                        ?: "已装 ${installed.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    loadError = null
                    scope.launch {
                        viewModel.loadPluginMarket(force = true).fold(
                            onSuccess = { market = it },
                            onFailure = { loadError = it.message ?: "刷新失败" },
                        )
                    }
                },
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }

        // ---------------------------------------------------------- 搜索 + 切换
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜表情、记忆、人格…", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            FilterChip(
                selected = tab == 0,
                onClick = { tab = 0 },
                label = { Text("市场", fontSize = 13.sp) },
            )
            FilterChip(
                selected = tab == 1,
                onClick = { tab = 1 },
                label = { Text("已安装 ${installed.size}", fontSize = 13.sp) },
            )
        }

        // ---------------------------------------------------------- 提示条
        // 用局部 val 接一下：state 变量无法智能转换，直接 !! 在并发更新时有风险
        val bannerText = message
        val bannerError = loadError
        when {
            installingId != null -> Banner(progressText.ifBlank { "正在安装…" }, isError = false)
            bannerText != null -> Banner(
                bannerText,
                isError = isError,
                action = if (needRestart) "立即重启" else null,
                onAction = {
                    needRestart = false
                    onRestart()
                },
            )
            bannerError != null -> Banner(bannerError, isError = true)
        }

        Spacer(Modifier.height(4.dp))

        // ---------------------------------------------------------- 列表
        Box(modifier = Modifier.weight(1f)) {
            when {
                tab == 0 && market == null && loadError == null -> Loading()
                tab == 0 && filtered.isEmpty() -> Empty("没有匹配的插件，换个关键词试试")
                tab == 1 && installed.isEmpty() -> Empty("还没有装插件。去市场里找找表情包、人格、记忆。")
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tab == 0) {
                        items(filtered, key = { it.id }) { plugin ->
                            val alreadyInstalled = installed.any { it.dirName == repoDir(plugin.repo) }
                            MarketRow(
                                plugin = plugin,
                                installed = alreadyInstalled,
                                installing = installingId == plugin.id,
                                onInstall = {
                                    if (installingId != null) return@MarketRow
                                    installingId = plugin.id
                                    message = null
                                    needRestart = false
                                    scope.launch {
                                        val result = viewModel.installPlugin(plugin) { line ->
                                            progressText = line
                                        }
                                        installingId = null
                                        progressText = ""
                                        result.fold(
                                            onSuccess = {
                                                isError = false
                                                message = "已安装 ${plugin.displayName}"
                                                needRestart = true
                                                refreshInstalled()
                                            },
                                            onFailure = { error ->
                                                isError = true
                                                message = error.message ?: "安装失败"
                                                needRestart = false
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        if (failed.isNotEmpty()) {
                            item(key = "failed-summary") {
                                Text(
                                    text = "有 ${failed.size} 个插件没加载起来，下面标红的会写明原因",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                )
                            }
                        }
                        items(installed, key = { it.dirName }) { plugin ->
                            InstalledRow(
                                plugin = plugin,
                                error = failureFor(plugin.dirName, failed),
                                onConfigure = { onOpenConfig(plugin.dirName) },
                                onUninstall = {
                                    scope.launch {
                                        viewModel.uninstallPlugin(plugin.dirName).fold(
                                            onSuccess = {
                                                isError = false
                                                message = "已删除 ${plugin.displayName}"
                                                needRestart = true
                                                refreshInstalled()
                                            },
                                            onFailure = { error ->
                                                isError = true
                                                message = error.message ?: "删除失败"
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "市场里的插件由社区维护，能不能用取决于它是否跟得上你的 AstrBot 版本。" +
                "装完回「已安装」看有没有红字：没红字就是加载成功了；" +
                "点「配置」空白不代表坏了，很多插件本来就没有配置界面。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            lineHeight = 16.sp,
        )
    }
}

// ---------------------------------------------------------------- 组件

@Composable
private fun Banner(
    text: String,
    isError: Boolean,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.weight(1f),
            lineHeight = 17.sp,
        )
        action?.let {
            TextButton(onClick = onAction) { Text(it, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                "正在拉取插件列表…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun MarketRow(
    plugin: MarketPlugin,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 0.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (plugin.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("★ ${plugin.stars}")
                        if (plugin.version.isNotBlank()) append(" · ${plugin.version}")
                        if (plugin.author.isNotBlank()) append(" · ${plugin.author}")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                installing -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                installed -> OutlinedButton(onClick = {}, enabled = false) {
                    Text("已装", fontSize = 12.sp)
                }
                else -> Button(onClick = onInstall) { Text("安装", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun InstalledRow(
    plugin: InstalledPlugin,
    error: String? = null,
    onConfigure: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(plugin.dirName)
                        if (plugin.version.isNotBlank()) append(" · ${plugin.version}")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "加载失败：${error.take(120)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onConfigure) {
                Text("配置", fontSize = 12.sp)
            }
            TextButton(onClick = onUninstall) {
                Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

/** 从仓库地址推出安装目录名（GitHub 上的仓库名）。 */
private fun repoDir(repo: String): String =
    repo.trim().removeSuffix("/").removeSuffix(".git").substringAfterLast('/')

/**
 * 把运行时报告的失败信息对到某个目录上。
 *
 * AstrBot 的 key 有时是插件名、有时是目录名，两者不一定完全一致，所以先精确比、再模糊比。
 */
private fun failureFor(dirName: String, failed: Map<String, String>): String? {
    failed[dirName]?.let { return it }
    return failed.entries.firstOrNull { (key, _) ->
        key.equals(dirName, ignoreCase = true) ||
            key.contains(dirName, ignoreCase = true) ||
            dirName.contains(key, ignoreCase = true)
    }?.value?.takeIf { it.isNotBlank() }
}
