package com.example.taskflowai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 任务整体状态:待执行 → 执行中 → (可暂停) → 成功/失败 */
enum class TaskStatus { PENDING, RUNNING, PAUSED, SUCCESS, FAILED }

/**
 * 任务实体:一条用户指令对应一个任务,任务由若干步骤([StepEntity])组成。
 * Room 是任务状态的唯一事实来源 —— Worker/进程随时可以死掉,重启后一切从库里恢复。
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 用户的自然语言指令,如"给妈妈发短信说晚点回家" */
    val instruction: String,

    /** 创建时间(Unix 毫秒时间戳) */
    val createdAt: Long,

    val status: TaskStatus = TaskStatus.PENDING,

    /** 大模型的真实文本回答(规划前的话术 / 无需工具时的完整回答);无则为 null */
    val answer: String? = null,
)
