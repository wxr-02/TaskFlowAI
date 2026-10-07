package com.example.taskflowai.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.taskflowai.data.executor.TaskExecutor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 后台任务执行 Worker。
 *
 * 设计要点:**Worker 是"无状态的一次性推进器"**——
 * - 每次入队只把任务向前推进到下一个中断点或终点;
 * - 任务被挂起(PAUSED)时,executor 完成落库后正常返回,Worker 以 success 结束,
 *   不占后台配额、不重试、不阻塞任何东西;
 * - 用户确认/授权后,Repository 再次 enqueue(带上 resumeStepId 与决定),新 Worker 从库里重建进度继续执行。
 */
@HiltWorker
class TaskExecutionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val executor: TaskExecutor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return Result.failure()

        val resumeStepId = inputData.getLong(KEY_RESUME_STEP_ID, -1L)
        val userApproved = inputData.getBoolean(KEY_USER_APPROVED, false)
        Log.d(TAG, "doWork task=$taskId resumeStep=$resumeStepId approved=$userApproved")

        return try {
            if (resumeStepId > 0L) {
                executor.resumeTask(taskId, resumeStepId, userApproved)
            } else {
                executor.executeTask(taskId)
            }
            // 无论"全部完成"还是"中途挂起",本次推进使命都已结束,正常返回 success;
            // 挂起后的续跑完全依靠"用户操作 → 再次入队"。
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "task=$taskId 执行异常", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "TaskExecWorker"

        const val KEY_TASK_ID = "key_task_id"
        const val KEY_RESUME_STEP_ID = "key_resume_step_id"
        const val KEY_USER_APPROVED = "key_user_approved"

        /** 构造一次执行/恢复请求 */
        fun request(
            taskId: Long,
            resumeStepId: Long = -1L,
            userApproved: Boolean = false,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TaskExecutionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TASK_ID to taskId,
                        KEY_RESUME_STEP_ID to resumeStepId,
                        KEY_USER_APPROVED to userApproved,
                    )
                )
                .build()
    }
}
