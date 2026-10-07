package com.example.taskflowai.domain.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具定义(领域模型)。
 *
 * 这是"我们自己"的工具描述,与网络层 [com.example.taskflowai.data.remote.ToolDefinition]
 * 刻意分开:领域层不依赖网络协议,阶段 2 的 Agent 会根据 [name] 找到对应的
 * 本地实现(Tool 接口)并执行,[parameters] 则描述函数签名,用于发给 LLM。
 *
 * @param name        函数名,如 "get_weather"(建议蛇形命名,LLM 生态惯例)
 * @param description 函数用途说明,模型靠它判断什么时候调用
 * @param parameters  参数的 JSON Schema,默认空对象 {"type":"object"}
 */
data class ToolDef(
    val name: String,
    val description: String,
    val parameters: JsonObject = buildJsonObject { put("type", "object") },
)
