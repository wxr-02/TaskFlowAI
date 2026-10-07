package com.example.taskflowai

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 应用入口 Application 类。
 *
 * @HiltAndroidApp 触发 Hilt 代码生成;同时实现 [Configuration.Provider],
 * 把 [HiltWorkerFactory] 交给 WorkManager —— 这是 @HiltWorker 形式的
 * CoroutineWorker 能获得依赖注入(TaskExecutor 等)的前提
 * (配套地,默认的 WorkManagerInitializer 已在 Manifest 中移除)。
 */
@HiltAndroidApp
class TaskFlowApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
