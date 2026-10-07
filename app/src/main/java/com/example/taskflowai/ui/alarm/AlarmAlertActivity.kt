package com.example.taskflowai.ui.alarm

import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.taskflowai.data.alarm.AlarmReceiver
import com.example.taskflowai.ui.theme.TaskFlowTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏响铃界面:闹钟到点时由 fullScreenIntent 拉起(锁屏也会亮屏),
 * 循环播放系统闹铃,点"关闭闹钟"停止并结束。
 */
class AlarmAlertActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏上直接展示并点亮屏幕(与 Manifest 属性双重保障)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(AlarmReceiver.EXTRA_LABEL) ?: "闹钟"
        val triggerAt = intent.getLongExtra(AlarmReceiver.EXTRA_TRIGGER_AT, System.currentTimeMillis())
        val timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(triggerAt))

        startRingtone()

        setContent {
            TaskFlowTheme {
                AlarmAlertScreen(
                    timeText = timeText,
                    label = label,
                    onDismiss = { dismiss(alarmId) },
                )
            }
        }
    }

    private fun startRingtone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) {
                isLooping = true
            }
            play()
        }
    }

    private fun dismiss(alarmId: Long) {
        ringtone?.stop()
        if (alarmId >= 0) {
            NotificationManagerCompat.from(this).cancel(AlarmReceiver.notificationIdFor(alarmId))
        }
        finish()
    }

    override fun onDestroy() {
        ringtone?.stop()
        ringtone = null
        super.onDestroy()
    }
}

@Composable
private fun AlarmAlertScreen(
    timeText: String,
    label: String,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("⏰", fontSize = 64.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(0.7f),
            ) {
                Text("关闭闹钟")
            }
        }
    }
}
