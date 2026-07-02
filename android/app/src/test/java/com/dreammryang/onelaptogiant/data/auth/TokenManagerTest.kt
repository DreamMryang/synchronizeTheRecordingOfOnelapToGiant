package com.dreammryang.onelaptogiant.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TokenManagerTest {
    private lateinit var tokenStore: TokenStore
    private var loginCount = 0

    @Before
    fun setup() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_tm", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = TokenStore(prefs)
        loginCount = 0
    }

    private fun manager(login: suspend () -> String = { loginCount++; "token-$loginCount" }) =
        TokenManager(Platform.ONELAP, tokenStore, login)

    @Test
    fun `有缓存 token 时不登录`() = runTest {
        tokenStore.set(Platform.ONELAP, "cached")
        assertEquals("cached", manager().getToken())
        assertEquals(0, loginCount)
    }

    @Test
    fun `无缓存时登录一次并缓存`() = runTest {
        val m = manager()
        assertEquals("token-1", m.getToken())
        assertEquals("token-1", m.getToken())
        assertEquals(1, loginCount)
        assertEquals("token-1", tokenStore.get(Platform.ONELAP))
    }

    @Test
    fun `认证失败时清缓存续登并重试一次`() = runTest {
        tokenStore.set(Platform.ONELAP, "stale")
        var calls = 0
        val result = manager().withAuthRetry { token ->
            calls++
            if (token == "stale") throw AuthFailedException("401")
            "ok-with-$token"
        }
        assertEquals("ok-with-token-1", result)
        assertEquals(2, calls)
        assertEquals(1, loginCount)
    }

    @Test
    fun `续登后仍认证失败则向上传播`() = runTest {
        tokenStore.set(Platform.ONELAP, "stale")
        assertThrows(AuthFailedException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager().withAuthRetry<String> { throw AuthFailedException("401") }
            }
        }
        assertEquals(1, loginCount)
    }

    @Test
    fun `非认证异常不触发续登`() = runTest {
        tokenStore.set(Platform.ONELAP, "cached")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager().withAuthRetry<String> { error("网络炸了") }
            }
        }
        assertEquals(0, loginCount)
        assertEquals("cached", tokenStore.get(Platform.ONELAP))
    }
}
