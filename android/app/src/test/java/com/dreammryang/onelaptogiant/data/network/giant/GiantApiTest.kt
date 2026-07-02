package com.dreammryang.onelaptogiant.data.network.giant

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import com.dreammryang.onelaptogiant.data.network.HttpClientProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GiantApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: GiantApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = GiantApi(
            client = HttpClientProvider.client,
            json = HttpClientProvider.json,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun teardown() = server.shutdown()

    @Test
    fun `登录表单提交并返回 user_token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"user_token":"gt-1"}"""))
        assertEquals("gt-1", api.login("user", "pwd"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("username=user"))
        assertTrue(body.contains("password=pwd"))
    }

    @Test
    fun `登录无 user_token 抛 LoginFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"msg":"账号或密码错误"}"""))
        assertThrows(LoginFailedException::class.java) {
            runBlocking { api.login("user", "wrong") }
        }
    }

    @Test
    fun `buildSummary 同名多条任一成功即成功`() {
        val records = listOf(
            UploadRecord(file = "a.fit", status = "失败", msg = "解析出错"),
            UploadRecord(file = "a.fit", status = "成功", msg = "success"),
            UploadRecord(file = "b.fit", status = "失败", msg = "文件损坏"),
            UploadRecord(file = "c.fit", status = "成功", msg = "success"),
        )
        val summary = GiantApi.buildSummary(records)
        assertEquals(setOf("a.fit", "b.fit", "c.fit"), summary.uploaded)
        assertEquals(setOf("b.fit"), summary.failedProcess.keys)
        assertTrue(summary.failedProcess["b.fit"]!!.contains("文件损坏"))
    }

    @Test
    fun `fetchAllUpload 解析契约样例`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"status":1,"data":[
                  {"msg":"success","file":"MAGENE_C416_2026-06-18-17-51-08_768485_1781794983851.fit",
                   "status":"成功","time":"2026-06-19 00:00:11","brand":"onelap","device":"bike_computer"}
                ]}
                """.trimIndent()
            )
        )
        val summary = api.fetchAllUpload("gt-1")
        assertEquals(setOf("MAGENE_C416_2026-06-18-17-51-08_768485_1781794983851.fit"), summary.uploaded)
        assertTrue(summary.failedProcess.isEmpty())
        assertTrue(server.takeRequest().body.readUtf8().contains("token=gt-1"))
    }

    @Test
    fun `fetchAllUpload status 异常抛 AuthFailedException`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":0}"""))
        assertThrows(AuthFailedException::class.java) {
            runBlocking { api.fetchAllUpload("stale") }
        }
    }

    @Test
    fun `整批上传携带全部固定字段与文件`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":1}"""))
        val f1 = tmp.newFile("a.fit").apply { writeText("AAA") }
        val f2 = tmp.newFile("b.fit").apply { writeText("BBB") }

        assertTrue(api.uploadFits("gt-1", listOf(f1, f2)))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("name=\"token\""))
        assertTrue(body.contains("gt-1"))
        assertTrue(body.contains("name=\"device\""))
        assertTrue(body.contains("bike_computer"))
        assertTrue(body.contains("name=\"brand\""))
        assertTrue(body.contains("onelap"))
        assertTrue(body.contains("name=\"files[]\"; filename=\"a.fit\""))
        assertTrue(body.contains("name=\"files[]\"; filename=\"b.fit\""))
    }

    @Test
    fun `上传 status 非 1 返回 false`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":0}"""))
        val f = tmp.newFile("a.fit").apply { writeText("AAA") }
        assertFalse(api.uploadFits("gt-1", listOf(f)))
    }
}
