package com.astrmobile.core.container

import org.json.JSONObject
import java.io.File

/**
 * 读取 NapCat WebUI 的登录口令。
 *
 * 路径随安装方式变化（`/root/napcat/config`、`/opt/QQ/.../napcat/config` 等），
 * 所以优先读 start.sh 写下的标记文件，再退回固定候选，最后从启动日志里抓。
 *
 * 注意 App 侧不做目录递归遍历：容器里 QQ 的 resources 目录有数万文件，
 * 在手机上遍历会卡好几秒。
 */
object NapCatConfigReader {

    /** start.sh 在容器内写下的配置目录绝对路径（容器视角）。 */
    private const val HINT_FILE = "root/.astrmobile-napcat-config"

    private val CONFIG_DIR_CANDIDATES = listOf(
        "root/napcat/config",
        "opt/QQ/resources/app/app_launcher/napcat/config",
        "root/Napcat/opt/QQ/resources/app/app_launcher/napcat/config",
        "root/.config/NapCat/config",
        "root/.config/napcat/config",
        "root/napcat/napcat/config",
    )

    fun readToken(paths: ContainerPaths): String? {
        configDir(paths)?.let { dir ->
            runCatching {
                JSONObject(File(dir, "webui.json").readText()).optString("token")
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return readTokenFromLog(paths)
    }

    /** 定位 NapCat 的 config 目录；供写入连接配置时复用。 */
    fun configDir(paths: ContainerPaths): File? {
        // 1) start.sh 写的标记（最准，且是 O(1)）
        val hint = File(paths.rootfsDir, HINT_FILE)
        if (hint.exists()) {
            val raw = runCatching { hint.readText().trim() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                val dir = File(paths.rootfsDir, raw.removePrefix("/"))
                if (dir.isDirectory) return dir
            }
        }

        // 2) 固定候选
        for (relative in CONFIG_DIR_CANDIDATES) {
            val dir = File(paths.rootfsDir, relative)
            if (File(dir, "webui.json").exists() || File(dir, "onebot11.json").exists()) {
                return dir
            }
        }
        return null
    }

    /** 兜底：NapCat 启动日志里会打印一次 WebUI 口令。 */
    private fun readTokenFromLog(paths: ContainerPaths): String? {
        val log = File(paths.rootfsDir, "root/napcat.log")
        if (!log.exists()) return null

        // 只读尾部 64KB：日志可能几百 MB，readText() 会先把它整个读进内存。
        val tail = runCatching {
            val length = log.length()
            if (length <= 0) return null
            val size = minOf(length, 64L * 1024).toInt()
            val buffer = ByteArray(size)
            java.io.RandomAccessFile(log, "r").use { raf ->
                raf.seek(length - size)
                raf.readFully(buffer)
            }
            String(buffer, Charsets.UTF_8)
        }.getOrNull() ?: return null

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
