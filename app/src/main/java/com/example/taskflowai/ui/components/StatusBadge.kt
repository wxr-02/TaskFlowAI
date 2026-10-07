package com.example.taskflowai.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.taskflowai.data.local.StepStatus
import com.example.taskflowai.data.local.TaskStatus

/*
 * 状态可视化组件 —— 动画的落点:
 * 状态值(来自 MVI UiState)变化时,Crossfade 自动做交叉淡入淡出("⏳ 变 ✅" 的实现)。
 * UI 不需要知道"什么时候该动"—— 状态驱动动画,这正是 MVI + Compose 的组合方式。
 */

/** 文字状态徽章:文字或颜色变化时交叉淡入 */
@Composable
fun AnimatedStatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = text to color,
        animationSpec = tween(durationMillis = 260),
        label = "statusBadge",
        modifier = modifier,
    ) { (value, valueColor) ->
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = valueColor,
            modifier = Modifier
                .border(1.dp, valueColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 步骤状态节点(Stepper 左侧圆形节点)。
 * 内部用 Crossfade 在 编号/转圈/⚠️/✅/✕ 之间切换 —— 步骤状态每变一次,画面就"流动"一次。
 */
@Composable
fun StepStatusNode(order: Int, status: StepStatus, modifier: Modifier = Modifier) {
    val content = stepStatusColor(status)
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(content.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = status,
            animationSpec = tween(durationMillis = 320),
            label = "stepStatusNode",
        ) { value ->
            when (value) {
                StepStatus.PENDING -> Text(
                    text = order.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                )

                StepStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )

                StepStatus.WAITING_USER -> Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "等待用户",
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )

                StepStatus.SUCCESS -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "成功",
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )

                StepStatus.FAILED -> Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "失败",
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun taskStatusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TaskStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    TaskStatus.SUCCESS -> Color(0xFF2E7D32)
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
}

@Composable
fun stepStatusColor(status: StepStatus): Color = when (status) {
    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
    StepStatus.WAITING_USER -> MaterialTheme.colorScheme.tertiary
    StepStatus.SUCCESS -> Color(0xFF2E7D32)
    StepStatus.FAILED -> MaterialTheme.colorScheme.error
}
