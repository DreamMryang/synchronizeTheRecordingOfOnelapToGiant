package com.dreammryang.onelaptogiant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.sync.SyncProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val configured: Boolean = false,
    val progress: SyncProgress? = null,
    val lastSession: SyncSessionEntity? = null,
    val processFailedCount: Int = 0,
) {
    val syncing: Boolean get() = progress != null
}

class HomeViewModel(
    configured: Flow<Boolean>,
    progress: Flow<SyncProgress?>,
    lastSession: Flow<SyncSessionEntity?>,
    processFailedCount: Flow<Int>,
    private val onSyncRequested: () -> Unit,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(configured, progress, lastSession, processFailedCount) { c, p, s, f ->
            HomeUiState(configured = c, progress = p, lastSession = s, processFailedCount = f)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onSyncClick() = onSyncRequested()
}
