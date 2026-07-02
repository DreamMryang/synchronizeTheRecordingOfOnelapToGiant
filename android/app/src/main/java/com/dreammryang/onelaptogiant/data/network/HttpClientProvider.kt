package com.dreammryang.onelaptogiant.data.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    // all_upload 可达数千条、上传为整批多文件，读写超时放宽到 60s
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
