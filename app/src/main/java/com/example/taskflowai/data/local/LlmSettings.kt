package com.example.taskflowai.data.local

import com.example.taskflowai.data.remote.LlmConfig

/** 底层模型服务商 */
enum class LlmProvider(val displayName: String) {
    DEEPSEEK("DeepSeek(对话)"),
    QWEN("Qwen 通义千问(对话 + 向量)"),
}

/**
 * 运行时的大模型配置(DataStore 持久化,内存态供拦截器/客户端同步读取)。
 *
 * 默认值取自 [LlmConfig] 常量 —— 常量从"唯一来源"降级为"出厂默认值",
 * 用户在设置页的修改会覆盖它并持久化。
 */
data class LlmSettings(
    val apiKey: String = LlmConfig.API_KEY,
    val provider: LlmProvider = LlmProvider.DEEPSEEK,
    /** 可自定义;切换服务商时会被预填为该服务商默认地址 */
    val baseUrl: String = LlmConfig.DEEPSEEK_BASE_URL,
) {
    /** 对话模型名(随服务商切换) */
    val chatModel: String
        get() = when (provider) {
            LlmProvider.DEEPSEEK -> LlmConfig.CHAT_MODEL          // deepseek-chat
            LlmProvider.QWEN -> "qwen-plus"
        }

    /** 向量模型名 */
    val embeddingModel: String
        get() = when (provider) {
            // DeepSeek 无 embeddings 接口,调用会 404 并自动回退本地向量
            LlmProvider.DEEPSEEK -> LlmConfig.EMBEDDING_MODEL     // text-embedding-v3
            LlmProvider.QWEN -> LlmConfig.EMBEDDING_MODEL
        }

    val isApiKeyConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        /** 各服务商的默认地址(设置页切换时用于预填) */
        fun defaultBaseUrl(provider: LlmProvider): String = when (provider) {
            LlmProvider.DEEPSEEK -> LlmConfig.DEEPSEEK_BASE_URL
            LlmProvider.QWEN -> LlmConfig.QWEN_BASE_URL
        }
    }
}
