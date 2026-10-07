package com.example.taskflowai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.taskflowai.data.local.TaskStatus
import com.example.taskflowai.ui.components.AnimatedStatusBadge
import com.example.taskflowai.ui.components.ReleaseVoiceRecognizerOnDispose
import com.example.taskflowai.ui.components.VoiceMicButton
import com.example.taskflowai.ui.components.taskStatusColor

/**
 * Home 屏:输入指令 → 提交任务 → 观察最近任务列表。
 *
 * MVI 落地方式:
 * - 本文件**没有任何业务逻辑**:只有「渲染 state」与「把点击翻译成 Intent」两件事;
 * - 导航副作用由 [MainEffect] 驱动(LaunchedEffect 收集),不塞进状态;
 * - 状态变化的动画全部由 Compose 自动完成(见下方 AnimatedVisibility / animateFloatAsState)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenTask: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    prefillInstruction: String? = null,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 页面离开时释放语音识别器
    ReleaseVoiceRecognizerOnDispose()

    // 外部预填(adb --es prefill / 深链):仅在内容变化时应用一次,不覆盖用户后续输入
    LaunchedEffect(prefillInstruction) {
        if (!prefillInstruction.isNullOrBlank()) {
            viewModel.onIntent(MainIntent.InputChanged(prefillInstruction))
        }
    }

    // 副作用:收集导航事件(不进状态,避免重组/旋转时重复导航)
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MainEffect.NavigateToTask -> onOpenTask(effect.taskId)
                MainEffect.NavigateToSettings -> onOpenSettings()
            }
        }
    }

    // 错误 → Snackbar(需要时带"去设置"引导)→ 回注 ErrorShown 清除
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (state.canGoSettings) "去设置" else null,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onIntent(MainIntent.OpenSettings)
            }
            viewModel.onIntent(MainIntent.ErrorShown)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text("TaskFlow", fontWeight = FontWeight.SemiBold)
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = { viewModel.onIntent(MainIntent.OpenSettings) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 悬浮卡片式输入框(带阴影,右侧发送按钮)
            InputCard(
                input = state.input,
                submitting = state.submitting,
                canSubmit = state.canSubmit,
                onInputChange = { viewModel.onIntent(MainIntent.InputChanged(it)) },
                onSubmit = { viewModel.onIntent(MainIntent.Submit) },
                onVoiceResult = { viewModel.onIntent(MainIntent.VoiceResult(it)) },
                onVoiceError = { viewModel.onIntent(MainIntent.ShowError(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            if (state.tasks.isEmpty()) {
                EmptyTasksHint(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        // 每个任务项出现时淡入 + 纵向展开;插入/移动由 animateItem 平滑处理
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { visible = true }
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                            exit = fadeOut(tween(150)),
                            modifier = Modifier.animateItem(),
                        ) {
                            SwipeToDeleteTask(
                                task = task,
                                onOpen = { onOpenTask(task.id) },
                                onDelete = { viewModel.onIntent(MainIntent.DeleteTask(task.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 悬浮输入卡:BasicTextField 无边框融入卡片;右侧麦克风(长按说话)+ 发送按钮 */
@Composable
private fun InputCard(
    input: String,
    submitting: Boolean,
    canSubmit: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoiceResult: (String) -> Unit,
    onVoiceError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                enabled = !submitting,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 3,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                text = "描述你的任务,例如:给妈妈发短信说晚点回家",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 按住说话:脉冲动画提示倾听中,松开后识别并自动发送
            VoiceMicButton(
                onRecognized = onVoiceResult,
                onError = onVoiceError,
                enabled = !submitting,
            )

            FilledIconButton(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.size(48.dp),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                    )
                }
            }
        }
    }
}

/** 任务卡片:指令 + 状态徽章(动画)+ 进度条(动画) */
@Composable
private fun TaskCard(task: TaskUiModel, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = task.instruction,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedStatusBadge(
                    text = task.statusText,
                    color = taskStatusColor(task.status),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = task.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${task.completedSteps}/${task.totalSteps} 步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 大模型的真实回答 / 失败原因(摘要展示,点开详情看全文)
            task.answer?.let { answer ->
                val prefix = if (task.status == TaskStatus.FAILED) "⚠️" else "💬"
                Text(
                    text = "$prefix $answer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 进度条动画:状态每推进一次,数值平滑过渡
            val animatedProgress by animateFloatAsState(
                targetValue = task.progress,
                animationSpec = tween(durationMillis = 500),
                label = "taskProgress",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 任务卡片 + 左划删除:
 * - 卡片向左拖动(跟手,限制在 -96dp..0 内),右侧露出红色"删除"按钮;
 * - 松手按"过半或带速度"吸附到展开/收起;
 * - 展开状态下点卡片先收起,收起状态下点卡片进详情;
 * - 点"删除"→ 发 Intent 删除任务,列表由 Room Flow 实时移除(带移除动画)。
 */
@Composable
private fun SwipeToDeleteTask(
    task: TaskUiModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxWidth()) {
        // 底层:红色删除面板(靠右,卡片左移后露出)
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(96.dp)
                    .clickable {
                        scope.launch { offsetX.animateTo(0f, tween(150)) }
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }

        // 上层:卡片本体(水平拖动跟手,松开吸附)
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    onDragStopped = { velocity ->
                        val open = offsetX.value < -revealPx * 0.4f || velocity < -400f
                        offsetX.animateTo(if (open) -revealPx else 0f, tween(200))
                    },
                ),
        ) {
            TaskCard(
                task = task,
                onClick = {
                    if (offsetX.value < 0f) {
                        // 展开状态下点击:先收起,不导航
                        scope.launch { offsetX.animateTo(0f, tween(180)) }
                    } else {
                        onOpen()
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyTasksHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "还没有任务\n在上方输入指令,开始你的第一个任务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
