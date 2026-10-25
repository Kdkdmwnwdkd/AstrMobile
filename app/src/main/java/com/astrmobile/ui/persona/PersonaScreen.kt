package com.astrmobile.ui.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.PersonaItem
import com.astrmobile.core.container.PersonaTemplate
import com.astrmobile.core.container.PersonaTemplates
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.InlineLoading
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

/** 编辑中的一轮示例对话。 */
private data class DialogRow(val user: String, val assistant: String)

/**
 * 人格设定。
 *
 * 这是「像人」最直接的一刀：一段 system prompt 决定它怎么说话。
 * 数据存在 AstrBot 的库里，所以要过它的接口，而不是改文件。
 *
 * 这一版的重点是**示例对话**。AstrBot 的人格除了人设，还能存一组
 * 「对方说 / 它答」的成对文本，作为 few-shot 注入上下文。实测里
 * 「你说话很幽默」换来的是一段四平八稳的回复，而两句真实的拌嘴
 * 能让模型自己找到那个调子——所以这里把示例对话做成了一等公民。
 */
@Composable
fun PersonaScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRestart: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var cookie by remember { mutableStateOf<String?>(null) }
    var personas by remember { mutableStateOf<List<PersonaItem>>(emptyList()) }
    var activeId by remember { mutableStateOf("default") }

    // null = 正在新建一条还没保存的人格
    var editingId by remember { mutableStateOf<String?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var draftId by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var draftFallback by remember { mutableStateOf("") }
    val dialogRows = remember { mutableStateListOf<DialogRow>() }

    var password by remember { mutableStateOf("astrbot") }
    var needPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var pendingRestart by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 记住当前编辑的对象，切换/刷新时用来判断是否有未保存改动
    val editedItem = remember { mutableStateOf<PersonaItem?>(null) }

    // 人格只在 AstrBot 启动时加载一次，保存进库后必须重启容器才生效。
    // 用户保存完通常直接返回，所以离开页面时替他重启，避免"保存了但没变"。
    DisposableEffect(Unit) {
        onDispose {
            if (pendingRestart) onRestart()
        }
    }

    fun loadInto(item: PersonaItem?) {
        editedItem.value = item
        editingId = item?.id
        isNew = false
        draftId = item?.id.orEmpty()
        draft = item?.systemPrompt.orEmpty()
        draftFallback = item?.customErrorMessage.orEmpty()
        dialogRows.clear()
        item?.beginDialogs?.chunked(2)?.forEach { pair ->
            dialogRows.add(DialogRow(pair.getOrNull(0).orEmpty(), pair.getOrNull(1).orEmpty()))
        }
        message = null
        dirty = false
        saved = false
    }

    suspend fun ensureSession(force: Boolean = false): String? {
        if (!force) cookie?.let { return it }
        return viewModel.loginAstrBot(password).fold(
            onSuccess = {
                viewModel.setAstrBotPassword(password)
                cookie = it
                needPassword = false
                it
            },
            onFailure = {
                needPassword = true
                message = it.message
                isError = true
                null
            },
        )
    }

    suspend fun reload() {
        val session = ensureSession() ?: run {
            loading = false
            return
        }
        activeId = viewModel.defaultPersonaId()
        viewModel.listPersonas(session).fold(
            onSuccess = { list ->
                personas = list
                // 优先选中「真正在生效的那个」：AstrBot 默认人格 id 一般是 default，
                // 但用户在后台换过就不是了。改错对象是"保存了没变化"最常见的原因。
                if (editedItem.value == null && !isNew) {
                    val target = list.firstOrNull { it.id == activeId }
                        ?: list.firstOrNull { it.id == "default" }
                        ?: list.firstOrNull()
                    loadInto(target)
                }
                loading = false
            },
            onFailure = {
                message = it.message
                isError = true
                loading = false
            },
        )
    }

    LaunchedEffect(Unit) { reload() }

    fun startNew() {
        editedItem.value = null
        editingId = null
        isNew = true
        val used = personas.map { it.id }.toSet()
        var index = 1
        while (used.contains("persona$index")) index++
        draftId = "persona$index"
        draft = ""
        draftFallback = ""
        dialogRows.clear()
        dialogRows.add(DialogRow("", ""))
        message = null
        isError = false
        dirty = true
        saved = false
    }

    fun applyTemplate(template: PersonaTemplate) {
        draft = template.prompt
        draftFallback = ""
        dialogRows.clear()
        template.dialogs.chunked(2).forEach { pair ->
            dialogRows.add(DialogRow(pair.getOrNull(0).orEmpty(), pair.getOrNull(1).orEmpty()))
        }
        dirty = true
        saved = false
        message = "已套用模板「${template.name}」，可以按自己的习惯改。"
        isError = false
    }

    fun guardSwitch(action: () -> Unit) {
        if (dirty) pendingSwitch = action else action()
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
                    text = "人格设定",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "决定机器人怎么说话、是什么性格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineLoading()
                Spacer(Modifier.size(10.dp))
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        // ---------------------------------------------------------- 密码兜底
        if (needPassword) {
            SectionCard(title = "需要后台密码") {
                HintText("改过 AstrBot 后台密码的话，在这里填一次。默认是 astrbot。")
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("后台密码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = {
                        loading = true
                        message = null
                        scope.launch {
                            ensureSession(force = true)
                            reload()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("连接") }
            }
            Spacer(Modifier.height(Spacing.lg))
        }

        // ---------------------------------------------------------- 人格清单
        SectionCard(
            title = "人格清单",
            subtitle = "当前生效：$activeId",
            trailing = {
                IconButton(onClick = { guardSwitch { startNew() } }) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建人格")
                }
            },
        ) {
            if (personas.isEmpty()) {
                HintText("还没有人格，点右上角「+」新建一条。")
            } else {
                personas.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = Spacing.xs),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (item.id == activeId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.id,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (item.id == activeId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = buildString {
                                    append("${item.systemPrompt.length} 字")
                                    if (item.dialogRounds > 0) append(" · ${item.dialogRounds} 轮示例")
                                    if (item.id == activeId) append(" · 生效中")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                guardSwitch { loadInto(item) }
                            },
                        ) {
                            Text(if (editingId == item.id) "编辑中" else "编辑")
                        }
                        if (!item.isBuiltinDefault) {
                            IconButton(onClick = { confirmDelete = item.id }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "删除 ${item.id}",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // ---------------------------------------------------------- 编辑
        SectionCard(
            title = when {
                isNew -> "新建人格"
                editingId == null -> "设定人设"
                else -> "编辑「$editingId」"
            },
        ) {
            if (isNew) {
                OutlinedTextField(
                    value = draftId,
                    onValueChange = {
                        draftId = it
                        dirty = true
                    },
                    label = { Text("人格 ID") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "字母、数字、下划线或横线，用来在后台区分不同人格",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                )
                Spacer(Modifier.height(Spacing.md))
            }

            HintText(
                "先说清它是谁、什么性格、怎么说话。写具体比写抽象有用：" +
                    "「你是一个话少但嘴毒的室友，一次只说一两句」比「你很有趣」强得多。",
            )
            Spacer(Modifier.height(Spacing.md))

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    dirty = true
                    saved = false
                },
                label = { Text("人设（system prompt）") },
                placeholder = { Text("你是一个……") },
                minLines = 6,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                supportingText = {
                    Text(
                        text = "${draft.length} 字",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )

            Spacer(Modifier.height(Spacing.md))

            DialogEditor(
                rows = dialogRows,
                onDirty = {
                    dirty = true
                    saved = false
                },
            )

            Spacer(Modifier.height(Spacing.md))

            Text(
                text = "兜底回复（可选）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = draftFallback,
                onValueChange = {
                    draftFallback = it
                    dirty = true
                    saved = false
                },
                placeholder = { Text("模型调用失败时回给用户的话，比如「等下再说，我这边卡了」") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )

            Spacer(Modifier.height(Spacing.md))

            Text(
                text = "套用模板",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            // 两两一行：模板名字长度不一，塞满一行在窄屏上会被挤成两三个字
            PersonaTemplates.all.chunked(2).forEach { rowTemplates ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowTemplates.forEach { template ->
                        AssistChip(
                            onClick = { applyTemplate(template) },
                            label = { Text(template.name, fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
            }

            Spacer(Modifier.height(Spacing.lg))

            Button(
                onClick = {
                    val targetId = if (isNew) PersonaTemplates.sanitizeId(draftId) else editingId
                    val invalid = dialogRows.indexOfFirst { row ->
                        (row.user.isBlank() && row.assistant.isNotBlank()) ||
                            (row.user.isNotBlank() && row.assistant.isBlank())
                    }
                    when {
                        targetId.isNullOrBlank() -> {
                            isError = true
                            message = "人格 ID 不能为空"
                        }

                        !PersonaTemplates.isValidId(targetId) -> {
                            isError = true
                            message = "人格 ID 只能用字母、数字、下划线或横线"
                        }

                        isNew && personas.any { it.id == targetId } -> {
                            isError = true
                            message = "已经有一个叫「$targetId」的人格了，换一个 ID"
                        }

                        draft.isBlank() -> {
                            isError = true
                            message = "人设不能为空"
                        }

                        invalid >= 0 -> {
                            isError = true
                            message = "第 ${invalid + 1} 轮示例对话只填了一半。要么补齐，要么整轮删掉。"
                        }

                        else -> {
                            busy = true
                            message = null
                            saved = false
                            scope.launch {
                                val session = ensureSession() ?: run {
                                    busy = false
                                    return@launch
                                }
                                val dialogs = dialogRows.flatMap { row ->
                                    listOf(row.user.trim(), row.assistant.trim())
                                }.filter { it.isNotEmpty() }

                                val result = viewModel.savePersona(
                                    cookie = session,
                                    id = targetId,
                                    prompt = draft,
                                    beginDialogs = dialogs,
                                    customErrorMessage = draftFallback,
                                    exists = !isNew,
                                )
                                busy = false
                                result.fold(
                                    onSuccess = {
                                        isError = false
                                        saved = true
                                        dirty = false
                                        pendingRestart = true
                                        message = "已保存。返回上一页会自动重启容器，约 30 秒后生效。"
                                        isNew = false
                                        cookie = null
                                        editedItem.value = null
                                        loading = true
                                        reload()
                                    },
                                    onFailure = { error ->
                                        isError = true
                                        message = error.message ?: "保存失败"
                                    },
                                )
                            }
                        }
                    }
                },
                enabled = !busy && draft.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) {
                    InlineLoading(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (isNew) "创建人格" else "保存人格")
                }
            }

            message?.let { text ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    lineHeight = 17.sp,
                )
            }

            if (saved) {
                Button(
                    onClick = {
                        pendingRestart = false
                        onRestart()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("立即重启生效（不点就返回时自动重启）")
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        SectionCard(title = "改完还是不像人？") {
            HintText(
                "人格只管「怎么说话」。如果它回复得很短很客气、或者像客服，" +
                    "问题通常在别处：\n" +
                    "· 模型太保守：换一个更敢说的，或者把温度调高一点\n" +
                    "· 上下文里塞了太多提示词：插件装太多会稀释人格\n" +
                    "· 缺示例：示例对话比形容词管用，先补两轮\n" +
                    "要它主动找人聊、记得以前聊过什么，去「插件」里装主动消息和长期记忆。",
            )
        }

        Spacer(Modifier.height(Spacing.lg))
    }

    // ------------------------------------------------------------ 确认框
    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除人格「$target」？") },
            text = {
                Text(
                    "删掉之后这条人设和示例对话都没了，AstrBot 后台也看不到。" +
                        "如果它正在被某个群使用，那个群会退回默认人格。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        scope.launch {
                            val session = ensureSession() ?: return@launch
                            viewModel.deletePersona(session, target).fold(
                                onSuccess = {
                                    pendingRestart = true
                                    message = "已删除，重启后生效。"
                                    isError = false
                                    if (editingId == target) {
                                        editedItem.value = null
                                        loading = true
                                        reload()
                                    } else {
                                        personas = personas.filterNot { it.id == target }
                                    }
                                },
                                onFailure = {
                                    isError = true
                                    message = it.message ?: "删除失败"
                                },
                            )
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }

    pendingSwitch?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前编辑的内容还没保存，切换过去就没了。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSwitch = null
                        dirty = false
                        action()
                    },
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text("继续编辑") }
            },
        )
    }
}

/**
 * 示例对话编辑器。
 *
 * 每一轮是「对方说 / 它答」两格。这是刻意做成成对而不是一整段文本的：
 * AstrBot 存的就是扁平数组，成对输入才能在保存前就发现"只填了一半"，
 * 而不是等服务端把半句话塞进上下文、语气跑偏了再回头找原因。
 */
@Composable
private fun DialogEditor(
    rows: SnapshotStateList<DialogRow>,
    onDirty: () -> Unit,
) {
    Text(
        text = "示例对话（${rows.size} 轮）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.xs))
    HintText(
        "给它看两轮真实的对话，比写十句形容词管用。语气、长度、标点都照着写。",
    )
    Spacer(Modifier.height(Spacing.sm))

    rows.forEachIndexed { index, row ->
        Column(modifier = Modifier.padding(bottom = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${index + 1} 轮",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        rows.removeAt(index)
                        onDirty()
                    },
                    enabled = rows.size > 1,
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "删除这一轮",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = row.user,
                onValueChange = {
                    rows[index] = row.copy(user = it)
                    onDirty()
                },
                label = { Text("对方说", fontSize = 12.sp) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = row.assistant,
                onValueChange = {
                    rows[index] = row.copy(assistant = it)
                    onDirty()
                },
                label = { Text("它答", fontSize = 12.sp) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )
        }
    }

    if (rows.size < 4) {
        TextButton(
            onClick = {
                rows.add(DialogRow("", ""))
                onDirty()
            },
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(Spacing.xs))
            Text("再加一轮")
        }
    }
}
