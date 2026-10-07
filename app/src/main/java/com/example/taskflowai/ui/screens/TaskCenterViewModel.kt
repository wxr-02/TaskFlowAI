package com.example.taskflowai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.taskflowai.data.local.TaskWithSteps
import com.example.taskflowai.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 任务中心 ViewModel。
 *
 * [tasks] 直接来自 Room 的 Flow —— 引擎在后台每写一次状态(甚至 Worker 在另一进程时刻),
 * UI 都会自动重组,无需任何刷新逻辑。
 */
@HiltViewModel
class TaskCenterViewModel @Inject constructor(
    private val repository: TaskRepository,
) : ViewModel() {

    private val _input = MutableStateFlow(DEFAULT_INSTRUCTION)
    val input: StateFlow<String> = _input.asStateFlow()

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 任务列表(含步骤),实时刷新 */
    val tasks: StateFlow<List<TaskWithSteps>> = repository.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onInputChange(value: String) {
        _input.value = value
    }

    /** 点预设指令:仅填充输入框 */
    fun fillInput(value: String) {
        _input.value = value
    }

    /** 提交指令:LLM 规划 → 落库 → 入队后台执行 */
    fun submit() {
        val instruction = _input.value.trim()
        if (instruction.isEmpty() || _submitting.value) return

        viewModelScope.launch {
            _submitting.value = true
            _error.value = null
            try {
                repository.submitInstruction(instruction)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "规划失败:${e.message}"
            } finally {
                _submitting.value = false
            }
        }
    }

    /** 用户批准挂起的步骤(确认执行 / 已在系统里授权) */
    fun approveStep(taskId: Long, stepId: Long) = resolve(taskId, stepId, approved = true)

    /** 用户取消挂起的步骤(该步失败、任务失败) */
    fun rejectStep(taskId: Long, stepId: Long) = resolve(taskId, stepId, approved = false)

    private fun resolve(taskId: Long, stepId: Long, approved: Boolean) {
        viewModelScope.launch {
            try {
                repository.resolvePendingStep(taskId, stepId, approved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "操作失败:${e.message}"
            }
        }
    }

    private companion object {
        const val DEFAULT_INSTRUCTION = "给妈妈发短信说晚点回家"
    }
}
