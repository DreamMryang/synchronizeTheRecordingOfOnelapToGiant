package com.dreammryang.onelaptogiant.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.sync.SyncProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val sessions: List<SyncSessionEntity> = emptyList(),
    val configured: Boolean = false,
    val progress: SyncProgress? = null,
    val processFailedCount: Int = 0,
) {
    val syncing: Boolean get() = progress != null
}

class HistoryViewModel(
    sessions: Flow<List<SyncSessionEntity>>,
    configured: Flow<Boolean>,
    progress: Flow<SyncProgress?>,
    processFailedCount: Flow<Int>,
    private val onSyncRequested: () -> Unit,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> =
        combine(sessions, configured, progress, processFailedCount) { s, c, p, f ->
            HistoryUiState(sessions = s, configured = c, progress = p, processFailedCount = f)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message

    init {
        viewModelScope.launch {
            var prev: SyncSessionEntity? = null
            sessions.collect { list ->
                val first = list.firstOrNull()
                if (prev != null && first != null && prev!!.id == first.id &&
                    prev!!.status == SessionStatus.RUNNING && first.status != SessionStatus.RUNNING
                ) {
                    _message.emit(buildDoneMessage(first))
                }
                prev = first
            }
        }
    }

    private fun buildDoneMessage(s: SyncSessionEntity): String = when (s.status) {
        SessionStatus.SUCCESS -> "同步完成：成功（同步 ${s.syncedCount} 条）"
        SessionStatus.PARTIAL -> "同步完成：部分成功（成功 ${s.syncedCount} 条，失败 ${s.failedCount} 条）"
        SessionStatus.NO_NEW -> "同步完成：无新记录"
        SessionStatus.FAILED -> "同步失败：${s.errorMsg ?: "未知错误"}"
        SessionStatus.RUNNING -> "" // 不会触发（调用处已过滤终态）
    }

    fun onSyncClick() = onSyncRequested()
}
