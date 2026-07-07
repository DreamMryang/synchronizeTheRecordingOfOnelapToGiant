package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * HttpClientUtil TDD 测试：用 JDK 自带 HttpServer 起本地服务，验证状态码语义。
 * 重点：downloadFile 修复前不检查状态码，token 失效时会把错误页字节存成 FIT 文件。
 */
public class HttpClientUtilTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // 200：返回正常内容
        server.createContext("/ok", ex -> respond(ex, 200, "FIT-BYTES"));
        // 401：认证失效
        server.createContext("/unauth", ex -> respond(ex, 401, "unauthorized"));
        // 500：其他服务端错误
        server.createContext("/err", ex -> respond(ex, 500, "boom"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ===== doGet：401 → AuthFailedException =====
    @Test(expected = AuthFailedException.class)
    public void testDoGet_401_throwsAuthFailed() {
        HttpClientUtil.doGet(baseUrl + "/unauth", null, null);
    }

    // ===== doGet：500 → RuntimeException（非认证） =====
    @Test
    public void testDoGet_500_throwsRuntimeButNotAuthFailed() {
        try {
            HttpClientUtil.doGet(baseUrl + "/err", null, null);
            fail("500 应抛异常");
        } catch (AuthFailedException e) {
            fail("500 不应判为认证失效");
        } catch (RuntimeException e) {
            // 预期
        }
    }

    // ===== downloadFile：200 → 正常落盘 =====
    @Test
    public void testDownloadFile_200_writesFile() throws IOException {
        File out = File.createTempFile("dl_ok_", ".fit");
        assertTrue(out.delete());
        HttpClientUtil.downloadFile(baseUrl + "/ok", null, out);
        assertTrue("200 应产生文件", out.exists());
        assertTrue("文件应非空", out.length() > 0);
        out.delete();
    }

    // ===== downloadFile：401 → AuthFailedException，且不产生文件（关键修复点）=====
    @Test
    public void testDownloadFile_401_throwsAuthFailedAndNoFileWritten() throws IOException {
        File out = File.createTempFile("dl_401_", ".fit");
        assertTrue(out.delete());
        try {
            HttpClientUtil.downloadFile(baseUrl + "/unauth", null, out);
            fail("401 应抛 AuthFailedException");
        } catch (AuthFailedException e) {
            // 预期
        }
        assertFalse("401 时不应把错误页写成 FIT 文件", out.exists());
    }

    // ===== downloadFile：500 → RuntimeException，不产生文件 =====
    @Test
    public void testDownloadFile_500_throwsAndNoFileWritten() throws IOException {
        File out = File.createTempFile("dl_500_", ".fit");
        assertTrue(out.delete());
        try {
            HttpClientUtil.downloadFile(baseUrl + "/err", null, out);
            fail("500 应抛异常");
        } catch (AuthFailedException e) {
            fail("500 不应判为认证失效");
        } catch (RuntimeException e) {
            // 预期
        }
        assertFalse("500 时不应产生文件", out.exists());
    }
}
