package com.dreammryang.onelaptogiant.ui.home

import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.sync.SyncProgress
import com.dreammryang.onelaptogiant.sync.SyncStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `聚合四路输入为 UiState`() = runTest {
        val session = SyncSessionEntity(
            id = 1, triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS, startedAt = 1L,
        )
        var triggered = 0
        val vm = HomeViewModel(
            configured = flowOf(true),
            progress = flowOf(SyncProgress(SyncStep.DOWNLOADING, 1, 3)),
            lastSession = flowOf(session),
            processFailedCount = flowOf(2),
            onSyncRequested = { triggered++ },
        )

        val state = vm.uiState.first { it.configured }

        assertTrue(state.syncing)
        assertEquals(SyncStep.DOWNLOADING, state.progress!!.step)
        assertEquals(1L, state.lastSession!!.id)
        assertEquals(2, state.processFailedCount)

        vm.onSyncClick()
        assertEquals(1, triggered)
    }

    @Test
    fun `默认状态未配置且空闲`() = runTest {
        val vm = HomeViewModel(flowOf(false), flowOf(null), flowOf(null), flowOf(0)) {}
        val state = vm.uiState.first()
        assertFalse(state.configured)
        assertFalse(state.syncing)
    }
}
