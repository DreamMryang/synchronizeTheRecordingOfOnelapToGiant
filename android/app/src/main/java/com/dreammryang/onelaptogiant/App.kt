package com.dreammryang.onelaptogiant

import android.app.Application
import com.dreammryang.onelaptogiant.di.AppContainer
import timber.log.Timber

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        container = AppContainer(this)
    }
}
