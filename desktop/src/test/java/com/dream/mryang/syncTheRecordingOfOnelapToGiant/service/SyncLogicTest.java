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

    /** 宽限期之外的时间点：服务端非成功可定论为处理失败 */
    private static final long LONG_AGO = 0L;
    private static final long NOW = SyncLogic.PROCESS_GRACE_MS + 1;

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
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_DOWNLOADED, "a.fit", s, LONG_AGO, NOW));
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_DOWNLOAD_FAILED, "a.fit", s, LONG_AGO, NOW));
    }

    // ===== 纯逻辑：UPLOAD_FAILED 但服务端已成功 → 校正为 SYNCED =====
    @Test
    public void testReconcileTarget_uploadFailedButServerSuccess_becomesSynced() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"成功\"}]}");
        assertEquals(SyncRecordDao.STATUS_SYNCED,
                SyncLogic.reconcileTarget(SyncRecordDao.STATUS_UPLOAD_FAILED, "a.fit", s, LONG_AGO, NOW));
    }

    // ===== 纯逻辑：SYNCED 但服务端处理失败（超宽限期）→ 校正为 PROCESS_FAILED =====
    @Test
    public void testReconcileTarget_syncedButServerFailed_becomesProcessFailed() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"处理失败\",\"msg\":\"坏\"}]}");
        assertEquals(SyncRecordDao.STATUS_PROCESS_FAILED,
                SyncLogic.reconcileTarget(SyncRecordDao.STATUS_SYNCED, "a.fit", s, LONG_AGO, NOW));
        assertEquals(SyncRecordDao.STATUS_PROCESS_FAILED,
                SyncLogic.reconcileTarget(SyncRecordDao.STATUS_UPLOAD_FAILED, "a.fit", s, LONG_AGO, NOW));
    }

    // ===== 纯逻辑：宽限期内的非成功视为处理中，不标记 PROCESS_FAILED =====
    @Test
    public void testReconcileTarget_withinGrace_returnsNull() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"处理失败\",\"msg\":\"坏\"}]}");
        long syncTime = NOW - SyncLogic.PROCESS_GRACE_MS + 1;
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_SYNCED, "a.fit", s, syncTime, NOW));
    }

    // ===== 纯逻辑：无同步时间的记录不享受宽限期 =====
    @Test
    public void testReconcileTarget_noSyncTime_notProtectedByGrace() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"处理失败\",\"msg\":\"坏\"}]}");
        assertEquals(SyncRecordDao.STATUS_PROCESS_FAILED,
                SyncLogic.reconcileTarget(SyncRecordDao.STATUS_SYNCED, "a.fit", s, null, NOW));
    }

    // ===== 纯逻辑：PROCESS_FAILED 在服务端转成功后自愈为 SYNCED =====
    @Test
    public void testReconcileTarget_processFailedSelfHealsToSynced() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"成功\"}]}");
        assertEquals(SyncRecordDao.STATUS_SYNCED,
                SyncLogic.reconcileTarget(SyncRecordDao.STATUS_PROCESS_FAILED, "a.fit", s, LONG_AGO, NOW));
    }

    // ===== 纯逻辑：PROCESS_FAILED 在服务端仍报失败时保持不变 =====
    @Test
    public void testReconcileTarget_processFailedStillFailed_returnsNull() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"处理失败\",\"msg\":\"坏\"}]}");
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_PROCESS_FAILED, "a.fit", s, LONG_AGO, NOW));
    }

    // ===== 纯逻辑：服务端无记录 → null（保持现状，下次自然重试）=====
    @Test
    public void testReconcileTarget_serverHasNoRecord_returnsNull() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[]}");
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_UPLOAD_FAILED, "a.fit", s, LONG_AGO, NOW));
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_PROCESS_FAILED, "a.fit", s, LONG_AGO, NOW));
    }

    // ===== 纯逻辑：目标与现状相同 → null（无需写库）=====
    @Test
    public void testReconcileTarget_targetEqualsCurrent_returnsNull() {
        AllUploadSummary s = summary("{\"status\":1,\"data\":[{\"file\":\"a.fit\",\"status\":\"成功\"}]}");
        assertNull(SyncLogic.reconcileTarget(SyncRecordDao.STATUS_SYNCED, "a.fit", s, LONG_AGO, NOW));
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

    // ===== 集成：reconcileLocal 把 UPLOAD_FAILED（无 sync_time）落库校正为 PROCESS_FAILED =====
    @Test
    public void testReconcileLocal_flipsUploadFailedToProcessFailed() {
        SyncRecordDao.upsertDownloaded("bad.fit", "u", 1L);
        SyncRecordDao.markUploadFailed(Arrays.asList("bad.fit"), "上次 500");
        SyncLogic.reconcileLocal(summary(
                "{\"status\":1,\"data\":[{\"file\":\"bad.fit\",\"status\":\"处理失败\",\"msg\":\"坏文件\"}]}"));
        assertEquals(SyncRecordDao.STATUS_PROCESS_FAILED, SyncRecordDao.findStatus("bad.fit"));
    }

    // ===== 集成：刚同步（宽限期内）的 SYNCED 记录不因服务端暂时非成功而误判 =====
    @Test
    public void testReconcileLocal_syncedWithinGrace_staysSynced() {
        SyncRecordDao.upsertDownloaded("fresh.fit", "u", 1L);
        SyncRecordDao.markSynced(Arrays.asList("fresh.fit")); // sync_time = now → 宽限期内
        SyncLogic.reconcileLocal(summary(
                "{\"status\":1,\"data\":[{\"file\":\"fresh.fit\",\"status\":\"处理失败\",\"msg\":\"坏文件\"}]}"));
        assertEquals(SyncRecordDao.STATUS_SYNCED, SyncRecordDao.findStatus("fresh.fit"));
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
