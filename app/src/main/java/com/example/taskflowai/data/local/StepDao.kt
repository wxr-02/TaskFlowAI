package com.example.taskflowai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface StepDao {

    @Insert
    suspend fun insertAll(steps: List<StepEntity>)

    /** 按 orderIndex 升序取某任务的全部步骤 —— 执行引擎的顺序依据 */
    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY orderIndex ASC")
    suspend fun getByTask(taskId: Long): List<StepEntity>

    @Query("SELECT * FROM steps WHERE id = :id")
    suspend fun getById(id: Long): StepEntity?

    /** 通用状态/结果更新;message 传 null 表示清空 */
    @Query("UPDATE steps SET status = :status, resultMessage = :message WHERE id = :id")
    suspend fun updateStatus(id: Long, status: StepStatus, message: String?)

    /**
     * 挂起:单条 UPDATE 原子写入"状态 + 挂起原因 + 挂起细节 + 提示文案"。
     * 原子性保证 Flow 观察者不会看到中间态(如已 WAITING_USER 但 pendingType 还是旧的)。
     */
    @Query(
        """
        UPDATE steps
        SET status = :status, pendingType = :type, pendingData = :data, resultMessage = :message
        WHERE id = :id
        """
    )
    suspend fun pause(id: Long, status: StepStatus, type: PendingType?, data: String?, message: String?)

    /** 挂起已解决:清空挂起标记 */
    @Query("UPDATE steps SET pendingType = NULL, pendingData = NULL WHERE id = :id")
    suspend fun clearPending(id: Long)

    /** 用户已确认过本步骤(持久化,重放时据此携带"已确认"上下文) */
    @Query("UPDATE steps SET confirmed = 1 WHERE id = :id")
    suspend fun markConfirmed(id: Long)
}
