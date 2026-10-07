package com.example.taskflowai.domain.tool.impl

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract
import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.TimeZone
import javax.inject.Inject

/**
 * 添加日历日程。
 *
 * 实现方式:直接通过 ContentResolver 写入系统日历 —— 这是"敏感系统数据",
 * 所以先走权限预检(WRITE_CALENDAR),权限不足时返回 [ToolResult.RequiresPermission] 由上层处理。
 *
 * 注:生产实现还需 READ_CALENDAR 来查询可写日历 ID(此处演示用默认日历 ID)。
 */
class AddCalendarEventTool @Inject constructor() : Tool {

    override val name = "add_calendar_event"

    override val description = "向系统日历添加一个日程。startTime/endTime 为 Unix 毫秒时间戳。"

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "title":     { "type": "string",  "description": "日程标题" },
            "startTime": { "type": "integer", "description": "开始时间,Unix 毫秒时间戳" },
            "endTime":   { "type": "integer", "description": "结束时间,Unix 毫秒时间戳" }
          },
          "required": ["title", "startTime", "endTime"]
        }
    """.trimIndent()

    override val requiredPermissions: List<String> = listOf(Manifest.permission.WRITE_CALENDAR)

    override suspend fun execute(args: JSONObject, context: ToolContext): ToolResult {
        val title = args.optString("title").trim()
        val start = args.optLong("startTime", 0L)
        val end = args.optLong("endTime", 0L)
        if (title.isEmpty() || start <= 0L || end <= start) {
            return ToolResult.Failure("参数不完整或时间非法:需要 title、startTime、endTime(毫秒时间戳,且 endTime > startTime)")
        }

        // 权限预检:统一用声明列表,不满足即拦截
        if (!requiredPermissions.all { context.hasPermission(it) }) {
            return ToolResult.RequiresPermission(requiredPermissions)
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return withContext(Dispatchers.IO) {
            try {
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    ToolResult.Success("已添加日程:$title")
                } else {
                    ToolResult.Failure("日历插入失败(未找到可写日历)")
                }
            } catch (e: Exception) {
                ToolResult.Failure("日历写入异常:${e.message}")
            }
        }
    }
}
