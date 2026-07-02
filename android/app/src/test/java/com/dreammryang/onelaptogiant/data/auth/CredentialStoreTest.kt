package com.dreammryang.onelaptogiant.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    private lateinit var tokenStore: TokenStore
    private lateinit var store: CredentialStore

    @Before
    fun setup() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_secure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = TokenStore(prefs)
        store = CredentialStore(prefs, tokenStore)
    }

    @Test
    fun `token 按平台读写清除`() {
        tokenStore.set(Platform.ONELAP, "t1")
        tokenStore.set(Platform.GIANT, "t2")
        assertEquals("t1", tokenStore.get(Platform.ONELAP))
        tokenStore.clear(Platform.ONELAP)
        assertNull(tokenStore.get(Platform.ONELAP))
        assertEquals("t2", tokenStore.get(Platform.GIANT))
    }

    @Test
    fun `保存顽鹿账号只清顽鹿 token`() {
        tokenStore.set(Platform.ONELAP, "t1")
        tokenStore.set(Platform.GIANT, "t2")
        store.saveOnelap("user", "pwd")
        assertNull(tokenStore.get(Platform.ONELAP))
        assertEquals("t2", tokenStore.get(Platform.GIANT))
        assertEquals("user", store.onelapAccount)
        assertEquals("pwd", store.onelapPassword)
    }

    @Test
    fun `保存捷安特账号只清捷安特 token`() {
        tokenStore.set(Platform.ONELAP, "t1")
        tokenStore.set(Platform.GIANT, "t2")
        store.saveGiant("user", "pwd")
        assertNull(tokenStore.get(Platform.GIANT))
        assertEquals("t1", tokenStore.get(Platform.ONELAP))
        assertEquals("user", store.giantUsername)
        assertEquals("pwd", store.giantPassword)
    }

    @Test
    fun `四项齐全才算已配置且 configured 流同步更新`() {
        assertFalse(store.isConfigured())
        assertFalse(store.configured.value)
        store.saveOnelap("a", "b")
        assertFalse(store.isConfigured())
        store.saveGiant("c", "d")
        assertTrue(store.isConfigured())
        assertTrue(store.configured.value)
    }
}
