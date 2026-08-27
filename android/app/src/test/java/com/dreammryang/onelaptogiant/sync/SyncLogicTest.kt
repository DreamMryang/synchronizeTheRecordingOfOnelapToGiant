package com.dreammryang.onelaptogiant.sync

import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncLogicTest {
    private val summary = AllUploadSummary(
        uploaded = setOf("ok.fit", "bad.fit"),
        failedProcess = mapOf("bad.fit" to "失败: 解析出错"),
    )

    /** 上传于宽限期之前，服务端仍非成功 → 可定论为处理失败 */
    private val longAgo = 0L
    private val now = SyncLogic.PROCESS_GRACE_MS + 1

    @Test
    fun `UPLOAD_FAILED 但服务端已成功则校正为 SYNCED`() {
        assertEquals(
            RecordStatus.SYNCED,
            SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "ok.fit", summary, longAgo, now),
        )
    }

    @Test
    fun `服务端有记录但全部非成功且超过宽限期则校正为 PROCESS_FAILED`() {
        assertEquals(
            RecordStatus.PROCESS_FAILED,
            SyncLogic.reconcile(RecordStatus.SYNCED, "bad.fit", summary, longAgo, now),
        )
        assertEquals(
            RecordStatus.PROCESS_FAILED,
            SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "bad.fit", summary, longAgo, now),
        )
    }

    @Test
    fun `宽限期内的非成功视为处理中不标记 PROCESS_FAILED`() {
        assertNull(
            SyncLogic.reconcile(
                RecordStatus.SYNCED, "bad.fit", summary,
                syncTime = now - SyncLogic.PROCESS_GRACE_MS + 1, now = now,
            )
        )
    }

    @Test
    fun `无同步时间的记录不享受宽限期`() {
        assertEquals(
            RecordStatus.PROCESS_FAILED,
            SyncLogic.reconcile(RecordStatus.SYNCED, "bad.fit", summary, syncTime = null, now = now),
        )
    }

    @Test
    fun `PROCESS_FAILED 记录在服务端不再报失败时自愈为 SYNCED`() {
        assertEquals(
            RecordStatus.SYNCED,
            SyncLogic.reconcile(RecordStatus.PROCESS_FAILED, "ok.fit", summary, longAgo, now),
        )
    }

    @Test
    fun `PROCESS_FAILED 记录在服务端仍报失败时保持不变`() {
        assertNull(SyncLogic.reconcile(RecordStatus.PROCESS_FAILED, "bad.fit", summary, longAgo, now))
    }

    @Test
    fun `状态已一致或服务端无记录时返回 null 不变`() {
        assertNull(SyncLogic.reconcile(RecordStatus.SYNCED, "ok.fit", summary, longAgo, now))
        assertNull(SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "not-on-server.fit", summary, longAgo, now))
        assertNull(SyncLogic.reconcile(RecordStatus.PROCESS_FAILED, "not-on-server.fit", summary, longAgo, now))
    }

    @Test
    fun `下载态记录不参与 reconcile`() {
        assertNull(SyncLogic.reconcile(RecordStatus.DOWNLOADED, "ok.fit", summary, longAgo, now))
        assertNull(SyncLogic.reconcile(RecordStatus.DOWNLOAD_FAILED, "bad.fit", summary, longAgo, now))
    }

    @Test
    fun `会话状态归纳`() {
        assertEquals(SessionStatus.NO_NEW, SyncLogic.sessionStatus(foundCount = 0, failedCount = 0))
        assertEquals(SessionStatus.SUCCESS, SyncLogic.sessionStatus(foundCount = 3, failedCount = 0))
        assertEquals(SessionStatus.PARTIAL, SyncLogic.sessionStatus(foundCount = 3, failedCount = 1))
        assertEquals(SessionStatus.PARTIAL, SyncLogic.sessionStatus(foundCount = 3, failedCount = 3))
    }
}
