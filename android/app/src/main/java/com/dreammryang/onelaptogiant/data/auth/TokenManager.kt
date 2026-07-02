package com.dreammryang.onelaptogiant.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class AuthFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LoginFailedException(message: String) : Exception(message)

class TokenManager(
    private val platform: Platform,
    private val tokenStore: TokenStore,
    private val login: suspend () -> String,
) {
    private val mutex = Mutex()

    suspend fun getToken(): String = mutex.withLock {
        tokenStore.get(platform) ?: login().also { tokenStore.set(platform, it) }
    }

    fun invalidate() = tokenStore.clear(platform)

    suspend fun <T> withAuthRetry(block: suspend (token: String) -> T): T {
        val token = getToken()
        return try {
            block(token)
        } catch (e: AuthFailedException) {
            Timber.i("%s 认证失效，重新登录后重试一次: %s", platform, e.message)
            invalidate()
            block(getToken())
        }
    }
}
