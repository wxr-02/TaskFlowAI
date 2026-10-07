package com.example.taskflowai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.taskflowai.data.local.PendingType
import com.example.taskflowai.data.local.StepEntity
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskStatus
import com.example.taskflowai.data.local.TaskWithSteps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 预设演示指令:点击仅填充输入框 */
private val PRESET_INSTRUCTIONS = listOf(
    "给妈妈发短信说晚点回家",
    "北京今天天气怎么样",
    "先查北京天气,再设个明早7点的闹钟",
)

/**
 * 阶段 3:任务中心。
 *
 * - 上半部分:输入指令 → 提交(LLM 规划 → Room 落库 → WorkManager 后台执行);
 * - 下半部分:任务列表实时刷新(Room Flow),步骤挂起时提供操作按钮:
 *     · 确认类挂起 → [确认执行] / [取消]
 *     · 权限类挂起 → [去授权](发起系统权限申请,成功后自动恢复) / [取消]
 */
@Composable
fun TaskCenterScreen(
    modifier: Modifier = Modifier,
    viewModel: TaskCenterViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        // ===== 顶部:提交指令 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("TaskFlow AI", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "阶段 3 · 任务中心(中断与恢复)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onInputChange,
                label = { Text("输入指令") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !submitting,
                minLines = 2,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESET_INSTRUCTIONS.forEach { preset ->
                    AssistChip(
                        onClick = { viewModel.fillInput(preset) },
                        label = { Text(preset, maxLines = 1) },
                    )
                }
            }

            Button(
                onClick = viewModel::submit,
                enabled = !submitting && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (submitting) "规划中…" else "提交任务")
            }

            error?.let { message ->
                Text(
                    text = "❌ $message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ===== 任务列表(实时) =====
        if (tasks.isEmpty()) {
            Text(
                text = "暂无任务 —— 输入指令并点击「提交任务」开始",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(tasks, key = { it.task.id }) { item ->
                    TaskCard(item = item, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun TaskCard(item: TaskWithSteps, viewModel: TaskCenterViewModel) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.task.instruction,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(text = taskStatusText(item.task.status), color = taskStatusColor(item.task.status))
            }
            Text(
                text = "#${item.task.id} · ${formatTime(item.task.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item.orderedSteps.isEmpty()) {
                Text(
                    text = "(无步骤:模型判断无需调用工具)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                item.orderedSteps.forEach { step ->
                    StepRow(step = step, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: StepEntity, viewModel: TaskCenterViewModel) {
    // 权限类挂起:拉起系统权限申请;全部授予后自动恢复执行,否则保持挂起等待重试
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.isNotEmpty() && result.values.all { it }) {
            viewModel.approveStep(step.taskId, step.id)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "#${step.orderIndex + 1} ${step.toolName}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(text = stepStatusText(step.status), color = stepStatusColor(step.status))
        }

        step.resultMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (step.status == StepStatus.WAITING_USER) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step.pendingType) {
                    PendingType.CONFIRMATION -> {
                        Button(onClick = { viewModel.approveStep(step.taskId, step.id) }) {
                            Text("确认执行")
                        }
                        OutlinedButton(onClick = { viewModel.rejectStep(step.taskId, step.id) }) {
                            Text("取消")
                        }
                    }

                    PendingType.PERMISSION -> {
                        Button(
                            onClick = {
                                permissionLauncher.launch(step.pendingPermissions().toTypedArray())
                            }
                        ) {
                            Text("去授权")
                        }
                        OutlinedButton(onClick = { viewModel.rejectStep(step.taskId, step.id) }) {
                            Text("取消")
                        }
                    }

                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun taskStatusText(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "待执行"
    TaskStatus.RUNNING -> "执行中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.SUCCESS -> "成功"
    TaskStatus.FAILED -> "失败"
}

private fun stepStatusText(status: StepStatus): String = when (status) {
    StepStatus.PENDING -> "待执行"
    StepStatus.RUNNING -> "执行中"
    StepStatus.WAITING_USER -> "等待用户"
    StepStatus.SUCCESS -> "成功"
    StepStatus.FAILED -> "失败"
}

@Composable
private fun taskStatusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TaskStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    TaskStatus.SUCCESS -> Color(0xFF2E7D32)
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
}

@Composable
private fun stepStatusColor(status: StepStatus): Color = when (status) {
    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
    StepStatus.WAITING_USER -> MaterialTheme.colorScheme.tertiary
    StepStatus.SUCCESS -> Color(0xFF2E7D32)
    StepStatus.FAILED -> MaterialTheme.colorScheme.error
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
