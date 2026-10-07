package com.example.taskflowai.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.taskflowai.domain.tool.ToolResult

/** 预设演示指令:点击仅填充输入框,再点"发送指令"即可 */
private val PRESET_INSTRUCTIONS = listOf(
    "给妈妈发短信说晚点回家",
    "明天早上7点叫我起床",
    "北京今天天气怎么样",
    "明天下午3点开产品评审会",
)

/**
 * 阶段 2 测试界面:输入一条指令,观察完整链路 ——
 * 工具名/参数解析 → 敏感操作确认 → 权限预检拦截,每一步的状态都在下方卡片中展示。
 */
@Composable
fun ToolDemoScreen(
    modifier: Modifier = Modifier,
    viewModel: ToolDemoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TaskFlow AI", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "阶段 2 · Function Calling 工具测试",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            label = { Text("输入指令") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            minLines = 2,
        )

        // 预设指令,快速演示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESET_INSTRUCTIONS.forEach { preset ->
                AssistChip(
                    onClick = { viewModel.fillInput(preset) },
                    label = { Text(preset, maxLines = 1) },
                )
            }
        }

        Button(
            onClick = viewModel::sendInstruction,
            enabled = !state.isLoading && state.input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isLoading) "执行中…" else "发送指令")
        }

        // ===== 结果区:按验收要求,逐步展示解析与状态 =====

        state.replyText?.let { reply ->
            ResultCard(title = "💬 模型回复") { Text(reply) }
        }

        if (state.toolCallName != null) {
            ResultCard(title = "🔧 解析出工具调用") {
                Text("工具名:${state.toolCallName}", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = state.toolCallArgs.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        when (val result = state.toolResult) {
            is ToolResult.Success -> ResultCard(title = "✅ 执行成功") {
                Text(result.message)
            }

            is ToolResult.Failure -> ResultCard(title = "❌ 执行失败") {
                Text(result.error)
            }

            is ToolResult.RequiresPermission -> ResultCard(title = "🔒 权限不足,已拦截") {
                Text("该操作需要权限:${result.permissions.joinToString()}")
                Text(
                    text = "当前未授予,未执行任何动作(属预期拦截,而非崩溃)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ToolResult.RequiresUserConfirmation -> ResultCard(title = "⚠️ 敏感操作,等待用户确认") {
                Text(result.prompt)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::confirmToolCall,
                        enabled = !state.isLoading,
                    ) { Text("确认执行") }
                    OutlinedButton(
                        onClick = viewModel::cancelToolCall,
                        enabled = !state.isLoading,
                    ) { Text("取消") }
                }
            }

            null -> Unit
        }

        state.error?.let { err ->
            ResultCard(title = "❌ 出错") { Text(err) }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ResultCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
