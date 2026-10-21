package com.astrmobile.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.ConfiguredModel
import com.astrmobile.core.container.ModelPreset
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 模型配置页。
 *
 * 这是决定「机器人到底会不会说话」的一步：QQ 连上了、消息也到了 AstrBot，
 * 但没有可用的对话模型，它只会报「未配置模型」。
 *
 * 表单直接写 AstrBot 的配置文件，省得用户进网页后台翻「服务提供商」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelScreen(
    viewModel: MainViewModel,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onRestart: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var reloadKey by remember { mutableStateOf(0) }
    val models by produceState<List<ConfiguredModel>?>(null, reloadKey) {
        value = viewModel.readModels()
    }

    var preset by remember { mutableStateOf(ModelPreset.DeepSeek) }
    var apiBase by remember { mutableStateOf(ModelPreset.DeepSeek.apiBase) }
    var apiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf(ModelPreset.DeepSeek.model) }
    var keyVisible by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var needsRestart by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var actingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ConfiguredModel?>(null) }

    // 已经配过的话，把接口地址和模型名回填，用户换 Key 时不用重填全部。
    LaunchedEffect(models) {
        val existing = models?.firstOrNull() ?: return@LaunchedEffect
        if (apiKey.isBlank()) {
            apiBase = existing.apiBase.ifBlank { apiBase }
            modelName = existing.model.ifBlank { modelName }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
            }
            Text(
                text = "对话模型",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- 模型列表
        val current = models?.firstOrNull { it.enabled } ?: models?.firstOrNull()
        SectionCard(title = "已配置的模型") {
            when {
                models == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("正在读取配置…", style = MaterialTheme.typography.bodyMedium)
                }
                models.isNullOrEmpty() -> Text(
                    text = "还没有配置模型。机器人能收到 QQ 消息，但不会回复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 19.sp,
                )
                else -> {
                    models.orEmpty().forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        ModelRow(
                            model = item,
                            isActive = item.enabled,
                            busy = actingId == item.id,
                            onActivate = {
                                actingId = item.id
                                message = null
                                scope.launch {
                                    val result = viewModel.activateModel(item.id)
                                    actingId = null
                                    result.fold(
                                        onSuccess = { isError = false; message = it.message; reloadKey++ },
                                        onFailure = { isError = true; message = it.message ?: "切换失败" },
                                    )
                                }
                            },
                            onToggle = {
                                actingId = item.id
                                message = null
                                scope.launch {
                                    val result = viewModel.setModelEnabled(item.id, !item.enabled)
                                    actingId = null
                                    result.fold(
                                        onSuccess = { isError = false; message = it.message; reloadKey++ },
                                        onFailure = { isError = true; message = it.message ?: "操作失败" },
                                    )
                                }
                            },
                            onDelete = { pendingDelete = item },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 表单
        SectionCard(title = "添加或替换") {
            HintText(
                "选厂商会自动填好接口地址和模型名，贴 Key 即可。可以存多个，点「设为使用中」切换。" +
                    "Agnes 目前免费（限 20 次/分钟），适合先跑起来，但免费策略随时可能变，别当唯一方案。",
            )
            Spacer(Modifier.height(10.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelPreset.entries.forEach { item ->
                    FilterChip(
                        selected = preset == item,
                        onClick = {
                            preset = item
                            if (item != ModelPreset.Custom) {
                                apiBase = item.apiBase
                                modelName = item.model
                            }
                            message = null
                        },
                        label = { Text(item.label, fontSize = 13.sp) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = apiBase,
                onValueChange = { apiBase = it },
                label = { Text("接口地址（Base URL）") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (keyVisible) "隐藏" else "显示",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名") },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    busy = true
                    message = null
                    saved = false
                    scope.launch {
                        val result = viewModel.saveModel(apiBase, apiKey, modelName)
                        busy = false
                        result.fold(
                            onSuccess = { outcome ->
                                saved = true
                                isError = false
                                needsRestart = outcome.needsRestart
                                message = outcome.message
                                apiKey = ""
                                reloadKey++
                            },
                            onFailure = { error ->
                                isError = true
                                message = error.message ?: "保存失败"
                            },
                        )
                    }
                },
                enabled = !busy && apiBase.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank(),
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
                    Text("保存并设为当前")
                }
            }

            message?.let { text ->
                Spacer(Modifier.height(8.dp))
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

            if (saved && needsRestart) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                    Text("立即重启使配置生效")
                }
            }

            // 测试连接：直接让 AstrBot 拿这个 Key 发一次真实请求，
            // 这是“到底生效没有”的唯一可靠答案，比看状态卡猜有用。
            Spacer(Modifier.height(2.dp))
            TextButton(
                onClick = {
                    testing = true
                    message = null
                    scope.launch {
                        val result = viewModel.testModel()
                        testing = false
                        result.fold(
                            onSuccess = {
                                isError = false
                                message = it
                            },
                            onFailure = {
                                isError = true
                                message = it.message ?: "测试失败"
                            },
                        )
                    }
                },
                enabled = !testing && (current != null || saved),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (testing) "正在测试连接…" else "测试连接")
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "可以保存多个模型，同一时间只有一个处于「使用中」。密钥只写入本机容器，不会上传。",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )
    }

    // ---------------------------------------------------------- 删除确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个模型？") },
            text = {
                Text(
                    "「${target.model}」会从 AstrBot 里删掉。如果它是当前使用中的模型，" +
                        "机器人会变成收到消息但不回复，直到你启用另一个。",
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    actingId = target.id
                    message = null
                    scope.launch {
                        val result = viewModel.removeModel(target.id)
                        actingId = null
                        result.fold(
                            onSuccess = {
                                isError = false
                                saved = false
                                reloadKey++
                                message = it.message
                            },
                            onFailure = {
                                isError = true
                                message = it.message ?: "删除失败"
                            },
                        )
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 模型列表里的一行：名字、地址、密钥掩码，以及三个操作。 */
@Composable
private fun ModelRow(
    model: ConfiguredModel,
    isActive: Boolean,
    busy: Boolean,
    onActivate: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.model.ifBlank { "未命名模型" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Text(
                    text = "使用中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(model.apiBase.ifBlank { "未填接口地址" })
                if (model.keyHint.isNotBlank()) append("\n密钥 ${model.keyHint}")
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
        )

        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isActive) {
                TextButton(onClick = onActivate, enabled = !busy) { Text("设为使用中") }
            }
            TextButton(onClick = onToggle, enabled = !busy) {
                Text(if (model.enabled) "停用" else "启用")
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
            if (busy) {
                Spacer(Modifier.size(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
        }
    }
}
