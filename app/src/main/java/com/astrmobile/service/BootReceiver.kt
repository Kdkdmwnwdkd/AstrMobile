package com.astrmobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.astrmobile.AstrMobileApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启：仅在用户已完成初始化的前提下拉起前台服务。
 *
 * 用 goAsync() 保持广播接收器存活到启动完成——Android 12+ 的后台 FGS 启动豁免
 * 依赖接收器仍处于"活跃"状态，先返回再异步启动会被系统拒绝且静默失败。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as? AstrMobileApp ?: return
        val pendingResult = goAsync()

        app.appScope.launch {
            try {
                val settings = app.container.settings.settings.first()
                if (settings.autoStartOnBoot && app.container.bootstrapper.isReady()) {
                    ContainerService.start(context, alsoStartContainer = true)
                }
            } catch (error: Throwable) {
                app.container.controller.pushLog(
                    "[APP] 开机自启失败：${error.javaClass.simpleName} ${error.message}",
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
