package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import java.util.function.Supplier;

/**
 * Token 缓存（懒失效 + 自动续登）。
 * <p>
 * 各端约定：仅在无缓存时登录；业务请求判定认证失效（抛 {@link AuthFailedException}）时
 * 清缓存 → 重新登录 → 重试原请求一次。契约见 docs/api/ 「认证失效判定」。
 * <p>
 * 单端内可能被 Quartz 任务串行调用，方法加锁保证 token 状态一致。
 */
public class TokenCache {

    /** 登录函数，返回新 token；返回 null/空串视为登录失败。 */
    private final Supplier<String> loginFn;

    private volatile String token;

    public TokenCache(Supplier<String> loginFn) {
        this.loginFn = loginFn;
    }

    /**
     * 携带有效 token 执行业务调用；若调用抛 {@link AuthFailedException}，
     * 清缓存并重新登录后重试一次，重试仍失败则异常上抛。
     */
    public synchronized <T> T withAuthRetry(TokenCall<T> call) {
        String currentToken = ensureToken();
        try {
            return call.call(currentToken);
        } catch (AuthFailedException first) {
            // 认证失效：清缓存、重新登录、重试一次
            token = null;
            String freshToken = ensureToken();
            return call.call(freshToken);
        }
    }

    private String ensureToken() {
        if (token == null) {
            String newToken = loginFn.get();
            if (newToken == null || newToken.isEmpty()) {
                throw new RuntimeException("登录失败：返回的 token 为空");
            }
            token = newToken;
        }
        return token;
    }

    /** 携带 token 的业务调用；允许抛受检异常（HTTP 层可能抛 IOException 包装）。 */
    @FunctionalInterface
    public interface TokenCall<T> {
        T call(String token);
    }
}
