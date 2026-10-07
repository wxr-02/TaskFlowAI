package com.example.taskflowai.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * OpenAI 兼容协议的 Embeddings(文本嵌入)数据模型。
 * 用于后续 RAG 阶段:把文本转向量,存进向量库做相似度检索。
 */

/**
 * Embedding 请求体。
 *
 * @param input 支持两种形式(协议规定):单个字符串,或字符串数组。
 *              用 [JsonElement] 统一承载 —— 字符串序列化成 "text",
 *              数组序列化成 ["a","b"]。推荐用 [ofText] / [ofTexts] 工厂方法构造。
 */
@Serializable
data class EmbeddingRequest(
    val model: String,
    val input: JsonElement,
) {
    companion object {
        /** 单个字符串形式 */
        fun ofText(model: String, text: String): EmbeddingRequest =
            EmbeddingRequest(model, JsonPrimitive(text))

        /** 批量字符串数组形式 */
        fun ofTexts(model: String, texts: List<String>): EmbeddingRequest =
            EmbeddingRequest(model, JsonArray(texts.map { JsonPrimitive(it) }))
    }
}

/** 一条嵌入结果 */
@Serializable
data class EmbeddingData(
    val index: Int = 0,
    /** 文本对应的向量(通常 1024/1536/3072 维) */
    val embedding: List<Float> = emptyList(),
)

/** Embedding 响应体 */
@Serializable
data class EmbeddingResponse(
    val model: String? = null,
    val data: List<EmbeddingData> = emptyList(),
    val usage: Usage? = null,
)
