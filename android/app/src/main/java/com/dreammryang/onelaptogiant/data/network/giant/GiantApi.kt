package com.dreammryang.onelaptogiant.data.network.giant

import com.dreammryang.onelaptogiant.data.auth.AuthFailedException
import com.dreammryang.onelaptogiant.data.auth.LoginFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File

@Serializable
data class GiantLoginResponse(@SerialName("user_token") val userToken: String? = null)

@Serializable
data class AllUploadResponse(val status: Int = 0, val data: List<UploadRecord>? = null)

@Serializable
data class UploadRecord(val file: String = "", val status: String = "", val msg: String? = null)

@Serializable
data class UploadFitResponse(val status: Int = 0)

class GiantApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://ridelife.giant.com.cn",
) : GiantClient {

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder().url("$baseUrl/index.php/api/login").post(form).build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val token = runCatching { json.decodeFromString<GiantLoginResponse>(text).userToken }.getOrNull()
            token?.takeIf { it.isNotBlank() }
                ?: throw LoginFailedException("捷安特登录失败: HTTP ${resp.code}, body=$text")
        }
    }

    override suspend fun fetchAllUpload(token: String): AllUploadSummary = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("token", token).build()
        val request = Request.Builder().url("$baseUrl/index.php/api/all_upload").post(form).build()
        client.newCall(request).execute().use { resp ->
            ensureAuthorized(resp)
            val parsed = json.decodeFromString<AllUploadResponse>(resp.body?.string().orEmpty())
            // 该接口仅带 token 参数，status 异常≈token 失效（联调后如有更细错误码再调整）
            if (parsed.status != 1) throw AuthFailedException("all_upload status=${parsed.status}，视为认证失效")
            buildSummary(parsed.data ?: emptyList())
        }
    }

    override suspend fun uploadFits(token: String, files: List<File>): Boolean = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("token", token)
            .addFormDataPart("device", "bike_computer")
            .addFormDataPart("brand", "onelap")
        files.forEach { builder.addFormDataPart("files[]", it.name, it.asRequestBody(FIT_MEDIA)) }
        val request = Request.Builder().url("$baseUrl/index.php/api/upload_fit").post(builder.build()).build()
        client.newCall(request).execute().use { resp ->
            ensureAuthorized(resp)
            val parsed = runCatching {
                json.decodeFromString<UploadFitResponse>(resp.body?.string().orEmpty())
            }.getOrNull()
            parsed?.status == 1
        }
    }

    // 捷安特认证失效判定集中处；错误码以联调抓包为准（docs/api/giant.md 末节）
    private fun ensureAuthorized(resp: Response) {
        if (resp.code == 401 || resp.code == 403) {
            throw AuthFailedException("捷安特认证失效: HTTP ${resp.code}")
        }
    }

    companion object {
        const val STATUS_SUCCESS = "成功"
        private val FIT_MEDIA = "application/octet-stream".toMediaType()

        // 同名多条：任一成功即成功；全部非成功 → 处理失败（取最后一条的状态+消息作展示文案）
        fun buildSummary(records: List<UploadRecord>): AllUploadSummary {
            val byFile = records.filter { it.file.isNotBlank() }.groupBy { it.file }
            val failed = byFile
                .filterValues { list -> list.none { it.status == STATUS_SUCCESS } }
                .mapValues { (_, list) ->
                    val last = list.last()
                    listOfNotNull(last.status.takeIf { it.isNotBlank() }, last.msg).joinToString(": ")
                }
            return AllUploadSummary(uploaded = byFile.keys, failedProcess = failed)
        }
    }
}
