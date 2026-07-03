package com.dreammryang.onelaptogiant.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.SyncRecordEntity
import com.dreammryang.onelaptogiant.sync.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionDetailViewModel(
    records: Flow<List<SyncRecordEntity>>,
    private val retry: suspend (recordId: Long) -> SyncOutcome,
) : ViewModel() {

    val records: StateFlow<List<SyncRecordEntity>> =
        records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message

    fun onRetry(recordId: Long) {
        viewModelScope.launch {
            val text = when (val outcome = retry(recordId)) {
                is SyncOutcome.Finished ->
                    if (outcome.status == SessionStatus.SUCCESS) "重试成功" else "重试未成功，详见历史"
                SyncOutcome.Skipped -> "已有同步在进行，稍后再试"
            }
            _message.emit(text)
        }
    }
}
