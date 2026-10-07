package com.example.taskflowai.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

/** 工具名(阶段 2 注册表的命名约定)→ 图标 */
fun toolIcon(toolName: String): ImageVector = when (toolName) {
    "send_sms" -> Icons.Filled.MailOutline
    "set_alarm" -> Icons.Filled.Notifications
    "add_calendar_event" -> Icons.Filled.DateRange
    "search_weather" -> Icons.Filled.Search
    else -> Icons.Filled.Build
}
