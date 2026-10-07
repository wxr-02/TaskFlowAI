package com.example.taskflowai.data.repository

import android.util.Log
import com.example.taskflowai.data.local.KnowledgeDao
import com.example.taskflowai.data.local.KnowledgeEntity
import com.example.taskflowai.data.local.StepDao
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskDao
import com.example.taskflowai.data.local.TaskStatus
import com.example.taskflowai.data.remote.LlmClient
import com.example.taskflowai.domain.agent.LocalEmbedder
import com.example.taskflowai.domain.agent.cosineSimilarity
import com.example.taskflowai.domain.agent.toByteArray
import com.example.taskflowai.domain.agent.toFloatArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RAG 知识库仓库:检索(读)+ 学习(写)。
 *
 * 高性能要点:
 * - 全部向量计算在 [Dispatchers.Default](计算密集型协程池)执行,不阻塞 UI;
 * - 余弦相似度按 [CHUNK_SIZE] 分片 **并行** 计算(每片一个协程),既利用多核,
 *   又避免"每条记录建一个协程"的调度开销。
 */
@Singleton
class KnowledgeRepository @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val taskDao: TaskDao,
    private val stepDao: StepDao,
    private val llmClient: LlmClient,
) {

    /**
     * 检索与 [query] 相似的历史任务(相似度 ≥ [SIMILARITY_THRESHOLD],取 Top [MAX_CONTEXT])。
     * 调用方(TaskRepository)在提交任务前的规划阶段使用,结果拼进 Few-Shot 提示词。
     */
    suspend fun retrieveSimilar(query: String): List<KnowledgeEntity> =
        withContext(Dispatchers.Default) {
            val all = knowledgeDao.getAll()
            if (all.isEmpty()) return@withContext emptyList()

            Log.d(
                TAG,
                "retrieveSimilar «$query» on thread=${Thread.currentThread().name}, ${all.size} record(s)",
            )

            val query = embedDetailed(query)
            val queryVector = query.vector

            // ===== 维度安全:切换服务商后(如本地 256 维 ↔ Qwen 1024 维)旧向量无法比较 =====
            // - 查询向量来自远端且维度不符:用当前引擎对该条重新嵌入并回写(自愈,一次性,限流);
            // - 查询向量来自本地回退:跳过不重写(避免把远端向量污染成本地向量);
            // 直接按维度比较,无需额外字段 —— 维度可由 ByteArray 长度推导(size / 4)。
            var reEmbedded = 0
            var skipped = 0
            val comparable = all.mapNotNull { record ->
                val stored = record.embedding.toFloatArray()
                when {
                    stored.size == queryVector.size -> record to stored

                    query.fromRemote && reEmbedded < MAX_REEMBED_PER_QUERY -> {
                        val fresh = embedDetailed(record.instruction)
                        if (fresh.vector.size == queryVector.size) {
                            knowledgeDao.updateEmbedding(record.id, fresh.vector.toByteArray())
                            reEmbedded++
                            Log.i(
                                TAG,
                                "self-heal: re-embedded #${record.id} (dim ${stored.size} → ${fresh.vector.size})",
                            )
                            record to fresh.vector
                        } else {
                            skipped++
                            null
                        }
                    }

                    else -> {
                        skipped++
                        null
                    }
                }
            }
            if (reEmbedded > 0 || skipped > 0) {
                Log.i(TAG, "dimension check: reEmbedded=$reEmbedded, skipped=$skipped (query dim=${queryVector.size})")
            }

            // 分片并行:chunk → async(Default 池) → awaitAll → 拉平
            val scored = coroutineScope {
                comparable.chunked(CHUNK_SIZE)
                    .map { chunk ->
                        async {
                            chunk.map { (record, vector) ->
                                record to queryVector.cosineSimilarity(vector)
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }

            val hits = scored
                .filter { it.second >= SIMILARITY_THRESHOLD }
                .sortedByDescending { it.second }
                .take(MAX_CONTEXT)

            Log.d(TAG, "retrieveSimilar: ${hits.size} hit(s), scores=${hits.map { "%.3f".format(it.second) }}")
            hits.map { it.first }
        }

    /**
     * 学习闭环:任务完全成功后,把"指令 + 工具序列 + 向量"写入知识库。
     *
     * 防污染三守卫(只有干干净净的成功才允许入库):
     * 1. 任务状态必须是 SUCCESS;
     * 2. 步骤非空;
     * 3. 所有步骤都必须 SUCCESS(失败/取消/仍挂起的任务绝不入库)。
     */
    suspend fun remember(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.status != TaskStatus.SUCCESS) return
        val steps = stepDao.getByTask(taskId)
        if (steps.isEmpty() || steps.any { it.status != StepStatus.SUCCESS }) return

        try {
            val vector = embedDetailed(task.instruction).vector
            val toolsJson = buildJsonArray {
                steps.forEach { step ->
                    addJsonObject {
                        put("tool", step.toolName)
                        put("arguments", step.argumentsJson)
                    }
                }
            }.toString()

            // 同一指令只保留最新一条,避免重复任务占满 Few-Shot 名额
            knowledgeDao.deleteByInstruction(task.instruction)
            knowledgeDao.insert(
                KnowledgeEntity(
                    instruction = task.instruction,
                    toolSequenceJson = toolsJson,
                    embedding = vector.toByteArray(),
                )
            )
            Log.d(
                TAG,
                "learned ✔ «${task.instruction}» tools=${steps.map { it.toolName }} dim=${vector.size}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "learning failed for task #$taskId: ${e.message}")
        }
    }

    /** 当前知识条数(设置页展示 / 验收用) */
    suspend fun count(): Int = knowledgeDao.count()

    /** 清除全部记忆(设置页"清除记忆";任务数据不受影响) */
    suspend fun clear() {
        knowledgeDao.deleteAll()
        Log.d(TAG, "knowledge cleared")
    }

    /** 向量 + 来源标记(来源决定维度自愈策略:仅"远端查询向量"有资格触发重嵌入) */
    private data class EmbeddedVector(val vector: FloatArray, val fromRemote: Boolean)

    /**
     * 文本向量化:优先远端嵌入(OpenAI 兼容 /embeddings,切 Qwen 后即为真语义向量);
     * DeepSeek 无此接口或网络异常时,回退本地 n-gram 向量,保证 RAG 闭环随时可用。
     */
    private suspend fun embedDetailed(text: String): EmbeddedVector =
        try {
            EmbeddedVector(llmClient.embed(text).toFloatArray(), fromRemote = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "远端 embedding 不可用(${e.message}),回退本地 n-gram 向量")
            EmbeddedVector(LocalEmbedder.embed(text), fromRemote = false)
        }

    private companion object {
        const val TAG = "KnowledgeRepo"

        /** 相似度阈值:低于该值的历史不进入上下文(prompt 建议 0.75) */
        const val SIMILARITY_THRESHOLD = 0.75f

        /** 最多注入的历史条数(Top 3) */
        const val MAX_CONTEXT = 3

        /** 并行分片大小:每片一个协程 */
        const val CHUNK_SIZE = 16

        /** 单次检索最多自愈重嵌入的条数(限流,防止切换服务商后一次性打爆网络) */
        const val MAX_REEMBED_PER_QUERY = 20
    }
}
