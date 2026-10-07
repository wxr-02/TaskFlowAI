package com.example.taskflowai.ui.screens

import com.example.taskflowai.data.local.PendingType
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskStatus

/*
 * MVI - 任务详情屏契约(与 MainContract 同构:State / Intent / Effect 三分离)。
 */

/** 详情屏完整 UI 状态 */
data class TaskDetailUiState(
    /** 任务是否已从数据库加载出来 */
    val loaded: Boolean = false,
    val instruction: String = "",
    /** 大模型的真实回答(无工具任务即完整回答) */
    val answer: String? = null,
    val status: TaskStatus? = null,
    val statusText: String = "",
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    /** 0f..1f,进度条动画用 */
    val progress: Float = 0f,
    val steps: List<StepUiModel> = emptyList(),
)

/** 单个步骤的展示模型(参数摘要、文案等已由 VM 组装完毕,UI 直接渲染) */
data class StepUiModel(
    val id: Long,
    /** 1 起的展示序号 */
    val order: Int,
    val isLast: Boolean,
    /** 原始工具名(UI 用它映射图标) */
    val toolName: String,
    val toolDisplayName: String,
    /** 解析后的核心参数摘要,如 number: 妈妈 · message: 晚点回家 */
    val argsSummary: String,
    val status: StepStatus,
    val statusText: String,
    /** 挂起提示/执行结果 */
    val message: String?,
    val pendingType: PendingType?,
    /** PERMISSION 挂起时,需要向系统申请的权限 */
    val permissions: List<String>,
)

/** 用户事件 */
sealed interface TaskDetailIntent {
    /** 点击"允许"(确认类直接恢复;权限类先走系统权限申请) */
    data class ApproveStep(val stepId: Long) : TaskDetailIntent
    /** 点击"拒绝" */
    data class RejectStep(val stepId: Long) : TaskDetailIntent
    /** 系统权限申请结果回注 */
    data class PermissionResult(val stepId: Long, val granted: Boolean) : TaskDetailIntent
}

/** 一次性副作用:请求系统权限(由 Compose 层的 launcher 执行) */
sealed interface TaskDetailEffect {
    data class RequestPermissions(val stepId: Long, val permissions: List<String>) : TaskDetailEffect
}

internal fun StepStatus.displayText(): String = when (this) {
    StepStatus.PENDING -> "待执行"
    StepStatus.RUNNING -> "执行中"
    StepStatus.WAITING_USER -> "等待用户"
    StepStatus.SUCCESS -> "成功"
    StepStatus.FAILED -> "失败"
}
