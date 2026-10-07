package com.example.taskflowai.di

import android.content.Context
import androidx.room.Room
import com.example.taskflowai.data.local.AlarmDao
import com.example.taskflowai.data.local.KnowledgeDao
import com.example.taskflowai.data.local.StepDao
import com.example.taskflowai.data.local.TaskDao
import com.example.taskflowai.data.local.TaskFlowDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TaskFlowDatabase =
        Room.databaseBuilder(context, TaskFlowDatabase::class.java, TaskFlowDatabase.NAME)
            .addMigrations(
                TaskFlowDatabase.MIGRATION_1_2,
                TaskFlowDatabase.MIGRATION_2_3,
                TaskFlowDatabase.MIGRATION_3_4,
                TaskFlowDatabase.MIGRATION_4_5,
            )
            .build()

    @Provides
    @Singleton
    fun provideTaskDao(database: TaskFlowDatabase): TaskDao = database.taskDao()

    @Provides
    @Singleton
    fun provideStepDao(database: TaskFlowDatabase): StepDao = database.stepDao()

    @Provides
    @Singleton
    fun provideKnowledgeDao(database: TaskFlowDatabase): KnowledgeDao = database.knowledgeDao()

    @Provides
    @Singleton
    fun provideAlarmDao(database: TaskFlowDatabase): AlarmDao = database.alarmDao()
}
