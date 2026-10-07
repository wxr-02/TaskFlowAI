package com.example.taskflowai.ui.screens

import com.example.taskflowai.data.local.TaskStatus

/*
 * MVI - Home(任务中心)屏契约。
 *
 * 单向数据流的三要素在此严格分离:
 *  - UiState :不可变数据,VM 单向产出,UI 只负责渲染(所有文案/进度在 VM 算好,UI 零逻辑);
 *  - Intent  :用户事件,UI 唯一的状态变更入口(ViewModel.onIntent);
 *  - Effect  :一次性副作用(如导航),不属于状态,不进 UiState。
 */

/** Home 屏完整 UI 状态 */
data class MainUiState(
    /** 输入框内容 */
    val input: String,
    /** 是否正在提交(LLM 规划中) */
    val submitting: Boolean = false,
    /** 错误提示(展示后由 Intent.ErrorShown 清除) */
    val error: String? = null,
    /** 错误是否应引导用户去设置页(API Key 未配置 / 网络异常) */
    val canGoSettings: Boolean = false,
    /** 最近的任务列表(展示模型) */
    val tasks: List<TaskUiModel> = emptyList(),
) {
    /** UI 可读的派生属性(仍属于 View 逻辑,不是业务逻辑) */
    val canSubmit: Boolean get() = !submitting && input.isNotBlank()
}

/** 任务卡片展示模型 */
data class TaskUiModel(
    val id: Long,
    val instruction: String,
    /** 大模型的真实回答(无需工具时为完整回答;触发工具时为开场话术);无则为 null */
    val answer: String? = null,
    val status: TaskStatus,
    val statusText: String,
    val timeText: String,
    val completedSteps: Int,
    val totalSteps: Int,
    /** 0f..1f,用于进度条动画 */
    val progress: Float,
)

/** 用户事件:UI 把它们交给 [MainViewModel.onIntent],自身不改变任何状态 */
sealed interface MainIntent {
    data class InputChanged(val text: String) : MainIntent
    data object Submit : MainIntent
    data object ErrorShown : MainIntent
    /** 语音识别结果:填入输入框并自动发送 */
    data class VoiceResult(val text: String) : MainIntent
    /** 展示一次错误提示(如语音识别失败) */
    data class ShowError(val message: String) : MainIntent
    /** 打开设置页(顶栏入口或错误提示的引导) */
    data object OpenSettings : MainIntent
    /** 删除一条任务(右划露出删除按钮) */
    data class DeleteTask(val taskId: Long) : MainIntent
}

/** 一次性副作用(不放进 State,避免旋转/重组时重复触发) */
sealed interface MainEffect {
    /** 提交成功后跳转到任务详情,观察执行过程 */
    data class NavigateToTask(val taskId: Long) : MainEffect

    /** 引导跳转到设置页(API Key 未配置等) */
    data object NavigateToSettings : MainEffect
}

/** 默认演示指令:短信(需确认)在前、闹钟在后,便于观察"允许→✅→继续执行下一个步骤" */
internal const val DEFAULT_INSTRUCTION = "先给妈妈发短信说晚点回家,再设个明早7点的闹钟"

/** 状态 → 展示文案(展示逻辑收敛在 VM 侧,UI 不重复实现) */
internal fun TaskStatus.displayText(): String = when (this) {
    TaskStatus.PENDING -> "待执行"
    TaskStatus.RUNNING -> "执行中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.SUCCESS -> "成功"
    TaskStatus.FAILED -> "失败"
}
