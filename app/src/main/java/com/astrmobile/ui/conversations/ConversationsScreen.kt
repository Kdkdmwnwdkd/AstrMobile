package com.astrmobile.ui.conversations

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.ConversationSummary
import com.astrmobile.ui.components.EmptyState
import kotlinx.coroutines.launch
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.InlineLoading
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField

private const val PAGE_SIZE = 20

/**
 * 对话记录。
 *
 * 用途很具体：机器人到底回没回、回了什么、有没有报错，
 * 不用再进网页后台翻。
 */
@Composable
fun ConversationsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var conversations by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    // 正在重命名/待删除的会话。null 表示没有弹窗。
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        loading = true
        page = 1
        val cookie = viewModel.loginAstrBot().getOrElse {
            message = "${it.message}（可先在「人格设定」页填入改过的后台密码）"
            loading = false
            return@LaunchedEffect
        }
        viewModel.listConversations(cookie, page = 1).fold(
            onSuccess = {
                conversations = it
                hasMore = it.size >= PAGE_SIZE
                message = null
                loading = false
            },
            onFailure = {
                message = it.message
                loading = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "对话记录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (conversations.isEmpty()) "最近的消息" else "${conversations.size} 个会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = { reloadKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }

        Spacer(Modifier.height(Spacing.md))

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.error,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(Spacing.md))
        }

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                InlineLoading()
                Spacer(Modifier.width(Spacing.md))
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            }

            conversations.isEmpty() -> EmptyState(
                icon = Icons.Rounded.ChatBubbleOutline,
                title = "还没有对话记录",
                detail = "在群里 @ 机器人说句话，它回复之后这里就会出现。" +
                    "如果一直空着，先去「自检」看看消息通道通没通。",
            )

            else -> {
                conversations.forEach { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        expanded = expanded == conversation.cid,
                        onToggle = {
                            expanded = if (expanded == conversation.cid) null else conversation.cid
                        },
                        onRename = {
                            renameText = conversation.title
                            renaming = conversation
                        },
                        onDelete = { deleting = conversation },
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }

                if (hasMore) {
                    TextButton(
                        onClick = {
                            if (!loadingMore) {
                                loadingMore = true
                                scope.launch {
                                    val cookie = viewModel.loginAstrBot().getOrNull()
                                    if (cookie == null) {
                                        loadingMore = false
                                        return@launch
                                    }
                                    viewModel.listConversations(cookie, page + 1).fold(
                                        onSuccess = { list ->
                                            page += 1
                                            // 分页期间可能有新会话插到前面，按 cid 去重
                                            conversations =
                                                (conversations + list).distinctBy { it.cid }
                                            hasMore = list.size >= PAGE_SIZE
                                        },
                                        onFailure = { message = it.message },
                                    )
                                    loadingMore = false
                                }
                            }
                        },
                        enabled = !loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (loadingMore) {
                            InlineLoading(size = 14.dp)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("正在加载…")
                        } else {
                            Text("加载更多")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))
    }

    // ---------------------------------------------------------------- 重命名
    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        val title = renameText
                        renaming = null
                        scope.launch {
                            viewModel.renameConversation(target, title).fold(
                                onSuccess = {
                                    message = null
                                    reloadKey++
                                },
                                onFailure = { message = it.message ?: "重命名失败" },
                            )
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    // ---------------------------------------------------------------- 删除
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这个会话？") },
            text = {
                Text(
                    "会删掉这台设备上的对话历史，网页后台也看不到了。" +
                        "QQ 里的聊天记录不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        scope.launch {
                            viewModel.deleteConversation(target).fold(
                                onSuccess = {
                                    message = null
                                    // 删掉的如果是展开着的那个，顺手收起来
                                    if (expanded == target.cid) expanded = null
                                    reloadKey++
                                },
                                onFailure = { message = it.message ?: "删除失败" },
                            )
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val last = conversation.history.lastOrNull()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggle) {
                    Text(
                        if (expanded) "收起" else "${conversation.history.size} 条",
                        fontSize = 12.sp,
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            if (last != null) {
                Text(
                    text = "${roleLabel(last.role)}：${last.content}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = Spacing.md)) {
                    conversation.history.forEach { msg ->
                        val isUser = msg.role == "user"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = if (isUser) {
                                Arrangement.Start
                            } else {
                                Arrangement.End
                            },
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUser) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    },
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(0.92f),
                            ) {
                                Column(modifier = Modifier.padding(Spacing.md)) {
                                    Text(
                                        text = roleLabel(msg.role),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = if (isUser) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = msg.content,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 18.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun roleLabel(role: String): String = when (role) {
    "user" -> "对方"
    "assistant" -> "机器人"
    "system" -> "系统"
    else -> role
}
