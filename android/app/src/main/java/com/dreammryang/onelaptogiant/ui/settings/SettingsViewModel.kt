package com.dreammryang.onelaptogiant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreammryang.onelaptogiant.data.auth.CredentialStore
import com.dreammryang.onelaptogiant.data.settings.INTERVAL_OFF
import com.dreammryang.onelaptogiant.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val onelapAccount: String = "",
    val onelapPassword: String = "",
    val giantUsername: String = "",
    val giantPassword: String = "",
    val recentDays: String = "30",
    val intervalHours: Int = 6,
    val wifiOnly: Boolean = false,
    val loaded: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
    private val schedule: (intervalHours: Int, wifiOnly: Boolean) -> Unit,
    private val cancelSchedule: () -> Unit,
    private val clearHistory: suspend () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val _saved = MutableSharedFlow<Unit>()
    val saved: SharedFlow<Unit> = _saved

    private val _cleared = MutableSharedFlow<Unit>()
    val cleared: SharedFlow<Unit> = _cleared

    init {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(
                onelapAccount = credentials.onelapAccount.orEmpty(),
                onelapPassword = credentials.onelapPassword.orEmpty(),
                giantUsername = credentials.giantUsername.orEmpty(),
                giantPassword = credentials.giantPassword.orEmpty(),
                recentDays = settings.recentDays.first().toString(),
                intervalHours = settings.intervalHours.first(),
                wifiOnly = settings.wifiOnly.first(),
                loaded = true,
            )
        }
    }

    fun onIntervalSelected(hours: Int): Job =
        viewModelScope.launch {
            settings.setIntervalHours(hours)
            _uiState.value = _uiState.value.copy(intervalHours = hours)
            // 首次配置齐全自动注册；后续改间隔即时生效（UPDATE 策略重排）；选「关闭」则取消周期任务
            rescheduleIfConfigured(hours, _uiState.value.wifiOnly)
            _saved.emit(Unit)
        }

    fun onWifiOnlyChanged(wifiOnly: Boolean): Job =
        viewModelScope.launch {
            settings.setWifiOnly(wifiOnly)
            _uiState.value = _uiState.value.copy(wifiOnly = wifiOnly)
            rescheduleIfConfigured(_uiState.value.intervalHours, wifiOnly)
            _saved.emit(Unit)
        }

    fun onRecentDaysConfirmed(daysText: String): Job =
        viewModelScope.launch {
            val days = daysText.toIntOrNull()?.coerceIn(1, 365) ?: 30
            settings.setRecentDays(days)
            _uiState.value = _uiState.value.copy(recentDays = days.toString())
            _saved.emit(Unit)
        }

    fun saveOnelapCredentials(account: String, password: String): Job =
        viewModelScope.launch {
            credentials.saveOnelap(account.trim(), password)
            val s = _uiState.value
            _uiState.value = s.copy(onelapAccount = account.trim(), onelapPassword = password)
            rescheduleIfConfigured(s.intervalHours, s.wifiOnly)
            _saved.emit(Unit)
        }

    fun saveGiantCredentials(username: String, password: String): Job =
        viewModelScope.launch {
            credentials.saveGiant(username.trim(), password)
            val s = _uiState.value
            _uiState.value = s.copy(giantUsername = username.trim(), giantPassword = password)
            rescheduleIfConfigured(s.intervalHours, s.wifiOnly)
            _saved.emit(Unit)
        }

    fun onClearHistory(): Job =
        viewModelScope.launch {
            clearHistory()
            _cleared.emit(Unit)
        }

    private fun rescheduleIfConfigured(intervalHours: Int, wifiOnly: Boolean) {
        if (credentials.isConfigured()) {
            if (intervalHours == INTERVAL_OFF) cancelSchedule() else schedule(intervalHours, wifiOnly)
        }
    }
}
