package com.example.taskflowai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface KnowledgeDao {

    @Insert
    suspend fun insert(entity: KnowledgeEntity)

    /**
     * 全量取回参与相似度计算。
     * 演示规模(几十~几千条)下直接遍历即可;生产级(十万级)应换向量索引
     * (如逐条分页扫描或引入 HNSW 近似最近邻),接口保持不变。
     */
    @Query("SELECT * FROM knowledge")
    suspend fun getAll(): List<KnowledgeEntity>

    /** 同一指令只保留最新一条,避免重复任务把 Few-Shot 名额占满 */
    @Query("DELETE FROM knowledge WHERE instruction = :instruction")
    suspend fun deleteByInstruction(instruction: String)

    /** 维度自愈:服务商切换后,用当前引擎重新嵌入并回写 */
    @Query("UPDATE knowledge SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray)

    @Query("SELECT COUNT(*) FROM knowledge")
    suspend fun count(): Int

    /** 清除全部记忆(设置页"清除记忆") */
    @Query("DELETE FROM knowledge")
    suspend fun deleteAll()
}
