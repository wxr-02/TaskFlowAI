package com.example.taskflowai.ui.screens

import com.example.taskflowai.data.local.LlmProvider

/*
 * MVI - 设置屏契约(State / Intent 设计与其他屏一致)。
 */

data class SettingsUiState(
    /** API Key(UI 以密码格式 *** 显示) */
    val apiKey: String = "",
    val provider: LlmProvider = LlmProvider.DEEPSEEK,
    /** Base URL(可自定义;切换服务商时预填默认值) */
    val baseUrl: String = "",
    /** 当前 RAG 知识库条数(清除记忆后即时刷新) */
    val knowledgeCount: Int = 0,
    /** 一次性提示(保存成功/记忆已清空),展示后经 MessageShown 清除 */
    val message: String? = null,
)

sealed interface SettingsIntent {
    data class ApiKeyChanged(val value: String) : SettingsIntent
    data class ProviderSelected(val provider: LlmProvider) : SettingsIntent
    data class BaseUrlChanged(val value: String) : SettingsIntent
    data object Save : SettingsIntent
    data object ClearMemory : SettingsIntent
    data object MessageShown : SettingsIntent
}
