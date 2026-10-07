package com.example.taskflowai.data.remote

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * OpenAI 兼容协议的 Retrofit 接口。
 *
 * 接口方法用完整 [Url] 而不是相对路径 —— 因为 Base URL 在设置页可**运行时切换**
 * (DeepSeek / Qwen / 自定义),而 Retrofit 的 baseUrl 构建后不可变;
 * 由 [LlmClient] 根据当前设置拼接完整地址。
 *
 * 鉴权(Authorization: Bearer xxx)由 OkHttp 拦截器在**请求时刻**从设置中读取并添加。
 */
interface LlmApiService {

    /** 对话补全(支持 Function Calling:请求带 tools,响应里可能返回 tool_calls) */
    @POST
    suspend fun chatCompletion(@Url url: String, @Body request: ChatRequest): ChatResponse

    /** 文本嵌入(用于 RAG) */
    @POST
    suspend fun embedding(@Url url: String, @Body request: EmbeddingRequest): EmbeddingResponse
}
