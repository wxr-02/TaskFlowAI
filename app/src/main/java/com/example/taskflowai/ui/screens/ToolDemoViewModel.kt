package com.example.taskflowai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.taskflowai.data.remote.ChatMessage
import com.example.taskflowai.data.remote.LlmClient
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolRegistry
import com.example.taskflowai.domain.tool.ToolResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 工具测试界面状态。
 * 按验收要求,把"解析出的工具名/参数"与"执行结果状态"显式暴露给 UI 观察。
 */
data class ToolDemoUiState(
    /** 输入框内容(预填 prompt 示例;也可点预设指令快速填充) */
    val input: String = DEFAULT_INPUT,
    val isLoading: Boolean = false,
    /** 未触发工具时的纯文本回复 */
    val replyText: String? = null,
    /** LLM 解析出的工具名(如 send_sms) */
    val toolCallName: String? = null,
    /** LLM 生成的原始参数 JSON 字符串 */
    val toolCallArgs: String? = null,
    /** 工具执行结果(密封类状态之一) */
    val toolResult: ToolResult? = null,
    /** 网络/解析层面的异常 */
    val error: String? = null,
) {
    companion object {
        const val DEFAULT_INPUT = "给妈妈发短信说晚点回家"
    }
}

/**
 * 阶段 2 的测试 ViewModel:指令 → LLM tool_calls → 工具执行 → 确认/权限状态。
 *
 * 完整流程(阶段 3 的 Agent 循环会自动化这里的编排,现在先手动演示):
 *   1. 组装 system + user 消息,并把 ToolRegistry 的全部工具按 OpenAI 格式随请求发送;
 *   2. 解析响应的 tool_calls(工具名 + 参数);
 *   3. 交给 ToolRegistry 执行 —— 敏感操作会返回 RequiresUserConfirmation;
 *   4. 用户点"确认执行"后,用 withConfirmation 的上下文重试,走到权限闸/真实执行。
 */
@HiltViewModel
class ToolDemoViewModel @Inject constructor(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val toolContext: ToolContext,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolDemoUiState())
    val uiState: StateFlow<ToolDemoUiState> = _uiState.asStateFlow()

    fun onInputChange(value: String) = _uiState.update { it.copy(input = value) }

    /** 点预设指令:仅填充输入框,便于快速演示 */
    fun fillInput(text: String) = _uiState.update { it.copy(input = text) }

    /** 发送指令:发 LLM → 解析 tool_calls → 执行工具 → 更新状态 */
    fun sendInstruction() {
        val instruction = _uiState.value.input.trim()
        if (instruction.isEmpty() || _uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    replyText = null, toolCallName = null, toolCallArgs = null,
                    toolResult = null, error = null,
                )
            }
            try {
                val messages = listOf(
                    ChatMessage(role = "system", content = buildSystemPrompt()),
                    ChatMessage(role = "user", content = instruction),
                )
                val response = llmClient.chat(messages, tools = toolRegistry.toolDefs())

                val message = response.choices.firstOrNull()?.message
                val toolCall = message?.toolCalls?.firstOrNull()

                if (toolCall == null) {
                    // 模型判断无需工具,直接文本回复
                    _uiState.update {
                        it.copy(isLoading = false, replyText = message?.content ?: "(模型没有返回内容)")
                    }
                    return@launch
                }

                // 解析出工具名与参数并执行
                val name = toolCall.function.name
                val argsJson = toolCall.function.arguments
                val result = toolRegistry.execute(name, argsJson, toolContext)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        // 模型发起工具调用前常带有话术(如"我来帮你…"),一并展示
                        replyText = message.content?.takeIf { c -> c.isNotBlank() },
                        toolCallName = name,
                        toolCallArgs = argsJson,
                        toolResult = result,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "请求失败:${e.message}") }
            }
        }
    }

    /** 用户点"确认执行":带"已确认"上下文重试同一工具(会走到下一道闸:权限/真实执行) */
    fun confirmToolCall() {
        val state = _uiState.value
        val name = state.toolCallName ?: return
        val argsJson = state.toolCallArgs ?: return
        if (state.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val result = toolRegistry.execute(name, argsJson, toolContext.withConfirmation(name))
                _uiState.update { it.copy(isLoading = false, toolResult = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "执行失败:${e.message}") }
            }
        }
    }

    /** 用户点"取消":丢弃本次待确认的工具调用 */
    fun cancelToolCall() = _uiState.update {
        it.copy(toolCallName = null, toolCallArgs = null, toolResult = null)
    }

    /** system 提示:告诉模型当前时间(日历/闹钟需要),并强调必须用工具而非凭空回答 */
    private fun buildSystemPrompt(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm(EEEE)", Locale.CHINA).format(Date())
        return "你是 TaskFlow AI 助手,可以调用系统工具完成用户请求。当前时间:$now。" +
            "当用户请求涉及闹钟、日历、短信、天气时,必须调用对应工具,不要凭空回答。" +
            "用户只提供联系人称呼而未给号码时,number 参数直接填称呼即可(发送前会有用户确认环节)。"
    }
}
