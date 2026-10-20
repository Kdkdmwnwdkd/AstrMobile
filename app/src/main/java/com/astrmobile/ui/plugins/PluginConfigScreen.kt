package com.astrmobile.ui.plugins

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.PluginConfigItem
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 插件参数。
 *
 * 表单由插件自带的 `_conf_schema.json` 动态生成，不是写死的——
 * 1300 多个插件各有各的参数，硬编码不可能覆盖。
 *
 * 支持的类型：bool（开关）、int/float（数字）、string（单行）、
 * text（多行）、list（每行一项）、select（选项）。
 */
@Composable
fun PluginConfigScreen(
    viewModel: MainViewModel,
    pluginId: String,
    onBack: () -> Unit,
    onRestart: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<PluginConfigItem>?>(null) }
    var values = remember { mutableStateMapOf<String, Any?>() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(pluginId) {
        val cookie = viewModel.loginAstrBot("astrbot", "astrbot").getOrElse {
            message = "${it.message}（可先在「人格设定」页填入改过的后台密码）"
            isError = true
            loading = false
            return@LaunchedEffect
        }

        viewModel.loadPluginConfig(cookie, pluginId).fold(
            onSuccess = { snapshot ->
                items = snapshot.items
                snapshot.items.forEach { item ->
                    values[item.key] = when (item.type) {
                        "list" -> (item.value as? List<*>)?.joinToString("\n") ?: ""
                        else -> item.value
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
                    text = pluginId,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = "插件参数",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        message?.let { text ->
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
            Spacer(Modifier.height(10.dp))
        }

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在读取配置…", style = MaterialTheme.typography.bodyMedium)
            }

            items == null -> HintText("这个插件没有可配置项，或者读取失败。")

            else -> {
                val list = items.orEmpty()
                Text(
                    text = "共 ${list.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                list.forEach { item ->
                    ConfigRow(
                        item = item,
                        value = values[item.key],
                        onChange = { values[item.key] = it },
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        busy = true
                        message = null
                        saved = false
                        scope.launch {
                            val cookie = viewModel.loginAstrBot("astrbot", "astrbot").getOrNull()
                            if (cookie == null) {
                                busy = false
                                message = "登录 AstrBot 失败"
                                isError = true
                                return@launch
                            }
                            val payload = mutableMapOf<String, Any?>()
                            list.forEach { item ->
                                payload[item.key] = coerce(item, values[item.key])
                            }
                            viewModel.savePluginConfig(cookie, pluginId, payload).fold(
                                onSuccess = {
                                    isError = false
                                    saved = true
                                    message = "已保存，重启后生效"
                                },
                                onFailure = {
                                    isError = true
                                    message = it.message ?: "保存失败"
                                },
                            )
                            busy = false
                        }
                    },
                    enabled = !busy,
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

                if (saved) {
                    TextButton(onClick = onRestart) {
                        Text("立即重启使配置生效")
                    }
                }

                Spacer(Modifier.height(10.dp))
                HintText("参数由插件自己定义，含义以插件说明为准。改错了可以点插件详情里的重置，或从备份恢复。")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ConfigRow(
    item: PluginConfigItem,
    value: Any?,
    onChange: (Any?) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        when (item.type) {
            "bool" -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    )
                    if (item.hint.isNotBlank()) {
                        Text(
                            text = item.hint,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = value as? Boolean ?: false,
                    onCheckedChange = { onChange(it) },
                )
            }

            "select" -> {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                )
                if (item.hint.isNotBlank()) {
                    Text(
                        text = item.hint,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.options.take(6).forEach { option ->
                        FilterChip(
                            selected = value?.toString() == option,
                            onClick = { onChange(option) },
                            label = { Text(option, fontSize = 11.sp) },
                        )
                    }
                }
            }

            "int", "float" -> {
                OutlinedTextField(
                    value = value?.toString().orEmpty().let {
                        if (it == "null" || it == "0.0") "" else it
                    },
                    onValueChange = { onChange(it) },
                    label = { Text(item.label, fontSize = 12.sp) },
                    supportingText = {
                        val range = buildString {
                            if (item.min != null && item.max != null) {
                                append("${item.min} ~ ${item.max}")
                            }
                            if (item.hint.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(item.hint)
                            }
                        }
                        if (range.isNotBlank()) Text(range, fontSize = 10.sp)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (item.type == "int") {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Decimal
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
            }

            "text", "list" -> {
                OutlinedTextField(
                    value = value?.toString().orEmpty(),
                    onValueChange = { onChange(it) },
                    label = { Text(item.label, fontSize = 12.sp) },
                    supportingText = {
                        Text(
                            if (item.type == "list") {
                                "每行一项${if (item.hint.isNotBlank()) " · ${item.hint}" else ""}"
                            } else {
                                item.hint.ifBlank { "" }
                            },
                            fontSize = 10.sp,
                        )
                    },
                    minLines = if (item.type == "list") 3 else 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
            }

            else -> {
                OutlinedTextField(
                    value = value?.toString().orEmpty(),
                    onValueChange = { onChange(it) },
                    label = { Text(item.label, fontSize = 12.sp) },
                    supportingText = {
                        if (item.hint.isNotBlank()) Text(item.hint, fontSize = 10.sp)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
            }
        }
    }
}

/** 把界面上的字符串还原成配置需要的类型。 */
private fun coerce(item: PluginConfigItem, raw: Any?): Any? = when (item.type) {
    "bool" -> raw as? Boolean ?: false
    "int" -> when (raw) {
        is Int -> raw
        is Number -> raw.toInt()
        else -> raw?.toString()?.trim()?.toIntOrNull() ?: item.value
    }
    "float" -> when (raw) {
        is Double -> raw
        is Number -> raw.toDouble()
        else -> raw?.toString()?.trim()?.toDoubleOrNull() ?: item.value
    }
    "list" -> raw?.toString()
        ?.split("\n")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList<String>()
    else -> raw?.toString().orEmpty()
}
