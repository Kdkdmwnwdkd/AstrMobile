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
 * 关键点：配置里的 `dashboard.password` 存的是 **md5 哈希**，不是明文
 * （见 astrbot/core/utils/auth_password.py 的 set_dashboard_password_hashes）。
 * 之前直接把该字段当密码显示，用户抄进去必然登录失败。
 *
 * 因此这里只做两种判断：
 * - 哈希等于默认口令 "astrbot" 的 md5 → 说明没改过，可以显示明文
 * - 两个字段都为空 → 还没设过密码，默认口令可用
 * 其它情况（用户自己改过）无法还原明文，返回 null 不显示，避免误导。
 */
object AstrBotConfigReader {

    private const val CONFIG_RELATIVE_PATH = "root/data/cmd_config.json"

    /** AstrBot 默认口令，定义在 auth_password.DEFAULT_DASHBOARD_PASSWORD。 */
    private const val DEFAULT_PASSWORD = "astrbot"

    /** md5("astrbot")。 */
    private const val DEFAULT_PASSWORD_MD5 = "77b90590a8945a7d36c963981a307dc9"

    fun readDashboard(paths: ContainerPaths): DashboardCredentials? {
        val file = File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        if (!file.exists()) return null

        return runCatching {
            val dashboard = JSONObject(file.readText()).optJSONObject("dashboard")
                ?: return@runCatching null
            val username = dashboard.optString("username").ifBlank { DEFAULT_PASSWORD }
            val storedPassword = dashboard.optString("password")
            val storedPbkdf2 = dashboard.optString("pbkdf2_password")

            val plaintext = when {
                storedPassword.equals(DEFAULT_PASSWORD_MD5, ignoreCase = true) -> DEFAULT_PASSWORD
                storedPassword.isBlank() && storedPbkdf2.isBlank() -> DEFAULT_PASSWORD
                else -> null
            }
            plaintext?.let { DashboardCredentials(username, it) }
        }.getOrNull()
    }
}
