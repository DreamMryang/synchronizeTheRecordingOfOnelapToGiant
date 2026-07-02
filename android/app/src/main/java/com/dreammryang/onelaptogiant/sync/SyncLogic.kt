package com.dreammryang.onelaptogiant.sync

import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary

object SyncLogic {

    fun reconcile(current: RecordStatus, fitUrl: String, summary: AllUploadSummary): RecordStatus? {
        if (current != RecordStatus.SYNCED && current != RecordStatus.UPLOAD_FAILED) return null
        val target = when {
            summary.failedProcess.containsKey(fitUrl) -> RecordStatus.PROCESS_FAILED
            fitUrl in summary.uploaded -> RecordStatus.SYNCED
            else -> return null // 服务端无记录：保持现状，等下次同步自然重试
        }
        return target.takeIf { it != current }
    }

    fun sessionStatus(foundCount: Int, failedCount: Int): SessionStatus = when {
        foundCount == 0 -> SessionStatus.NO_NEW
        failedCount == 0 -> SessionStatus.SUCCESS
        else -> SessionStatus.PARTIAL
    }
}
