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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.PersonaItem
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 人格设定。
 *
 * 这是「像人」最直接的一刀：一段 system prompt 决定它怎么说话。
 * 数据存在 AstrBot 的库里，所以要过它的接口，而不是改文件。
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
    var selected by remember { mutableStateOf<PersonaItem?>(null) }
    var draft by remember { mutableStateOf("") }

    var password by remember { mutableStateOf("astrbot") }
    var needPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    suspend fun ensureSession(force: Boolean = false): String? {
        if (!force) cookie?.let { return it }
        return viewModel.loginAstrBot(password).fold(
            onSuccess = {
                // 记住这次成功的密码，插件配置、对话记录、知识库共用
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
        viewModel.listPersonas(session).fold(
            onSuccess = { list ->
                personas = list
                // 默认选中第一条，让用户一点进来就能改
                if (selected == null) {
                    list.firstOrNull()?.let {
                        selected = it
                        draft = it.systemPrompt
                    }
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

        Spacer(Modifier.height(12.dp))

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        // ---------------------------------------------------------- 密码兜底
        if (needPassword) {
            SectionCard(title = "需要后台密码") {
                HintText("改过 AstrBot 后台密码的话，在这里填一次。默认是 astrbot。")
                Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(14.dp))
        }

        // ---------------------------------------------------------- 选择人格
        if (personas.isNotEmpty()) {
            SectionCard(title = "现有的人格") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    personas.forEach { item ->
                        FilterChip(
                            selected = selected?.id == item.id,
                            onClick = {
                                selected = item
                                draft = item.systemPrompt
                                message = null
                                saved = false
                            },
                            label = { Text(item.id, fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ---------------------------------------------------------- 编辑
        SectionCard(title = selected?.let { "编辑「${it.id}」" } ?: "设定人设") {
            HintText(
                "用一句话说清它是谁、什么性格、怎么说话。写具体比写抽象有用：" +
                    "「你是一个话少但嘴毒的室友，用短句，偶尔冷嘲热讽」比「你很有趣」强得多。",
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("人设（system prompt）") },
                placeholder = { Text("你是一个……") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    busy = true
                    message = null
                    saved = false
                    scope.launch {
                        val session = ensureSession() ?: run {
                            busy = false
                            return@launch
                        }
                        val target = selected
                        val result = if (target == null) {
                            viewModel.savePersona(
                                cookie = session,
                                id = "default",
                                prompt = draft,
                                exists = false,
                            )
                        } else {
                            viewModel.savePersona(
                                cookie = session,
                                id = target.id,
                                prompt = draft,
                                exists = true,
                            )
                        }
                        busy = false
                        result.fold(
                            onSuccess = {
                                isError = false
                                saved = true
                                message = "已保存，重启后生效"
                                selected = null
                                cookie = null
                                loading = true
                                reload()
                            },
                            onFailure = { error ->
                                isError = true
                                message = error.message ?: "保存失败"
                            },
                        )
                    }
                },
                enabled = !busy && draft.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("保存人格")
                }
            }

            message?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    lineHeight = 17.sp,
                )
            }

            if (saved) {
                TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                    Text("立即重启使配置生效")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "怎么让它更像人") {
            HintText(
                "人格只决定「怎么说话」。要它主动找人聊、有情绪波动、记得以前聊过什么，" +
                    "去「插件」里装：\n" +
                    "· 主动消息：会主动开口，带免打扰时段\n" +
                    "· 长期记忆：记住聊过的事\n" +
                    "· 人格演化：性格会随对话变化",
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
