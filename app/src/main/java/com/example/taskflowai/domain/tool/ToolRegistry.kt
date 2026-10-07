package com.example.taskflowai.domain.tool

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具注册中心:@Singleton,Hilt 通过多绑定自动注入全部 [Tool] 实现
 * (见 di/ToolModule:TODO 新增工具只需加一行 @Binds @IntoSet,本类零改动)。
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val tools: Set<@JvmSuppressWildcards Tool>,
) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    init {
        // 名字是 LLM 调用工具的唯一钥匙,重复会静默覆盖,属于开发期错误,直接暴露
        require(byName.size == tools.size) {
            "存在重名工具:${tools.groupBy { it.name }.filterValues { it.size > 1 }.keys}"
        }
    }

    fun all(): List<Tool> = byName.values.toList()

    fun get(name: String): Tool? = byName[name]

    /** 转成网络层模型(JSON Schema 字符串 → JsonObject),供 [com.example.taskflowai.data.remote.LlmClient] 按 OpenAI 格式发送 */
    fun toolDefs(): List<ToolDef> = tools.map { tool ->
        ToolDef(
            name = tool.name,
            description = tool.description,
            parameters = Json.parseToJsonElement(tool.parametersSchema).jsonObject,
        )
    }

    /**
     * 统一执行入口:按名查表执行;查不到(模型幻觉出未注册工具)返回 Failure 而不是崩溃。
     *
     * @param argumentsJson LLM 生成的参数 JSON 字符串
     */
    suspend fun execute(name: String, argumentsJson: String, context: ToolContext): ToolResult {
        val tool = byName[name] ?: return ToolResult.Failure("未注册的工具:$name")
        return try {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            tool.execute(args, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure("工具 $name 执行异常:${e.message}")
        }
    }
}
