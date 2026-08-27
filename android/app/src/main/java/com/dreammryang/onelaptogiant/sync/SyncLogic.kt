package com.dreammryang.onelaptogiant.sync

import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.network.giant.AllUploadSummary

object SyncLogic {

    const val PROCESS_GRACE_MS: Long = 48 * 60 * 60 * 1000L

    fun reconcile(
        current: RecordStatus,
        fitUrl: String,
        summary: AllUploadSummary,
        syncTime: Long?,
        now: Long,
    ): RecordStatus? {
        val reconcilable = current == RecordStatus.SYNCED ||
            current == RecordStatus.UPLOAD_FAILED ||
            current == RecordStatus.PROCESS_FAILED
        if (!reconcilable) return null

        // 服务端处理异步（见 docs/api/giant.md §3）：宽限期内不上结论，避免把「处理中」误判为处理失败
        val inGrace = syncTime != null && now - syncTime < PROCESS_GRACE_MS

        val target = when {
            summary.failedProcess.containsKey(fitUrl) -> {
                if (inGrace && current != RecordStatus.PROCESS_FAILED) return null
                RecordStatus.PROCESS_FAILED
            }
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
