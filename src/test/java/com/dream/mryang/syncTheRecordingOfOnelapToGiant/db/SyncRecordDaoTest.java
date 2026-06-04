package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * SyncRecordDao TDD 测试
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

    // ===== 测试 1：空库 findAllFitUrls 为空 =====
    @Test
    public void testFindAllFitUrls_emptyDb_returnsEmptySet() {
        Set<String> urls = SyncRecordDao.findAllFitUrls();
        assertNotNull(urls);
        assertTrue("空库时 findAllFitUrls 应返回空集合", urls.isEmpty());
    }

    // ===== 测试 2：init 幂等（再次 init 不抛异常、库仍可用）=====
    @Test
    public void testInit_calledTwice_isIdempotent() {
        // 第二次 init 同一路径不应抛异常
        SyncRecordDao.init(dbPath);
        // 库仍然可用
        Set<String> urls = SyncRecordDao.findAllFitUrls();
        assertNotNull(urls);
    }

    // ===== 测试 3：insertDownloaded 后 findAllFitUrls 含该 fitUrl =====
    @Test
    public void testInsertDownloaded_fitUrlAppearsInFindAllFitUrls() {
        SyncRecordDao.insertDownloaded("activity_001.fit", "testUser", 1024L);
        Set<String> urls = SyncRecordDao.findAllFitUrls();
        assertTrue("insertDownloaded 后 findAllFitUrls 应包含该 fitUrl", urls.contains("activity_001.fit"));
    }

    // ===== 测试 4：markDownloadFailed 后该 fitUrl 也出现在 findAllFitUrls =====
    @Test
    public void testMarkDownloadFailed_fitUrlAppearsInFindAllFitUrls() {
        SyncRecordDao.markDownloadFailed("activity_002.fit", "testUser", "连接超时");
        Set<String> urls = SyncRecordDao.findAllFitUrls();
        assertTrue("markDownloadFailed 后 findAllFitUrls 应包含该 fitUrl", urls.contains("activity_002.fit"));
    }

    // ===== 测试 5：insertDownloaded 再 markSynced，findStatus == STATUS_SYNCED =====
    @Test
    public void testMarkSynced_statusBecomesStatusSynced() {
        SyncRecordDao.insertDownloaded("activity_003.fit", "testUser", 2048L);
        SyncRecordDao.markSynced(Arrays.asList("activity_003.fit"));
        String status = SyncRecordDao.findStatus("activity_003.fit");
        assertEquals(SyncRecordDao.STATUS_SYNCED, status);
    }

    // ===== 测试 6：insertDownloaded 再 markUploadFailed，findStatus == STATUS_UPLOAD_FAILED =====
    @Test
    public void testMarkUploadFailed_statusBecomesStatusUploadFailed() {
        SyncRecordDao.insertDownloaded("activity_004.fit", "testUser", 4096L);
        SyncRecordDao.markUploadFailed(Arrays.asList("activity_004.fit"), "上传接口 500");
        String status = SyncRecordDao.findStatus("activity_004.fit");
        assertEquals(SyncRecordDao.STATUS_UPLOAD_FAILED, status);
    }
}
