package com.dreammryang.onelaptogiant.data.auth

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CredentialStore(
    private val prefs: SharedPreferences,
    private val tokenStore: TokenStore,
) {
    val onelapAccount: String? get() = prefs.getString(KEY_ONELAP_ACCOUNT, null)
    val onelapPassword: String? get() = prefs.getString(KEY_ONELAP_PASSWORD, null)
    val giantUsername: String? get() = prefs.getString(KEY_GIANT_USERNAME, null)
    val giantPassword: String? get() = prefs.getString(KEY_GIANT_PASSWORD, null)

    private val _configured = MutableStateFlow(isConfigured())
    val configured: StateFlow<Boolean> = _configured

    fun saveOnelap(account: String, password: String) {
        prefs.edit()
            .putString(KEY_ONELAP_ACCOUNT, account)
            .putString(KEY_ONELAP_PASSWORD, password)
            .apply()
        tokenStore.clear(Platform.ONELAP)
        _configured.value = isConfigured()
    }

    fun saveGiant(username: String, password: String) {
        prefs.edit()
            .putString(KEY_GIANT_USERNAME, username)
            .putString(KEY_GIANT_PASSWORD, password)
            .apply()
        tokenStore.clear(Platform.GIANT)
        _configured.value = isConfigured()
    }

    fun isConfigured(): Boolean =
        !onelapAccount.isNullOrBlank() && !onelapPassword.isNullOrBlank() &&
            !giantUsername.isNullOrBlank() && !giantPassword.isNullOrBlank()

    private companion object {
        const val KEY_ONELAP_ACCOUNT = "onelap_account"
        const val KEY_ONELAP_PASSWORD = "onelap_password"
        const val KEY_GIANT_USERNAME = "giant_username"
        const val KEY_GIANT_PASSWORD = "giant_password"
    }
}
