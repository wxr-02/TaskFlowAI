package com.example.taskflowai.domain.tool.impl

import com.example.taskflowai.data.alarm.AlarmScheduler
import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolResult
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 查询闹钟(只读)。
 *
 * 背景:TaskFlow 的闹钟由应用自持注册(为了可取消),**不会出现在系统时钟应用里** ——
 * 这个工具就是用户查看"我设了哪些闹钟"的入口(也可以直接问助手)。
 */
class QueryAlarmsTool @Inject constructor(
    private val alarmScheduler: AlarmScheduler,
) : Tool {

    override val name = "query_alarms"

    override val description = "查询当前由 TaskFlow 设置的闹钟列表(注意:系统时钟应用里手动设置的闹钟不在此列,任何应用都无法读取或修改它)。"

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {}
        }
    """.trimIndent()

    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(args: JSONObject, context: ToolContext): ToolResult {
        val alarms = alarmScheduler.listAll()
        if (alarms.isEmpty()) {
            return ToolResult.Success("当前没有设置任何闹钟")
        }
        val list = alarms.joinToString(";") { alarm ->
            "${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(alarm.triggerAtMillis))}(${alarm.label})"
        }
        return ToolResult.Success("当前有 ${alarms.size} 个闹钟:$list。到点会在应用内全屏响铃。")
    }
}
