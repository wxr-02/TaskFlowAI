package com.example.taskflowai.domain.tool

/*
 * 工具执行结果 —— 密封类(sealed class)设计。
 *
 * 为什么是 sealed:工具的"结局"是一个封闭集合,调用方(ViewModel / 未来的 Agent 编排器)
 * 用 when 穷尽处理;将来新增一种结局时,编译器会强制所有调用点补全分支,不会遗漏。
 *
 * 关键洞见:后两个状态不是"失败",而是 agent 循环中的**正常控制信号**——
 *   - 缺权限   → 上层去发起权限申请,拿到后再重试;
 *   - 需确认   → 上层弹 UI 让用户拍板,确认后带"已确认"上下文重试。
 * 所以它们绝不能用异常(throw)表达:异常意味着意外,而这里是预设好的流程。
 */
sealed class ToolResult {

    /**
     * 执行成功。
     * @param message 给用户/LLM 读的自然语言结果
     * @param data    可选的结构化数据(如天气对象),留给后续阶段做 RAG/展示用
     */
    data class Success(val message: String, val data: Any? = null) : ToolResult()

    /** 执行失败(参数错误、系统拒绝、IO 异常等),交给 LLM 纠正参数重试或向用户解释 */
    data class Failure(val error: String) : ToolResult()

    /** 缺少运行时权限:返回需要的权限列表,由上层决定何时/如何申请,工具本身不弹窗 */
    data class RequiresPermission(val permissions: List<String>) : ToolResult()

    /** 敏感操作需要用户确认:prompt 是给用户看的确认文案,确认后用 withConfirmation 重试 */
    data class RequiresUserConfirmation(val prompt: String) : ToolResult()
}
