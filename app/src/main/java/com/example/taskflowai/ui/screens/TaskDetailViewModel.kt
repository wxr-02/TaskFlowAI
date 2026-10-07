package com.example.taskflowai.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.taskflowai.data.local.PendingType
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskWithSteps
import com.example.taskflowai.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * 任务详情屏 ViewModel(MVI)。
 *
 * - 状态:数据库 Flow → 展示模型(UiState),UI 全程只读;
 * - 事件:onIntent 唯一入口,"允许/拒绝"最终落到 [TaskRepository.resolvePendingStep]
 *   —— 即 prompt 要求的"点击允许 → 发 Intent 给 ViewModel → 调用 Repository.resumeTask";
 * - 权限类挂起:先经 Effect 让 Compose 层发起系统权限申请,结果以 Intent 回注,再恢复执行。
 */
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val repository: TaskRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 导航参数 taskId(route: task/{taskId}) */
    private val taskId: Long = checkNotNull(savedStateHandle["taskId"]) { "缺少 taskId 导航参数" }

    private val _effects = MutableSharedFlow<TaskDetailEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<TaskDetailEffect> = _effects.asSharedFlow()

    val uiState: StateFlow<TaskDetailUiState> = repository.observeTask(taskId)
        .map { it?.toUiState() ?: TaskDetailUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TaskDetailUiState(),
        )

    /** 唯一的状态入口 */
    fun onIntent(intent: TaskDetailIntent) {
        when (intent) {
            is TaskDetailIntent.ApproveStep -> approveStep(intent.stepId)
            is TaskDetailIntent.RejectStep -> resolve(intent.stepId, approved = false)
            is TaskDetailIntent.PermissionResult -> resolve(intent.stepId, approved = intent.granted)
        }
    }

    private fun approveStep(stepId: Long) {
        val step = uiState.value.steps.find { it.id == stepId } ?: return
        if (step.pendingType == PendingType.PERMISSION) {
            // 权限类:交给 Compose 层的全局 launcher 发起系统申请(Effect),
            // 授权结果会以 PermissionResult 回来,再真正恢复任务
            viewModelScope.launch {
                _effects.emit(TaskDetailEffect.RequestPermissions(stepId, step.permissions))
            }
        } else {
            // 确认类:直接恢复(引擎会用"已确认"上下文重放该步骤)
            resolve(stepId, approved = true)
        }
    }

    private fun resolve(stepId: Long, approved: Boolean) {
        viewModelScope.launch {
            repository.resolvePendingStep(taskId, stepId, approved)
        }
    }

    private fun TaskWithSteps.toUiState(): TaskDetailUiState {
        val ordered = orderedSteps
        val done = ordered.count { it.status == StepStatus.SUCCESS }
        return TaskDetailUiState(
            loaded = true,
            instruction = task.instruction,
            answer = task.answer,
            status = task.status,
            statusText = task.status.displayText(),
            completedSteps = done,
            totalSteps = ordered.size,
            progress = if (ordered.isEmpty()) 0f else done.toFloat() / ordered.size,
            steps = ordered.mapIndexed { index, step ->
                StepUiModel(
                    id = step.id,
                    order = index + 1,
                    isLast = index == ordered.lastIndex,
                    toolName = step.toolName,
                    toolDisplayName = toolDisplayName(step.toolName),
                    argsSummary = summarizeArgs(step.argumentsJson),
                    status = step.status,
                    statusText = step.status.displayText(),
                    message = step.resultMessage,
                    pendingType = step.pendingType,
                    permissions = step.pendingPermissions(),
                )
            },
        )
    }

    /** 把 LLM 生成的参数 JSON 压成一行摘要(展示逻辑,属于 ViewModel 的职责) */
    private fun summarizeArgs(argumentsJson: String): String = runCatching {
        val json = JSONObject(argumentsJson)
        json.keys().asSequence().joinToString(" · ") { key ->
            val raw = json.optString(key)
            val short = if (raw.length > 24) raw.take(24) + "…" else raw
            "$key: $short"
        }
    }.getOrDefault(argumentsJson.take(80))

    private companion object {

        fun toolDisplayName(toolName: String): String = when (toolName) {
            "send_sms" -> "发送短信"
            "set_alarm" -> "设置闹钟"
            "add_calendar_event" -> "添加日程"
            "search_weather" -> "查询天气"
            else -> toolName
        }
    }
}
