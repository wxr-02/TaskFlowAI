package com.example.taskflowai.data.executor

import android.util.Log
import com.example.taskflowai.data.local.PendingType
import com.example.taskflowai.data.local.StepDao
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskDao
import com.example.taskflowai.data.local.TaskStatus
import com.example.taskflowai.data.repository.KnowledgeRepository
import com.example.taskflowai.domain.agent.TaskSummarizer
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolRegistry
import com.example.taskflowai.domain.tool.ToolResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 任务执行引擎:按 [orderIndex] 顺序执行步骤,支持"挂起/恢复"。
 *
 * 核心原则 —— **引擎无内存状态,一切以 Room 为准**:
 * 每推进一步都先把状态写库;遇到 RequiresXxx 就落库挂起标记后直接返回。
 * 因此进程被杀、Worker 结束都不会丢进度,恢复时从库里重建执行位置。
 *
 * 中断语义(与 [ToolResult] 一一对应):
 * - Success                  → 步骤 SUCCESS,继续下一步;
 * - Failure                  → 步骤 FAILED,任务 FAILED(终止);
 * - RequiresUserConfirmation → 步骤 WAITING_USER + 任务 PAUSED(等"确认");
 * - RequiresPermission       → 步骤 WAITING_USER + 任务 PAUSED(等"授权")。
 */
@Singleton
class TaskExecutor @Inject constructor(
    private val taskDao: TaskDao,
    private val stepDao: StepDao,
    private val toolRegistry: ToolRegistry,
    private val toolContext: ToolContext,
    private val knowledgeRepository: KnowledgeRepository,
    private val taskSummarizer: TaskSummarizer,
) {

    /** 从头(或从第一个未完成步骤)开始执行任务;已在终态的任务直接忽略 */
    suspend fun executeTask(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.status == TaskStatus.SUCCESS || task.status == TaskStatus.FAILED) return

        Log.d(TAG, "executeTask #$taskId: ${task.status} -> RUNNING")
        taskDao.updateStatus(taskId, TaskStatus.RUNNING)
        runSteps(taskId)
    }

    /**
     * 用户对挂起步骤作出决定后恢复执行。
     *
     * @param userApproved true=确认执行/已授权 false=取消(该步 FAILED,任务 FAILED)
     */
    suspend fun resumeTask(taskId: Long, stepId: Long, userApproved: Boolean) {
        val step = stepDao.getById(stepId) ?: return
        // 状态守卫:只有仍处于挂起态的步骤才响应"决定",重复/过期的决定直接忽略(幂等)
        if (step.status != StepStatus.WAITING_USER) {
            Log.d(TAG, "resumeTask #$taskId step=$stepId ignored (status=${step.status})")
            return
        }
        Log.d(TAG, "resumeTask #$taskId step=$stepId approved=$userApproved pending=${step.pendingType}")

        if (!userApproved) {
            stepDao.updateStatus(stepId, StepStatus.FAILED, "用户已取消")
            taskDao.updateStatus(taskId, TaskStatus.FAILED)
            return
        }

        // 批准:
        // 1) 确认类中断 → 持久化"已确认"标记(重放该步时携带 withConfirmation 上下文,
        //    且该标记跨中断/跨进程有效,不会让用户被重复要求确认);
        // 2) 权限类中断 → 用户已在系统层完成授权,直接清标记重放,执行时再做真实权限复查。
        if (step.pendingType == PendingType.CONFIRMATION) {
            stepDao.markConfirmed(stepId)
        }
        stepDao.clearPending(stepId)
        stepDao.updateStatus(stepId, StepStatus.PENDING, null)

        taskDao.updateStatus(taskId, TaskStatus.RUNNING)
        runSteps(taskId)
    }

    /** 顺序执行(跳过已完成的步骤),遇挂起/失败即返回 */
    private suspend fun runSteps(taskId: Long) {
        val steps = stepDao.getByTask(taskId)
        for (step in steps) {
            when (step.status) {
                StepStatus.SUCCESS, StepStatus.FAILED -> continue // 已完成/已终结:跳过(幂等重放的关键)
                StepStatus.WAITING_USER -> {
                    // 还有未决的挂起(不该由普通执行越过),保持暂停
                    taskDao.updateStatus(taskId, TaskStatus.PAUSED)
                    return
                }
                StepStatus.PENDING, StepStatus.RUNNING -> Unit // 可执行(曾执行到一半被杀掉的 RUNNING 也重新执行)
            }

            stepDao.updateStatus(step.id, StepStatus.RUNNING, null)
            Log.d(TAG, "task #$taskId step#${step.orderIndex} ${step.toolName}: RUNNING")

            // 已确认过的步骤用"已确认"上下文重放 —— 这是两段式确认的落点
            val context = if (step.confirmed) toolContext.withConfirmation(step.toolName) else toolContext

            when (val result = toolRegistry.execute(step.toolName, step.argumentsJson, context)) {
                is ToolResult.Success -> {
                    stepDao.updateStatus(step.id, StepStatus.SUCCESS, result.message)
                    Log.d(TAG, "task #$taskId step#${step.orderIndex}: SUCCESS")
                }

                is ToolResult.Failure -> {
                    stepDao.updateStatus(step.id, StepStatus.FAILED, result.error)
                    taskDao.updateStatus(taskId, TaskStatus.FAILED)
                    Log.d(TAG, "task #$taskId step#${step.orderIndex}: FAILED - ${result.error}")
                    return
                }

                is ToolResult.RequiresUserConfirmation -> {
                    stepDao.pause(
                        id = step.id,
                        status = StepStatus.WAITING_USER,
                        type = PendingType.CONFIRMATION,
                        data = null, // 确认类的细节就是 prompt 本身,存 resultMessage
                        message = result.prompt,
                    )
                    taskDao.updateStatus(taskId, TaskStatus.PAUSED)
                    Log.d(TAG, "task #$taskId step#${step.orderIndex}: WAITING_USER(confirmation) -> PAUSED")
                    return
                }

                is ToolResult.RequiresPermission -> {
                    stepDao.pause(
                        id = step.id,
                        status = StepStatus.WAITING_USER,
                        type = PendingType.PERMISSION,
                        data = Json.encodeToString(result.permissions),
                        message = "需要权限:${result.permissions.joinToString()}",
                    )
                    taskDao.updateStatus(taskId, TaskStatus.PAUSED)
                    Log.d(TAG, "task #$taskId step#${step.orderIndex}: WAITING_USER(permission) -> PAUSED")
                    return
                }
            }
        }

        taskDao.updateStatus(taskId, TaskStatus.SUCCESS)
        Log.d(TAG, "task #$taskId: SUCCESS(全部步骤完成)")

        // ===== 学习闭环:仅"全部步骤成功"的任务入库;失败/取消/挂起中的任务不记录 =====
        // 在 Worker 协程里完成(后台线程),embedding 生成不阻塞任何 UI。
        knowledgeRepository.remember(taskId)

        // ===== Agent 闭环:把工具结果回灌大模型,生成自然语言总结(有步骤时才需要)=====
        // 总结失败只会记日志、保留原有 answer,绝不改变任务已成功的状态。
        if (steps.isNotEmpty()) {
            taskSummarizer.summarize(taskId)?.let { summary ->
                taskDao.setAnswer(taskId, summary)
                Log.d(TAG, "task #$taskId final answer updated by summarizer")
            }
        }
    }

    private companion object {
        const val TAG = "TaskExecutor"
    }
}
