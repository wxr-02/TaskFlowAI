package com.example.taskflowai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * RAG 知识条目:一条"成功完成过的任务"的向量化记忆。
 *
 * 越用越聪明的基础 —— 每次任务成功,把"用户指令 + 实际调用的工具序列"连同
 * 指令的向量一起存下来;下次遇到相似指令时检索出来作为 Few-Shot 示例,
 * 让 LLM 沿用历史参数与调用方式。
 */
@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 历史任务的用户指令(检索的 key 也来自与它的相似度) */
    val instruction: String,

    /** 实际执行的工具序列 JSON:[{"tool":"set_alarm","arguments":"{...}"}, ...] */
    val toolSequenceJson: String,

    /** 指令的向量(FloatArray 序列化;远端 embedding 失败时为本地 n-gram 向量) */
    val embedding: ByteArray,
) {
    // ByteArray 作为 data class 字段需要自定义 equals/hashCode,否则按引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnowledgeEntity) return false
        return id == other.id &&
            instruction == other.instruction &&
            toolSequenceJson == other.toolSequenceJson &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + instruction.hashCode()
        result = 31 * result + toolSequenceJson.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
