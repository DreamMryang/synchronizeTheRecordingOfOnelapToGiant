package com.dreammryang.onelaptogiant.di

import android.app.Application
import android.content.SharedPreferences
import androidx.room.Room
import androidx.work.WorkManager
import com.dreammryang.onelaptogiant.data.auth.CredentialStore
import com.dreammryang.onelaptogiant.data.auth.Platform
import com.dreammryang.onelaptogiant.data.auth.TokenManager
import com.dreammryang.onelaptogiant.data.auth.TokenStore
import com.dreammryang.onelaptogiant.data.auth.createSecurePrefs
import com.dreammryang.onelaptogiant.data.db.AppDatabase
import com.dreammryang.onelaptogiant.data.network.HttpClientProvider
import com.dreammryang.onelaptogiant.data.network.giant.GiantApi
import com.dreammryang.onelaptogiant.data.network.onelap.OnelapApi
import com.dreammryang.onelaptogiant.data.settings.SettingsRepository
import com.dreammryang.onelaptogiant.data.settings.settingsDataStore
import com.dreammryang.onelaptogiant.sync.SyncEngine
import com.dreammryang.onelaptogiant.sync.SyncScheduler
import kotlinx.coroutines.flow.first
import java.io.File

class AppContainer(private val app: Application) {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(app, AppDatabase::class.java, "sync.db").build()
    }

    private val securePrefs: SharedPreferences by lazy { createSecurePrefs(app) }

    val tokenStore: TokenStore by lazy { TokenStore(securePrefs) }

    val credentialStore: CredentialStore by lazy { CredentialStore(securePrefs, tokenStore) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(app.settingsDataStore) }

    val onelapApi: OnelapApi by lazy { OnelapApi(HttpClientProvider.client, HttpClientProvider.json) }

    val giantApi: GiantApi by lazy { GiantApi(HttpClientProvider.client, HttpClientProvider.json) }

    val onelapTokens: TokenManager by lazy {
        TokenManager(Platform.ONELAP, tokenStore) {
            val account = credentialStore.onelapAccount
            val password = credentialStore.onelapPassword
            require(!account.isNullOrBlank() && !password.isNullOrBlank()) { "顽鹿账号未配置" }
            onelapApi.login(account, password)
        }
    }

    val giantTokens: TokenManager by lazy {
        TokenManager(Platform.GIANT, tokenStore) {
            val account = credentialStore.giantUsername
            val password = credentialStore.giantPassword
            require(!account.isNullOrBlank() && !password.isNullOrBlank()) { "捷安特账号未配置" }
            giantApi.login(account, password)
        }
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            onelap = onelapApi,
            giant = giantApi,
            onelapTokens = onelapTokens,
            giantTokens = giantTokens,
            sessionDao = database.sessionDao(),
            recordDao = database.recordDao(),
            recentDaysProvider = { settingsRepository.recentDays.first() },
            fitDir = File(app.filesDir, "fit"),
        )
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(WorkManager.getInstance(app)) }
}
