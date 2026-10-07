package com.example.taskflowai.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.taskflowai.data.local.PendingType
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskStatus
import com.example.taskflowai.ui.components.AnimatedStatusBadge
import com.example.taskflowai.ui.components.StepStatusNode
import com.example.taskflowai.ui.components.stepStatusColor
import com.example.taskflowai.ui.components.taskStatusColor
import com.example.taskflowai.ui.components.toolIcon

/**
 * 任务详情屏:步骤条(Stepper)。
 *
 * 交互闭环(MVI):
 *   步骤挂起(WAITING_USER)→ 卡片内展开操作区 → 点"允许"
 *   → UI 发 [TaskDetailIntent.ApproveStep] → ViewModel
 *   → (确认类)Repository.resumeTask 直接恢复 / (权限类)先经 Effect 弹系统权限申请
 *   → 引擎继续执行 → 数据库状态变化 → MVI UiState 更新
 *   → Crossfade/AnimatedVisibility 自动播放"⚠️ → ⏳ → ✅"动画并点亮后续步骤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 全局动态权限请求:整屏唯一的 launcher,由 ViewModel 的 Effect 触发,结果回注为 Intent
    var pendingPermissionStepId by remember { mutableStateOf(-1L) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val stepId = pendingPermissionStepId
        if (stepId > 0) {
            val granted = result.isNotEmpty() && result.values.all { it }
            viewModel.onIntent(TaskDetailIntent.PermissionResult(stepId, granted))
            pendingPermissionStepId = -1L
        }
    }
    // 特殊权限:精确闹钟(SCHEDULE_EXACT_ALARM)不是运行时权限,需跳系统设置页授权
    val exactAlarmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val stepId = pendingPermissionStepId
        if (stepId > 0) {
            // 返回后让引擎重新执行该步骤(未授予会再次挂起,已授予则继续)
            viewModel.onIntent(TaskDetailIntent.PermissionResult(stepId, granted = true))
            pendingPermissionStepId = -1L
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TaskDetailEffect.RequestPermissions -> {
                    pendingPermissionStepId = effect.stepId
                    val needsExactAlarmSettings = Build.VERSION.SDK_INT >= 31 &&
                        effect.permissions.contains(Manifest.permission.SCHEDULE_EXACT_ALARM)
                    if (needsExactAlarmSettings) {
                        runCatching {
                            exactAlarmLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            )
                        }.onFailure {
                            exactAlarmLauncher.launch(Intent(Settings.ACTION_SETTINGS))
                        }
                    } else {
                        permissionLauncher.launch(effect.permissions.toTypedArray())
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text("任务详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!state.loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            item { DetailHeader(state) }

            // 大模型的真实回答(无需工具的问题即完整回答;有工具时为规划开场话术)
            state.answer?.let { answer ->
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 18.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (state.status == TaskStatus.FAILED) "⚠️ 失败信息" else "💬 模型回答",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(answer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            items(state.steps, key = { it.id }) { step ->
                StepperRow(
                    step = step,
                    onApprove = { viewModel.onIntent(TaskDetailIntent.ApproveStep(step.id)) },
                    onReject = { viewModel.onIntent(TaskDetailIntent.RejectStep(step.id)) },
                )
            }

            if (state.answer == null && state.steps.isEmpty()) {
                item {
                    Text(
                        text = "(模型没有返回内容)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 头部:指令 + 总状态(动画徽章)+ 进度条(数值平滑动画) */
@Composable
private fun DetailHeader(state: TaskDetailUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = state.instruction,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.status?.let { status ->
                AnimatedStatusBadge(text = state.statusText, color = taskStatusColor(status))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "已完成 ${state.completedSteps}/${state.totalSteps} 步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val animatedProgress by animateFloatAsState(
            targetValue = state.progress,
            animationSpec = tween(durationMillis = 600),
            label = "detailProgress",
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            strokeCap = StrokeCap.Round,
        )
    }
}

/** 一行步骤:左侧节点 + 连接线,右侧步骤卡片 */
@Composable
private fun StepperRow(
    step: StepUiModel,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 左侧:状态节点(Crossfade 动画)+ 竖直连接线
        Column(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepStatusNode(order = step.order, status = step.status)
            if (!step.isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }

        StepCard(
            step = step,
            onApprove = onApprove,
            onReject = onReject,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 14.dp),
        )
    }
}

/** 步骤卡片:工具图标 + 名称 + 参数摘要 + 挂起时展开的操作区(AnimatedVisibility) */
@Composable
private fun StepCard(
    step: StepUiModel,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = toolIcon(step.toolName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = step.toolDisplayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                AnimatedStatusBadge(
                    text = step.statusText,
                    color = stepStatusColor(step.status),
                )
            }

            // 解析出的核心参数
            Text(
                text = step.argsSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // 挂起提示 / 执行结果
            step.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == StepStatus.WAITING_USER) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // ===== 交互点:WAITING_USER 时卡片内展开操作区(带展开/收起动画)=====
            AnimatedVisibility(
                visible = step.status == StepStatus.WAITING_USER,
                enter = fadeIn(tween(250)) + expandVertically(tween(280)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (step.pendingType == PendingType.PERMISSION) {
                            "该操作需要系统权限,是否允许?"
                        } else {
                            "该操作需要你确认后才会执行,是否允许?"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = onApprove,
                            enabled = step.pendingType != null,
                        ) {
                            Text("允许")
                        }
                        OutlinedButton(onClick = onReject) {
                            Text("拒绝")
                        }
                    }
                }
            }
        }
    }
}
