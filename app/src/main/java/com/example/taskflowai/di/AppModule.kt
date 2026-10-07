package com.example.taskflowai.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** app 生命周期级别的协程作用域(用于 DataStore → 内存的长期镜像等) */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** DataStore 文件名 */
private val Context.settingsDataStore by preferencesDataStore(name = "taskflow_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * WorkManager 实例。注意:自定义 HiltWorkerFactory 后,WorkManagerInitializer
     * 已从 Manifest 移除(见 AndroidManifest),初始化由 [com.example.taskflowai.TaskFlowApp]
     * 提供的 Configuration 完成;这里在首次注入(晚于 Application.onCreate)时取用,顺序安全。
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /** 设置持久化(Preferences DataStore) */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
