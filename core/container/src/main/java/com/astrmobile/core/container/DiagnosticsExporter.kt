package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把排障需要的东西打成一个 zip：环境状态、日志尾部、脱敏后的配置。
 *
 * 为什么要有这个：机器人装在手机上长期跑，出问题时（装不上、起不来、QQ 扫码失败）
 * 用户能提供的信息通常只有一句"它不动了"。日志页能看，但让人手动挑文件很麻烦，
 * 而且配置里全是 API Key 和口令，直接发出来等于泄露。
 *
 * 两条硬规则：
 * - **只读，不改任何东西**：不会动容器、不会触发重启，导出失败也不影响运行。
 * - **配置必须脱敏**：凡是 password / token / secret / api key 这类键，值一律替换掉。
 *   这条有单测盯着——导出后的 zip 里不允许出现原始密钥字符串。
 */
object DiagnosticsExporter {

    /** 单个日志最多带尾部这么多字节。安装日志动辄几十 MB，全带上没意义。 */
    private const val LOG_TAIL_BYTES = 256 * 1024

    /** 配置文件超过这个大小就不带了（正常配置只有几 KB）。 */
    private const val CONFIG_MAX_BYTES = 512 * 1024

    /**
     * 需要脱敏的键名。
     *
     * 注意 AstrBot 的 API Key 存在名为 `key` 的字段里（值是字符串数组），
     * 所以不能只匹配 `api_key` —— 只写 `key` 又会误伤 `keywords` 之类，
     * 因此用"包含 + 精确"两条规则组合判断。
     */
    private val SECRET_KEY = Regex(
        "(?i)(pass|pwd|token|secret|apikey|api[-_]key|cookie|authorization|session|credential)",
    )

    /** 兜底的逐行脱敏：只处理字符串值，数组形式单独走 [SECRET_ARRAY]。 */
    private val STRING_FIELD = Regex("""("[^"]*"\s*:\s*")([^"]*)(")""")

    private val STRING_FIELD_NAME = Regex("^\\s*\"([^\"]*)\"")

    private val SECRET_ARRAY = Regex("""("[^"]*"\s*:\s*\[)([^\]]*)(\])""")

    private fun isSecretKey(key: String): Boolean =
        SECRET_KEY.containsMatchIn(key) ||
            key.equals("key", ignoreCase = true) ||
            key.endsWith("_key", ignoreCase = true) ||
            key.endsWith("Key")

    /**
     * 生成诊断包。
     *
     * @param meta 由调用方补充的环境信息（应用版本、Android 版本、机型、容器状态等）。
     */
    fun export(
        paths: ContainerPaths,
        target: File,
        meta: Map<String, String>,
    ): Result<File> = runCatching {
        target.parentFile?.mkdirs()

        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.writeText("meta.txt", buildMeta(paths, meta))
            zip.writeLog("logs/container.log", paths.containerLog)
            zip.writeLog("logs/install.log", paths.installLog)

            val astrbotConfig = File(paths.rootfsDir, ASTRBOT_CONFIG_RELATIVE)
            if (astrbotConfig.isFile) {
                zip.writeText("config/cmd_config.json", readRedacted(astrbotConfig))
            }

            NapCatConfigReader.configDir(paths)?.let { dir ->
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".json") }
                    ?.sortedBy { it.name }
                    ?.forEach { file ->
                        if (file.length() <= CONFIG_MAX_BYTES) {
                            zip.writeText("config/napcat/${file.name}", readRedacted(file))
                        }
                    }
            }
        }

        target
    }

    /** 配置路径与 AstrBotConfigReader 保持一致。 */
    private const val ASTRBOT_CONFIG_RELATIVE = "root/data/cmd_config.json"

    private fun readRedacted(file: File): String {
        val raw = runCatching { file.readText() }.getOrElse { return "（读取失败：${it.javaClass.simpleName}）" }
        return redactJson(raw)
    }

    private fun buildMeta(paths: ContainerPaths, meta: Map<String, String>): String = buildString {
        meta.forEach { (key, value) -> appendLine("$key: $value") }
        appendLine()
        appendLine("--- 容器状态 ---")
        appendLine("rootfs 已安装: ${paths.isRootfsInstalled()}")
        appendLine("bootstrap 完成: ${paths.isBootstrapFinished()}")
        appendLine("工具链就绪: ${paths.isToolchainReady()}")
        appendLine("端口: AstrBot=${paths.astrbotPort} NapCat=${paths.napcatPort} OneBot=${paths.onebotPort}")
        appendLine()
        appendLine("--- 文件 ---")
        listOf(
            "container.log" to paths.containerLog,
            "install.log" to paths.installLog,
            "install.state" to paths.installStateFile,
            "pid" to paths.pidFile,
        ).forEach { (label, file) ->
            val size = if (file.exists()) "${file.length()} B" else "不存在"
            appendLine("$label: $size")
        }
    }

    private fun ZipOutputStream.writeText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writeLog(name: String, source: File) {
        if (!source.isFile || source.length() == 0L) {
            writeText(name, "（没有日志：${source.name}）")
            return
        }

        val tail = readTail(source, LOG_TAIL_BYTES)
        putNextEntry(ZipEntry(name))
        write(tail)
        closeEntry()
    }

    /** 只读尾部，避免把几百 MB 的日志整个读进内存。 */
    private fun readTail(file: File, maxBytes: Int): ByteArray {
        val length = file.length()
        val size = minOf(length, maxBytes.toLong()).toInt()
        val buffer = ByteArray(size)
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - size)
                raf.readFully(buffer)
            }
        }
        if (length <= maxBytes) return buffer

        val header = "…（已截断，仅保留最后 ${size / 1024} KB，原文件 ${length / 1024} KB）\n"
        return header.toByteArray(Charsets.UTF_8) + buffer
    }

    /**
     * 脱敏 JSON。合法 JSON 走结构化处理（能准确识别嵌套层级里的密钥字段），
     * 非法 JSON 退化成逐行正则替换——宁可少带信息，也不能把明文密钥发出去。
     */
    fun redactJson(raw: String): String {
        val trimmed = raw.trimStart()

        val parsed: Any? = when {
            trimmed.startsWith("{") -> runCatching { JSONObject(raw) }.getOrNull()
            trimmed.startsWith("[") -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        }

        return when (parsed) {
            is JSONObject -> {
                scrubObject(parsed)
                parsed.toString(2)
            }

            is JSONArray -> {
                scrubArray(parsed)
                parsed.toString(2)
            }

            else -> redactText(raw)
        }
    }

    /** 非法 JSON 的兜底：只按 `"name": "value"` 和 `"name": [ ... ]` 两种形态替换。 */
    private fun redactText(raw: String): String {
        val afterStrings = STRING_FIELD.replace(raw) { match ->
            val name = STRING_FIELD_NAME.find(match.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
            if (isSecretKey(name)) "${match.groupValues[1]}***${match.groupValues[3]}" else match.value
        }
        return SECRET_ARRAY.replace(afterStrings) { match ->
            val name = STRING_FIELD_NAME.find(match.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
            if (isSecretKey(name)) "${match.groupValues[1]}\"***\"${match.groupValues[3]}" else match.value
        }
    }

    private fun scrubObject(obj: JSONObject) {
        for (key in obj.keys().asSequence().toList()) {
            val value = obj.opt(key)
            when {
                // 键名判断必须在类型判断之前：AstrBot 的 API Key 存成
                // `"key": ["sk-..."]`，先看类型会走进数组分支，元素就漏掉了。
                isSecretKey(key) -> obj.put(key, maskValue(value))
                value is JSONObject -> scrubObject(value)
                value is JSONArray -> scrubArray(value)
            }
        }
    }

    private fun scrubArray(array: JSONArray) {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> scrubObject(value)
                is JSONArray -> scrubArray(value)
            }
        }
    }

    /** 密钥字段可能是字符串，也可能是字符串数组（AstrBot 的 `key` 就是数组）。 */
    private fun maskValue(value: Any?): Any = when (value) {
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) {
                put(maskScalar(value.opt(index)))
            }
        }

        else -> maskScalar(value)
    }

    private fun maskScalar(value: Any?): String {
        val text = value as? String ?: return if (value == null) "" else "***"
        // 空值保持空，避免把"没配置"改写成"配置了一个秘密"
        if (text.isBlank()) return ""
        return "***(len=${text.length})"
    }
}
