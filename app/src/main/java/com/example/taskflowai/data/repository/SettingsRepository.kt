package com.example.taskflowai.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.taskflowai.data.local.LlmProvider
import com.example.taskflowai.data.local.LlmSettings
import com.example.taskflowai.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置仓库:DataStore(Preferences)持久化 + 内存 StateFlow 缓存。
 *
 * 为什么要缓存:OkHttp 拦截器与 LlmClient 在**请求时刻**需要同步读取最新配置
 * (API Key / Base URL),不能 suspend 去读 DataStore;这里用 app 级协程把
 * DataStore 流镜像到内存 StateFlow,读写都是即时的,持久化由 DataStore 保证。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _settings = MutableStateFlow(LlmSettings())

    /** 当前设置(内存态,随 DataStore 变化实时更新) */
    val settings: StateFlow<LlmSettings> = _settings.asStateFlow()

    init {
        // app 生命周期级别的镜像:DataStore → 内存
        scope.launch {
            dataStore.data.collect { prefs ->
                _settings.value = prefs.toLlmSettings()
            }
        }
    }

    /** 保存设置(持久化 + 内存态经上面的镜像自动更新) */
    suspend fun update(apiKey: String, provider: LlmProvider, baseUrl: String) {
        dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = apiKey.trim()
            prefs[KEY_PROVIDER] = provider.name
            prefs[KEY_BASE_URL] = baseUrl.trim()
        }
    }

    private fun Preferences.toLlmSettings(): LlmSettings {
        val provider = this[KEY_PROVIDER]
            ?.let { runCatching { LlmProvider.valueOf(it) }.getOrNull() }
            ?: LlmSettings().provider
        return LlmSettings(
            apiKey = this[KEY_API_KEY] ?: LlmSettings().apiKey,
            provider = provider,
            baseUrl = this[KEY_BASE_URL] ?: LlmSettings.defaultBaseUrl(provider),
        )
    }

    private companion object {
        val KEY_API_KEY = stringPreferencesKey("llm_api_key")
        val KEY_PROVIDER = stringPreferencesKey("llm_provider")
        val KEY_BASE_URL = stringPreferencesKey("llm_base_url")
    }
}
