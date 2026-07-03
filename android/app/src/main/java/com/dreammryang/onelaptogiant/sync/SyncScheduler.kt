package com.dreammryang.onelaptogiant.sync

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dreammryang.onelaptogiant.data.db.TriggerType
import java.util.concurrent.TimeUnit

class SyncScheduler(private val workManager: WorkManager) {

    fun schedulePeriodic(intervalHours: Int, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to TriggerType.AUTO.name))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }

    fun triggerManual() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to TriggerType.MANUAL.name))
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val PERIODIC_WORK = "sync_periodic"
        const val MANUAL_WORK = "sync_manual"
    }
}
