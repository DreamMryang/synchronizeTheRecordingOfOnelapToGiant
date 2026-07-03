package com.dreammryang.onelaptogiant.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.dreammryang.onelaptogiant.data.auth.CredentialStore
import com.dreammryang.onelaptogiant.data.auth.Platform
import com.dreammryang.onelaptogiant.data.auth.TokenStore
import com.dreammryang.onelaptogiant.data.settings.INTERVAL_OFF
import com.dreammryang.onelaptogiant.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var tokenStore: TokenStore
    private lateinit var credentials: CredentialStore
    private val scheduled = mutableListOf<Pair<Int, Boolean>>()
    private var cancelCount = 0
    private var clearHistoryCount = 0

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = ioScope) {
            tmp.newFile("settings_vm.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_settings_vm", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenStore = TokenStore(prefs)
        credentials = CredentialStore(prefs, tokenStore)
        scheduled.clear()
        cancelCount = 0
        clearHistoryCount = 0
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        ioScope.cancel()
    }

    private fun vm() = SettingsViewModel(
        settings,
        credentials,
        schedule = { h, w -> scheduled += h to w },
        cancelSchedule = { cancelCount++ },
        clearHistory = { clearHistoryCount++ },
    )

    @Test
    fun `初始状态从存储加载`() = runTest {
        credentials.saveOnelap("acc", "pwd")
        settings.setIntervalHours(12)

        val state = vm().uiState.first { it.loaded }

        assertEquals("acc", state.onelapAccount)
        assertEquals(12, state.intervalHours)
        assertEquals("30", state.recentDays)
    }

    @Test
    fun `弹窗确定保存账号即写入并清对应token`() = runTest {
        tokenStore.set(Platform.ONELAP, "old-onelap-token")
        tokenStore.set(Platform.GIANT, "old-giant-token")
        val vm = vm()
        vm.uiState.first { it.loaded }

        vm.saveOnelapCredentials("a", "b").join()

        assertEquals("a", credentials.onelapAccount)
        assertEquals("b", credentials.onelapPassword)
        assertNull(tokenStore.get(Platform.ONELAP)) // 改账号清对应 token
        assertEquals("old-giant-token", tokenStore.get(Platform.GIANT)) // 未涉及的平台 token 保留
        assertTrue(scheduled.isEmpty()) // 捷安特尚未配置，不调度

        vm.saveGiantCredentials("c", "d").join()

        assertEquals("c", credentials.giantUsername)
        assertEquals("d", credentials.giantPassword)
        assertNull(tokenStore.get(Platform.GIANT))
        assertEquals(listOf(6 to false), scheduled) // 四项齐全，按当前间隔（默认 6）调度
    }

    @Test
    fun `页面保存只写同步选项不动凭证`() = runTest {
        credentials.saveOnelap("a", "b")
        credentials.saveGiant("c", "d")
        tokenStore.set(Platform.ONELAP, "onelap-token")
        tokenStore.set(Platform.GIANT, "giant-token")
        val vm = vm()
        vm.uiState.first { it.loaded }
        vm.update { it.copy(recentDays = "15", intervalHours = 3, wifiOnly = true) }

        vm.save().join()

        assertEquals("a", credentials.onelapAccount)
        assertEquals("b", credentials.onelapPassword)
        assertEquals("c", credentials.giantUsername)
        assertEquals("d", credentials.giantPassword)
        assertEquals("onelap-token", tokenStore.get(Platform.ONELAP)) // 凭证未变，token 不清
        assertEquals("giant-token", tokenStore.get(Platform.GIANT))
        assertEquals(15, settings.recentDays.first())
        assertEquals(3, settings.intervalHours.first())
        assertTrue(settings.wifiOnly.first())
        assertEquals(listOf(3 to true), scheduled)
    }

    @Test
    fun `账号不全时保存不注册调度`() = runTest {
        val vm = vm()
        vm.uiState.first { it.loaded }

        vm.saveOnelapCredentials("a", "b").join() // 捷安特未配

        assertTrue(scheduled.isEmpty())
        assertEquals(0, cancelCount)
    }

    @Test
    fun `间隔选关闭时取消调度而不注册`() = runTest {
        credentials.saveOnelap("a", "b")
        credentials.saveGiant("c", "d")
        val vm = vm()
        vm.uiState.first { it.loaded }
        vm.update { it.copy(intervalHours = INTERVAL_OFF) }

        vm.save().join()

        assertEquals(1, cancelCount)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `非法天数回落默认 30`() = runTest {
        val vm = vm()
        vm.uiState.first { it.loaded }
        vm.update { it.copy(recentDays = "abc") }

        vm.save().join()

        assertEquals(30, settings.recentDays.first())
    }

    @Test
    fun `清空历史调用一次 lambda 并发出 cleared`() = runTest {
        val vm = vm()
        vm.uiState.first { it.loaded }
        val emitted = mutableListOf<Unit>()
        val job = launch { vm.cleared.collect { emitted += it } }
        runCurrent()

        vm.onClearHistory().join()
        runCurrent()

        assertEquals(1, clearHistoryCount)
        assertEquals(1, emitted.size)
        job.cancel()
    }
}
