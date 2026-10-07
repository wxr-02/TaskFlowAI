package com.example.taskflowai.data.repository

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.taskflowai.data.local.StepDao
import com.example.taskflowai.data.local.StepEntity
import com.example.taskflowai.data.local.TaskDao
import com.example.taskflowai.data.local.TaskEntity
import com.example.taskflowai.data.local.TaskStatus
import com.example.taskflowai.data.local.TaskWithSteps
import com.example.taskflowai.data.remote.ChatMessage
import com.example.taskflowai.data.remote.LlmClient
import com.example.taskflowai.data.worker.TaskExecutionWorker
import com.example.taskflowai.domain.agent.PromptBuilder
import com.example.taskflowai.domain.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 任务仓库:统筹"规划 → 持久化 → 后台执行 → 恢复"的完整链路。
 *
 * 入队策略(供"中断/恢复"配合):
 * - 每个任务用独立的唯一工作名("task-execution-$taskId") + [ExistingWorkPolicy.APPEND_OR_REPLACE]:
 *   同一任务的多次请求会串行执行而不是并发推进;
 * - 由于 [com.example.taskflowai.data.executor.TaskExecutor] 有状态守卫(只响应 WAITING_USER 的步骤),
 *   重复或过期的决定是**幂等**的(no-op),不会造成重复执行。
 */
@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val stepDao: StepDao,
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val workManager: WorkManager,
    private val knowledgeRepository: KnowledgeRepository,
) {

    /** UI 实时观察:任务列表 + 步骤(Flow 来自 Room,任意状态变化自动推送) */
    fun observeTasks(): Flow<List<TaskWithSteps>> = taskDao.observeAllWithSteps()

    fun observeTask(taskId: Long): Flow<TaskWithSteps?> = taskDao.observeById(taskId)

    /**
     * 删除任务(含步骤,外键级联)。
     * 若任务正在执行:Worker 对已删除行的更新会安全地 no-op;
     * 若任务处于挂起态:后续的"确认/授权"恢复会因查不到任务而安全退出。
     * 知识库(学习记忆)独立管理,不受删除影响。
     */
    suspend fun deleteTask(taskId: Long) {
        taskDao.deleteById(taskId)
        Log.d(TAG, "task #$taskId deleted")
    }

    /**
     * 提交一条指令:
     * 1. 先落库一个 PENDING 任务(哪怕随后进程死掉,任务也不会丢);
     * 2. 让 LLM 规划出工具调用序列;
     * 3. 每个 tool_call 落库为一个步骤(orderIndex = 调用顺序);
     * 4. 入队 Worker 开始后台执行。
     */
    suspend fun submitInstruction(instruction: String): Long {
        val taskId = taskDao.insert(
            TaskEntity(
                instruction = instruction,
                createdAt = System.currentTimeMillis(),
                status = TaskStatus.PENDING,
            )
        )
        Log.d(TAG, "submit #$taskId: $instruction")

        // ===== RAG:检索相似历史 → 与基础指令拼成带 Few-Shot 的规划提示词 =====
        // 检索在 Dispatchers.Default 执行(见 KnowledgeRepository),规划期间 UI 不阻塞。
        val history = knowledgeRepository.retrieveSimilar(instruction)
        val systemPrompt = PromptBuilder.buildPlannerPrompt(history)
        Log.d(
            TAG,
            "planning with ${history.size} few-shot example(s), system prompt ${systemPrompt.length} chars",
        )

        val response = try {
            llmClient.chat(
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = instruction),
                ),
                tools = toolRegistry.toolDefs(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 防呆:规划失败(网络等)不能把任务留在"待执行"里当孤儿 ——
            // 标记 FAILED,并把失败原因存进 answer,列表/详情直接可见。
            taskDao.setAnswer(taskId, "模型规划失败:${e.message ?: "未知错误"}")
            taskDao.updateStatus(taskId, TaskStatus.FAILED)
            Log.w(TAG, "task #$taskId planning failed: ${e.message}")
            throw e // 继续抛给 UI 弹 Snackbar 提示
        }
        val assistantMessage = response.choices.firstOrNull()?.message
        val toolCalls = assistantMessage?.toolCalls.orEmpty()

        // 大模型的真实文本回答:无需工具时是完整回答;触发工具时是规划前的开场话术
        val answer = assistantMessage?.content?.takeIf { it.isNotBlank() }
        if (answer != null) {
            taskDao.setAnswer(taskId, answer)
            Log.d(TAG, "task #$taskId answer: «${answer.take(50)}…»")
        }

        if (toolCalls.isEmpty()) {
            // 模型判断无需调用工具:这是一次真实问答,任务带着回答直接完成
            taskDao.updateStatus(taskId, TaskStatus.SUCCESS)
            Log.d(TAG, "task #$taskId: 无需工具,真实回答已保存")
            return taskId
        }

        stepDao.insertAll(
            toolCalls.mapIndexed { index, call ->
                StepEntity(
                    taskId = taskId,
                    toolName = call.function.name,
                    argumentsJson = call.function.arguments,
                    orderIndex = index,
                    // Agent 闭环:保存工具调用 id,执行完把结果回灌给模型时按协议配对
                    toolCallId = call.id,
                )
            }
        )
        Log.d(TAG, "task #$taskId planned ${toolCalls.size} step(s): ${toolCalls.map { it.function.name }}")

        enqueue(taskId, TaskExecutionWorker.request(taskId))
        return taskId
    }

    /**
     * 用户对挂起步骤作出决定(确认/取消、去授权之后):
     * 再次入队 Worker,由引擎携决定从该步骤恢复执行。
     */
    suspend fun resolvePendingStep(taskId: Long, stepId: Long, approved: Boolean) {
        Log.d(TAG, "resolve #$taskId step=$stepId approved=$approved")
        enqueue(taskId, TaskExecutionWorker.request(taskId, resumeStepId = stepId, userApproved = approved))
    }

    private fun enqueue(taskId: Long, request: OneTimeWorkRequest) {
        // 每个任务一条串行工作链:同名请求按加入顺序执行(APPEND_OR_REPLACE),
        // 不会出现两个 Worker 并发推进同一个任务。
        workManager.enqueueUniqueWork(
            "task-execution-$taskId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private companion object {
        const val TAG = "TaskRepository"
    }
}
