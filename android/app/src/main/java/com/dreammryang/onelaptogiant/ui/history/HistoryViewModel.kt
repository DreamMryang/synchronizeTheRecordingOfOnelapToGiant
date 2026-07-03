package com.dreammryang.onelaptogiant.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.sync.SyncProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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

    fun onSyncClick() = onSyncRequested()
}
