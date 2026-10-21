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
import androidx.compose.material3.CircularProgressIndicator
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
import com.astrmobile.ui.components.HintText

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

    LaunchedEffect(reloadKey) {
        loading = true
        val cookie = viewModel.loginAstrBot().getOrElse {
            message = "${it.message}（可先在「人格设定」页填入改过的后台密码）"
            loading = false
            return@LaunchedEffect
        }
        viewModel.listConversations(cookie).fold(
            onSuccess = {
                conversations = it
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
            .padding(horizontal = 20.dp, vertical = 12.dp)
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

        Spacer(Modifier.height(12.dp))

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.error,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
        }

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            }

            conversations.isEmpty() -> {
                HintText(
                    "还没有对话记录。\n" +
                        "在群里 @ 机器人说句话，它回复之后这里就会出现。\n" +
                        "如果一直空着，先去「自检」看看消息通道通没通。",
                )
            }

            else -> conversations.forEach { conversation ->
                ConversationCard(
                    conversation = conversation,
                    expanded = expanded == conversation.cid,
                    onToggle = {
                        expanded = if (expanded == conversation.cid) null else conversation.cid
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val last = conversation.history.lastOrNull()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
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
                Column(modifier = Modifier.padding(top = 10.dp)) {
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
                                Column(modifier = Modifier.padding(10.dp)) {
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
