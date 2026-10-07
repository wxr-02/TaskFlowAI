package com.example.taskflowai.domain.tool

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具上下文:工具与 Android 系统之间的唯一桥梁。
 *
 * 安全设计:
 * - 只持有 application Context(经 Hilt 注入),天然不会造成泄漏,工具也拿不到 Activity;
 * - 系统能力通过受控入口暴露(getService/启动界面/查权限),工具不能随意 startActivity;
 * - [isConfirmed] / [withConfirmation] 承载"用户已确认"状态,支撑敏感工具的两段式执行:
 *   第一次执行返回 RequiresUserConfirmation,用户确认后用 withConfirmation 生成的上下文重试。
 */
@Singleton
class ToolContext private constructor(
    private val appContext: Context,
    /** 已获用户确认的工具名集合(空集 = 尚未有确认) */
    private val confirmedTools: Set<String>,
) {

    @Inject
    constructor(@ApplicationContext appContext: Context) : this(appContext, emptySet())

    val contentResolver: ContentResolver get() = appContext.contentResolver

    /** SmsManager:API 31+ 用 getSystemService,低版本走已废弃但仍可用的 getDefault */
    fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    /** 检查运行时权限是否已授予 */
    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 安全启动系统界面:自动补 NEW_TASK。
     * 目标不存在(ActivityNotFoundException)或被系统权限策略拒绝(SecurityException,
     * 部分厂商的系统应用会要求调用方持有特定权限)时返回 false,而不是让调用方崩溃。
     */
    fun startActivitySafely(intent: Intent): Boolean = try {
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** 该工具是否已被用户确认 */
    fun isConfirmed(toolName: String): Boolean = toolName in confirmedTools

    /** 生成"已确认该工具"的新上下文(用户点确认后重试时使用) */
    fun withConfirmation(toolName: String): ToolContext =
        ToolContext(appContext, confirmedTools + toolName)
}
