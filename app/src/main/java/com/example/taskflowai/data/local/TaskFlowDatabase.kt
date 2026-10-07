package com.example.taskflowai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 枚举 ↔ 字符串转换(Room 原生不支持枚举列) */
class Converters {
    @TypeConverter
    fun taskStatusToString(status: TaskStatus): String = status.name

    @TypeConverter
    fun stringToTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun stepStatusToString(status: StepStatus): String = status.name

    @TypeConverter
    fun stringToStepStatus(value: String): StepStatus = StepStatus.valueOf(value)

    @TypeConverter
    fun pendingTypeToString(type: PendingType?): String? = type?.name

    @TypeConverter
    fun stringToPendingType(value: String?): PendingType? = value?.let(PendingType::valueOf)
}

/**
 * 任务 + 知识库 + 闹钟数据库(唯一事实来源)。
 * v2:knowledge 表(RAG);v3:tasks.answer;v4:alarms 表;
 * v5:steps.toolCallId(Agent 闭环:工具结果回灌 LLM 时的协议配对)。
 * 均通过增量迁移升级,老数据不丢。
 */
@Database(
    entities = [TaskEntity::class, StepEntity::class, KnowledgeEntity::class, AlarmEntity::class],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TaskFlowDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun stepDao(): StepDao

    abstract fun knowledgeDao(): KnowledgeDao

    abstract fun alarmDao(): AlarmDao

    companion object {
        const val NAME = "taskflow.db"

        /** v1 → v2:新增知识库表 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `knowledge` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `instruction` TEXT NOT NULL,
                        `toolSequenceJson` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3:任务表新增 answer 列(大模型真实回答) */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `answer` TEXT")
            }
        }

        /** v3 → v4:新增闹钟登记表(可管理的闹钟) */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `alarms` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `triggerAtMillis` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v4 → v5:步骤表新增 toolCallId(Agent 闭环:工具结果回灌的协议配对) */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `steps` ADD COLUMN `toolCallId` TEXT")
            }
        }
    }
}
