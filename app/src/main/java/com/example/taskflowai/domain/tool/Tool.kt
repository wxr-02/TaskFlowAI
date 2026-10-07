package com.example.taskflowai.domain.tool

import org.json.JSONObject

/**
 * 系统工具契约:工具的定义(名字/描述/schema/所需权限)与执行(execute)分离,
 * 系统能力只通过 [ToolContext] 访问 —— 工具永远不持有 Activity,UI 层也不感知工具细节。
 *
 * 新增工具 = 实现本接口 + 在 di/ToolModule 里加一行多绑定,其余代码零改动。
 */
interface Tool {

    /** 工具名,蛇形命名(与 OpenAI Function Calling 惯例一致),如 send_sms */
    val name: String

    /** 用途说明,LLM 依据它决定何时调用 */
    val description: String

    /** 参数的标准 JSON Schema,Registry 会解析后按 OpenAI tools 格式随请求发送 */
    val parametersSchema: String

    /** 该工具需要的 Android 运行时权限(声明式;执行时逐一预检,不足则返回 RequiresPermission) */
    val requiredPermissions: List<String>

    /**
     * 执行工具。
     *
     * @param args    LLM 生成的参数(已从 JSON 字符串解析)
     * @param context 工具上下文:封装 application Context,提供系统能力的唯一安全入口
     */
    suspend fun execute(args: JSONObject, context: ToolContext): ToolResult
}
