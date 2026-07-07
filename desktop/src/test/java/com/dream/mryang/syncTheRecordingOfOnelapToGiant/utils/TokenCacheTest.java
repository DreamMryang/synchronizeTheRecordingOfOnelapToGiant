package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * TokenCache TDD 测试：懒登录缓存 + 失效续登重试一次（契约见 docs/api/ 认证失效判定）
 */
public class TokenCacheTest {

    // ===== 测试 1：多次调用只登录一次，token 被缓存 =====
    @Test
    public void testWithAuthRetry_tokenCachedAcrossCalls_loginOnlyOnce() {
        AtomicInteger loginCount = new AtomicInteger();
        TokenCache cache = new TokenCache(() -> "token-" + loginCount.incrementAndGet());

        String first = cache.withAuthRetry(token -> token);
        String second = cache.withAuthRetry(token -> token);

        assertEquals("token-1", first);
        assertEquals("token-1", second);
        assertEquals("多次调用应只登录一次", 1, loginCount.get());
    }

    // ===== 测试 2：AuthFailedException 触发清缓存、重新登录并重试一次 =====
    @Test
    public void testWithAuthRetry_authFailed_reloginAndRetryOnce() {
        AtomicInteger loginCount = new AtomicInteger();
        TokenCache cache = new TokenCache(() -> "token-" + loginCount.incrementAndGet());

        String result = cache.withAuthRetry(token -> {
            if ("token-1".equals(token)) {
                throw new AuthFailedException("token 失效");
            }
            return "成功:" + token;
        });

        assertEquals("成功:token-2", result);
        assertEquals("失效后应重新登录一次", 2, loginCount.get());
    }

    // ===== 测试 3：续登后再次失败，异常上抛（只重试一次） =====
    @Test
    public void testWithAuthRetry_authFailedTwice_exceptionPropagates() {
        AtomicInteger loginCount = new AtomicInteger();
        AtomicInteger callCount = new AtomicInteger();
        TokenCache cache = new TokenCache(() -> "token-" + loginCount.incrementAndGet());

        try {
            cache.withAuthRetry(token -> {
                callCount.incrementAndGet();
                throw new AuthFailedException("持续失效");
            });
            fail("二次失败应上抛 AuthFailedException");
        } catch (AuthFailedException e) {
            assertEquals("持续失效", e.getMessage());
        }
        assertEquals("业务调用应恰好两次（原始 + 重试）", 2, callCount.get());
        assertEquals("登录应恰好两次（首次 + 续登）", 2, loginCount.get());
    }

    // ===== 测试 4：登录返回空 token 视为登录失败 =====
    @Test
    public void testWithAuthRetry_loginReturnsEmpty_throwsRuntimeException() {
        TokenCache cache = new TokenCache(() -> "");
        try {
            cache.withAuthRetry(token -> token);
            fail("登录返回空 token 应抛 RuntimeException");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("登录"));
        }
    }

    // ===== 测试 5：重试成功后，后续调用继续复用新 token（不再登录） =====
    @Test
    public void testWithAuthRetry_afterRelogin_newTokenCached() {
        AtomicInteger loginCount = new AtomicInteger();
        TokenCache cache = new TokenCache(() -> "token-" + loginCount.incrementAndGet());

        cache.withAuthRetry(token -> {
            if ("token-1".equals(token)) {
                throw new AuthFailedException("token 失效");
            }
            return token;
        });
        String next = cache.withAuthRetry(token -> token);

        assertEquals("token-2", next);
        assertEquals("续登后的 token 应被缓存", 2, loginCount.get());
    }
}
