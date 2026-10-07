package com.example.taskflowai.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json

/** 步骤状态:比任务多一个 WAITING_USER(等待用户确认/授权) */
enum class StepStatus { PENDING, RUNNING, WAITING_USER, SUCCESS, FAILED }

/** 挂起原因:决定恢复时如何"解锁"这一步 */
enum class PendingType {
    /** 敏感操作等待用户点"确认" */
    CONFIRMATION,

    /** 等待系统运行时权限被授予 */
    PERMISSION,
}

/**
 * 步骤实体:一条工具调用对应一个步骤。
 *
 * 除 prompt 要求的基础字段外,额外持久化三个"恢复语义"字段
 * (它们必须落库:Worker 是无状态的,进程可能被杀,恢复时必须知道
 *  ①在等什么 ②等哪些权限 ③用户是否已确认过):
 * - [pendingType] / [pendingData]:中断类型与细节;
 * - [confirmed]:本步骤是否已获用户确认(重放时据此带 withConfirmation 上下文)。
 */
@Entity(
    tableName = "steps",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("taskId")],
)
data class StepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val taskId: Long,

    /** 工具名,如 send_sms */
    val toolName: String,

    /** LLM 生成的参数 JSON 字符串 */
    val argumentsJson: String,

    val status: StepStatus = StepStatus.PENDING,

    /** 结果/挂起提示(给人看的文案) */
    val resultMessage: String? = null,

    /** 执行顺序,从 0 开始,由 LLM 规划出的先后顺序决定 */
    val orderIndex: Int,

    /** 挂起类型;null = 未挂起 */
    val pendingType: PendingType? = null,

    /** 挂起细节:PERMISSION 时为需要的权限列表 JSON */
    val pendingData: String? = null,

    /** 本步骤是否已获用户确认(跨中断、跨进程持久化) */
    val confirmed: Boolean = false,

    /** LLM 发起的工具调用 id(Agent 闭环:把结果回灌给模型时用于协议配对) */
    val toolCallId: String? = null,
) {
    /** 解析 PERMISSION 挂起时需要的权限列表(UI 用它发起系统权限申请) */
    fun pendingPermissions(): List<String> =
        pendingData
            ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
}
