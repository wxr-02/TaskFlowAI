package com.example.taskflowai.domain.tool.impl

import android.Manifest
import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolResult
import org.json.JSONObject
import javax.inject.Inject

/**
 * 发送短信 —— 展示"敏感操作双闸门"的典型样本:
 *
 * 1. 用户确认闸:[execute] 第一次调用必然返回 [ToolResult.RequiresUserConfirmation],
 *    UI 弹确认框;用户点"确认"后,调用方用 context.withConfirmation(name) 重试;
 * 2. 权限闸:确认后检查 SEND_SMS 运行时权限,不足则返回 [ToolResult.RequiresPermission];
 * 3. 两闸都通过才真正发送 —— 任何一步不满足都只返回状态,绝不崩溃。
 */
class SendSmsTool @Inject constructor() : Tool {

    override val name = "send_sms"

    override val description = "给指定手机号发送一条短信。属于敏感操作,真实发送前会要求用户确认并检查 SEND_SMS 权限。"

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "number":  { "type": "string", "description": "收件人手机号;若用户只提供了称呼(如\"妈妈\"),直接填该称呼,发送前会有人工确认环节" },
            "message": { "type": "string", "description": "短信内容" }
          },
          "required": ["number", "message"]
        }
    """.trimIndent()

    override val requiredPermissions: List<String> = listOf(Manifest.permission.SEND_SMS)

    override suspend fun execute(args: JSONObject, context: ToolContext): ToolResult {
        val number = args.optString("number").trim()
        val message = args.optString("message").trim()
        if (number.isEmpty() || message.isEmpty()) {
            return ToolResult.Failure("参数不完整:需要 number 和 message")
        }

        // 闸一:敏感操作必须经用户确认
        if (!context.isConfirmed(name)) {
            return ToolResult.RequiresUserConfirmation("即将向「$number」发送短信:「$message」。确认发送吗?")
        }

        // 闸二:系统权限预检
        if (!requiredPermissions.all { context.hasPermission(it) }) {
            return ToolResult.RequiresPermission(requiredPermissions)
        }

        // 双闸通过,真实发送
        return try {
            @Suppress("DEPRECATION")
            context.smsManager().sendTextMessage(number, null, message, null, null)
            ToolResult.Success("短信已发送至 $number")
        } catch (e: Exception) {
            ToolResult.Failure("短信发送失败:${e.message}")
        }
    }
}
