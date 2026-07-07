package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * SyncRecordDao TDD 测试。
 * <p>
 * 去重职责已移交捷安特服务端（见 docs/design/multi-client-sync.md），本地库仅记账；
 * 写入改为 upsert 以支持失败后自然重试（同一 fit_url 不再撞 UNIQUE 约束）。
 */
public class SyncRecordDaoTest {

    private String dbPath;

    @Before
    public void setUp() throws Exception {
        // createTempFile 会建出空文件，SQLite 需要不存在或空文件路径，先删掉让 SQLite 自行初始化
        File tmp = File.createTempFile("sync_record_test_", ".db");
        assertTrue("删除临时文件以便 SQLite 初始化", tmp.delete());
        dbPath = tmp.getAbsolutePath();
        SyncRecordDao.init(dbPath);
    }

    // ===== 测试 1：init 幂等（再次 init 不抛异常、库仍可用）=====
    @Test
    public void testInit_calledTwice_isIdempotent() {
        SyncRecordDao.init(dbPath);
        assertNull("空库查询任意 fitUrl 应为 null", SyncRecordDao.findStatus("nope.fit"));
    }

    // ===== 测试 2：upsertDownloaded 首次插入，状态为 DOWNLOADED =====
    @Test
    public void testUpsertDownloaded_firstInsert_statusDownloaded() {
        SyncRecordDao.upsertDownloaded("a.fit", "u", 1024L);
        assertEquals(SyncRecordDao.STATUS_DOWNLOADED, SyncRecordDao.findStatus("a.fit"));
    }

    // ===== 测试 3：upsertDownloadFailed 首次插入，状态为 DOWNLOAD_FAILED =====
    @Test
    public void testUpsertDownloadFailed_firstInsert_statusDownloadFailed() {
        SyncRecordDao.upsertDownloadFailed("b.fit", "u", "连接超时");
        assertEquals(SyncRecordDao.STATUS_DOWNLOAD_FAILED, SyncRecordDao.findStatus("b.fit"));
    }

    // ===== 测试 4：失败后 upsert 回 DOWNLOADED（自然重试，不撞 UNIQUE，error_msg 清空）=====
    @Test
    public void testUpsertDownloaded_afterFailure_flipsBackToDownloaded() {
        SyncRecordDao.upsertDownloadFailed("c.fit", "u", "上次失败");
        // 再次同步成功下载：应更新而非抛 UNIQUE 约束异常
        SyncRecordDao.upsertDownloaded("c.fit", "u", 2048L);
        assertEquals(SyncRecordDao.STATUS_DOWNLOADED, SyncRecordDao.findStatus("c.fit"));
    }

    // ===== 测试 5：markSynced 批量置 SYNCED =====
    @Test
    public void testMarkSynced_statusBecomesSynced() {
        SyncRecordDao.upsertDownloaded("d.fit", "u", 2048L);
        SyncRecordDao.markSynced(Arrays.asList("d.fit"));
        assertEquals(SyncRecordDao.STATUS_SYNCED, SyncRecordDao.findStatus("d.fit"));
    }

    // ===== 测试 6：markUploadFailed 批量置 UPLOAD_FAILED =====
    @Test
    public void testMarkUploadFailed_statusBecomesUploadFailed() {
        SyncRecordDao.upsertDownloaded("e.fit", "u", 4096L);
        SyncRecordDao.markUploadFailed(Arrays.asList("e.fit"), "上传接口 500");
        assertEquals(SyncRecordDao.STATUS_UPLOAD_FAILED, SyncRecordDao.findStatus("e.fit"));
    }

    // ===== 测试 7：findReconcilable 只返回 SYNCED / UPLOAD_FAILED =====
    @Test
    public void testFindReconcilable_onlySyncedAndUploadFailed() {
        SyncRecordDao.upsertDownloaded("downloaded.fit", "u", 1L);          // DOWNLOADED，不参与
        SyncRecordDao.upsertDownloadFailed("dlfail.fit", "u", "x");         // DOWNLOAD_FAILED，不参与
        SyncRecordDao.upsertDownloaded("synced.fit", "u", 1L);
        SyncRecordDao.markSynced(Arrays.asList("synced.fit"));              // SYNCED，参与
        SyncRecordDao.upsertDownloaded("upfail.fit", "u", 1L);
        SyncRecordDao.markUploadFailed(Arrays.asList("upfail.fit"), "500"); // UPLOAD_FAILED，参与

        Map<String, String> reconcilable = SyncRecordDao.findReconcilable();
        assertEquals(2, reconcilable.size());
        assertEquals(SyncRecordDao.STATUS_SYNCED, reconcilable.get("synced.fit"));
        assertEquals(SyncRecordDao.STATUS_UPLOAD_FAILED, reconcilable.get("upfail.fit"));
        assertFalse(reconcilable.containsKey("downloaded.fit"));
        assertFalse(reconcilable.containsKey("dlfail.fit"));
    }

    // ===== 测试 8：updateStatus 置 PROCESS_FAILED 并写 error_msg =====
    @Test
    public void testUpdateStatus_toProcessFailed_setsStatus() {
        SyncRecordDao.upsertDownloaded("f.fit", "u", 1L);
        SyncRecordDao.markSynced(Arrays.asList("f.fit"));
        SyncRecordDao.updateStatus("f.fit", SyncRecordDao.STATUS_PROCESS_FAILED, "处理失败: 坏文件");
        assertEquals(SyncRecordDao.STATUS_PROCESS_FAILED, SyncRecordDao.findStatus("f.fit"));
    }

    // ===== 测试 9：updateStatus 首次转 SYNCED 补 sync_time =====
    @Test
    public void testUpdateStatus_toSynced_backfillsSyncTime() {
        SyncRecordDao.upsertDownloaded("g.fit", "u", 1L);
        SyncRecordDao.markUploadFailed(Arrays.asList("g.fit"), "500"); // 无 sync_time
        SyncRecordDao.updateStatus("g.fit", SyncRecordDao.STATUS_SYNCED, null);
        assertEquals(SyncRecordDao.STATUS_SYNCED, SyncRecordDao.findStatus("g.fit"));
        assertNotNull("首次转 SYNCED 应补 sync_time", SyncRecordDao.findSyncTime("g.fit"));
    }
}
