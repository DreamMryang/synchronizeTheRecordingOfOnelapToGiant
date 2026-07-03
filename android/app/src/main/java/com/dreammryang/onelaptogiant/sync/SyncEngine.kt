package com.dreammryang.onelaptogiant.sync

import com.dreammryang.onelaptogiant.data.auth.TokenManager
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordDao
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.data.db.SyncSessionDao
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import com.dreammryang.onelaptogiant.data.network.giant.GiantClient
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class SyncStep(val label: String) {
    PREPARING("查询捷安特已上传列表"),
    RECONCILING("校正本地记录"),
    LISTING("查询顽鹿活动"),
    DOWNLOADING("下载 FIT 文件"),
    UPLOADING("上传到捷安特"),
}

data class SyncProgress(val step: SyncStep, val done: Int = 0, val total: Int = 0)

sealed interface SyncOutcome {
    data class Finished(val sessionId: Long, val status: SessionStatus) : SyncOutcome
    data object Skipped : SyncOutcome
}

class SyncEngine(
    private val onelap: OnelapClient,
    private val giant: GiantClient,
    private val onelapTokens: TokenManager,
    private val giantTokens: TokenManager,
    private val sessionDao: SyncSessionDao,
    private val recordDao: SyncRecordDao,
    private val recentDaysProvider: suspend () -> Int,
    private val fitDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress

    /** 一次同步 = 一个会话；已有同步在跑时直接跳过不排队 */
    suspend fun sync(trigger: TriggerType): SyncOutcome {
        if (!mutex.tryLock()) {
            Timber.i("已有同步在运行，跳过本次 %s 触发", trigger)
            return SyncOutcome.Skipped
        }
        try {
            return runSession(trigger)
        } finally {
            _progress.value = null
            mutex.unlock()
        }
    }

    private suspend fun runSession(trigger: TriggerType): SyncOutcome.Finished {
        val sessionId = sessionDao.insert(
            SyncSessionEntity(triggerType = trigger, status = SessionStatus.RUNNING, startedAt = clock())
        )
        try {
            // 1. 捷安特侧准备：每会话仅一次 all_upload（唯一去重事实源）
            _progress.value = SyncProgress(SyncStep.PREPARING)
            val summary = giantTokens.withAuthRetry { giant.fetchAllUpload(it) }

            // 2. 本机记录 reconcile
            _progress.value = SyncProgress(SyncStep.RECONCILING)
            reconcileLocal(summary)

            // 3. 顽鹿侧：活动列表 + 逐条详情，按服务端集合去重
            _progress.value = SyncProgress(SyncStep.LISTING)
            val days = recentDaysProvider()
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(days.toLong())
            val activityIds = onelapTokens.withAuthRetry {
                onelap.listActivityIds(it, startDate.format(DATE_FMT), endDate.format(DATE_FMT))
            }
            val candidates = mutableListOf<Pair<String, String>>() // activityId to fitUrl
            for (id in activityIds) {
                val fitUrl = onelapTokens.withAuthRetry { onelap.fetchFitUrl(it, id) } ?: continue
                if (fitUrl in summary.uploaded) continue
                candidates += id to fitUrl
            }
            if (candidates.isEmpty()) {
                finishSession(sessionId, SessionStatus.NO_NEW, 0, 0, 0, 0)
                return SyncOutcome.Finished(sessionId, SessionStatus.NO_NEW)
            }

            // 4. 下载（单条失败不中断；本地已下载且文件仍在则复用）
            var downloadedCount = 0
            var failedCount = 0
            val toUpload = mutableListOf<Pair<SyncRecordEntity, File>>()
            candidates.forEachIndexed { index, (activityId, fitUrl) ->
                _progress.value = SyncProgress(SyncStep.DOWNLOADING, index, candidates.size)
                val existing = recordDao.getByFitUrl(fitUrl)
                try {
                    val localFile = File(fitDir, fitUrl)
                    val reuse = existing?.status == RecordStatus.DOWNLOADED && localFile.exists()
                    val file = if (reuse) {
                        localFile
                    } else {
                        onelapTokens.withAuthRetry { onelap.downloadFit(it, fitUrl, fitDir) }
                    }
                    val record = upsertRecord(
                        existing, fitUrl, activityId, sessionId, RecordStatus.DOWNLOADED,
                        fileSize = file.length(),
                        downloadTime = if (reuse) existing?.downloadTime else clock(),
                    )
                    toUpload += record to file
                    downloadedCount++
                } catch (e: com.dreammryang.onelaptogiant.data.auth.AuthFailedException) {
                    throw e // 续登后仍失败属流程级问题，交给外层
                } catch (e: Exception) {
                    Timber.w(e, "下载失败: %s", fitUrl)
                    upsertRecord(
                        existing, fitUrl, activityId, sessionId, RecordStatus.DOWNLOAD_FAILED,
                        errorMsg = e.message ?: e.javaClass.simpleName,
                    )
                    failedCount++
                }
            }

            // 5. 整批上传，按整批结果标记（真实处理结果由下次会话 reconcile 确认）
            var syncedCount = 0
            if (toUpload.isNotEmpty()) {
                _progress.value = SyncProgress(SyncStep.UPLOADING, 0, toUpload.size)
                val ok = try {
                    giantTokens.withAuthRetry { giant.uploadFits(it, toUpload.map { p -> p.second }) }
                } catch (e: com.dreammryang.onelaptogiant.data.auth.AuthFailedException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "整批上传异常")
                    false
                }
                val newStatus = if (ok) RecordStatus.SYNCED else RecordStatus.UPLOAD_FAILED
                val now = clock()
                toUpload.forEach { (record, _) ->
                    recordDao.update(
                        record.copy(
                            status = newStatus,
                            syncTime = if (ok) now else record.syncTime,
                            errorMsg = if (ok) null else "整批上传失败",
                            updatedAt = now,
                        )
                    )
                }
                if (ok) syncedCount = toUpload.size else failedCount += toUpload.size
            }

            val status = SyncLogic.sessionStatus(candidates.size, failedCount)
            finishSession(sessionId, status, candidates.size, downloadedCount, syncedCount, failedCount)
            return SyncOutcome.Finished(sessionId, status)
        } catch (e: Exception) {
            // 流程级失败：登录 / all_upload / 活动列表等
            Timber.e(e, "同步会话失败")
            val session = sessionDao.getById(sessionId)
            if (session != null) {
                sessionDao.update(
                    session.copy(
                        status = SessionStatus.FAILED,
                        finishedAt = clock(),
                        errorMsg = e.message ?: e.javaClass.simpleName,
                    )
                )
            }
            return SyncOutcome.Finished(sessionId, SessionStatus.FAILED)
        }
    }

    private suspend fun reconcileLocal(summary: AllUploadSummary) {
        for (record in recordDao.getReconcilable()) {
            val newStatus = SyncLogic.reconcile(record.status, record.fitUrl, summary) ?: continue
            val now = clock()
            recordDao.update(
                record.copy(
                    status = newStatus,
                    errorMsg = if (newStatus == RecordStatus.PROCESS_FAILED) {
                        summary.failedProcess[record.fitUrl]
                    } else {
                        null
                    },
                    syncTime = if (newStatus == RecordStatus.SYNCED && record.syncTime == null) now else record.syncTime,
                    updatedAt = now,
                )
            )
        }
    }

    private suspend fun upsertRecord(
        existing: SyncRecordEntity?,
        fitUrl: String,
        activityId: String?,
        sessionId: Long,
        status: RecordStatus,
        fileSize: Long? = null,
        errorMsg: String? = null,
        downloadTime: Long? = null,
    ): SyncRecordEntity {
        val now = clock()
        return if (existing == null) {
            val entity = SyncRecordEntity(
                fitUrl = fitUrl, activityId = activityId, sessionId = sessionId, status = status,
                fileSize = fileSize, errorMsg = errorMsg, downloadTime = downloadTime,
                createdAt = now, updatedAt = now,
            )
            entity.copy(id = recordDao.insert(entity))
        } else {
            val entity = existing.copy(
                activityId = activityId ?: existing.activityId,
                sessionId = sessionId,
                status = status,
                fileSize = fileSize ?: existing.fileSize,
                errorMsg = errorMsg,
                downloadTime = downloadTime ?: existing.downloadTime,
                updatedAt = now,
            )
            recordDao.update(entity)
            entity
        }
    }

    private suspend fun finishSession(
        sessionId: Long,
        status: SessionStatus,
        found: Int,
        downloaded: Int,
        synced: Int,
        failed: Int,
    ) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.update(
            session.copy(
                status = status,
                finishedAt = clock(),
                foundCount = found,
                downloadedCount = downloaded,
                syncedCount = synced,
                failedCount = failed,
            )
        )
    }

    private companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
