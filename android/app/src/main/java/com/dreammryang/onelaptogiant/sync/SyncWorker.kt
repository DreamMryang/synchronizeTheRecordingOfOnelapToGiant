package com.dreammryang.onelaptogiant.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dreammryang.onelaptogiant.App
import com.dreammryang.onelaptogiant.data.db.TriggerType

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = if (inputData.getString(KEY_TRIGGER) == TriggerType.MANUAL.name) {
            TriggerType.MANUAL
        } else {
            TriggerType.AUTO
        }
        val engine = (applicationContext as App).container.syncEngine
        // 业务成败已在会话中记账；失败靠下个周期自然重试，Worker 本身不重试
        engine.sync(trigger)
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}
