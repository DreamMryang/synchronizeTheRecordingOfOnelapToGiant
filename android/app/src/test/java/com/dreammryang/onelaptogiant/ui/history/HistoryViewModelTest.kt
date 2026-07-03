package com.dreammryang.onelaptogiant.ui.history

import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.data.db.TriggerType
import com.dreammryang.onelaptogiant.sync.SyncProgress
import com.dreammryang.onelaptogiant.sync.SyncStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `聚合五路输入为 UiState`() = runTest {
        val session = SyncSessionEntity(
            id = 1, triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS, startedAt = 1L,
        )
        var triggered = 0
        val vm = HistoryViewModel(
            sessions = flowOf(listOf(session)),
            configured = flowOf(true),
            progress = flowOf(SyncProgress(SyncStep.DOWNLOADING, 1, 3)),
            processFailedCount = flowOf(2),
            onSyncRequested = { triggered++ },
            deleteSession = {},
        )

        val state = vm.uiState.first { it.configured }

        assertTrue(state.syncing)
        assertEquals(SyncStep.DOWNLOADING, state.progress!!.step)
        assertEquals(1, state.sessions.size)
        assertEquals(1L, state.sessions[0].id)
        assertEquals(2, state.processFailedCount)

        vm.onSyncClick()
        assertEquals(1, triggered)
    }

    @Test
    fun `默认状态未配置且空闲空列表`() = runTest {
        val vm = HistoryViewModel(flowOf(emptyList()), flowOf(false), flowOf(null), flowOf(0), {}, deleteSession = {})
        val state = vm.uiState.first()
        assertFalse(state.configured)
        assertFalse(state.syncing)
        assertTrue(state.sessions.isEmpty())
    }

    @Test
    fun `onSyncClick 触发回调`() = runTest {
        var triggered = 0
        val vm = HistoryViewModel(
            flowOf(emptyList()), flowOf(true), flowOf(null), flowOf(0),
            onSyncRequested = { triggered++ }, deleteSession = {},
        )
        vm.onSyncClick()
        vm.onSyncClick()
        assertEquals(2, triggered)
    }

    @Test
    fun `删除会话调用 lambda 并发出已删除会话提示`() = runTest {
        val deleted = mutableListOf<Long>()
        val vm = HistoryViewModel(
            flowOf(emptyList()), flowOf(true), flowOf(null), flowOf(0),
            onSyncRequested = {}, deleteSession = { deleted += it },
        )
        val messages = mutableListOf<String>()
        val job = launch { vm.message.collect { messages += it } }
        runCurrent()

        vm.onDeleteSession(42L)
        runCurrent()

        assertEquals(listOf(42L), deleted)
        assertEquals(listOf("已删除会话"), messages)
        job.cancel()
    }

    @Test
    fun `会话从进行中翻转为终态时发出完成提示`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val sessionsFlow = MutableStateFlow(
                    listOf(
                        SyncSessionEntity(
                            id = 1, triggerType = TriggerType.AUTO, status = SessionStatus.RUNNING, startedAt = 1L,
                        ),
                    ),
                )
                val vm = HistoryViewModel(sessionsFlow, flowOf(true), flowOf(null), flowOf(0), {}, deleteSession = {})
                val messages = mutableListOf<String>()
                val job = launch { vm.message.collect { messages += it } }
                runCurrent() // 确保 collect 已建立订阅，再触发终态

                sessionsFlow.value = listOf(
                    SyncSessionEntity(
                        id = 1, triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS,
                        startedAt = 1L, syncedCount = 2,
                    ),
                )
                runCurrent()

                assertEquals(1, messages.size)
                assertEquals("同步完成：成功（同步 2 条）", messages[0])
                job.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `首次加载即终态不触发提示`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val sessionsFlow = MutableStateFlow(
                    listOf(
                        SyncSessionEntity(
                            id = 1, triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS, startedAt = 1L,
                        ),
                    ),
                )
                val vm = HistoryViewModel(sessionsFlow, flowOf(true), flowOf(null), flowOf(0), {}, deleteSession = {})
                val messages = mutableListOf<String>()
                val job = launch { vm.message.collect { messages += it } }
                runCurrent()

                sessionsFlow.value = listOf(
                    SyncSessionEntity(
                        id = 1, triggerType = TriggerType.AUTO, status = SessionStatus.SUCCESS, startedAt = 1L,
                    ),
                )
                runCurrent()

                assertTrue(messages.isEmpty())
                job.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `FAILED 终态提示携带错误信息`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val sessionsFlow = MutableStateFlow(
                    listOf(
                        SyncSessionEntity(
                            id = 2, triggerType = TriggerType.AUTO, status = SessionStatus.RUNNING, startedAt = 1L,
                        ),
                    ),
                )
                val vm = HistoryViewModel(sessionsFlow, flowOf(true), flowOf(null), flowOf(0), {}, deleteSession = {})
                val messages = mutableListOf<String>()
                val job = launch { vm.message.collect { messages += it } }
                runCurrent()

                sessionsFlow.value = listOf(
                    SyncSessionEntity(
                        id = 2, triggerType = TriggerType.AUTO, status = SessionStatus.FAILED,
                        startedAt = 1L, errorMsg = "网络不可用",
                    ),
                )
                runCurrent()

                assertEquals(1, messages.size)
                assertEquals("同步失败：网络不可用", messages[0])
                job.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
