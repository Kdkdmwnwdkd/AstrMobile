package com.astrmobile.core.container

import org.json.JSONObject
import java.io.File

data class DashboardCredentials(
    val username: String,
    val password: String,
)

/**
 * 读取 AstrBot 自带 WebUI 的登录凭据。
 *
 * AstrBot 4.x 首次启动会生成随机密码写进 `data/cmd_config.json` 的 dashboard 段，
 * 只在启动日志里打印一次。用户从 App 点进后台时往往只看到登录页、不知道该填什么，
 * 于是以为"什么功能都没有"。这里直接把它读出来展示。
 */
object AstrBotConfigReader {

    private const val CONFIG_RELATIVE_PATH = "root/data/cmd_config.json"

    fun readDashboard(paths: ContainerPaths): DashboardCredentials? {
        val file = File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        if (!file.exists()) return null

        return runCatching {
            val root = JSONObject(file.readText())
            val dashboard = root.optJSONObject("dashboard") ?: return@runCatching null
            val username = dashboard.optString("username").ifBlank { "astrbot" }
            val password = dashboard.optString("password")
            if (password.isBlank()) null else DashboardCredentials(username, password)
        }.getOrNull()
    }
}
