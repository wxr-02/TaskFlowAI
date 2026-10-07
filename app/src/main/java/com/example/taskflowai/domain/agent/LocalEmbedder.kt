package com.example.taskflowai.domain.agent

import kotlin.math.sqrt

/**
 * 本地确定性向量化 —— 离线回退方案。
 *
 * 背景:DeepSeek 未提供 embeddings 接口;若不回退,RAG 闭环在 DeepSeek Key 下
 * 完全无法运行(学习入库和检索都会失败)。这里用"字符 unigram + bigram 哈希"
 * 把文本映射到固定维度:
 *  - 相同的字/词组命中相同维度,近重复句(如"明天早上7点的闹钟"/"明天早上8点的闹钟")
 *    相似度很高,足以支撑"重复模式学习"这一核心场景;
 *  - 纯本地、离线、确定性(同一文本永远得到同一向量),演示与测试友好。
 *
 * 局限:无法理解同义改写与指代(如"和昨天一样")—— 这类语义检索请把
 * LlmConfig.BASE_URL 切到 Qwen(DashScope 的 text-embedding-v3),
 * 届时 [KnowledgeRepository][com.example.taskflowai.data.repository.KnowledgeRepository]
 * 会优先使用远端向量,本类自动退居备用。
 */
object LocalEmbedder {

    const val DIMENSION = 256

    /** bigram 比单字携带更强的顺序信息,给予更高权重 */
    private const val BIGRAM_WEIGHT = 1.5f

    fun embed(text: String): FloatArray {
        val vector = FloatArray(DIMENSION)
        val normalized = text.lowercase().filterNot { it.isWhitespace() }
        if (normalized.isEmpty()) return vector

        for (char in normalized) {
            vector[hash(char.toString()) % DIMENSION] += 1f
        }
        for (i in 0 until normalized.length - 1) {
            vector[hash(normalized.substring(i, i + 2)) % DIMENSION] += BIGRAM_WEIGHT
        }

        // L2 归一化:让余弦相似度与文本长度无关,稳定在 [0,1](词频向量非负)
        var norm = 0f
        for (value in vector) norm += value * value
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    /** FNV-1a 哈希:实现简单、分布均匀 */
    private fun hash(token: String): Int {
        var h = -2128831035 // 2166136261 作为有符号 Int(FNV offset basis)
        for (c in token) {
            h = h xor c.code
            h *= 16777619 // Int 溢出回绕即为所需行为
        }
        return h and 0x7FFFFFFF
    }
}
