package com.astrmobile.ui.behavior

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.BehaviorSettings
import com.astrmobile.core.ui.Radius
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.ErrorState
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.InlineLoading
import com.astrmobile.ui.components.LoadingState
import com.astrmobile.ui.components.SectionCard
import com.astrmobile.ui.components.SettingSwitch
import kotlinx.coroutines.launch

/**
 * 机器人行为设置。
 *
 * 这几项原来只能进网页后台改，而它们恰好是「会反复调」的：群里太吵就加
 * 白名单、@ 太多就关掉引用、换群就改唤醒前缀。手机上开浏览器登录改 JSON
 * 是最别扭的一条路。
 *
 * 只放高频项。AstrBot 的配置有几百个字段，全搬上来只会变成一个比 WebUI
 * 更难用的表单。
 */
@Composable
fun BehaviorScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.behavior.collectAsStateWithLifecycle()
    val error by viewModel.behaviorError.collectAsStateWithLifecycle()
    val busy by viewModel.behaviorBusy.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadBehavior() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "机器人行为",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                InlineLoading(size = 18.dp)
                Spacer(Modifier.width(Spacing.sm))
            }
        }

        val current = settings
        Box(modifier = Modifier.weight(1f)) {
            when {
                current == null && error != null -> ErrorState(
                    message = error.orEmpty(),
                    onRetry = { scope.launch { viewModel.loadBehavior() } },
                )

                current == null -> LoadingState(text = "读取配置…")

                current != null -> BehaviorForm(
                    initial = current,
                    busy = busy,
                    onSave = { updated ->
                        viewModel.saveBehavior(updated).fold(
                            onSuccess = { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { failure ->
                                Toast.makeText(
                                    context,
                                    failure.message ?: "保存失败",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun BehaviorForm(
    initial: BehaviorSettings,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onSave: suspend (BehaviorSettings) -> Unit,
) {
    // 用 initial 作为 key：重新拉取配置后表单要跟着重置，否则用户看到的是
    // 上次的输入框内容，分不清保存成功没有。
    var wakePrefixes by remember(initial) { mutableStateOf(initial.wakePrefixes.joinToString(", ")) }
    var replyPrefix by remember(initial) { mutableStateOf(initial.replyPrefix) }
    var replyWithMention by remember(initial) { mutableStateOf(initial.replyWithMention) }
    var replyWithQuote by remember(initial) { mutableStateOf(initial.replyWithQuote) }
    var friendNeedsWake by remember(initial) { mutableStateOf(initial.friendNeedsWake) }
    var uniqueSession by remember(initial) { mutableStateOf(initial.uniqueSession) }
    var whiteListEnabled by remember(initial) { mutableStateOf(initial.whiteListEnabled) }
    var whiteList by remember(initial) { mutableStateOf(initial.whiteList.joinToString("\n")) }

    val scope = rememberCoroutineScope()

    fun collect() = BehaviorSettings(
        wakePrefixes = wakePrefixes.split(",", "，")
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        replyPrefix = replyPrefix,
        replyWithMention = replyWithMention,
        replyWithQuote = replyWithQuote,
        friendNeedsWake = friendNeedsWake,
        uniqueSession = uniqueSession,
        whiteListEnabled = whiteListEnabled,
        whiteList = whiteList.split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() },
    )

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionCard(title = "唤醒方式", subtitle = "决定机器人什么时候理你") {
                OutlinedTextField(
                    value = wakePrefixes,
                    onValueChange = { wakePrefixes = it },
                    label = { Text("唤醒前缀") },
                    placeholder = { Text("/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.md),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                HintText(
                    if (wakePrefixes.isBlank()) {
                        "留空了：群里每一条消息都会触发回复。大多时候这不是你想要的。"
                    } else {
                        "多个前缀用逗号分隔。群里需要带上它才会回应，比如「/今天天气」。"
                    },
                )

                Spacer(Modifier.height(Spacing.md))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                SettingSwitch(
                    title = "私聊也要唤醒",
                    detail = "关掉之后，私聊里发什么都直接回复",
                    checked = friendNeedsWake,
                    onCheckedChange = { friendNeedsWake = it },
                )
            }

            SectionCard(title = "回复方式", subtitle = "决定回复长什么样") {
                SettingSwitch(
                    title = "回复时 @ 对方",
                    detail = "群里能看清在回谁，但也更容易被注意到",
                    checked = replyWithMention,
                    onCheckedChange = { replyWithMention = it },
                )
                SettingSwitch(
                    title = "引用原消息",
                    detail = "引用对方那条消息再回，上下文更清楚，消息也更长",
                    checked = replyWithQuote,
                    onCheckedChange = { replyWithQuote = it },
                )

                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = replyPrefix,
                    onValueChange = { replyPrefix = it },
                    label = { Text("回复前缀") },
                    placeholder = { Text("留空则不加") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.md),
                )
                Spacer(Modifier.height(Spacing.sm))
                HintText("加在每条回复开头，比如「（小声）」这种。")
            }

            SectionCard(title = "会话", subtitle = "上下文的隔离方式") {
                SettingSwitch(
                    title = "每个群独立上下文",
                    detail = "关掉之后所有人共用一份记忆，容易串台",
                    checked = uniqueSession,
                    onCheckedChange = { uniqueSession = it },
                )
            }

            SectionCard(
                title = "白名单",
                subtitle = "限制哪些群、哪些人能用",
            ) {
                SettingSwitch(
                    title = "启用白名单",
                    detail = "关掉则任何群、任何人都能用，包括陌生人拉它进的新群",
                    checked = whiteListEnabled,
                    onCheckedChange = { whiteListEnabled = it },
                )

                if (whiteListEnabled) {
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = whiteList,
                        onValueChange = { whiteList = it },
                        label = { Text("允许的群号 / QQ 号") },
                        placeholder = { Text("一行一个") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.md),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    HintText(
                        if (whiteList.isBlank()) {
                            "列表是空的。开了白名单但一个号都没填，机器人不会回应任何人。"
                        } else {
                            "只填数字。群号在群资料里，QQ 号在个人资料里。"
                        },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = Spacing.md),
        ) {
            Button(
                onClick = { scope.launch { onSave(collect()) } },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(Radius.md),
            ) {
                if (busy) InlineLoading(color = MaterialTheme.colorScheme.onPrimary)
                else Text("保存")
            }
        }
    }
}
