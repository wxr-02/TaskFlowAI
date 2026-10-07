package com.example.taskflowai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TaskFlow 自持闹钟的登记表。
 *
 * 为什么需要登记:系统不允许第三方应用删除"时钟应用"里的闹钟,
 * 因此助手若要能"关掉闹钟",就必须把闹钟注册为自己的(AlarmManager.setAlarmClock),
 * 并在本地记录 PendingIntent 的身份(id 即 requestCode),才能列举与取消。
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 响铃时间(Unix 毫秒时间戳) */
    val triggerAtMillis: Long,

    /** 备注标签,如 起床 */
    val label: String,

    /** 创建时间 */
    val createdAt: Long,
)
