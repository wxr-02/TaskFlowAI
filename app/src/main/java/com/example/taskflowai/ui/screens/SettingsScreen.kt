package com.example.taskflowai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.taskflowai.data.local.LlmProvider

/**
 * 设置页:模型服务配置(API Key / 服务商 / Base URL)+ 记忆管理。
 * UI 只渲染 state、发送 intent,所有持久化逻辑在 [SettingsViewModel]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.onIntent(SettingsIntent.MessageShown)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ===== 模型服务 =====
            Text("模型服务", style = MaterialTheme.typography.titleMedium)

            LlmProvider.entries.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.provider == provider,
                            onClick = { viewModel.onIntent(SettingsIntent.ProviderSelected(provider)) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.provider == provider,
                        onClick = null,
                    )
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = { viewModel.onIntent(SettingsIntent.ApiKeyChanged(it)) },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("以 *** 显示;仅保存在本机 DataStore,不会上传") },
            )

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = { viewModel.onIntent(SettingsIntent.BaseUrlChanged(it)) },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("OpenAI 兼容地址,可自定义;切换服务商会预填默认值") },
            )

            Text(
                text = "提示:选择 Qwen(DashScope)可获得真实语义向量,RAG 的「和昨天一样」" +
                    "这类指代式检索需要它;DeepSeek 暂无 embeddings 接口,将使用本地向量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { viewModel.onIntent(SettingsIntent.Save) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存设置")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // ===== 记忆管理(RAG 知识库) =====
            Text("记忆管理(RAG 知识库)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "当前知识库:${state.knowledgeCount} 条 —— 每次任务成功会自动学习;" +
                    "清除后应用回到「空白记忆」状态,可重新训练习惯。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清除记忆", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // 破坏性操作二次确认(防呆)
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除全部记忆?") },
            text = { Text("将删除全部 RAG 学习记录(当前 ${state.knowledgeCount} 条),任务历史不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onIntent(SettingsIntent.ClearMemory)
                        showClearDialog = false
                    },
                ) {
                    Text("确定清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}
