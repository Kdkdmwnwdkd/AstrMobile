package com.astrmobile.ui.plugins

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.EmptyState
import com.astrmobile.ui.components.InlineLoading

/**
 * 插件市场。
 *
 * 表情包、情感记忆、主动聊天这些都是插件，不是 App 内建功能。
 * 电脑版在后台的「插件管理」里装，这里把同一件事搬进 App。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var detail by remember { mutableStateOf<MarketPlugin?>(null) }
    var limit by remember { mutableIntStateOf(80) }

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

    fun startInstall(plugin: MarketPlugin) {
        if (installingId != null) return
        installingId = plugin.id
        message = null
        needRestart = false
        scope.launch {
            val result = viewModel.installPlugin(plugin) { line -> progressText = line }
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
    }

    LaunchedEffect(Unit) {
        refreshInstalled()
        viewModel.loadPluginMarket().fold(
            onSuccess = { market = it },
            onFailure = { loadError = it.message ?: "加载失败" },
        )
    }

    val filtered = remember(market, query, limit) {
        val all = market.orEmpty()
        val q = query.trim()
        when {
            q.isEmpty() -> all.take(limit)
            else -> all.filter { item ->
                item.displayName.contains(q, ignoreCase = true) ||
                    item.id.contains(q, ignoreCase = true) ||
                    item.description.contains(q, ignoreCase = true) ||
                    item.tags.any { it.contains(q, ignoreCase = true) }
            }.take(limit)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------------------------------------------------------- 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.sm),
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
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(horizontal = Spacing.lg),
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

        Spacer(Modifier.height(Spacing.xs))

        // ---------------------------------------------------------- 列表
        Box(modifier = Modifier.weight(1f)) {
            when {
                tab == 0 && market == null && loadError == null -> Loading()
                tab == 0 && filtered.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "没有匹配的插件",
                    detail = "换个关键词试试",
                )
                tab == 1 && installed.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Extension,
                    title = "还没有装插件",
                    detail = "去市场里找找表情包、人格、记忆。",
                )
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (tab == 0) {
                        items(filtered, key = { it.id }) { plugin ->
                            val alreadyInstalled = installed.any { it.dirName == repoDir(plugin.repo) }
                            MarketRow(
                                plugin = plugin,
                                installed = alreadyInstalled,
                                installing = installingId == plugin.id,
                                onInstall = { startInstall(plugin) },
                                onOpenDetail = { detail = plugin },
                            )
                        }
                        val total = market?.size ?: 0
                        if (filtered.size >= limit && total > filtered.size) {
                            item(key = "load-more") {
                                TextButton(
                                    onClick = { limit += 80 },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("加载更多（还有 ${total - filtered.size} 个）")
                                }
                            }
                        }
                    } else {
                        if (failed.isNotEmpty()) {
                            item(key = "failed-summary") {
                                Text(
                                    text = "有 ${failed.size} 个插件没加载起来，下面标红的会写明原因",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
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
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            lineHeight = 16.sp,
        )
    }

    // ---------------------------------------------------------- 插件详情
    // 列表里的简介只有两行，完整的描述、作者、仓库放到弹层里看
    detail?.let { plugin ->
        val alreadyInstalled = installed.any { it.dirName == repoDir(plugin.repo) }
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.section),
            ) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = buildString {
                        append("★ ${plugin.stars}")
                        if (plugin.version.isNotBlank()) append(" · v${plugin.version}")
                        if (plugin.author.isNotBlank()) append(" · ${plugin.author}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = plugin.description.ifBlank { "这个插件没有写简介。" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 22.sp,
                )

                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = "仓库：${plugin.repo}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                )
                if (plugin.tags.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "标签：${plugin.tags.joinToString(" / ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(Spacing.xl))
                if (alreadyInstalled) {
                    OutlinedButton(onClick = { detail = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("已安装，去看「已安装」")
                    }
                } else {
                    Button(
                        onClick = {
                            detail = null
                            startInstall(plugin)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = installingId == null,
                    ) {
                        Text("安装这个插件")
                    }
                }
            }
        }
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
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
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
            InlineLoading(size = 24.dp)
            Spacer(Modifier.height(Spacing.md))
            Text(
                "正在拉取插件列表…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun MarketRow(
    plugin: MarketPlugin,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
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
            Spacer(Modifier.width(Spacing.md))
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
                Spacer(Modifier.height(Spacing.xs))
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
            Spacer(Modifier.width(Spacing.sm))
            when {
                installing -> InlineLoading(size = 18.dp)
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
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
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
