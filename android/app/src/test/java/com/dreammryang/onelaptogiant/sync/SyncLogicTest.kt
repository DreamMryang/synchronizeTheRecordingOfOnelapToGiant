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

    @Test
    fun `UPLOAD_FAILED 但服务端已成功则校正为 SYNCED`() {
        assertEquals(
            RecordStatus.SYNCED,
            SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "ok.fit", summary),
        )
    }

    @Test
    fun `服务端有记录但全部非成功则校正为 PROCESS_FAILED`() {
        assertEquals(
            RecordStatus.PROCESS_FAILED,
            SyncLogic.reconcile(RecordStatus.SYNCED, "bad.fit", summary),
        )
        assertEquals(
            RecordStatus.PROCESS_FAILED,
            SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "bad.fit", summary),
        )
    }

    @Test
    fun `状态已一致或服务端无记录时返回 null 不变`() {
        assertNull(SyncLogic.reconcile(RecordStatus.SYNCED, "ok.fit", summary))
        assertNull(SyncLogic.reconcile(RecordStatus.UPLOAD_FAILED, "not-on-server.fit", summary))
    }

    @Test
    fun `非 SYNCED-UPLOAD_FAILED 状态不参与 reconcile`() {
        assertNull(SyncLogic.reconcile(RecordStatus.DOWNLOADED, "ok.fit", summary))
        assertNull(SyncLogic.reconcile(RecordStatus.DOWNLOAD_FAILED, "bad.fit", summary))
        assertNull(SyncLogic.reconcile(RecordStatus.PROCESS_FAILED, "bad.fit", summary))
    }

    @Test
    fun `会话状态归纳`() {
        assertEquals(SessionStatus.NO_NEW, SyncLogic.sessionStatus(foundCount = 0, failedCount = 0))
        assertEquals(SessionStatus.SUCCESS, SyncLogic.sessionStatus(foundCount = 3, failedCount = 0))
        assertEquals(SessionStatus.PARTIAL, SyncLogic.sessionStatus(foundCount = 3, failedCount = 1))
        assertEquals(SessionStatus.PARTIAL, SyncLogic.sessionStatus(foundCount = 3, failedCount = 3))
    }
}
