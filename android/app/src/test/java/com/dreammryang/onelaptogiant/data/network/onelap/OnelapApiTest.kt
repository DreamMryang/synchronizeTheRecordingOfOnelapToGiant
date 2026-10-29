package com.dreammryang.onelaptogiant.data.network.onelap

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import com.dreammryang.onelaptogiant.data.network.HttpClientProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

// Robolectric 仅为最后的续登集成测试提供 SharedPreferences；与 MockWebServer 兼容
@RunWith(RobolectricTestRunner::class)
class OnelapApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: OnelapApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = OnelapApi(
            client = HttpClientProvider.client,
            json = HttpClientProvider.json,
            loginBaseUrl = base,
            apiBaseUrl = base,
            nonceProvider = { "abcdef0123456789" },
            timestampProvider = { 1751443200L },
        )
    }

    @After
    fun teardown() = server.shutdown()

    @Test
    fun `登录携带正确签名三件套并返回 token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"userinfo":{"uid":123456},"token":"tok-1"}]}"""))

        val token = api.login("13800138000", "pass123")

        assertEquals("tok-1", token)
        val req = server.takeRequest()
        assertEquals("/api/login", req.path)
        assertEquals("abcdef0123456789", req.getHeader("nonce"))
        assertEquals("1751443200", req.getHeader("timestamp"))
        assertEquals("d68287cd4b8b6d4af22adf1691029ea7", req.getHeader("sign"))
        assertTrue(req.body.readUtf8().contains("\"password\":\"32250170a0dca92d53ec9624f336ca24\""))
    }

    @Test
    fun `登录 data 为空抛 LoginFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertThrows(LoginFailedException::class.java) {
            runBlocking { api.login("a", "b") }
        }
    }

    @Test
    fun `活动列表两阶段查询 total 大于首页时二次全量拉取`() = runBlocking {
        login()
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"success"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"pagination":{"total":3},"list":[{"id":"6a45f39dc323b737cc09a3a1"},{"id":"6a45f39dc323b737cc09a3a2"}]}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"pagination":{"total":3},"list":[{"id":"6a45f39dc323b737cc09a3a1"},{"id":"6a45f39dc323b737cc09a3a2"},{"id":"6a45f39dc323b737cc09a3a3"}]}}"""
            )
        )

        val ids = api.listActivityIds("tok", "2026-06-01", "2026-07-01")

        assertEquals(
            listOf("6a45f39dc323b737cc09a3a1", "6a45f39dc323b737cc09a3a2", "6a45f39dc323b737cc09a3a3"),
            ids,
        )
        val refresh = server.takeRequest()
        assertEquals("/api/otm/ride_record/capability_list", refresh.path)
        assertEquals("POST", refresh.method)
        assertEquals("tok", refresh.getHeader("Authorization"))
        assertEquals("""{"uid":"123456"}""", refresh.body.readUtf8())
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/api/otm/ride_record/list", first.path)
        assertEquals("/api/otm/ride_record/list", second.path)
        assertEquals("tok", first.getHeader("Authorization"))
        assertTrue(first.body.readUtf8().contains("\"limit\":20"))
        assertTrue(second.body.readUtf8().contains("\"limit\":3"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `活动列表 total 不超过首页时刷新后只查询一次`() = runBlocking {
        login()
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"success"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"pagination":{"total":1},"list":[{"id":"6a45f39dc323b737cc09a3a7"}]}}"""
            )
        )
        assertEquals(listOf("6a45f39dc323b737cc09a3a7"), api.listActivityIds("tok", "2026-06-01", "2026-07-01"))
        assertEquals(3, server.requestCount)
        assertEquals("/api/otm/ride_record/capability_list", server.takeRequest().path)
        assertEquals("/api/otm/ride_record/list", server.takeRequest().path)
    }

    @Test
    fun `每次查询列表都刷新且兼容字符串成功码`() = runBlocking {
        login()
        repeat(2) {
            server.enqueue(MockResponse().setBody("""{"code":"200","message":"success"}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"pagination":{"total":0},"list":[]}}"""))

            assertEquals(emptyList<String>(), api.listActivityIds("tok", "2026-06-01", "2026-07-01"))
            assertEquals("/api/otm/ride_record/capability_list", server.takeRequest().path)
            assertEquals("/api/otm/ride_record/list", server.takeRequest().path)
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `刷新业务失败或缺少 code 时不查询列表`(): Unit = runBlocking {
        login()
        for (body in listOf("""{"code":401,"message":"expired"}""", """{"message":"missing code"}""")) {
            server.enqueue(MockResponse().setBody(body))

            assertThrows(AuthFailedException::class.java) {
                runBlocking { api.listActivityIds("tok", "2026-06-01", "2026-07-01") }
            }
            assertEquals("/api/otm/ride_record/capability_list", server.takeRequest().path)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `刷新 HTTP 401 或 403 时不查询列表`(): Unit = runBlocking {
        login()
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code))

            assertThrows(AuthFailedException::class.java) {
                runBlocking { api.listActivityIds("tok", "2026-06-01", "2026-07-01") }
            }
            assertEquals("/api/otm/ride_record/capability_list", server.takeRequest().path)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `登录缺少 UID 或 token 为空时失败`(): Unit = runBlocking {
        for (data in listOf(
            """{"token":"tok"}""",
            """{"userinfo":{},"token":"tok"}""",
            """{"userinfo":{"uid":123456},"token":""}""",
        )) {
            server.enqueue(MockResponse().setBody("""{"data":[$data]}"""))
            assertThrows(LoginFailedException::class.java) {
                runBlocking { api.login("a", "b") }
            }
        }
    }

    @Test
    fun `缓存 token 缺少对应 UID 时续登后刷新列表`() = runBlocking {
        val manager = tokenManager("persisted-token")
        server.enqueue(MockResponse().setBody("""{"data":[{"userinfo":{"uid":654321},"token":"fresh"}]}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"success"}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"pagination":{"total":0},"list":[]}}"""))

        val ids = manager.withAuthRetry { api.listActivityIds(it, "2026-06-01", "2026-07-01") }

        assertEquals(emptyList<String>(), ids)
        assertEquals(3, server.requestCount)
        assertEquals("/api/login", server.takeRequest().path)
        val refresh = server.takeRequest()
        assertEquals("/api/otm/ride_record/capability_list", refresh.path)
        assertEquals("fresh", refresh.getHeader("Authorization"))
        assertEquals("""{"uid":"654321"}""", refresh.body.readUtf8())
        assertEquals("/api/otm/ride_record/list", server.takeRequest().path)
    }

    @Test
    fun `刷新失败续登后使用新 UID 和 token 重试`() = runBlocking {
        login()
        val manager = tokenManager("tok")
        server.enqueue(MockResponse().setBody("""{"code":401,"message":"expired"}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"userinfo":{"uid":654321},"token":"fresh"}]}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"success"}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"pagination":{"total":0},"list":[]}}"""))

        assertEquals(emptyList<String>(), manager.withAuthRetry {
            api.listActivityIds(it, "2026-06-01", "2026-07-01")
        })
        assertEquals(5, server.requestCount)
        val first = server.takeRequest()
        assertEquals("/api/otm/ride_record/capability_list", first.path)
        assertEquals("tok", first.getHeader("Authorization"))
        assertEquals("""{"uid":"123456"}""", first.body.readUtf8())
        assertEquals("/api/login", server.takeRequest().path)
        val retry = server.takeRequest()
        assertEquals("/api/otm/ride_record/capability_list", retry.path)
        assertEquals("fresh", retry.getHeader("Authorization"))
        assertEquals("""{"uid":"654321"}""", retry.body.readUtf8())
        assertEquals("fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `刷新失败续登后再次失败不再重试`(): Unit = runBlocking {
        login()
        val manager = tokenManager("tok")
        server.enqueue(MockResponse().setBody("""{"code":401}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"userinfo":{"uid":654321},"token":"fresh"}]}"""))
        server.enqueue(MockResponse().setBody("""{"code":401}"""))

        assertThrows(AuthFailedException::class.java) {
            runBlocking { manager.withAuthRetry { api.listActivityIds(it, "2026-06-01", "2026-07-01") } }
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `token 不匹配时不使用旧 UID 刷新`(): Unit = runBlocking {
        login()
        assertThrows(AuthFailedException::class.java) {
            runBlocking { api.listActivityIds("other-token", "2026-06-01", "2026-07-01") }
        }
        assertEquals(1, server.requestCount)
    }

    private suspend fun login() {
        server.enqueue(MockResponse().setBody("""{"data":[{"userinfo":{"uid":123456},"token":"tok"}]}"""))
        assertEquals("tok", api.login("a", "b"))
        assertEquals("/api/login", server.takeRequest().path)
    }

    private fun tokenManager(token: String): com.dreammryang.onelaptogiant.data.auth.TokenManager {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("test_onelap_refresh", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = com.dreammryang.onelaptogiant.data.auth.TokenStore(prefs)
        val platform = com.dreammryang.onelaptogiant.data.auth.Platform.ONELAP
        store.set(platform, token)
        return com.dreammryang.onelaptogiant.data.auth.TokenManager(platform, store) { api.login("a", "b") }
    }

    @Test
    fun `详情返回 fitUrl 或 null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"ridingRecord":{"fitUrl":"a.fit"}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"ridingRecord":{"fitUrl":""}}}"""))
        assertEquals("a.fit", api.fetchFitUrl("tok", "101"))
        assertNull(api.fetchFitUrl("tok", "102"))
    }

    @Test
    fun `下载按 Base64 路径写入目标文件`() = runBlocking {
        server.enqueue(MockResponse().setBody("FITDATA"))
        val dir = tmp.newFolder("fit")

        val file = api.downloadFit("tok", "ride_2026_0618.fit", dir)

        assertEquals("ride_2026_0618.fit", file.name)
        assertEquals("FITDATA", file.readText())
        val expectedPath = "/api/otm/ride_record/analysis/fit_content/" +
            Base64.getEncoder().encodeToString("ride_2026_0618.fit".toByteArray())
        assertEquals(expectedPath, server.takeRequest().path)
    }

    @Test
    fun `HTTP 401 抛 AuthFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(AuthFailedException::class.java) {
            runBlocking { api.fetchFitUrl("stale", "101") }
        }
    }

    @Test
    fun `配合 TokenManager 实现 401 续登后重试`() = runBlocking {
        // 场景：缓存 token 失效 → 详情 401 → 自动登录 → 重试成功（设计 §4 / 测试策略「网络测试」）
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("test_onelap_it", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val tokenStore = com.dreammryang.onelaptogiant.data.auth.TokenStore(prefs)
        tokenStore.set(com.dreammryang.onelaptogiant.data.auth.Platform.ONELAP, "stale")
        val manager = com.dreammryang.onelaptogiant.data.auth.TokenManager(
            com.dreammryang.onelaptogiant.data.auth.Platform.ONELAP, tokenStore,
        ) { api.login("13800138000", "pass123") }

        server.enqueue(MockResponse().setResponseCode(401))                       // 旧 token 请求
        server.enqueue(MockResponse().setBody("""{"data":[{"userinfo":{"uid":123456},"token":"fresh"}]}""")) // 自动续登
        server.enqueue(MockResponse().setBody("""{"data":{"ridingRecord":{"fitUrl":"a.fit"}}}""")) // 重试

        val fitUrl = manager.withAuthRetry { token -> api.fetchFitUrl(token, "101") }

        assertEquals("a.fit", fitUrl)
        assertEquals(3, server.requestCount)
        server.takeRequest() // 401 的那次
        server.takeRequest() // 登录
        assertEquals("fresh", server.takeRequest().getHeader("Authorization"))
    }
}
