package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

/**
 * 认证失效异常：业务请求返回 401/403，或响应表明 token 未认证（无 user_token / status 异常）时抛出。
 * <p>
 * 由 {@link TokenCache#withAuthRetry} 捕获，触发清缓存 → 重新登录 → 重试一次。
 * 认证失效判定契约见 docs/api/giant.md、docs/api/onelap.md。
 */
public class AuthFailedException extends RuntimeException {
    public AuthFailedException(String message) {
        super(message);
    }
}
