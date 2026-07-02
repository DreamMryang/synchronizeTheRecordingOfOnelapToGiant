package com.dreammryang.onelaptogiant.data.network.onelap

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Base64

@Serializable
data class OnelapLoginResponse(val data: List<OnelapLoginData>? = null)

@Serializable
data class OnelapLoginData(val token: String? = null)

@Serializable
data class RideListResponse(val data: RideListData? = null)

@Serializable
data class RideListData(val pagination: RidePagination? = null, val list: List<RideItem>? = null)

@Serializable
data class RidePagination(val total: Int = 0)

@Serializable
data class RideItem(val id: Long)

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
            parsed?.data?.firstOrNull()?.token
                ?: throw LoginFailedException("顽鹿登录失败: HTTP ${resp.code}, body=$text")
        }
    }

    override suspend fun listActivityIds(
        token: String,
        startDate: String,
        endDate: String,
    ): List<String> = withContext(Dispatchers.IO) {
        // 惯用两阶段：先 limit=20 取 total，total 更大时再全量取回
        val first = queryList(token, limit = 20, startDate, endDate)
        val total = first.pagination?.total ?: 0
        val items = if (total > (first.list?.size ?: 0)) {
            queryList(token, limit = total, startDate, endDate).list
        } else {
            first.list
        }
        (items ?: emptyList()).map { it.id.toString() }
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
