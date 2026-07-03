package com.dreammryang.onelaptogiant.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager)
    }

    @Test
    fun `重复注册周期任务仍只有一个（UPDATE 策略）`() {
        scheduler.schedulePeriodic(intervalHours = 6, wifiOnly = false)
        scheduler.schedulePeriodic(intervalHours = 12, wifiOnly = true)

        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.UNMETERED, infos[0].constraints.requiredNetworkType)
    }

    @Test
    fun `手动同步入队唯一一次性任务`() {
        scheduler.triggerManual()
        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.MANUAL_WORK).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun `取消周期任务`() {
        scheduler.schedulePeriodic(intervalHours = 6, wifiOnly = false)

        scheduler.cancelPeriodic()

        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get()
        assertTrue(infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
