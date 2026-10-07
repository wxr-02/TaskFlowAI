package com.example.taskflowai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AlarmDao {

    @Insert
    suspend fun insert(alarm: AlarmEntity): Long

    @Query("SELECT * FROM alarms ORDER BY triggerAtMillis ASC")
    suspend fun getAll(): List<AlarmEntity>

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)
}
