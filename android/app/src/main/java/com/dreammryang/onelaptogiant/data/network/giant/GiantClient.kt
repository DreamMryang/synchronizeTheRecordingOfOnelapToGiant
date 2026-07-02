package com.dreammryang.onelaptogiant.data.network.giant

import java.io.File

data class AllUploadSummary(
    val uploaded: Set<String>,
    val failedProcess: Map<String, String>,
)

interface GiantClient {
    suspend fun fetchAllUpload(token: String): AllUploadSummary
    suspend fun uploadFits(token: String, files: List<File>): Boolean
}
