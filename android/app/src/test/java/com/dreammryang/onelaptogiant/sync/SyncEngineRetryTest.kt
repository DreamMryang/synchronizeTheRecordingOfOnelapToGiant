package com.dreammryang.onelaptogiant.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreammryang.onelaptogiant.data.auth.Platform
import com.dreammryang.onelaptogiant.data.auth.TokenManager
import com.dreammryang.onelaptogiant.data.auth.TokenStore
import com.dreammryang.onelaptogiant.data.db.AppDatabase
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import com.dreammryang.onelaptogiant.data.network.giant.GiantClient
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

private class RetryFakeOnelap : OnelapClient {
    var failDownload = false
    var downloadCount = 0
    override suspend fun listActivityIds(token: String, startDate: String, endDate: String) = emptyList<String>()
    override suspend fun fetchFitUrl(token: String, activityId: String): String? = null
    override suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File {
        if (failDownload) throw IOException("模拟下载失败")
        downloadCount++
        targetDir.mkdirs()
        return File(targetDir, fitUrl).apply { writeBytes(byteArrayOf(1)) }
    }
}

private class RetryFakeGiant : GiantClient {
    var uploadOk = true
    val uploadedBatches = mutableListOf<List<String>>()
    override suspend fun fetchAllUpload(token: String) = AllUploadSummary(emptySet(), emptyMap())
    override suspend fun uploadFits(token: String, files: List<File>): Boolean {
        uploadedBatches += files.map { it.name }
        return uploadOk
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncEngineRetryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var onelap: RetryFakeOnelap
    private lateinit var giant: RetryFakeGiant
    private lateinit var engine: SyncEngine
    private lateinit var fitDir: File
    private var originSessionId = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            // 直接执行器：让 InvalidationTracker 刷新同步执行，避免 @After 关库后台竞态泄漏未捕获异常
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val prefs = context.getSharedPreferences("test_retry", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val tokenStore = TokenStore(prefs)
        onelap = RetryFakeOnelap()
        giant = RetryFakeGiant()
        fitDir = tmp.newFolder("fit")
        engine = SyncEngine(
            onelap, giant,
            TokenManager(Platform.ONELAP, tokenStore) { "ot" },
            TokenManager(Platform.GIANT, tokenStore) { "gt" },
            db.sessionDao(), db.recordDao(),
            recentDaysProvider = { 30 },
            fitDir = fitDir,
            clock = { 42L },
        )
        originSessionId = runBlocking {
            db.sessionDao().insert(
                SyncSessionEntity(
                    triggerType = TriggerType.AUTO, status = SessionStatus.PARTIAL,
                    startedAt = 1L, foundCount = 5, failedCount = 2,
                )
            )
        }
    }

    @After
    fun teardown() = db.close()

    private fun insertRecord(status: RecordStatus, fitUrl: String = "r.fit"): Long = runBlocking {
        db.recordDao().insert(
            SyncRecordEntity(
                fitUrl = fitUrl, sessionId = originSessionId, status = status,
                errorMsg = "原错误", createdAt = 1L, updatedAt = 1L,
            )
        )
    }

    @Test
    fun `重试 UPLOAD_FAILED 且文件仍在时只上传不下载`() = runBlocking {
        val recordId = insertRecord(RecordStatus.UPLOAD_FAILED)
        File(fitDir, "r.fit").writeBytes(byteArrayOf(9))

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        assertEquals(0, onelap.downloadCount)
        assertEquals(listOf(listOf("r.fit")), giant.uploadedBatches)
        val record = db.recordDao().getById(recordId)!!
        assertEquals(RecordStatus.SYNCED, record.status)
        assertEquals(outcome.sessionId, record.sessionId) // 记录归入新会话
        // 新会话为 MANUAL 且原会话统计不变
        assertEquals(TriggerType.MANUAL, db.sessionDao().getById(outcome.sessionId)!!.triggerType)
        val origin = db.sessionDao().getById(originSessionId)!!
        assertEquals(5, origin.foundCount)
        assertEquals(2, origin.failedCount)
    }

    @Test
    fun `重试 DOWNLOAD_FAILED 时先下载再上传`() = runBlocking {
        val recordId = insertRecord(RecordStatus.DOWNLOAD_FAILED)

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        assertEquals(1, onelap.downloadCount)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getById(recordId)!!.status)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals(1, session.foundCount)
        assertEquals(1, session.downloadedCount)
        assertEquals(1, session.syncedCount)
    }

    @Test
    fun `重试中上传失败记录回到 UPLOAD_FAILED 会话 PARTIAL`() = runBlocking {
        val recordId = insertRecord(RecordStatus.DOWNLOAD_FAILED)
        giant.uploadOk = false

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.PARTIAL, outcome.status)
        assertEquals(RecordStatus.UPLOAD_FAILED, db.recordDao().getById(recordId)!!.status)
    }

    @Test
    fun `重试中下载异常会话 FAILED 记录保持原状态`() = runBlocking {
        val recordId = insertRecord(RecordStatus.DOWNLOAD_FAILED)
        onelap.failDownload = true

        val outcome = engine.retryRecord(recordId) as SyncOutcome.Finished

        assertEquals(SessionStatus.FAILED, outcome.status)
        assertEquals(RecordStatus.DOWNLOAD_FAILED, db.recordDao().getById(recordId)!!.status)
    }

    @Test
    fun `PROCESS_FAILED 记录不可重试`(): Unit = runBlocking {
        val recordId = insertRecord(RecordStatus.PROCESS_FAILED)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.retryRecord(recordId) }
        }
    }
}
