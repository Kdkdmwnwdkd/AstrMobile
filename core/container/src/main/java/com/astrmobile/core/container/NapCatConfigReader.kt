package com.astrmobile.core.container

import org.json.JSONObject
import java.io.File

/**
 * 读取 NapCat WebUI 的登录 Token。
 *
 * NapCat 官方安装器把配置放在 QQ 资源目录里，不同版本/安装方式路径不同，
 * 所以这里按候选路径逐个尝试，最后再从启动日志里兜底抓一次。
 *
 * 没有这个 Token，用户打开 6099 只能看到登录页，无法进入扫码界面。
 */
object NapCatConfigReader {

    private val CONFIG_CANDIDATES = listOf(
        "root/Napcat/opt/QQ/resources/app/app_launcher/napcat/config/webui.json",
        "root/napcat/opt/QQ/resources/app/app_launcher/napcat/config/webui.json",
        "root/napcat/config/webui.json",
        "root/.config/NapCat/config/webui.json",
        "root/.config/napcat/config/webui.json",
    )

    fun readToken(paths: ContainerPaths): String? {
        for (relative in CONFIG_CANDIDATES) {
            val file = File(paths.rootfsDir, relative)
            if (!file.exists()) continue
            val token = runCatching {
                JSONObject(file.readText()).optString("token")
            }.getOrNull()
            if (!token.isNullOrBlank()) return token
        }
        return readTokenFromLog(paths)
    }

    /** 兜底：NapCat 启动日志里通常会打印一次 WebUI token。 */
    private fun readTokenFromLog(paths: ContainerPaths): String? {
        val log = File(paths.rootfsDir, "root/napcat.log")
        if (!log.exists()) return null

        val tail = runCatching { log.readText().takeLast(200_000) }.getOrNull() ?: return null
        val patterns = listOf(
            Regex("""[Tt]oken["'\s:=]+([A-Za-z0-9_\-]{8,})"""),
            Regex("""WebUI[^\n]*?([A-Za-z0-9_\-]{16,})"""),
        )
        patterns.forEach { regex ->
            val hit = regex.find(tail)?.groupValues?.getOrNull(1)
            if (!hit.isNullOrBlank()) return hit
        }
        return null
    }
}
