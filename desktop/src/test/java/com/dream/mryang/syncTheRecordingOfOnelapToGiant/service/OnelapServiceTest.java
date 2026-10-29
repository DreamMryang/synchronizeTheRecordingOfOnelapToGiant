package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class OnelapServiceTest {

    @Test
    public void testLoginResponse_cachesUidAndReturnsToken() throws Exception {
        OnelapService service = new OnelapService();

        String token = parseLoginResponse(service,
                "{\"code\":200,\"error\":\"success\",\"data\":[{\"userinfo\":{\"uid\":123456},"
                        + "\"token\":\"test-token\",\"refresh_token\":\"test-refresh-token\"}]}");

        assertEquals("test-token", token);
        assertEquals("123456", cachedUid(service));
    }

    @Test
    public void testLoginResponse_reloginRefreshesUid() throws Exception {
        OnelapService service = new OnelapService();
        parseLoginResponse(service,
                "{\"data\":[{\"userinfo\":{\"uid\":123456},\"token\":\"old-token\"}]}");

        String token = parseLoginResponse(service,
                "{\"data\":[{\"userinfo\":{\"uid\":654321},\"token\":\"new-token\"}]}");

        assertEquals("new-token", token);
        assertEquals("654321", cachedUid(service));
    }

    @Test
    public void testLoginResponse_missingUidRejectsLogin() throws Exception {
        OnelapService service = new OnelapService();

        assertNull(parseLoginResponse(service, "{\"data\":[{\"userinfo\":{},\"token\":\"test-token\"}]}"));
        assertNull(parseLoginResponse(service, "{\"data\":[{\"token\":\"test-token\"}]}"));
    }

    @Test
    public void testLoginResponse_emptyDataRejectsLogin() throws Exception {
        OnelapService service = new OnelapService();

        assertNull(parseLoginResponse(service, "{\"data\":[]}"));
        assertNull(parseLoginResponse(service, "{\"code\":401}"));
    }

    private String parseLoginResponse(OnelapService service, String json) throws Exception {
        Method method = OnelapService.class.getDeclaredMethod("parseLoginResponse", String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, json);
        } catch (InvocationTargetException e) {
            throw new AssertionError("登录响应解析失败", e.getCause());
        }
    }

    private String cachedUid(OnelapService service) throws Exception {
        Field field = OnelapService.class.getDeclaredField("uid");
        field.setAccessible(true);
        return (String) field.get(service);
    }
}
