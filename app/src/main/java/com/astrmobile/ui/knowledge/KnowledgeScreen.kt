package com.astrmobile.ui.knowledge

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.EmbeddingConfig
import com.astrmobile.core.container.KnowledgeBase
import com.astrmobile.core.container.KnowledgeDocument
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.InlineLoading

/**
 * 知识库。
 *
 * 放资料只是第一步：AstrBot 要切块、逐块算向量、入库，之后对话时才能检索到。
 * 所以这里走它的接口上传，而不是把文件拷进容器目录。
 */
@Composable
fun KnowledgeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRestart: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cookie by remember { mutableStateOf<String?>(null) }
    var embedding by remember { mutableStateOf<EmbeddingConfig?>(null) }
    var embeddingSaved by remember { mutableStateOf(false) }
    var kbs by remember { mutableStateOf<List<KnowledgeBase>>(emptyList()) }
    var selected by remember { mutableStateOf<KnowledgeBase?>(null) }
    var docs by remember { mutableStateOf<List<KnowledgeDocument>>(emptyList()) }

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    // 向量模型表单
    var embBase by remember { mutableStateOf("https://api.openai.com/v1") }
    var embKey by remember { mutableStateOf("") }
    var embModel by remember { mutableStateOf("text-embedding-3-small") }

    suspend fun ensureCookie(): String? {
        cookie?.let { return it }
        return viewModel.loginAstrBot().fold(
            onSuccess = { cookie = it; it },
            onFailure = {
                message = "${it.message}（可在「人格设定」页填入你改过的密码）"
                isError = true
                null
            },
        )
    }

    suspend fun reload() {
        embedding = viewModel.readEmbeddingConfig()
        if (embedding == null) {
            loading = false
            return
        }
        val session = ensureCookie() ?: run { loading = false; return }
        viewModel.listKnowledgeBases(session).fold(
            onSuccess = { kbs = it; loading = false },
            onFailure = { message = it.message; isError = true; loading = false },
        )
    }

    LaunchedEffect(Unit) { reload() }

    suspend fun openKb(kb: KnowledgeBase) {
        selected = kb
        val session = ensureCookie() ?: return
        viewModel.listKnowledgeDocuments(session, kb.id).fold(
            onSuccess = { docs = it },
            onFailure = { message = it.message; isError = true },
        )
    }

    // 选文件 → 读字节 → 上传
    // picker 只创建一次，直接捕获 selected 会永远拿到 null，必须用可观察的最新值
    val latestKb = rememberUpdatedState(selected)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kb = latestKb.value ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            progress = "正在读取文件…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(context, uri) ?: "document.txt"
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    // 边读边计数：先 readBytes() 再判断大小的话，用户选个 500MB 的 PDF
                    // 会先把内存吃光，检查根本走不到。
                    val bytes = readLimited(context, uri, MAX_UPLOAD_BYTES)
                    Triple(name, mime, bytes)
                }
            }

            result.fold(
                onSuccess = { (name, mime, bytes) ->
                    progress = "正在上传 ${name}（${bytes.size / 1024} KB）…"
                    val session = ensureCookie()
                    if (session == null) {
                        busy = false
                        return@launch
                    }
                    viewModel.uploadKnowledgeDocument(session, kb.id, name, mime, bytes).fold(
                        onSuccess = {
                            isError = false
                            message = "已上传，正在后台切块索引，稍等片刻刷新"
                            openKb(kb)
                        },
                        onFailure = {
                            isError = true
                            message = it.message ?: "上传失败"
                        },
                    )
                },
                onFailure = {
                    isError = true
                    message = it.message ?: "读取文件失败"
                },
            )
            progress = ""
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (selected != null) {
                    selected = null
                    docs = emptyList()
                } else {
                    onBack()
                }
            }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selected?.name ?: "知识库",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (selected == null) "给它一本参考书，回答前先翻书" else "文档 ${docs.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        message?.let { text ->
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
            Spacer(Modifier.height(Spacing.md))
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineLoading()
                Spacer(Modifier.size(10.dp))
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        // ---------------------------------------------------------- 向量模型
        if (embedding == null) {
            SectionCard(title = "先配一个向量模型") {
                HintText(
                    "知识库要靠它把文档变成向量才能检索，没有它建不了库。\n" +
                        "填一个支持 embeddings 的 OpenAI 兼容接口即可，" +
                        "例如 OpenAI 的 text-embedding-3-small，或国内厂商的同类服务。",
                )
                Spacer(Modifier.height(Spacing.md))

                OutlinedTextField(
                    value = embBase,
                    onValueChange = { embBase = it },
                    label = { Text("接口地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = embKey,
                    onValueChange = { embKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = embModel,
                    onValueChange = { embModel = it },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            viewModel.saveEmbeddingConfig(embBase, embKey, embModel).fold(
                                onSuccess = {
                                    isError = false
                                    // 不重新读取：配置写进文件了，但 AstrBot 还在用旧配置，
                                    // 读到了会让界面误以为「可以建库了」。
                                    embeddingSaved = true
                                    message = "已保存。必须重启一次容器，AstrBot 才会加载这个向量模型。"
                                },
                                onFailure = {
                                    isError = true
                                    message = it.message ?: "保存失败"
                                },
                            )
                            busy = false
                        }
                    },
                    enabled = !busy && embBase.isNotBlank() && embKey.isNotBlank() && embModel.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("保存") }

                if (embeddingSaved) {
                    TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                        Text("立即重启使配置生效")
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            HintText("重启后再回到这一页，就能建知识库了。")
            return@Column
        }

        // ---------------------------------------------------------- 知识库列表
        if (selected == null) {
            SectionCard(title = "我的知识库") {
                if (kbs.isEmpty()) {
                    HintText("还没有知识库。先建一个，比如「群规」「项目文档」。")
                } else {
                    kbs.forEach { kb ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xs),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(Spacing.md),
                            ) {
                                Icon(
                                    Icons.Rounded.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        kb.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "文档 ${kb.documentCount} 个",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val session = ensureCookie() ?: return@launch
                                        viewModel.deleteKnowledgeBase(session, kb.id).fold(
                                            onSuccess = { reload() },
                                            onFailure = {
                                                isError = true
                                                message = it.message
                                            },
                                        )
                                    }
                                }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = { showCreate = true; newName = "" },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("新建知识库") }
            }

            Spacer(Modifier.height(Spacing.lg))
            HintText(
                "用法：把群规、产品文档、设定集传进去，然后在 AstrBot 后台的" +
                    "「知识库」里把它设为默认（或用 /kb 命令切换），对话时它就会先查再答。",
            )
        } else {
            // ------------------------------------------------------ 文档列表
            SectionCard(title = "文档") {
                if (docs.isEmpty()) {
                    HintText("还没有文档。支持 txt / md / pdf / epub / docx。")
                } else {
                    docs.forEach { doc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    doc.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Default,
                                    fontSize = 13.sp,
                                )
                                if (doc.status.isNotBlank()) {
                                    Text(
                                        doc.status,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val session = ensureCookie() ?: return@launch
                                    val kb = selected ?: return@launch
                                    viewModel.deleteKnowledgeDocument(session, kb.id, doc.id).fold(
                                        onSuccess = { openKb(kb) },
                                        onFailure = {
                                            isError = true
                                            message = it.message
                                        },
                                    )
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineLoading()
                        Spacer(Modifier.size(10.dp))
                        Text(
                            progress.ifBlank { "处理中…" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    "text/*",
                                    "application/pdf",
                                    "application/epub+zip",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("上传文档") }
                }

                Spacer(Modifier.height(Spacing.sm))
                TextButton(
                    onClick = { selected?.let { scope.launch { openKb(it) } } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("刷新列表", fontSize = 13.sp) }
            }

            Spacer(Modifier.height(Spacing.lg))
            HintText(
                "上传后需要几十秒到几分钟做切块和向量化，状态从 processing 变成 completed " +
                    "才算可用。文档越多越慢，建议先传核心的几份。",
            )
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建知识库") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名称") },
                    placeholder = { Text("群规 / 项目文档") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isBlank()) return@TextButton
                        showCreate = false
                        busy = true
                        scope.launch {
                            val session = ensureCookie()
                            val providerId = embedding?.id
                            if (session == null || providerId.isNullOrBlank()) {
                                busy = false
                                return@launch
                            }
                            viewModel.createKnowledgeBase(session, name, providerId).fold(
                                onSuccess = {
                                    isError = false
                                    message = "已创建「$name」"
                                    reload()
                                },
                                onFailure = {
                                    isError = true
                                    message = it.message ?: "创建失败"
                                },
                            )
                            busy = false
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            },
        )
    }
}

/** 从 content Uri 里取真实文件名。 */
private fun queryName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}

/** 一次知识库上传的体积上限：再大就不是手机该干的事了。 */
private const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

/**
 * 带上限地读取文件。
 *
 * 不用 `readBytes()` 是因为它先把整个文件读进内存，再判断大小已经晚了——
 * 低配机型上选个几百 MB 的文件会直接 OOM 闪退。
 */
private fun readLimited(context: android.content.Context, uri: Uri, limit: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("读不到文件内容")
    input.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) {
                error("文件超过 ${limit / 1024 / 1024}MB，手机上处理会很吃力")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
