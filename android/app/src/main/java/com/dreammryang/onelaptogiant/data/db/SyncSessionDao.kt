package com.dreammryang.onelaptogiant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSessionDao {
    @Insert
    suspend fun insert(session: SyncSessionEntity): Long

    @Update
    suspend fun update(session: SyncSessionEntity)

    @Query("SELECT * FROM sync_session WHERE id = :id")
    suspend fun getById(id: Long): SyncSessionEntity?

    @Query("SELECT * FROM sync_session ORDER BY started_at DESC, id DESC")
    fun observeAll(): Flow<List<SyncSessionEntity>>

    @Query("SELECT * FROM sync_session WHERE status != 'RUNNING' ORDER BY started_at DESC, id DESC LIMIT 1")
    fun observeLatestFinished(): Flow<SyncSessionEntity?>

    @Query("UPDATE sync_session SET status = 'FAILED', error_msg = '进程中断', finished_at = :now WHERE status = 'RUNNING'")
    suspend fun failOrphanRunning(now: Long): Int
}
