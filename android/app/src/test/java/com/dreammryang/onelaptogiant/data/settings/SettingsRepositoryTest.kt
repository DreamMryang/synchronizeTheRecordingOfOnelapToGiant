package com.dreammryang.onelaptogiant.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun repo(): SettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("settings_test.preferences_pb")
        }
        return SettingsRepository(dataStore)
    }

    @After
    fun teardown() = scope.cancel()

    @Test
    fun `默认值 30 天 6 小时 任意网络`() = runBlocking {
        val repo = repo()
        assertEquals(30, repo.recentDays.first())
        assertEquals(6, repo.intervalHours.first())
        assertFalse(repo.wifiOnly.first())
    }

    @Test
    fun `写入后可读回`() = runBlocking {
        val repo = repo()
        repo.setRecentDays(7)
        repo.setIntervalHours(12)
        repo.setWifiOnly(true)
        assertEquals(7, repo.recentDays.first())
        assertEquals(12, repo.intervalHours.first())
        assertTrue(repo.wifiOnly.first())
    }
}
