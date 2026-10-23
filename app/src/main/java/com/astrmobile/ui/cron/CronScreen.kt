package com.astrmobile.ui.cron

import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.CronJob
import com.astrmobile.core.container.CronSchedule
import com.astrmobile.core.ui.Radius
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.EmptyState
import com.astrmobile.ui.components.HintText
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 定时任务。
 *
 * AstrBot 本来就有 cron 能力，但只在网页后台能配。手机上的场景反而更自然：
 * 早上八点群里报天气、晚上十一点提醒睡觉——这些不需要电脑。
 */
@Composable
fun CronScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val jobs by viewModel.cronJobs.collectAsStateWithLifecycle()
    val busy by viewModel.cronBusy.collectAsStateWithLifecycle()
    val error by viewModel.cronError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshCronJobs() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page, vertical = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "定时任务",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                IconButton(onClick = { scope.launch { viewModel.refreshCronJobs() } }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        if (error != null) {
            SectionCard(title = "读取失败") {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }

        when {
            jobs.isEmpty() && !busy -> EmptyState(
                icon = Icons.Rounded.Schedule,
                title = "还没有定时任务",
                detail = "比如每天早上八点，让它在群里说一句今天天气怎么样",
            )

            else -> jobs.forEach { job ->
                CronJobCard(
                    job = job,
                    onToggle = { enabled ->
                        scope.launch {
                            viewModel.toggleCronJob(job, enabled).onFailure {
                                Toast.makeText(context, it.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRunNow = {
                        scope.launch {
                            viewModel.runCronJobNow(job)
                                .onSuccess {
                                    Toast.makeText(context, "已触发一次", Toast.LENGTH_SHORT).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: "执行失败", Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            viewModel.deleteCronJob(job).onFailure {
                                Toast.makeText(context, it.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.md))
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        Button(
            onClick = { showCreate = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("新建任务")
        }

        Spacer(Modifier.height(Spacing.md))

        HintText(
            "到点后由 AstrBot 在指定会话里执行一次。任务保存在容器里，" +
                "重装环境会一并清掉，记得先备份。",
        )
    }

    if (showCreate) {
        CreateCronDialog(
            viewModel = viewModel,
            onDismiss = { showCreate = false },
            onCreated = { showCreate = false },
        )
    }
}

@Composable
private fun CronJobCard(
    job: CronJob,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    // 认不出来的表达式原样显示，不要瞎猜
    val schedule = CronSchedule.describe(job.cronExpression) ?: job.cronExpression

    SectionCard(
        title = job.name,
        subtitle = schedule,
        trailing = {
            Switch(checked = job.enabled, onCheckedChange = onToggle)
        },
    ) {
        if (job.note.isNotBlank()) {
            Text(
                text = job.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.sm))
        }

        Text(
            text = job.session.ifBlank { "未绑定会话" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(Spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = onRunNow,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Radius.sm),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("立即执行")
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(0.7f),
                shape = RoundedCornerShape(Radius.sm),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("删除")
            }
        }
    }
}

@Composable
private fun CreateCronDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var expression by remember { mutableStateOf("0 8 * * *") }
    var session by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var submitting by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    val expressionOk = CronSchedule.looksValid(expression)
    val canSubmit = name.isNotBlank() && session.isNotBlank() && expressionOk && !submitting

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("新建定时任务") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("早间播报") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.sm),
                )

                Spacer(Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = expression,
                    onValueChange = { expression = it },
                    label = { Text("执行时间（Cron 表达式）") },
                    singleLine = true,
                    isError = expression.isNotBlank() && !expressionOk,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.sm),
                )

                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = when {
                        expression.isBlank() -> "格式：分 时 日 月 周，例如 0 8 * * * = 每天 08:00"
                        !expressionOk -> "格式不对：需要 5 段，用空格分隔"
                        else -> CronSchedule.describe(expression) ?: "无法识别，将按原样提交"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        expression.isNotBlank() && !expressionOk -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Spacer(Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = session,
                    onValueChange = { session = it },
                    label = { Text("目标会话") },
                    placeholder = { Text("aiocqhttp:GroupMessage:123456") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.sm),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                choices = viewModel.cronSessionChoices()
                                showPicker = true
                            }
                        },
                    ) {
                        Text("从会话列表选择")
                    }
                }

                Spacer(Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("执行内容（提示词）") },
                    placeholder = { Text("用一句话播报今天的天气，并提醒该不该带伞") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.sm),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    submitting = true
                    scope.launch {
                        viewModel.createCronJob(
                            name = name.trim(),
                            cronExpression = expression.trim(),
                            session = session.trim(),
                            note = note.trim(),
                        )
                            .onSuccess { onCreated() }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    it.message ?: "创建失败",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        submitting = false
                    }
                },
            ) {
                Text(if (submitting) "创建中…" else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择会话") },
            text = {
                val items = choices.orEmpty()
                if (items.isEmpty()) {
                    Text(
                        text = "没有读到会话。可以先在群里和机器人说句话，或者手动填 UMO。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        items.forEach { (umo, title) ->
                            TextButton(
                                onClick = {
                                    session = umo
                                    showPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = umo,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("关闭") }
            },
        )
    }
}
