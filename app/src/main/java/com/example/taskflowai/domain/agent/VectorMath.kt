package com.example.taskflowai.domain.agent

import java.nio.ByteBuffer
import kotlin.math.sqrt

/*
 * 向量数学工具 —— 纯函数、无 Android 依赖,可独立单测。
 */

/**
 * 优化版余弦相似度:**单趟循环**。
 *
 * 朴素实现要遍历三趟(点积 / |a| / |b|);这里一趟同时累加三个标量,
 * 减少内存访问次数 —— 向量维度越高(如 1024/1536 维)收益越明显。
 *
 * 更极致的生产优化:入库前对向量做 L2 归一化(模长恒为 1),
 * 余弦相似度就退化成一次点积(省掉两个 sqrt 和除法)。
 * 本项目保留通用实现,便于未来接入未归一化的远端向量。
 */
fun FloatArray.cosineSimilarity(other: FloatArray): Float {
    if (size != other.size || isEmpty()) return 0f
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in indices) {
        val a = this[i]
        val b = other[i]
        dot += a * b
        normA += a * a
        normB += b * b
    }
    val denominator = sqrt(normA) * sqrt(normB)
    return if (denominator <= 0f) 0f else dot / denominator
}

/** FloatArray → ByteArray(存入 Room BLOB) */
fun FloatArray.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES)
    buffer.asFloatBuffer().put(this)
    return buffer.array()
}

/** ByteArray → FloatArray(从 Room 取出);长度非法时返回空数组(相似度计算会安全地得到 0) */
fun ByteArray.toFloatArray(): FloatArray {
    if (isEmpty() || size % Float.SIZE_BYTES != 0) return FloatArray(0)
    val out = FloatArray(size / Float.SIZE_BYTES)
    ByteBuffer.wrap(this).asFloatBuffer().get(out)
    return out
}
