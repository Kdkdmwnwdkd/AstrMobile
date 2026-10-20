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
    DeepSeek("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    OpenAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Zhipu("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    Qwen("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Kimi("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    Custom("自定义", "", ""),
}

/**
 * 读写 AstrBot 的对话模型配置（`data/cmd_config.json` 的 provider 数组）。
 *
 * 字段依据 AstrBot 4.x：
 * - `type` 决定用哪个适配器，OpenAI 兼容统一是 `openai_chat_completion`
 * - `key` 是**数组**（支持多把密钥轮询）
 * - 模型名在**顶层 `model`**，不在 `model_config` 里（openai_source 直接读 provider["model"]）
 *
 * 只维护一个固定 id 的条目，重复保存即覆盖，避免用户越点越多。
 */
object ModelConfigRepository {

    private const val CONFIG_RELATIVE_PATH = "root/data/cmd_config.json"
    private const val PROVIDER_ID = "astrmobile_llm"

    fun read(paths: ContainerPaths): List<ConfiguredModel> {
        val file = File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        if (!file.exists()) return emptyList()

        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("provider") ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    if (item.optString("provider_type") != "chat_completion") continue
                    add(
                        ConfiguredModel(
                            id = item.optString("id"),
                            apiBase = item.optString("api_base"),
                            model = item.optString("model"),
                            keyHint = maskKey(item.optJSONArray("key")?.optString(0).orEmpty()),
                            enabled = item.optBoolean("enable"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
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

        var updated = false
        for (index in 0 until providers.length()) {
            val item = providers.optJSONObject(index) ?: continue
            if (item.optString("id") != PROVIDER_ID) continue
            item.put("api_base", apiBase.trim())
            item.put("key", JSONArray().put(apiKey.trim()))
            item.put("model", model.trim())
            item.put("enable", true)
            updated = true
            break
        }

        if (!updated) {
            providers.put(
                JSONObject().apply {
                    put("id", PROVIDER_ID)
                    put("provider", "openai")
                    put("type", "openai_chat_completion")
                    put("provider_type", "chat_completion")
                    put("enable", true)
                    put("key", JSONArray().put(apiKey.trim()))
                    put("api_base", apiBase.trim())
                    put("model", model.trim())
                    put("timeout", 120)
                    put("proxy", "")
                    put("custom_headers", JSONObject())
                },
            )
        }

        root.put("provider", providers)
        // 不重排、不补字段：AstrBot 启动时会自行补全缺失的默认值。
        file.writeText(root.toString(2))
    }

    fun remove(paths: ContainerPaths): Result<Unit> = runCatching {
        val file = File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        if (!file.exists()) return@runCatching

        val root = JSONObject(file.readText())
        val providers = root.optJSONArray("provider") ?: return@runCatching
        val kept = JSONArray()
        for (index in 0 until providers.length()) {
            val item = providers.optJSONObject(index) ?: continue
            if (item.optString("id") == PROVIDER_ID) continue
            kept.put(item)
        }
        root.put("provider", kept)
        file.writeText(root.toString(2))
    }

    /** `sk-abcd1234...wxyz` → `sk-a****wxyz`，只用于界面确认，不用于登录。 */
    private fun maskKey(key: String): String {
        if (key.isBlank()) return ""
        if (key.length <= 8) return "****"
        return key.take(4) + "****" + key.takeLast(4)
    }
}
