package com.astrmobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.ChatMessage
import com.astrmobile.MainViewModel
import com.astrmobile.core.ui.Radius
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.EmptyState
import com.astrmobile.ui.components.InlineLoading
import kotlinx.coroutines.launch

/**
 * 试聊。
 *
 * 存在的理由：配完人格、换完模型之后，现在唯一的验证方式是打开网页后台
 * 再聊两句。手机上多绕这一层很不合理——尤其是在群里试，还得打扰别人。
 *
 * 走的是 AstrBot 的 webchat 通道，和 QQ 会话完全隔离：这里说的话不会
 * 出现在群聊里，也不会污染真实对话记录。
 */
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val busy by viewModel.chatBusy.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 新消息进来、以及流式追加时都跟着滚。用最后一条的文本长度做 key，
    // 只在内容真的变了才触发，避免无谓的滚动动画。
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        scope.launch { viewModel.sendChat(text) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // ---------------------------------------------------------- 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "试聊",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                InlineLoading(size = 18.dp)
                Spacer(Modifier.width(Spacing.md))
            }
            IconButton(onClick = { viewModel.resetChat() }) {
                Icon(Icons.Rounded.AddComment, contentDescription = "新对话")
            }
        }

        // ---------------------------------------------------------- 消息
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    title = "这里不影响 QQ",
                    detail = "试聊走的是独立的 webchat 通道，群里收不到这些消息。" +
                        "配完人格或者换了模型，先在这儿说两句看看效果。",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.page,
                        end = Spacing.page,
                        top = Spacing.md,
                        bottom = Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(messages) { message -> MessageBubble(message) }
                }
            }
        }

        // ---------------------------------------------------------- 输入
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = Spacing.sm),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…") },
                shape = RoundedCornerShape(Radius.md),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(Spacing.sm))
            IconButton(
                onClick = { send() },
                enabled = input.isNotBlank() && !busy,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (input.isNotBlank() && !busy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(Radius.md),
                    ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = "发送",
                    tint = if (input.isNotBlank() && !busy) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * 一条消息。
 *
 * 用户的靠右、用主色；机器人的靠左、用中性面。这是聊天界面的通用语言，
 * 不需要额外解释谁在说话。
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.User

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            // 气泡最宽留 12%，留白本身就在提示"这是对方的"
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(
                topStart = Radius.lg,
                topEnd = Radius.lg,
                bottomStart = if (isUser) Radius.lg else Radius.sm,
                bottomEnd = if (isUser) Radius.sm else Radius.lg,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                if (message.thinking.isNotBlank()) {
                    Text(
                        text = message.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }

                val body = when {
                    message.error != null -> message.error
                    message.text.isBlank() && message.streaming -> "正在思考…"
                    message.streaming -> message.text + "▍"
                    else -> message.text
                }

                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else if (message.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
