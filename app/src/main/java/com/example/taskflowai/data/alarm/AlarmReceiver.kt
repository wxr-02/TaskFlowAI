package com.example.taskflowai.data.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.taskflowai.ui.alarm.AlarmAlertActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闹钟触发广播:到点后弹出全屏响铃界面(经 fullScreenIntent,锁屏也能亮起)+ 高优先级通知,
 * 并清理本地登记。
 */
class AlarmReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlarmEntryPoint {
        fun alarmScheduler(): AlarmScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "闹钟"
        val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, System.currentTimeMillis())
        Log.d(TAG, "alarm fired id=$alarmId label=$label")

        // 清理登记(goAsync 保证协程跑完再结束广播)
        if (alarmId > 0) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    EntryPointAccessors
                        .fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
                        .alarmScheduler()
                        .onFired(alarmId)
                } finally {
                    pending.finish()
                }
            }
        }

        // 33+ 无通知权限则无法弹出(设置闹钟时工具已做权限预检,这里双保险)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skip alarm notification")
            return
        }

        showAlarmNotification(context, alarmId, label, triggerAt)
    }

    private fun showAlarmNotification(context: Context, alarmId: Long, label: String, triggerAt: Long) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        // 渠道声音 = 系统默认闹铃音(响铃兜底:即使全屏界面未拉起,通知本身也会出声)
        if (Build.VERSION.SDK_INT >= 26) {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "闹钟提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "TaskFlow 闹钟到点提醒"
                    setSound(
                        alarmUri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            )
        }

        val fullScreenIntent = Intent(context, AlarmAlertActivity::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_TRIGGER_AT, triggerAt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            alarmId.toInt().coerceAtLeast(1),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(triggerAt))

        // 主动尝试直接拉起全屏响铃界面:应用可见/被系统豁免时立即生效;
        // 被后台启动限制拦截时(如应用完全在后台),退回 fullScreenIntent(锁屏场景由系统拉起)。
        try {
            context.startActivity(fullScreenIntent)
            Log.d(TAG, "alarm screen launched directly")
        } catch (e: Exception) {
            Log.w(TAG, "direct launch blocked, fallback to fullScreenIntent: ${e.message}")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $timeText  $label")
            .setContentText("闹钟响了,点击关闭")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentIntent, true)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        NotificationManagerCompat.from(context).notify(notificationIdFor(alarmId), notification)
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        /** v2:渠道带闹铃音(渠道一旦创建声音不可改,故升版本号) */
        const val CHANNEL_ID = "taskflow_alarm_v2"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_LABEL = "alarm_label"
        const val EXTRA_TRIGGER_AT = "alarm_trigger_at"

        fun notificationIdFor(alarmId: Long): Int = (10_000 + alarmId).toInt()
    }
}
