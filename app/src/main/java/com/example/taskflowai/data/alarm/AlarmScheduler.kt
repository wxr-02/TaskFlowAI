package com.example.taskflowai.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.taskflowai.MainActivity
import com.example.taskflowai.data.local.AlarmDao
import com.example.taskflowai.data.local.AlarmEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 闹钟调度器:TaskFlow 自己持有闹钟(AlarmManager.setAlarmClock)。
 *
 * 为什么自持:安卓不允许第三方应用删除"时钟应用"的闹钟 ——
 * 要让助手能"关掉闹钟",闹钟就必须由本应用注册(系统级闹钟:状态栏图标、锁屏全屏响铃),
 * 并在 [AlarmDao] 登记身份(实体 id 即 PendingIntent 的 requestCode),才可列举与取消。
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmDao: AlarmDao,
) {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** 现在起下一个 HH:mm 的毫秒时间戳(已过则算明天) */
    fun nextOccurrence(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /** 注册一个系统级闹钟,并登记到本地以便管理 */
    suspend fun schedule(triggerAtMillis: Long, label: String): AlarmEntity {
        val entity = AlarmEntity(
            triggerAtMillis = triggerAtMillis,
            label = label,
            createdAt = System.currentTimeMillis(),
        )
        val id = alarmDao.insert(entity)
        val stored = entity.copy(id = id)

        // showIntent:用户点击状态栏闹钟图标时打开本应用
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            operationFor(stored),
        )
        Log.d(TAG, "scheduled alarm #$id at $triggerAtMillis ($label)")
        return stored
    }

    /** 取消一个闹钟(系统 + 本地登记) */
    suspend fun cancel(alarm: AlarmEntity) {
        alarmManager.cancel(operationFor(alarm))
        alarmDao.deleteById(alarm.id)
        Log.d(TAG, "cancelled alarm #${alarm.id}")
    }

    suspend fun listAll(): List<AlarmEntity> = alarmDao.getAll()

    /** 闹钟已触发:清理登记 */
    suspend fun onFired(id: Long) {
        alarmDao.deleteById(id)
        Log.d(TAG, "alarm #$id fired, registry cleaned")
    }

    /**
     * PendingIntent 的身份 = requestCode + 目标组件(extras 不参与比较),
     * 因此注册/取消都用同一个 id 作为 requestCode 才能正确命中。
     */
    private fun operationFor(alarm: AlarmEntity): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            Intent(context, AlarmReceiver::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
                putExtra(AlarmReceiver.EXTRA_LABEL, alarm.label)
                putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, alarm.triggerAtMillis)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "AlarmScheduler"
    }
}
