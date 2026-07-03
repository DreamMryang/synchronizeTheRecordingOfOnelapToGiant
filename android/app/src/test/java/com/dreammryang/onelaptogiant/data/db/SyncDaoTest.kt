package com.dreammryang.onelaptogiant.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() = db.close()

    private fun session(status: SessionStatus = SessionStatus.RUNNING) = SyncSessionEntity(
        triggerType = TriggerType.AUTO, status = status, startedAt = 1000L,
    )

    private fun record(fitUrl: String, sessionId: Long, status: RecordStatus) = SyncRecordEntity(
        fitUrl = fitUrl, sessionId = sessionId, status = status,
        createdAt = 1000L, updatedAt = 1000L,
    )

    @Test
    fun `会话插入更新与倒序查询`() = runTest {
        val id1 = db.sessionDao().insert(session())
        val id2 = db.sessionDao().insert(session().copy(startedAt = 2000L))

        val s1 = db.sessionDao().getById(id1)!!
        db.sessionDao().update(s1.copy(status = SessionStatus.SUCCESS, finishedAt = 1500L, syncedCount = 3))

        val all = db.sessionDao().observeAll().first()
        assertEquals(listOf(id2, id1), all.map { it.id })
        assertEquals(SessionStatus.SUCCESS, all[1].status)
        assertEquals(3, all[1].syncedCount)
    }

    @Test
    fun `observeLatestFinished 跳过 RUNNING 会话`() = runTest {
        assertNull(db.sessionDao().observeLatestFinished().first())
        val id1 = db.sessionDao().insert(session(SessionStatus.SUCCESS))
        db.sessionDao().insert(session(SessionStatus.RUNNING).copy(startedAt = 9999L))
        assertEquals(id1, db.sessionDao().observeLatestFinished().first()?.id)
    }

    @Test
    fun `记录按 fitUrl 查询与状态更新`() = runTest {
        val sid = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid, RecordStatus.DOWNLOADED))

        val found = db.recordDao().getByFitUrl("a.fit")
        assertNotNull(found)
        db.recordDao().update(found!!.copy(status = RecordStatus.SYNCED, syncTime = 2000L))
        assertEquals(RecordStatus.SYNCED, db.recordDao().getByFitUrl("a.fit")!!.status)
        assertNull(db.recordDao().getByFitUrl("missing.fit"))
    }

    @Test
    fun `getReconcilable 只返回 SYNCED 与 UPLOAD_FAILED`() = runTest {
        val sid = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid, RecordStatus.SYNCED))
        db.recordDao().insert(record("b.fit", sid, RecordStatus.UPLOAD_FAILED))
        db.recordDao().insert(record("c.fit", sid, RecordStatus.DOWNLOAD_FAILED))
        db.recordDao().insert(record("d.fit", sid, RecordStatus.PROCESS_FAILED))

        assertEquals(setOf("a.fit", "b.fit"), db.recordDao().getReconcilable().map { it.fitUrl }.toSet())
    }

    @Test
    fun `failOrphanRunning 只清理 RUNNING 会话`() = runTest {
        val id1 = db.sessionDao().insert(session(SessionStatus.RUNNING))
        val id2 = db.sessionDao().insert(session(SessionStatus.SUCCESS))
        val cleaned = db.sessionDao().failOrphanRunning(now = 9999L)
        assertEquals(1, cleaned)
        val orphan = db.sessionDao().getById(id1)!!
        assertEquals(SessionStatus.FAILED, orphan.status)
        assertEquals("进程中断", orphan.errorMsg)
        assertEquals(9999L, orphan.finishedAt)
        assertEquals(SessionStatus.SUCCESS, db.sessionDao().getById(id2)!!.status)
    }

    @Test
    fun `按会话查记录与处理失败计数`() = runTest {
        val sid1 = db.sessionDao().insert(session())
        val sid2 = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid1, RecordStatus.PROCESS_FAILED))
        db.recordDao().insert(record("b.fit", sid2, RecordStatus.SYNCED))

        assertEquals(listOf("a.fit"), db.recordDao().observeBySession(sid1).first().map { it.fitUrl })
        assertEquals(1, db.recordDao().observeProcessFailedCount().first())
    }

    @Test
    fun `sessionDao deleteAll 清空全部会话`() = runTest {
        db.sessionDao().insert(session())
        db.sessionDao().insert(session().copy(startedAt = 2000L))

        db.sessionDao().deleteAll()

        assertEquals(emptyList<SyncSessionEntity>(), db.sessionDao().observeAll().first())
    }

    @Test
    fun `sessionDao deleteById 只删单个会话`() = runTest {
        val id1 = db.sessionDao().insert(session())
        val id2 = db.sessionDao().insert(session().copy(startedAt = 2000L))

        db.sessionDao().deleteById(id1)

        val remaining = db.sessionDao().observeAll().first()
        assertEquals(listOf(id2), remaining.map { it.id })
    }

    @Test
    fun `recordDao deleteAll 清空全部记录`() = runTest {
        val sid = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid, RecordStatus.SYNCED))
        db.recordDao().insert(record("b.fit", sid, RecordStatus.SYNCED))

        db.recordDao().deleteAll()

        assertEquals(emptyList<SyncRecordEntity>(), db.recordDao().observeBySession(sid).first())
    }

    @Test
    fun `recordDao deleteById 只删单条记录`() = runTest {
        val sid = db.sessionDao().insert(session())
        val id1 = db.recordDao().insert(record("a.fit", sid, RecordStatus.SYNCED))
        db.recordDao().insert(record("b.fit", sid, RecordStatus.SYNCED))

        db.recordDao().deleteById(id1)

        assertEquals(listOf("b.fit"), db.recordDao().observeBySession(sid).first().map { it.fitUrl })
    }

    @Test
    fun `recordDao deleteBySession 只删指向该会话的记录`() = runTest {
        val sid1 = db.sessionDao().insert(session())
        val sid2 = db.sessionDao().insert(session())
        db.recordDao().insert(record("a.fit", sid1, RecordStatus.SYNCED))
        db.recordDao().insert(record("b.fit", sid2, RecordStatus.SYNCED))

        db.recordDao().deleteBySession(sid1)

        assertEquals(emptyList<SyncRecordEntity>(), db.recordDao().observeBySession(sid1).first())
        assertEquals(listOf("b.fit"), db.recordDao().observeBySession(sid2).first().map { it.fitUrl })
    }
}
