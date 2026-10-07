package com.example.taskflowai.data.remote

import android.util.Log
import com.example.taskflowai.data.local.LlmSettings
import com.example.taskflowai.data.repository.SettingsRepository
import com.example.taskflowai.domain.tool.ToolDef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 统一客户端:上层(ViewModel / Agent)只依赖这个类,不直接碰 Retrofit。
 *
 * 配置(API Key / Base URL / 模型名)在**每次请求时**从 [SettingsRepository] 读取,
 * 支持用户在设置页运行时切换服务商 —— 无需重启或重建网络栈。
 */
@Singleton
class LlmClient @Inject constructor(
    private val api: LlmApiService,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 发起一轮对话。
     *
     * @param messages 完整上下文(包含 system / 历史消息 / 本次 user 消息)
     * @param tools    可选工具列表;传了则模型可能返回 tool_calls(Agent 用)
     */
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDef>? = null): ChatResponse {
        val settings = settingsRepository.settings.value
        requireApiKey(settings)
        Log.d(
            TAG,
            "chat -> ${settings.baseUrl}chat/completions, model=${settings.chatModel}, tools=${tools?.size ?: 0}",
        )

        val request = ChatRequest(
            model = settings.chatModel,
            messages = messages,
            // 空列表等价于"没有工具":直接省略字段,避免部分服务端对 "tools": [] 报错。
            // ToolDef → ToolDefinition 的映射保证输出严格的 OpenAI Function Calling 格式:
            // [{ "type": "function", "function": { "name", "description", "parameters": <JSON Schema> } }]
            tools = tools?.takeIf { it.isNotEmpty() }?.map { it.toToolDefinition() },
            temperature = LlmConfig.DEFAULT_TEMPERATURE,
        )
        return api.chatCompletion(buildUrl(settings.baseUrl, "chat/completions"), request)
    }

    /**
     * 把一段文本转成向量(用于 RAG)。
     * 注意:DeepSeek 没有 embeddings 接口(会 404),调用方应做好本地回退;
     * 切 Qwen(DashScope)后即为真实语义向量。
     */
    suspend fun embed(text: String): List<Float> {
        val settings = settingsRepository.settings.value
        requireApiKey(settings)
        Log.d(TAG, "embed -> ${settings.baseUrl}embeddings, model=${settings.embeddingModel}")

        val response = api.embedding(
            buildUrl(settings.baseUrl, "embeddings"),
            EmbeddingRequest.ofText(settings.embeddingModel, text),
        )
        return response.data.firstOrNull()?.embedding
            ?: error("Embedding 响应中没有数据: $response")
    }

    /** 拼接完整地址(baseUrl 容错:有无结尾斜杠均可) */
    private fun buildUrl(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path

    /** 提前给出清晰报错,而不是等服务端返回 401 */
    private fun requireApiKey(settings: LlmSettings) {
        check(settings.isApiKeyConfigured) { "API Key 为空:请先在设置中配置" }
    }

    private companion object {
        const val TAG = "LlmClient"
    }
}

/** 领域模型 -> OpenAI 工具声明(响应/请求两侧类型解耦) */
private fun ToolDef.toToolDefinition(): ToolDefinition = ToolDefinition(
    type = "function",
    function = FunctionDefinition(
        name = name,
        description = description,
        parameters = parameters,
    ),
)
