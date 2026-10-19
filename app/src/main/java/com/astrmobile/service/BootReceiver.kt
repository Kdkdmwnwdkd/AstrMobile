package com.astrmobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.astrmobile.AstrMobileApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启：仅在用户已完成初始化的前提下拉起前台服务。
 * 不做“静默安装”之类的高风险行为，避免用户不知情时占用资源。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val app = context.applicationContext as? AstrMobileApp ?: return
        app.appScope.launch {
            runCatching {
                val settings = app.container.settings.settings.first()
                if (settings.autoStartOnBoot && app.container.bootstrapper.isReady()) {
                    ContainerService.start(context, alsoStartContainer = true)
                }
            }
        }
    }
}
