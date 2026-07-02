package com.dreammryang.onelaptogiant.data.network.onelap

import java.io.File

interface OnelapClient {
    suspend fun listActivityIds(token: String, startDate: String, endDate: String): List<String>
    suspend fun fetchFitUrl(token: String, activityId: String): String?
    suspend fun downloadFit(token: String, fitUrl: String, targetDir: File): File
}
