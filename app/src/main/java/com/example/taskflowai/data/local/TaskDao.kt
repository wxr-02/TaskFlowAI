package com.example.taskflowai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TaskStatus)

    /** 保存大模型的真实回答(展示用) */
    @Query("UPDATE tasks SET answer = :answer WHERE id = :id")
    suspend fun setAnswer(id: Long, answer: String?)

    /** 删除任务(步骤经外键 CASCADE 自动删除) */
    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * UI 实时观察入口:任务列表 + 每个任务的步骤。
     * Room 的 InvalidationTracker 会在 tasks/steps 任意一行变化时自动重新查询并发射,
     * UI 无需轮询 —— 这就是"状态变化实时可见"的实现方式。
     */
    @Transaction
    @Query("SELECT * FROM tasks ORDER BY id DESC")
    fun observeAllWithSteps(): Flow<List<TaskWithSteps>>

    /** 单任务实时观察(详情页用) */
    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<TaskWithSteps?>
}
