package com.dream.mryang.syncTheRecordingOfOnelapToGiant.db;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
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

    // ===== 测试 1：空库 findAllFileKeys 为空 =====
    @Test
    public void testFindAllFileKeys_emptyDb_returnsEmptySet() {
        Set<String> keys = SyncRecordDao.findAllFileKeys();
        assertNotNull(keys);
        assertTrue("空库时 findAllFileKeys 应返回空集合", keys.isEmpty());
    }

    // ===== 测试 2：init 幂等（再次 init 不抛异常、库仍可用）=====
    @Test
    public void testInit_calledTwice_isIdempotent() {
        // 第二次 init 同一路径不应抛异常
        SyncRecordDao.init(dbPath);
        // 库仍然可用
        Set<String> keys = SyncRecordDao.findAllFileKeys();
        assertNotNull(keys);
    }

    // ===== 测试 3：insertDownloaded 后 findAllFileKeys 含该 key =====
    @Test
    public void testInsertDownloaded_fileKeyAppearsInFindAllFileKeys() {
        SyncRecordDao.insertDownloaded("activity_001.fit", "testUser", 1024L);
        Set<String> keys = SyncRecordDao.findAllFileKeys();
        assertTrue("insertDownloaded 后 findAllFileKeys 应包含该 key", keys.contains("activity_001.fit"));
    }

    // ===== 测试 4：markDownloadFailed 后该 key 也出现在 findAllFileKeys =====
    @Test
    public void testMarkDownloadFailed_fileKeyAppearsInFindAllFileKeys() {
        SyncRecordDao.markDownloadFailed("activity_002.fit", "testUser", "连接超时");
        Set<String> keys = SyncRecordDao.findAllFileKeys();
        assertTrue("markDownloadFailed 后 findAllFileKeys 应包含该 key", keys.contains("activity_002.fit"));
    }
}
