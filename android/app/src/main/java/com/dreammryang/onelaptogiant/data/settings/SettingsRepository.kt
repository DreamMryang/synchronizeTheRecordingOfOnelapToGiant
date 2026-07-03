package com.dreammryang.onelaptogiant.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

const val INTERVAL_OFF = 0
val INTERVAL_OPTIONS = listOf(INTERVAL_OFF, 1, 3, 6, 12, 24)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val RECENT_DAYS = intPreferencesKey("sync_recent_days")
        val INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
        val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
    }

    val recentDays: Flow<Int> = dataStore.data.map { it[Keys.RECENT_DAYS] ?: 30 }
    val intervalHours: Flow<Int> = dataStore.data.map { it[Keys.INTERVAL_HOURS] ?: 6 }
    val wifiOnly: Flow<Boolean> = dataStore.data.map { it[Keys.WIFI_ONLY] ?: false }

    suspend fun setRecentDays(days: Int) {
        dataStore.edit { it[Keys.RECENT_DAYS] = days }
    }

    suspend fun setIntervalHours(hours: Int) {
        dataStore.edit { it[Keys.INTERVAL_HOURS] = hours }
    }

    suspend fun setWifiOnly(wifiOnly: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY] = wifiOnly }
    }
}
