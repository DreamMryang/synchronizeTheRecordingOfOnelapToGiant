package com.dreammryang.onelaptogiant.ui.history

import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.sync.SyncOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private val record = SyncRecordEntity(
        id = 7, fitUrl = "a.fit", sessionId = 1, status = RecordStatus.UPLOAD_FAILED,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `暴露记录列表`() = runTest(dispatcher) {
        val vm = SessionDetailViewModel(flowOf(listOf(record))) { SyncOutcome.Skipped }
        assertEquals(listOf(record), vm.records.first { it.isNotEmpty() })
    }

    @Test
    fun `重试成功与被跳过时发出对应提示`() = runTest(dispatcher) {
        var retried = mutableListOf<Long>()
        val vm = SessionDetailViewModel(flowOf(emptyList())) { id ->
            retried += id
            if (retried.size == 1) SyncOutcome.Finished(9L, SessionStatus.SUCCESS) else SyncOutcome.Skipped
        }
        val messages = mutableListOf<String>()
        val job = launch { vm.message.collect { messages += it } }
        runCurrent() // 确保 collect 已建立订阅，再触发 onRetry

        vm.onRetry(7L)
        runCurrent()
        vm.onRetry(7L)
        runCurrent()

        assertEquals(listOf(7L, 7L), retried)
        assertEquals(2, messages.size)
        assertEquals("重试成功", messages[0])
        assertEquals("已有同步在进行，稍后再试", messages[1])
        job.cancel()
    }
}
