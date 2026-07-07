package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.AuthFailedException;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AllUploadSummary.parse TDD 测试。
 * 规则与 Android GiantApi.buildSummary 一致：uploaded=出现过的全部 file（任意状态）；
 * 同名多条任一「成功」即成功，否则记入 failedProcess（取最后一条状态+消息）。
 */
public class AllUploadSummaryTest {

    // ===== 测试 1：单条成功 → uploaded 含该文件，failedProcess 为空 =====
    @Test
    public void testParse_singleSuccess_uploadedContainsFile() {
        String json = "{\"status\":1,\"data\":[" +
                "{\"file\":\"a.fit\",\"status\":\"成功\",\"msg\":\"success\"}]}";
        AllUploadSummary s = AllUploadSummary.parse(json);
        assertTrue(s.getUploaded().contains("a.fit"));
        assertTrue("成功文件不应进 failedProcess", s.getFailedProcess().isEmpty());
    }

    // ===== 测试 2：同名多条，任一成功即成功（不进 failedProcess） =====
    @Test
    public void testParse_duplicateFile_anySuccessCountsAsSuccess() {
        String json = "{\"status\":1,\"data\":[" +
                "{\"file\":\"b.fit\",\"status\":\"处理失败\",\"msg\":\"x\"}," +
                "{\"file\":\"b.fit\",\"status\":\"成功\",\"msg\":\"ok\"}]}";
        AllUploadSummary s = AllUploadSummary.parse(json);
        assertTrue(s.getUploaded().contains("b.fit"));
        assertFalse("同名任一成功即成功", s.getFailedProcess().containsKey("b.fit"));
    }

    // ===== 测试 3：全部非成功 → failedProcess 取最后一条的 status: msg =====
    @Test
    public void testParse_allFailed_recordedWithLastStatusAndMsg() {
        String json = "{\"status\":1,\"data\":[" +
                "{\"file\":\"c.fit\",\"status\":\"处理中\",\"msg\":\"first\"}," +
                "{\"file\":\"c.fit\",\"status\":\"处理失败\",\"msg\":\"坏文件\"}]}";
        AllUploadSummary s = AllUploadSummary.parse(json);
        assertTrue("已上传即入 uploaded（任意状态）", s.getUploaded().contains("c.fit"));
        assertEquals("处理失败: 坏文件", s.getFailedProcess().get("c.fit"));
    }

    // ===== 测试 4：空白 file 过滤 =====
    @Test
    public void testParse_blankFile_ignored() {
        String json = "{\"status\":1,\"data\":[" +
                "{\"file\":\"\",\"status\":\"成功\"}," +
                "{\"file\":\"d.fit\",\"status\":\"成功\"}]}";
        AllUploadSummary s = AllUploadSummary.parse(json);
        assertEquals(1, s.getUploaded().size());
        assertTrue(s.getUploaded().contains("d.fit"));
    }

    // ===== 测试 5：data 为空数组 → 两个集合都空 =====
    @Test
    public void testParse_emptyData_returnsEmptySets() {
        String json = "{\"status\":1,\"data\":[]}";
        AllUploadSummary s = AllUploadSummary.parse(json);
        assertTrue(s.getUploaded().isEmpty());
        assertTrue(s.getFailedProcess().isEmpty());
    }

    // ===== 测试 6：根 status != 1 → 视为认证失效，抛 AuthFailedException =====
    @Test(expected = AuthFailedException.class)
    public void testParse_statusNotOne_throwsAuthFailed() {
        AllUploadSummary.parse("{\"status\":0,\"msg\":\"未登录\"}");
    }

    // ===== 测试 7：只有 status（无 data 字段）status=1 → 空集合，不抛异常 =====
    @Test
    public void testParse_statusOneNoData_returnsEmpty() {
        AllUploadSummary s = AllUploadSummary.parse("{\"status\":1}");
        assertTrue(s.getUploaded().isEmpty());
        assertTrue(s.getFailedProcess().isEmpty());
    }
}
