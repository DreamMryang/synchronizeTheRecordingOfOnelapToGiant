package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * SyncLogic TDD 测试：reconcile 纯逻辑 + reconcileLocal 落库集成。
 * 规则与 Android 端 SyncLogic.reconcile 一致（docs/design/multi-client-sync.md §3）。
 */
public class SyncLogicTest {

    @Before
    public void setUp() throws Exception {
        File tmp = File.createTempFile("synclogic_test_", ".db");
        assertTrue(tmp.delete());
        SyncRecordDao.init(tmp.getAbsolutePath());
    }

    private AllUploadSummary summary(String json) {
        return AllUploadSummary.parse(json);
    }

    // ===== 纯逻辑：DOWNLOADED 等非 reconcile 态一律返回 null（不参与）=====
    @Test
    public void testReconcileTarget_nonReconcilableStatus_returnsNull() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"成功\"}]}");
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_DOWNLOADED, "a.fit", s));
    }

    // ===== 纯逻辑：UPLOAD_FAILED 但服务端已成功 → 校正为 SYNCED =====
    @Test
    public void testReconcileTarget_uploadFailedButServerSuccess_becomesSynced() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"成功\"}]}");
        assertEquals(SyncRecordDao.STATUS_SYNCED,
                SyncLogic.reconcileTarget(SyncRecordDao.STATUS_UPLOAD_FAILED, "a.fit", s));
    }

    // ===== 纯逻辑：SYNCED 但服务端处理失败 → 校正为 PROCESS_FAILED =====
    @Test
    public void testReconcileTarget_syncedButServerFailed_becomesProcessFailed() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"处理失败\",\"msg\":\"坏\"}]}");
        assertEquals(SyncRecordDao.STATUS_PROCESS_FAILED,
                SyncLogic.reconcileTarget(SyncRecordDao.STATUS_SYNCED, "a.fit", s));
    }

    // ===== 纯逻辑：服务端无记录 → null（保持现状，下次自然重试）=====
    @Test
    public void testReconcileTarget_serverHasNoRecord_returnsNull() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[]}");
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_UPLOAD_FAILED, "a.fit", s));
    }

    // ===== 纯逻辑：目标与现状相同 → null（无需写库）=====
    @Test
    public void testReconcileTarget_targetEqualsCurrent_returnsNull() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"成功\"}]}");
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_SYNCED, "a.fit", s));
    }

    // ===== 集成：reconcileLocal 把 UPLOAD_FAILED 落库校正为 SYNCED =====
    @Test
    public void testReconcileLocal_flipsUploadFailedToSynced() {
        SyncRecordDao.upsertDownloaded("ok.fit", "u", 1L);
        SyncRecordDao.markUploadFailed(Arrays.asList("ok.fit"), "上次 500");
        SyncLogic.reconcileLocal(summary(
                "{\"status\":1,\"data\":[{\"file\":\"ok.fit\",\"status\":\"成功\"}]}"));
        assertEquals(SyncRecordDao.STATUS_SYNCED, SyncRecordDao.findStatus("ok.fit"));
    }

    // ===== 集成：reconcileLocal 把 SYNCED 落库校正为 PROCESS_FAILED 并写 error_msg =====
    @Test
    public void testReconcileLocal_flipsSyncedToProcessFailedWithMsg() {
        SyncRecordDao.upsertDownloaded("bad.fit", "u", 1L);
        SyncRecordDao.markSynced(Arrays.asList("bad.fit"));
        SyncLogic.reconcileLocal(summary(
                "{\"status\":1,\"data\":[{\"file\":\"bad.fit\",\"status\":\"处理失败\",\"msg\":\"坏文件\"}]}"));
        assertEquals(SyncRecordDao.STATUS_PROCESS_FAILED, SyncRecordDao.findStatus("bad.fit"));
    }

    // ===== 集成：服务端无记录时 reconcileLocal 保持 UPLOAD_FAILED 不动 =====
    @Test
    public void testReconcileLocal_serverNoRecord_keepsStatus() {
        SyncRecordDao.upsertDownloaded("keep.fit", "u", 1L);
        SyncRecordDao.markUploadFailed(Arrays.asList("keep.fit"), "500");
        SyncLogic.reconcileLocal(summary("{\"status\":1,\"data\":[]}"));
        assertEquals(SyncRecordDao.STATUS_UPLOAD_FAILED, SyncRecordDao.findStatus("keep.fit"));
    }
}
