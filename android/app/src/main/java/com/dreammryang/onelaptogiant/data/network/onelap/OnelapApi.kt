package com.dreammryang.onelaptogiant.data.network.onelap

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Base64

@Serializable
data class OnelapLoginResponse(val data: List<OnelapLoginData>? = null)

@Serializable
data class OnelapLoginData(val token: String? = null, val userinfo: OnelapUserInfo? = null)

@Serializable
data class OnelapUserInfo(val uid: Long? = null)

@Serializable
data class CapabilityListResponse(val code: JsonPrimitive? = null, val message: String? = null)

@Serializable
data class RideListResponse(val data: RideListData? = null)

@Serializable
data class RideListData(val pagination: RidePagination? = null, val list: List<RideItem>? = null)

@Serializable
data class RidePagination(val total: Int = 0)

@Serializable
data class RideItem(val id: String) // 24 位十六进制字符串（2026-07 联调实测），见 docs/api/onelap.md §2

@Serializable
data class RideDetailResponse(val data: RideDetailData? = null)

@Serializable
data class RideDetailData(val ridingRecord: RidingRecord? = null)

@Serializable
data class RidingRecord(val fitUrl: String? = null)

class OnelapApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val loginBaseUrl: String = "https://www.onelap.cn",
    private val apiBaseUrl: String = "https://u.onelap.cn",
    private val nonceProvider: () -> String = { OnelapSign.nonce() },
    private val timestampProvider: () -> Long = { System.currentTimeMillis() / 1000 },
) : OnelapClient {

    private data class LoginSession(val token: String, val uid: Long)

    @Volatile
    private var loginSession: LoginSession? = null

    suspend fun login(account: String, passwordPlain: String): String = withContext(Dispatchers.IO) {
        val passwordMd5 = OnelapSign.md5Hex(passwordPlain)
        val nonce = nonceProvider()
        val timestamp = timestampProvider()
        val body = buildJsonObject {
            put("account", account)
            put("password", passwordMd5)
        }
        val request = Request.Builder()
            .url("$loginBaseUrl/api/login")
            .header("nonce", nonce)
            .header("timestamp", timestamp.toString())
            .header("sign", OnelapSign.sign(account, passwordMd5, nonce, timestamp))
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching { json.decodeFromString<OnelapLoginResponse>(text) }.getOrNull()
            val data = parsed?.data?.firstOrNull()
            val token = data?.token?.takeIf { it.isNotBlank() }
                ?: throw LoginFailedException("顽鹿登录失败: HTTP ${resp.code}")
            val uid = data.userinfo?.uid
                ?: throw LoginFailedException("顽鹿登录失败: 响应缺少 userinfo.uid")
            loginSession = LoginSession(token, uid)
            token
        }
    }

    override suspend fun listActivityIds(
        token: String,
        startDate: String,
        endDate: String,
    ): List<String> = withContext(Dispatchers.IO) {
        refreshActivityList(token)
        // 惯用两阶段：先 limit=20 取 total，total 更大时再全量取回
        val first = queryList(token, limit = 20, startDate, endDate)
        val total = first.pagination?.total ?: 0
        val items = if (total > (first.list?.size ?: 0)) {
            queryList(token, limit = total, startDate, endDate).list
        } else {
            first.list
        }
        (items ?: emptyList()).map { it.id }
    }

    private fun refreshActivityList(token: String) {
        // 进程重启后可能只有持久化 token；缺少对应 UID 时由 TokenManager 续登补全。
        val session = loginSession?.takeIf { it.token == token }
            ?: throw AuthFailedException("顽鹿缺少当前 token 对应的 UID，需重新登录")
        val body = buildJsonObject { put("uid", session.uid.toString()) }
        val request = Request.Builder()
            .url("$apiBaseUrl/api/otm/ride_record/capability_list")
            .header("Authorization", token)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            ensureAuthorized(resp)
            val parsed = json.decodeFromString<CapabilityListResponse>(resp.body?.string().orEmpty())
            if (parsed.code?.content != "200") {
                throw AuthFailedException("顽鹿 capability_list 接口返回非 200 code，视为认证失效：${parsed.message}")
            }
            Timber.i("调 capability_list 接口，响应值 code：%s, message：%s", parsed.code.content, parsed.message)
        }
    }

    private fun queryList(token: String, limit: Int, startDate: String, endDate: String): RideListData {
        val body = buildJsonObject {
            put("page", 1)
            put("limit", limit)
            put("start_date", startDate)
            put("end_date", endDate)
        }
        val request = Request.Builder()
            .url("$apiBaseUrl/api/otm/ride_record/list")
            .header("Authorization", token)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            ensureAuthorized(resp)
            val parsed = json.decodeFromString<RideListResponse>(resp.body?.string().orEmpty())
            return parsed.data ?: throw AuthFailedException("活动列表响应无 data，视为认证失效")
        }
    }

    override suspend fun fetchFitUrl(token: String, activityId: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$apiBaseUrl/api/otm/ride_record/analysis/$activityId")
                .header("Authorization", token)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                ensureAuthorized(resp)
                val parsed = json.decodeFromString<RideDetailResponse>(resp.body?.string().orEmpty())
                val data = parsed.data ?: throw AuthFailedException("活动详情响应无 data，视为认证失效")
                data.ridingRecord?.fitUrl?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File =
        withContext(Dispatchers.IO) {
            val encoded = Base64.getEncoder().encodeToString(fitUrl.toByteArray(Charsets.UTF_8))
            val request = Request.Builder()
                .url("$apiBaseUrl/api/otm/ride_record/analysis/fit_content/$encoded")
                .header("Authorization", token)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                ensureAuthorized(resp)
                if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}: $fitUrl")
                targetDir.mkdirs()
                val target = File(targetDir, fitUrl)
                resp.body!!.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
        }

    // 顽鹿认证失效判定集中处；错误码以联调抓包为准（docs/api/onelap.md 末节）
    private fun ensureAuthorized(resp: Response) {
        if (resp.code == 401 || resp.code == 403) {
            throw AuthFailedException("顽鹿认证失效: HTTP ${resp.code}")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
