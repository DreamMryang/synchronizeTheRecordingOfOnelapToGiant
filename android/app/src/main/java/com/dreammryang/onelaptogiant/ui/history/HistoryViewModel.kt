package com.dreammryang.onelaptogiant.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(sessions: Flow<List<SyncSessionEntity>>) : ViewModel() {
    val sessions: StateFlow<List<SyncSessionEntity>> =
        sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
