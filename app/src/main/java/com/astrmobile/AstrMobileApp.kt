package com.astrmobile

import android.app.Application
import com.astrmobile.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AstrMobileApp : Application() {

    lateinit var container: AppContainer
        private set

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // App 被杀后可能留下孤儿 proot 进程，启动时先清理，避免端口被占。
        appScope.launch {
            runCatching { container.controller.killStaleProcess() }
            container.controller.refreshInstallState()
        }
    }
}
