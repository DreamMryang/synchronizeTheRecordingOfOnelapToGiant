package com.dreammryang.onelaptogiant

import android.app.Application
import com.dreammryang.onelaptogiant.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        container = AppContainer(this)

        // Robolectric 下跳过后台初始化：真库写入会在全局 arch_disk_io 线程留下
        // InvalidationTracker 刷新任务，测试类环境切换后触发已关连接异常（测试并不需要这些副作用）
        if (android.os.Build.FINGERPRINT == "robolectric") return

        val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bgScope.launch {
            try {
                container.credentialStore // 预热 EncryptedSharedPreferences，避免首帧主线程初始化卡顿
            } catch (e: Exception) {
                Timber.w(e, "预热加密存储失败，不影响后续流程")
            }
        }
        bgScope.launch {
            try {
                val cleaned = container.database.sessionDao().failOrphanRunning(System.currentTimeMillis())
                if (cleaned > 0) Timber.i("已清理 %d 条进程中断残留的 RUNNING 会话", cleaned)
            } catch (e: Exception) {
                Timber.w(e, "清理残留 RUNNING 会话失败，将于下次启动重试")
            }
        }
    }
}
