package com.example.taskflowai.data.local

import androidx.room.Embedded
import androidx.room.Relation

/**
 * 任务 + 其全部步骤(Room 关系查询结果)。
 * UI 层通过 TaskDao 的 Flow 查询实时拿到它,任务/步骤任意一行变化都会自动重新发射。
 */
data class TaskWithSteps(
    @Embedded
    val task: TaskEntity,

    @Relation(parentColumn = "id", entityColumn = "taskId")
    val steps: List<StepEntity>,
) {
    /** Room 的 @Relation 不保证顺序,统一从这里按 orderIndex 取有序步骤 */
    val orderedSteps: List<StepEntity> get() = steps.sortedBy { it.orderIndex }
}
