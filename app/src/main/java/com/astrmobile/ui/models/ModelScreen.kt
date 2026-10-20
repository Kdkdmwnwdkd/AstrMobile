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
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "对话模型",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- 当前状态
        val current = models?.firstOrNull()
        SectionCard(title = "当前状态") {
            when {
                models == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("正在读取配置…", style = MaterialTheme.typography.bodyMedium)
                }
                current == null -> Text(
                    text = "还没有配置模型。机器人能收到 QQ 消息，但不会回复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 19.sp,
                )
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "已配置：${current.model}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${current.apiBase}\n密钥 ${current.keyHint}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    )
                    if (!current.enabled) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "该模型当前是禁用状态，保存一次即可重新启用。",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 表单
        SectionCard(title = "添加或替换") {
            HintText("选一个厂商会自动填好接口地址和模型名，你只需要贴 API Key。")
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
                        label = { Text(item.label, fontSize = 12.sp) },
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
                            onSuccess = {
                                saved = true
                                isError = false
                                message = "已保存。配置要重启后才生效。"
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
                    Text("保存")
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
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                    Text("立即重启使配置生效")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (current != null) {
            SectionCard(title = "移除") {
                HintText("移除后机器人会重新变成「收到消息但不回复」。")
                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.removeModel().fold(
                                onSuccess = {
                                    reloadKey++
                                    isError = false
                                    message = "已移除，重启后生效"
                                },
                                onFailure = {
                                    isError = true
                                    message = it.message ?: "移除失败"
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("删除当前模型", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Text(
            text = "密钥只写入本机容器内的配置文件，不会上传到任何地方。",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )
    }
}
