package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 已配置的对话模型。key 只回传脱敏后的片段，避免在界面上暴露完整密钥。 */
data class ConfiguredModel(
    val id: String,
    val apiBase: String,
    val model: String,
    val keyHint: String,
    val enabled: Boolean,
)

/** 常见厂商的 OpenAI 兼容端点，用来给表单预填，减少填错。 */
enum class ModelPreset(
    val label: String,
    val apiBase: String,
    val model: String,
) {
    // Agnes 国内站：免费额度随时可能调整，先在第一个方便试
    Agnes("Agnes（免费）", "https://api.agnes-ai.cn/v1", "agnes-2.5-flash"),
    DeepSeek("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    OpenAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Zhipu("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    Qwen("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Kimi("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    Custom("自定义", "", ""),
}

/**
 * 读写 AstrBot 的对话模型配置（`data/cmd_config.json`）。
 *
 * 这里有两种结构，取决于 AstrBot 启动过没有：
 *
 * **旧结构**（4.29 之前，或者我们刚写完还没启动）：
 * ```json
 * "provider": [{ "id": "...", "type": "openai_chat_completion",
 *                "provider_type": "chat_completion",
 *                "api_base": "...", "key": ["..."], "model": "..." }]
 * ```
 *
 * **新结构**（4.29 的 `_migra_provider_to_source_structure` 迁移后）：
 * ```json
 * "provider":         [{ "id": "...", "provider_source_id": "..._source",
 *                        "model": "...", "enable": true }]
 * "provider_sources": [{ "id": "..._source", "type": "openai_chat_completion",
 *                        "provider_type": "chat_completion",
 *                        "api_base": "...", "key": ["..."] }]
 * ```
 *
 * 迁移会把 `provider_type` / `api_base` / `key` 从 provider 里删掉。
 * 之前只认旧结构，后果是：迁移后 provider 里读不到 `provider_type`，
 * 条目被当成「不是对话模型」整条过滤 —— 界面永远显示「还没有配置模型」，
 * 用户只能一遍遍重填。写入同理，往 provider 里塞 api_base/key 会被
 * AstrBot 合并时当成覆盖值，表现为「保存成功但模型还在用旧 Key」。
 */
object ModelConfigRepository {

    private const val CONFIG_RELATIVE_PATH = "root/data/cmd_config.json"

    /** App 独占的 provider id：API 写入与文件回退都用它，避免动到用户自建的模型。 */
    const val PROVIDER_ID = "astrmobile_llm"
    private const val SOURCE_SUFFIX = "_source"

    /** 新结构里 provider 只保留这些键，其余都归 provider_sources。 */
    private val PROVIDER_ONLY_FIELDS = setOf(
        "id", "provider_source_id", "model", "modalities", "custom_extra_body", "enable",
    )

    fun read(paths: ContainerPaths): List<ConfiguredModel> {
        val root = readRoot(paths) ?: return emptyList()
        val providers = root.optJSONArray("provider") ?: return emptyList()
        val sources = root.optJSONArray("provider_sources")

        return buildList {
            for (index in 0 until providers.length()) {
                val provider = providers.optJSONObject(index) ?: continue
                val source = findById(sources, provider.optString("provider_source_id"))
                // 与 AstrBot 的合并顺序保持一致：source 打底，provider 覆盖
                val type = provider.valueOr(source, "type")
                val providerType = provider.valueOr(source, "provider_type")
                if (!isChatProvider(type, providerType)) continue
                add(
                    ConfiguredModel(
                        id = provider.optString("id"),
                        apiBase = provider.valueOr(source, "api_base"),
                        model = provider.optString("model").ifBlank { source?.optString("model").orEmpty() },
                        keyHint = maskKey(firstKey(provider).ifBlank { firstKey(source) }),
                        enabled = provider.optBoolean("enable", true),
                    ),
                )
            }
        }
    }

    fun save(
        paths: ContainerPaths,
        apiBase: String,
        apiKey: String,
        model: String,
    ): Result<Unit> = runCatching {
        require(apiBase.isNotBlank()) { "接口地址不能为空" }
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        require(model.isNotBlank()) { "模型名不能为空" }

        val file = File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        check(file.exists()) { "AstrBot 还没初始化，请先启动一次容器再回来配置" }

        val root = JSONObject(file.readText())
        val providers = root.optJSONArray("provider") ?: JSONArray()
        val sources = root.optJSONArray("provider_sources") ?: JSONArray()

        val provider = findById(providers, PROVIDER_ID) ?: JSONObject().apply {
            put("id", PROVIDER_ID)
            providers.put(this)
        }

        // 旧结构里的适配器信息要跟着搬进 source，否则迁移后 AstrBot 不知道用哪个适配器
        val adapterType = provider.optString("type").ifBlank { "openai_chat_completion" }
        val adapterProvider = provider.optString("provider").ifBlank { "openai" }

        val sourceId = provider.optString("provider_source_id").ifBlank { PROVIDER_ID + SOURCE_SUFFIX }
        val source = findById(sources, sourceId) ?: JSONObject().apply {
            put("id", sourceId)
            put("type", adapterType)
            put("provider", adapterProvider)
            put("provider_type", "chat_completion")
            put("timeout", 120)
            put("proxy", "")
            put("custom_headers", JSONObject())
            sources.put(this)
        }

        // 关键一步：把 provider 里残留的 api_base / key / type 删干净。
        // AstrBot 合并时 provider 会覆盖 provider_sources，留着旧值等于新 Key 白填。
        provider.keys().asSequence()
            .filter { it !in PROVIDER_ONLY_FIELDS }
            .toList()
            .forEach { provider.remove(it) }

        provider.put("provider_source_id", sourceId)
        provider.put("model", model.trim())
        provider.put("enable", true)
        if (!provider.has("modalities")) provider.put("modalities", JSONArray())
        if (!provider.has("custom_extra_body")) provider.put("custom_extra_body", JSONObject())

        source.put("api_base", apiBase.trim())
        source.put("key", JSONArray().put(apiKey.trim()))
        if (!source.has("type")) source.put("type", adapterType)
        if (!source.has("provider_type")) source.put("provider_type", "chat_completion")

        root.put("provider", providers)
        root.put("provider_sources", sources)
        file.writeText(root.toString(2))

        // 写完立刻按「读」的路径回验一遍。结构一旦对不上（AstrBot 又改结构了），
        // 这里当场报错，而不是让用户下次进页面才发现「又空了」。
        val written = read(paths).firstOrNull { it.id == PROVIDER_ID }
        check(written != null && written.keyHint.isNotBlank()) {
            "配置写进去了但读不回来，可能是 AstrBot 版本换了配置结构"
        }
    }

    fun remove(paths: ContainerPaths): Result<Unit> = runCatching {
        val file = File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        if (!file.exists()) return@runCatching

        val root = JSONObject(file.readText())
        val providers = root.optJSONArray("provider") ?: return@runCatching
        val sources = root.optJSONArray("provider_sources")

        val droppedSources = mutableSetOf<String>()
        val keptProviders = JSONArray()
        for (index in 0 until providers.length()) {
            val item = providers.optJSONObject(index) ?: continue
            if (item.optString("id") == PROVIDER_ID) {
                item.optString("provider_source_id").takeIf { it.isNotBlank() }?.let(droppedSources::add)
                continue
            }
            keptProviders.put(item)
        }
        root.put("provider", keptProviders)

        // 只删我们自己建的那条 source，别碰用户额外加的模型
        if (sources != null && droppedSources.isNotEmpty()) {
            val keptSources = JSONArray()
            for (index in 0 until sources.length()) {
                val item = sources.optJSONObject(index) ?: continue
                if (item.optString("id") in droppedSources) continue
                keptSources.put(item)
            }
            root.put("provider_sources", keptSources)
        }
        file.writeText(root.toString(2))
    }

    private fun readRoot(paths: ContainerPaths): JSONObject? {
        val file = File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun findById(array: JSONArray?, id: String): JSONObject? {
        if (array == null || id.isBlank()) return null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("id") == id) return item
        }
        return null
    }

    /** 先看自己，再回落到 provider_source —— 与 AstrBot 的 merge 顺序一致。 */
    private fun JSONObject.valueOr(fallback: JSONObject?, key: String): String {
        optString(key).takeIf { it.isNotBlank() }?.let { return it }
        return fallback?.optString(key).orEmpty()
    }

    /** `key` 正常是数组（支持多密钥轮询），个别老版本给的是字符串。 */
    private fun firstKey(obj: JSONObject?): String {
        if (obj == null) return ""
        val array = obj.optJSONArray("key") ?: return obj.optString("key").orEmpty()
        return array.optString(0).orEmpty()
    }

    private fun isChatProvider(type: String, providerType: String): Boolean =
        providerType == "chat_completion" || type.contains("chat_completion")

    /** `sk-abcd1234...wxyz` → `sk-a****wxyz`，只用于界面确认，不用于登录。 */
    private fun maskKey(key: String): String {
        if (key.isBlank()) return ""
        if (key.length <= 8) return "****"
        return key.take(4) + "****" + key.takeLast(4)
    }
}
