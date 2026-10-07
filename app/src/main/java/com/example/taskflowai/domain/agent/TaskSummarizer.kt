package com.example.taskflowai.domain.agent

import android.util.Log
import com.example.taskflowai.data.local.StepDao
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskDao
import com.example.taskflowai.data.remote.ChatMessage
import com.example.taskflowai.data.remote.FunctionCall
import com.example.taskflowai.data.remote.LlmClient
import com.example.taskflowai.data.remote.ToolCall
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 闭环的最后一环:把工具执行结果**回灌给大模型**,生成自然语言结论。
 *
 * 协议配对(OpenAI Function Calling):
 *   system(汇报指令) → user(原始任务) → assistant(带 tool_calls) → tool × N(各步结果)
 *   → 模型产出最终文本回答。
 *
 * 设计取舍:
 * - 汇报用独立的简短 system 提示(而非复用规划提示),聚焦"如实转述、不许编造";
 * - 任何失败(网络/协议)都只记日志并返回 null —— 任务此时已经成功,总结只是增益,绝不影响任务状态。
 */
@Singleton
class TaskSummarizer @Inject constructor(
    private val taskDao: TaskDao,
    private val stepDao: StepDao,
    private val llmClient: LlmClient,
) {

    /** 任务全部步骤成功时调用;返回自然语言总结,null 表示未能生成(不阻断任何流程) */
    suspend fun summarize(taskId: Long): String? {
        val task = taskDao.getById(taskId) ?: return null
        val steps = stepDao.getByTask(taskId)
        if (steps.isEmpty() || steps.any { it.status != StepStatus.SUCCESS }) return null

        return try {
            val messages = buildList {
                add(ChatMessage(role = "system", content = SYSTEM_PROMPT))
                add(ChatMessage(role = "user", content = task.instruction))
                // assistant 的历史工具调用(协议要求与下面的 tool 结果按 id 一一配对)
                add(
                    ChatMessage(
                        role = "assistant",
                        content = task.answer,
                        toolCalls = steps.map { step ->
                            ToolCall(
                                id = step.toolCallId ?: "call_${step.id}",
                                type = "function",
                                function = FunctionCall(name = step.toolName, arguments = step.argumentsJson),
                            )
                        },
                    )
                )
                // 各工具的执行结果
                steps.forEach { step ->
                    add(
                        ChatMessage(
                            role = "tool",
                            content = step.resultMessage ?: "(无结果)",
                            toolCallId = step.toolCallId ?: "call_${step.id}",
                        )
                    )
                }
            }

            // 不带 tools:让模型直接产出文本结论
            val response = llmClient.chat(messages)
            val text = response.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            Log.d(TAG, "task #$taskId summarized: «${text?.take(60)}…»")
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "summarize failed for task #$taskId: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "TaskSummarizer"

        val SYSTEM_PROMPT = "你是 TaskFlow AI 助手。用户的任务已由系统工具执行完毕,以下是完整的执行记录。" +
            "请用简洁自然的中文向用户汇报最终结果(一到两句),关键信息(时间、温度、收件人、金额等)" +
            "必须直接取自执行结果,不要编造,也不要输出协议细节。"
    }
}
