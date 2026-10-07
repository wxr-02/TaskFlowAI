package com.example.taskflowai.domain.tool.impl

import com.example.taskflowai.data.alarm.AlarmScheduler
import com.example.taskflowai.data.local.AlarmEntity
import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolResult
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 取消闹钟 —— 只对"由 TaskFlow 设置"的闹钟生效。
 *
 * (系统不允许第三方应用删除时钟应用里的闹钟,这正是本应用自持闹钟的原因:
 *  自己注册的才能自己取消。)
 *
 * 支持三种方式,可组合:
 * - all=true           取消全部;
 * - time="HH:mm"       按时间取消;
 * - label="起床"       按标签取消(包含匹配)。
 */
class CancelAlarmTool @Inject constructor(
    private val alarmScheduler: AlarmScheduler,
) : Tool {

    override val name = "cancel_alarm"

    override val description = "取消由 TaskFlow 设置的闹钟:可取消全部(参数 all=true)、按时间(time,HH:mm)或按标签(label)。"

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "all":   { "type": "boolean", "description": "true 表示取消全部由本应用设置的闹钟" },
            "time":  { "type": "string",  "description": "按时间取消,24 小时制 HH:mm" },
            "label": { "type": "string",  "description": "按标签取消(包含匹配)" }
          }
        }
    """.trimIndent()

    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(args: JSONObject, context: ToolContext): ToolResult {
        val cancelAll = args.optBoolean("all", false)
        val time = args.optString("time").trim()
        val label = args.optString("label").trim()

        val allAlarms = alarmScheduler.listAll()
        if (allAlarms.isEmpty()) {
            return ToolResult.Failure("当前没有由 TaskFlow 设置的闹钟可以取消")
        }

        // 安全防线:未给任何条件时绝不"默认全取消"(防止模型误调用清空用户全部闹钟)
        if (!cancelAll && time.isEmpty() && label.isEmpty()) {
            return ToolResult.Failure(
                "未指定取消条件。如需取消全部请传 all=true;当前 TaskFlow 闹钟:" +
                    allAlarms.joinToString("、") { describe(it) }
            )
        }

        val targets: List<AlarmEntity> = if (cancelAll) {
            allAlarms
        } else {
            allAlarms.filter { alarm ->
                (time.isNotEmpty() && formatTime(alarm.triggerAtMillis) == time) ||
                    (label.isNotEmpty() && alarm.label.contains(label, ignoreCase = true))
            }
        }

        if (targets.isEmpty()) {
            return ToolResult.Failure(
                "没有找到匹配的闹钟。当前 TaskFlow 闹钟:${allAlarms.joinToString("、") { describe(it) }}"
            )
        }

        targets.forEach { alarmScheduler.cancel(it) }
        return ToolResult.Success(
            "已取消 ${targets.size} 个闹钟:${targets.joinToString("、") { describe(it) }}"
        )
    }

    private fun describe(alarm: AlarmEntity): String =
        "${formatTime(alarm.triggerAtMillis)}(${alarm.label})"

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))
}
