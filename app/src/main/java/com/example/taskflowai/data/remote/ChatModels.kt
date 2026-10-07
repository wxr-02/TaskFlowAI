package com.example.taskflowai.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * OpenAI 兼容协议(DeepSeek / Qwen)的 Chat Completions 数据模型。
 *
 * 字段命名有两种风格:
 * - 请求侧(我们自己构造、发给服务端):直接用 Kotlin 命名;
 * - 响应侧或下划线字段(tool_calls / finish_reason 等):用 @SerialName 映射。
 *
 * 所有可空字段都给了默认值 null,配合 Json { explicitNulls = false }:
 * 请求时值为 null 的字段不会出现在 JSON 里(例如不带 tools 就不发 "tools": null),
 * 解析响应时服务端多返回的字段也被 ignoreUnknownKeys 忽略,接口升级不会崩。
 */

/**
 * 一条对话消息。
 *
 * @param role     角色:system / user / assistant / tool
 * @param content  文本内容。为 null 的场景:assistant 只发起工具调用(tool_calls)时,
 *                 OpenAI 协议允许 content 为 null,因此这里必须可空
 * @param toolCalls 模型发起的工具调用(仅 assistant 消息),普通对话为 null
 * @param toolCallId 工具结果消息(role="tool")必须回填对应的 tool_call id,
 *                   与 assistant 消息里的 tool_calls 一一对应(协议要求)
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
)

/**
 * 模型发起的一次工具调用(响应中/回灌请求中都会出现)。
 *
 * ⚠️ [type] 故意不设默认值(与 ToolDefinition.type 同理):
 * Json 配置 encodeDefaults=false 会跳过"值等于默认值"的字段 ——
 * Agent 闭环把 tool_calls 回灌给模型时,tool_calls 里缺 `"type":"function"`
 * 会被服务端直接 422 拒绝(实测 DeepSeek:"missing field `type`")。
 * 响应解码侧:OpenAI 兼容协议始终返回该字段,不设默认值不影响解析。
 *
 * @param id       本次调用的唯一 id,后续回传工具执行结果时要带上
 * @param type     固定为 "function"
 * @param function 要调用的函数名和参数
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall,
)

/**
 * 工具调用的函数信息。
 *
 * @param arguments JSON 字符串(注意是字符串,不是对象!),如 {"city":"北京"}
 */
@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String = "",
)

/**
 * 请求侧的工具声明,对应请求体 tools 数组的元素。
 *
 * ⚠️ [type] 故意不设默认值:序列化配置 encodeDefaults = false 会跳过"值等于默认值"的
 * 字段,如果 type 有默认值 "function",即使传了也不会出现在 JSON 里,导致服务端报错。
 * 不设默认值 → 一定会被序列化。
 */
@Serializable
data class ToolDefinition(
    val type: String,
    val function: FunctionDefinition,
)

/**
 * 工具的函数描述。
 *
 * @param parameters 参数的 JSON Schema,例如:
 *                   {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
 */
@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * Chat Completion 请求体。
 *
 * @param tools       可选的工具列表,传 null 时整个字段不会出现在 JSON 中
 * @param temperature 采样温度,传 null 时使用服务端默认值
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
)

/** 一个候选回复 */
@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage,
    /** 结束原因:stop(正常结束)/ tool_calls(要调工具)/ length(截断)等 */
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

/** Token 用量统计 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)

/** Chat Completion 响应体 */
@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)
