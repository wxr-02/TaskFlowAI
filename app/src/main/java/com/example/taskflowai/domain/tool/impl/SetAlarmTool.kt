package com.example.taskflowai.domain.tool.impl

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.taskflowai.data.alarm.AlarmScheduler
import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 设置闹钟 —— **由 TaskFlow 自己注册**(AlarmManager.setAlarmClock,系统级闹钟)。
 *
 * 这样设置出来的闹钟才能在之后被 [CancelAlarmTool] 关闭
 * (系统不允许第三方应用操作"时钟应用"里的闹钟)。
 * 到点由 [com.example.taskflowai.data.alarm.AlarmReceiver] 弹出全屏响铃界面。
 */
class SetAlarmTool @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val alarmScheduler: AlarmScheduler,
) : Tool {

    override val name = "set_alarm"

    override val description = "设置一个闹钟(到点会响铃)。time 为 24 小时制 HH:mm(如 07:30 表示下一个 07:30)," +
        "label 为备注。所设置的闹钟可以通过 cancel_alarm 取消。"

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "time":  { "type": "string", "description": "闹钟时间,24 小时制 HH:mm,如 07:30" },
            "label": { "type": "string", "description": "闹钟备注,如 起床" }
          },
          "required": ["time"]
        }
    """.trimIndent()

    /**
     * 声明式权限:
     * - SCHEDULE_EXACT_ALARM(API 31+,精确闹钟授权,走设置页);
     * - POST_NOTIFICATIONS(API 33+,响铃依赖通知/全屏 Intent)。
     * 低版本自动跳过检查(设备实测 Android 10 上二者均无需处理)。
     */
    override val requiredPermissions: List<String> = listOf(
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override suspend fun execute(args: JSONObject, context: ToolContext): ToolResult {
        val time = args.optString("time").trim()
        val label = args.optString("label").trim().ifEmpty { "闹钟" }

        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return ToolResult.Failure("时间格式应为 24 小时制 HH:mm(收到:\"$time\")")
        }

        // 权限预检(按系统版本条件判断)
        if (Build.VERSION.SDK_INT >= 31) {
            val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                return ToolResult.RequiresPermission(listOf(Manifest.permission.SCHEDULE_EXACT_ALARM))
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.RequiresPermission(listOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        return try {
            val triggerAt = alarmScheduler.nextOccurrence(hour, minute)
            val entity = alarmScheduler.schedule(triggerAt, label)
            val whenText = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(triggerAt))
            ToolResult.Success(
                "闹钟已设置:%s(%s),将于 $whenText 响铃,到点在应用内弹窗关闭。".format(
                    "%02d:%02d".format(hour, minute),
                    label,
                )
            )
        } catch (e: Exception) {
            ToolResult.Failure("闹钟设置失败:${e.message}")
        }
    }
}
