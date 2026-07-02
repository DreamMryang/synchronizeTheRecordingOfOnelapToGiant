package com.dreammryang.onelaptogiant.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TriggerType { AUTO, MANUAL }

enum class SessionStatus { RUNNING, SUCCESS, PARTIAL, FAILED, NO_NEW }

enum class RecordStatus { DOWNLOADED, DOWNLOAD_FAILED, SYNCED, UPLOAD_FAILED, PROCESS_FAILED }

@Entity(tableName = "sync_session")
data class SyncSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trigger_type") val triggerType: TriggerType,
    val status: SessionStatus,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "found_count") val foundCount: Int = 0,
    @ColumnInfo(name = "downloaded_count") val downloadedCount: Int = 0,
    @ColumnInfo(name = "synced_count") val syncedCount: Int = 0,
    @ColumnInfo(name = "failed_count") val failedCount: Int = 0,
    @ColumnInfo(name = "error_msg") val errorMsg: String? = null,
)

@Entity(tableName = "sync_record", indices = [Index(value = ["fit_url"], unique = true)])
data class SyncRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "fit_url") val fitUrl: String,
    @ColumnInfo(name = "activity_id") val activityId: String? = null,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val status: RecordStatus,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "error_msg") val errorMsg: String? = null,
    @ColumnInfo(name = "download_time") val downloadTime: Long? = null,
    @ColumnInfo(name = "sync_time") val syncTime: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
