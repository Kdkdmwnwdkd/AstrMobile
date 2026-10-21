package com.astrmobile.core.container

import android.content.Context
import android.system.Os
import java.io.File

/**
 * 把 APK 内的容器脚本写入 rootfs。
 *
 * 关键：每次启动都要重新注入，而不是只在安装时写一次。
 * 否则用户升级 App 后如果没有重跑「安装」，容器里仍是旧版脚本，
 * 表现为「改了脚本但行为没变」——这类问题排查成本极高。
 */
object ScriptInjector {

    // restart-napcat.sh 由 NapCat 看护逻辑调用：QQ 进程单独挂掉时只重启协议端
    private val SCRIPTS = listOf("bootstrap.sh", "start.sh", "restart-napcat.sh")

    fun inject(context: Context, paths: ContainerPaths) {
        paths.rootfsDir.mkdirs()
        SCRIPTS.forEach { name ->
            runCatching {
                val content = context.assets.open("scripts/$name").use { it.readBytes() }
                val target = File(paths.rootfsDir, "root/$name")
                target.parentFile?.mkdirs()
                target.writeBytes(content)
                Os.chmod(target.absolutePath, 0b111101101) // 0755
            }
        }
    }
}
