package com.redmi.autoattend.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PunchLogDao {

    @Insert
    suspend fun insert(log: PunchLogEntity): Long

    @Query("SELECT * FROM punch_log ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<PunchLogEntity>>

    @Query("SELECT * FROM punch_log ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<PunchLogEntity>

    @Query("SELECT * FROM punch_log WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC")
    suspend fun between(from: Long, to: Long): List<PunchLogEntity>

    /** 最近一次指定类型的成功记录，用于首页显示"今日是否已打卡" */
    @Query(
        """
        SELECT * FROM punch_log
        WHERE kind = :kind AND outcome = 'SUCCESS' AND startedAt >= :since
        ORDER BY startedAt DESC LIMIT 1
        """
    )
    suspend fun lastSuccessSince(kind: PunchKind, since: Long): PunchLogEntity?

    @Query("DELETE FROM punch_log WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM punch_log")
    suspend fun clearAll()
}