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
        server.enqueue(MockResponse().setBody("""{"data":[{"token":"tok-1"}]}"""))

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
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"limit\":20"))
        assertTrue(second.contains("\"limit\":3"))
    }

    @Test
    fun `活动列表 total 不超过首页时只请求一次`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"pagination":{"total":1},"list":[{"id":"6a45f39dc323b737cc09a3a7"}]}}"""
            )
        )
        assertEquals(listOf("6a45f39dc323b737cc09a3a7"), api.listActivityIds("tok", "2026-06-01", "2026-07-01"))
        assertEquals(1, server.requestCount)
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
        server.enqueue(MockResponse().setBody("""{"data":[{"token":"fresh"}]}""")) // 自动续登
        server.enqueue(MockResponse().setBody("""{"data":{"ridingRecord":{"fitUrl":"a.fit"}}}""")) // 重试

        val fitUrl = manager.withAuthRetry { token -> api.fetchFitUrl(token, "101") }

        assertEquals("a.fit", fitUrl)
        assertEquals(3, server.requestCount)
        server.takeRequest() // 401 的那次
        server.takeRequest() // 登录
        assertEquals("fresh", server.takeRequest().getHeader("Authorization"))
    }
}
