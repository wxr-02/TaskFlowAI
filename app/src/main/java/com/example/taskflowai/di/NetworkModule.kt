package com.example.taskflowai.di

import com.example.taskflowai.data.remote.LlmApiService
import com.example.taskflowai.data.remote.LlmConfig
import com.example.taskflowai.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络层依赖注入。
 *
 * 配置策略(阶段 6 起):API Key / Base URL 都可能被用户在设置页**运行时修改**,
 * 因此 —— Retrofit 不再绑定固定 baseUrl(接口用 @Url 传完整地址),
 * 鉴权拦截器在每次请求时从 [SettingsRepository] 读最新 Key。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** kotlinx.serialization 的 JSON 配置,Retrofit 转换器和业务代码共用同一份 */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // 服务端多返回字段直接忽略(接口升级不会导致解析崩溃)
        ignoreUnknownKeys = true
        // null 字段不参与序列化:请求里 tools / temperature 为 null 时不会出现在 JSON 中
        explicitNulls = false
        // 值为默认值的字段不发送(配合 ToolDefinition.type 故意不设默认值的设计)
        encodeDefaults = false
    }

    /** 日志拦截器:调试期打印请求/响应体,Authorization 头打码避免泄露 Key */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization")
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        settingsRepository: SettingsRepository,
    ): OkHttpClient =
        OkHttpClient.Builder()
            // 统一鉴权:每次请求时读取**当前**设置里的 API Key(支持运行时切换)
            .addInterceptor { chain ->
                val apiKey = settingsRepository.settings.value.apiKey
                val request = chain.request().newBuilder()
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor)
            // LLM 响应整体偏慢,默认 10s 容易误超时
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            // 仅占位;实际地址由接口的 @Url 提供(支持运行时切换 Base URL)
            .baseUrl(LlmConfig.DEEPSEEK_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideLlmApiService(retrofit: Retrofit): LlmApiService =
        retrofit.create(LlmApiService::class.java)
}
