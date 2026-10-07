package com.example.taskflowai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.taskflowai.data.local.LlmProvider
import com.example.taskflowai.data.local.LlmSettings
import com.example.taskflowai.data.repository.KnowledgeRepository
import com.example.taskflowai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置屏 ViewModel(MVI)。
 *
 * 采用"草稿"模式:输入先改本地 draft,点保存才写入 DataStore——
 * 避免用户改到一半(比如 Key 输了一半)就把半成品配置生效到网络层。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val knowledgeRepository: KnowledgeRepository,
) : ViewModel() {

    private data class Draft(val apiKey: String, val provider: LlmProvider, val baseUrl: String)

    private val draft = MutableStateFlow(Draft(apiKey = "", provider = LlmProvider.DEEPSEEK, baseUrl = ""))
    private val knowledgeCount = MutableStateFlow(0)
    private val message = MutableStateFlow<String?>(null)

    /** 用户是否动过草稿;未动过时跟随已保存设置(处理 DataStore 异步加载完成前的空窗) */
    private var userEdited = false

    val uiState: StateFlow<SettingsUiState> =
        combine(draft, knowledgeCount, message) { draft, count, msg ->
            SettingsUiState(
                apiKey = draft.apiKey,
                provider = draft.provider,
                baseUrl = draft.baseUrl,
                knowledgeCount = count,
                message = msg,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        // 已保存的设置 → 草稿(用户开始编辑后不再覆盖)
        viewModelScope.launch {
            settingsRepository.settings.collect { saved ->
                if (!userEdited) {
                    draft.value = Draft(saved.apiKey, saved.provider, saved.baseUrl)
                }
            }
        }
        refreshKnowledgeCount()
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.ApiKeyChanged -> {
                userEdited = true
                draft.update { it.copy(apiKey = intent.value) }
            }

            is SettingsIntent.ProviderSelected -> {
                userEdited = true
                // 切换服务商:预填该服务商默认地址(用户仍可自由改为自定义地址)
                draft.update {
                    it.copy(provider = intent.provider, baseUrl = LlmSettings.defaultBaseUrl(intent.provider))
                }
            }

            is SettingsIntent.BaseUrlChanged -> {
                userEdited = true
                draft.update { it.copy(baseUrl = intent.value) }
            }

            SettingsIntent.Save -> save()
            SettingsIntent.ClearMemory -> clearMemory()
            SettingsIntent.MessageShown -> message.value = null
        }
    }

    private fun save() {
        viewModelScope.launch {
            val current = draft.value
            settingsRepository.update(
                apiKey = current.apiKey,
                provider = current.provider,
                baseUrl = current.baseUrl,
            )
            userEdited = false // 保存后恢复"跟随已保存设置"
            message.value = "设置已保存"
        }
    }

    private fun clearMemory() {
        viewModelScope.launch {
            knowledgeRepository.clear()
            refreshKnowledgeCount()
            message.value = "记忆已清空,应用将重新开始学习"
        }
    }

    private fun refreshKnowledgeCount() {
        viewModelScope.launch {
            knowledgeCount.value = knowledgeRepository.count()
        }
    }
}
