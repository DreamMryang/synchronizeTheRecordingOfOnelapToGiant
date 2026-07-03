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
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import com.dreammryang.onelaptogiant.data.network.giant.GiantClient
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

// ---- fakes ----

private class FakeOnelap : OnelapClient {
    /** activityId -> fitUrl（null=该活动无 FIT） */
    var activities: List<Pair<String, String?>> = emptyList()
    var failDownload: Set<String> = emptySet()
    var downloadCount = 0

    override suspend fun listActivityIds(token: String, startDate: String, endDate: String) =
        activities.map { it.first }

    override suspend fun fetchFitUrl(token: String, activityId: String) =
        activities.first { it.first == activityId }.second

    override suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File {
        if (fitUrl in failDownload) throw IOException("模拟下载失败")
        downloadCount++
        targetDir.mkdirs()
        return File(targetDir, fitUrl).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }
}

private class FakeGiant : GiantClient {
    var summary = AllUploadSummary(emptySet(), emptyMap())
    var allUploadError: Exception? = null
    var uploadOk = true
    val uploadedBatches = mutableListOf<List<String>>()
    var entered = false
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun fetchAllUpload(token: String): AllUploadSummary {
        entered = true
        gate?.await()
        allUploadError?.let { throw it }
        return summary
    }

    override suspend fun uploadFits(token: String, files: List<File>): Boolean {
        uploadedBatches += files.map { it.name }
        return uploadOk
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var onelap: FakeOnelap
    private lateinit var giant: FakeGiant
    private lateinit var engine: SyncEngine
    private lateinit var fitDir: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("test_engine", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val tokenStore = TokenStore(prefs)
        onelap = FakeOnelap()
        giant = FakeGiant()
        fitDir = tmp.newFolder("fit")
        engine = SyncEngine(
            onelap = onelap,
            giant = giant,
            onelapTokens = TokenManager(Platform.ONELAP, tokenStore) { "ot" },
            giantTokens = TokenManager(Platform.GIANT, tokenStore) { "gt" },
            sessionDao = db.sessionDao(),
            recordDao = db.recordDao(),
            recentDaysProvider = { 30 },
            fitDir = fitDir,
            clock = { 1751443200000L },
        )
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `新文件下载上传成功会话 SUCCESS`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit", "102" to "b.fit", "103" to null)

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals(2, session.foundCount)
        assertEquals(2, session.downloadedCount)
        assertEquals(2, session.syncedCount)
        assertEquals(0, session.failedCount)
        assertNotNull(session.finishedAt)
        assertEquals(listOf(listOf("a.fit", "b.fit")), giant.uploadedBatches)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        assertTrue(File(fitDir, "a.fit").exists()) // 上传后文件保留
    }

    @Test
    fun `服务端已有的文件跳过且会话 NO_NEW`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit")
        giant.summary = AllUploadSummary(setOf("a.fit"), emptyMap())

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.NO_NEW, outcome.status)
        assertEquals(0, onelap.downloadCount)
        assertTrue(giant.uploadedBatches.isEmpty())
    }

    @Test
    fun `reconcile 将本地 SYNCED 校正为 PROCESS_FAILED 并带错误信息`() = runBlocking {
        val sid = db.sessionDao().insert(
            com.dreammryang.onelaptogiant.data.db.SyncSessionEntity(
                triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS, startedAt = 1L,
            )
        )
        db.recordDao().insert(
            SyncRecordEntity(
                fitUrl = "bad.fit", sessionId = sid, status = RecordStatus.SYNCED,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        giant.summary = AllUploadSummary(setOf("bad.fit"), mapOf("bad.fit" to "失败: 解析出错"))

        engine.sync(TriggerType.AUTO)

        val record = db.recordDao().getByFitUrl("bad.fit")!!
        assertEquals(RecordStatus.PROCESS_FAILED, record.status)
        assertEquals("失败: 解析出错", record.errorMsg)
    }

    @Test
    fun `单条下载失败不中断整体且会话 PARTIAL`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit", "102" to "bad.fit")
        onelap.failDownload = setOf("bad.fit")

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.PARTIAL, outcome.status)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        val failed = db.recordDao().getByFitUrl("bad.fit")!!
        assertEquals(RecordStatus.DOWNLOAD_FAILED, failed.status)
        assertNotNull(failed.errorMsg)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals(2, session.foundCount)
        assertEquals(1, session.syncedCount)
        assertEquals(1, session.failedCount)
    }

    @Test
    fun `整批上传失败全部标 UPLOAD_FAILED`() = runBlocking {
        onelap.activities = listOf("101" to "a.fit")
        giant.uploadOk = false

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.PARTIAL, outcome.status)
        assertEquals(RecordStatus.UPLOAD_FAILED, db.recordDao().getByFitUrl("a.fit")!!.status)
    }

    @Test
    fun `all_upload 失败会话整体 FAILED 且记录 error_msg`() = runBlocking {
        giant.allUploadError = IOException("网络不可用")

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.FAILED, outcome.status)
        val session = db.sessionDao().getById(outcome.sessionId)!!
        assertEquals("网络不可用", session.errorMsg)
        assertNotNull(session.finishedAt)
    }

    @Test
    fun `本地已下载且文件仍在时复用不重新下载`() = runBlocking {
        val sid = db.sessionDao().insert(
            com.dreammryang.onelaptogiant.data.db.SyncSessionEntity(
                triggerType = TriggerType.AUTO, status = SessionStatus.PARTIAL, startedAt = 1L,
            )
        )
        db.recordDao().insert(
            SyncRecordEntity(
                fitUrl = "a.fit", sessionId = sid, status = RecordStatus.DOWNLOADED,
                createdAt = 1L, updatedAt = 1L,
            )
        )
        File(fitDir, "a.fit").writeBytes(byteArrayOf(9))
        onelap.activities = listOf("101" to "a.fit")

        val outcome = engine.sync(TriggerType.AUTO) as SyncOutcome.Finished

        assertEquals(SessionStatus.SUCCESS, outcome.status)
        assertEquals(0, onelap.downloadCount)
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        assertEquals(outcome.sessionId, db.recordDao().getByFitUrl("a.fit")!!.sessionId)
    }

    @Test
    fun `同步进行中再次触发返回 Skipped`() = runBlocking {
        giant.gate = CompletableDeferred()
        val job = launch(Dispatchers.Default) { engine.sync(TriggerType.AUTO) }
        withTimeout(5000) { while (!giant.entered) delay(10) }

        assertEquals(SyncOutcome.Skipped, engine.sync(TriggerType.MANUAL))

        giant.gate!!.complete(Unit)
        job.join()
        assertFalse(engine.progress.value != null) // 结束后进度复位
    }
}
