package com.dreammryang.onelaptogiant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRecordDao {
    @Insert
    suspend fun insert(record: SyncRecordEntity): Long

    @Update
    suspend fun update(record: SyncRecordEntity)

    @Query("SELECT * FROM sync_record WHERE id = :id")
    suspend fun getById(id: Long): SyncRecordEntity?

    @Query("SELECT * FROM sync_record WHERE fit_url = :fitUrl")
    suspend fun getByFitUrl(fitUrl: String): SyncRecordEntity?

    @Query("SELECT * FROM sync_record WHERE status IN ('SYNCED', 'UPLOAD_FAILED')")
    suspend fun getReconcilable(): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_record WHERE session_id = :sessionId ORDER BY id")
    fun observeBySession(sessionId: Long): Flow<List<SyncRecordEntity>>

    @Query("SELECT COUNT(*) FROM sync_record WHERE status = 'PROCESS_FAILED'")
    fun observeProcessFailedCount(): Flow<Int>

    @Query("DELETE FROM sync_record")
    suspend fun deleteAll()

    @Query("DELETE FROM sync_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sync_record WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
