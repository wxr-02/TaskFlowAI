package com.example.taskflowai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskStatus
import com.example.taskflowai.data.local.TaskWithSteps
import com.example.taskflowai.data.repository.SettingsRepository
import com.example.taskflowai.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Home 屏 ViewModel(MVI)。
 *
 * 单向数据流:[uiState] = Room 的任务 Flow ⊕ 本地交互状态(MutableStateFlow + update),
 * UI 只渲染它;所有用户操作经 [onIntent] 进入,导航等副作用经 [effects] 出去。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 纯本地交互状态(不来自数据库) */
    private data class LocalState(
        val input: String = DEFAULT_INSTRUCTION,
        val submitting: Boolean = false,
        val error: String? = null,
        /** 错误是否应引导用户去设置页 */
        val canGoSettings: Boolean = false,
    )

    private val local = MutableStateFlow(LocalState())

    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<MainEffect> = _effects.asSharedFlow()

    /** 唯一的状态出口:任务列表(实时)+ 本地状态 → 不可变 UiState */
    val uiState: StateFlow<MainUiState> =
        combine(repository.observeTasks(), local) { tasks, localState ->
            MainUiState(
                input = localState.input,
                submitting = localState.submitting,
                error = localState.error,
                canGoSettings = localState.canGoSettings,
                tasks = tasks.map { it.toUiModel() },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(input = DEFAULT_INSTRUCTION),
        )

    /** 唯一的状态入口:处理 UI Intent */
    fun onIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.InputChanged -> local.update { it.copy(input = intent.text) }
            MainIntent.Submit -> submit()
            MainIntent.ErrorShown -> local.update { it.copy(error = null, canGoSettings = false) }
            is MainIntent.VoiceResult -> {
                // 语音识别完成:填入结果并自动发送(prompt6:松开后自动填入并发送)
                local.update { it.copy(input = intent.text) }
                submit()
            }
            is MainIntent.ShowError -> local.update { it.copy(error = intent.message, canGoSettings = false) }
            MainIntent.OpenSettings -> viewModelScope.launch { _effects.emit(MainEffect.NavigateToSettings) }
            is MainIntent.DeleteTask -> viewModelScope.launch {
                repository.deleteTask(intent.taskId)
            }
        }
    }

    private fun submit() {
        val instruction = local.value.input.trim()
        if (instruction.isEmpty() || local.value.submitting) return

        // 防呆①:API Key 未配置 → 禁止发送,Snackbar 引导去设置页(不崩溃)
        if (!settingsRepository.settings.value.isApiKeyConfigured) {
            local.update {
                it.copy(error = "请先在设置中配置 API Key,再发送任务", canGoSettings = true)
            }
            return
        }

        viewModelScope.launch {
            local.update { it.copy(submitting = true, error = null, canGoSettings = false) }
            try {
                val taskId = repository.submitInstruction(instruction)
                local.update { it.copy(submitting = false, input = "") }
                // 副作用:导航到详情,立刻观察任务执行
                _effects.emit(MainEffect.NavigateToTask(taskId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 防呆②:网络类异常 → 友好文案 + "去设置"引导
                local.update { it.copy(submitting = false, error = e.toUserMessage(), canGoSettings = true) }
            }
        }
    }

    private companion object {

        fun TaskWithSteps.toUiModel(): TaskUiModel {
            val total = orderedSteps.size
            val done = orderedSteps.count { it.status == StepStatus.SUCCESS }
            return TaskUiModel(
                id = task.id,
                instruction = task.instruction,
                answer = task.answer,
                status = task.status,
                statusText = task.status.displayText(),
                timeText = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(task.createdAt)),
                completedSteps = done,
                totalSteps = total,
                progress = when {
                    total > 0 -> done.toFloat() / total
                    task.status == TaskStatus.SUCCESS -> 1f
                    else -> 0f
                },
            )
        }
    }
}

/**
 * 异常 → 用户可读文案(全局防呆):
 * - 网络层异常(UnknownHost / SocketTimeout / Connect 等,均为 [IOException] 子类)→ 引导检查设置;
 * - 401 → 提示 API Key 问题;
 * - 其余 → 保留原始信息便于排查。
 */
private fun Throwable.toUserMessage(): String = when {
    this is HttpException && code() == 401 -> "API Key 无效或未授权,请到设置中检查"
    this is IOException -> "网络连接失败,请检查设置"
    else -> "请求失败:${message ?: "未知错误"}"
}
