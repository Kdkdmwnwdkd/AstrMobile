package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 一条人格设定。 */
data class PersonaItem(
    val id: String,
    val systemPrompt: String,
)

/**
 * AstrBot 后台 API 的最小客户端。
 *
 * 人格、插件参数这类数据存在 AstrBot 的数据库里，改文件没用，只能过它的接口。
 * 它用 HttpOnly Cookie 存 JWT，所以登录后要把 Set-Cookie 原样带上。
 *
 * 注意：接口前缀是 `/api/v1`，旧的 `/api/...` 是兼容层，不要用。
 */
class AstrBotApi(private val port: Int = 6185) {

    private val base = "http://127.0.0.1:$port/api/v1"

    /**
     * 试聊接口不在 /api/v1 下。
     *
     * `POST /api/chat/send` 是 dashboard 的老接口，AstrBot 标了
     * `include_in_schema=False`，但一直在用——WebUI 的聊天就是它。
     * v1 里的对应实现是 `/chat/threads/{id}/messages`，但要先建 thread、
     * 还得带 parent_message_id，对"新建一个会话直接说话"来说绕远了。
     */
    private val legacyBase = "http://127.0.0.1:$port/api"

    /** 登录并返回可直接复用的 Cookie 串。 */
    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()

        val response = request("POST", "/auth/login", body, cookie = null)
            ?: return@withContext Result.failure(
                IllegalStateException("AstrBot 没有响应，可能还在启动中"),
            )

        if (response.cookie.isBlank()) {
            val raw = response.message.ifBlank { "登录失败" }
            // AstrBot 4.29 会把首次生成的随机密码直接吐成英文原文，用户看不懂也不知道该干什么
            val friendly = if (
                raw.contains("Login failed", ignoreCase = true) ||
                raw.contains("password", ignoreCase = true)
            ) {
                "AstrBot 后台密码不对。\n" +
                    "首次使用：它启动时生成过随机密码，重启一次容器就会恢复成 astrbot（0.14.2 起）。\n" +
                    "自己改过密码：在页面顶部的密码框里填你自己的那个。"
            } else {
                raw
            }
            return@withContext Result.failure(IllegalStateException(friendly))
        }
        Result.success(response.cookie)
    }

    suspend fun listPersonas(cookie: String): Result<List<PersonaItem>> = withContext(Dispatchers.IO) {
        val response = request("GET", "/personas", null, cookie)
            ?: return@withContext Result.failure(IllegalStateException("读取人格失败：无响应"))

        // 必须判 ok：cookie 失效时返回的是 error，data 为空，
        // 不判就会显示成「还没有人格」，把认证问题伪装成空数据。
        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "读取人格失败" }),
            )
        }

        val data = response.data ?: return@withContext Result.success(emptyList())
        // 不同版本这里可能是数组，也可能包一层 {personas: [...]}
        val array = when {
            data is JSONArray -> data
            data is JSONObject -> data.optJSONArray("personas") ?: JSONArray()
            else -> JSONArray()
        }

        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("persona_id").ifBlank { item.optString("id") }
                if (id.isBlank()) continue
                add(
                    PersonaItem(
                        id = id,
                        systemPrompt = item.optString("system_prompt"),
                    ),
                )
            }
        }
        Result.success(items)
    }

    /** 已存在就更新，不存在就新建。 */
    suspend fun savePersona(
        cookie: String,
        id: String,
        systemPrompt: String,
        exists: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("persona_id", id)
            .put("system_prompt", systemPrompt)
            .toString()

        val (method, path) = if (exists) {
            "PUT" to "/personas/by-id"
        } else {
            "POST" to "/personas"
        }

        val response = request(method, path, body, cookie)
            ?: return@withContext Result.failure(IllegalStateException("保存失败：无响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "保存失败（HTTP ${response.code}）" }),
            )
        }
        Result.success(Unit)
    }

    suspend fun deletePersona(cookie: String, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val response = request(
            "DELETE",
            "/personas/by-id?persona_id=${java.net.URLEncoder.encode(id, "UTF-8")}",
            null,
            cookie,
        ) ?: return@withContext Result.failure(IllegalStateException("删除失败：无响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "删除失败（HTTP ${response.code}）" }),
            )
        }
        Result.success(Unit)
    }

    // ---------------------------------------------------------------- 对话记录

    /**
     * 读最近的对话。
     *
     * 返回 `{conversations: [{title, umo_info, history, updated_at}], pagination}`
     * history 是 `[{role, content}]`，content 可能是字符串，也可能是多模态数组。
     */
    suspend fun listConversations(
        cookie: String,
        pageSize: Int = 20,
        page: Int = 1,
    ): Result<List<ConversationSummary>> = withContext(Dispatchers.IO) {
        val response = request(
            "GET",
            "/conversations?page=$page&page_size=$pageSize&sort_by=updated_at&sort_order=desc&include_history=true",
            null,
            cookie,
        ) ?: return@withContext Result.failure(IllegalStateException("读取对话失败：无响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "读取对话失败" }),
            )
        }

        val root = response.body
            ?: return@withContext Result.failure(IllegalStateException("对话返回格式异常"))
        val data = root.optJSONObject("data")
            ?: return@withContext Result.success(emptyList())
        val array = data.optJSONArray("conversations")
            ?: return@withContext Result.success(emptyList())

        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val umoInfo = item.optJSONObject("umo_info")
                val history = parseHistory(item.optJSONArray("history"))
                if (history.isEmpty()) continue

                add(
                    ConversationSummary(
                        cid = item.optString("cid"),
                        userId = item.optString("user_id"),
                        title = item.optString("title").ifBlank {
                            umoInfo?.optString("alias").orEmpty()
                        }.ifBlank {
                            umoInfo?.optString("session_id").orEmpty()
                        }.ifBlank { "未命名会话" },
                        umo = item.optString("umo").ifBlank {
                            umoInfo?.optString("session_id").orEmpty()
                        },
                        messageType = umoInfo?.optString("message_type").orEmpty(),
                        updatedAt = item.optLong("updated_at"),
                        history = history,
                    ),
                )
            }
        }
        Result.success(items)
    }

    /** history 里每项是 {role, content}，content 可能是数组，摊平成文本。 */
    private fun parseHistory(array: JSONArray?): List<ConversationMessage> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role").ifBlank { "unknown" }
                val rawContent = item.opt("content")
                val text = when (rawContent) {
                    is String -> rawContent
                    is JSONArray -> buildString {
                        for (i in 0 until rawContent.length()) {
                            val part = rawContent.opt(i)
                            if (part is JSONObject) {
                                val piece = part.optString("text")
                                if (piece.isNotBlank()) {
                                    if (isNotEmpty()) append(' ')
                                    append(piece)
                                }
                            } else if (part is String) {
                                if (isNotEmpty()) append(' ')
                                append(part)
                            }
                        }
                    }
                    null -> ""
                    else -> rawContent.toString()
                }.trim()

                if (text.isNotEmpty()) add(ConversationMessage(role = role, content = text))
            }
        }
    }

    // ---------------------------------------------------------------- 插件配置

    /**
     * 读某个插件的配置项与当前值。
     *
     * 返回结构（config_service.get_plugin_config）：
     * `{metadata: {<plugin_id>: {items: schema}}, config: {...}}`
     * schema 每项形如 `{type, description, hint, default, options, slider}`。
     */
    suspend fun getPluginConfig(
        cookie: String,
        pluginId: String,
    ): Result<PluginConfigSnapshot> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(pluginId, "UTF-8")
        val response = request("GET", "/plugins/config?plugin_id=$encoded", null, cookie)
            ?: return@withContext Result.failure(IllegalStateException("读取配置失败：无响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "读取配置失败" }),
            )
        }

        val root = response.body ?: return@withContext Result.failure(
            IllegalStateException("配置返回为空"),
        )
        val schema = root.optJSONObject("metadata")
            ?.optJSONObject(pluginId)
            ?.optJSONObject("items")
        val current = root.optJSONObject("config")

        if (schema == null) {
            // 大多数社区插件根本没有 _conf_schema.json：它们装上就能用，
            // 或者靠改配置文件调参数。这不是错误，别用红字吓人。
            return@withContext Result.success(
                PluginConfigSnapshot(pluginId = pluginId, items = emptyList(), hasSchema = false),
            )
        }

        Result.success(
            PluginConfigSnapshot(
                pluginId = pluginId,
                items = parseSchema(schema, current),
            ),
        )
    }

    /** 保存插件配置。服务端要求 `{plugin_id, config}`。 */
    suspend fun savePluginConfig(
        cookie: String,
        pluginId: String,
        config: Map<String, Any?>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        fun toJson(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            else -> value
        }

        val configJson = JSONObject()
        config.forEach { (key, value) ->
            val jsonValue = toJson(value)
            // 嵌套分组用 `group.key` 表达，这里拆回对象
            if (key.contains('.')) {
                val (group, child) = key.split('.', limit = 2)
                val groupObj = configJson.optJSONObject(group) ?: JSONObject()
                groupObj.put(child, jsonValue)
                configJson.put(group, groupObj)
            } else {
                configJson.put(key, jsonValue)
            }
        }

        val body = JSONObject()
            .put("plugin_id", pluginId)
            .put("config", configJson)
            .toString()

        val response = request("PUT", "/plugins/config", body, cookie)
            ?: return@withContext Result.failure(IllegalStateException("保存失败：无响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "保存失败" }),
            )
        }
        Result.success(Unit)
    }

    /** 把 schema 与当前值摊平成可渲染的列表；嵌套对象展开成 `group.key`。 */
    private fun parseSchema(schema: JSONObject, current: JSONObject?): List<PluginConfigItem> {
        val items = mutableListOf<PluginConfigItem>()

        fun readValue(key: String, source: JSONObject?): Any? {
            if (source == null) return null
            if (!key.contains('.')) {
                return if (source.has(key)) source.opt(key) else null
            }
            val (group, child) = key.split('.', limit = 2)
            return source.optJSONObject(group)?.let { readValue(child, it) }
        }

        fun walk(prefix: String, node: JSONObject) {
            node.keys().forEach { rawKey ->
                val spec = node.optJSONObject(rawKey) ?: return@forEach
                val key = if (prefix.isEmpty()) rawKey else "$prefix.$rawKey"
                val type = spec.optString("type").ifBlank { "string" }

                if (type == "object") {
                    spec.optJSONObject("items")?.let { walk(key, it) }
                    return@forEach
                }

                val options = mutableListOf<String>()
                spec.optJSONArray("options")?.let { array ->
                    for (i in 0 until array.length()) {
                        val option = array.opt(i)
                        // options 可能是字符串，也可能是 {label, value}
                        if (option is JSONObject) {
                            options += option.optString("value").ifBlank {
                                option.optString("label")
                            }
                        } else {
                            options += option.toString()
                        }
                    }
                }

                val slider = spec.optJSONObject("slider")
                items += PluginConfigItem(
                    key = key,
                    label = spec.optString("description").ifBlank { rawKey },
                    hint = spec.optString("hint"),
                    type = type,
                    value = readValue(key, current) ?: spec.opt("default"),
                    options = options,
                    min = slider?.optInt("min"),
                    max = slider?.optInt("max"),
                    step = slider?.optInt("step"),
                )
            }
        }

        walk("", schema)
        return items
    }

    // ---------------------------------------------------------------- 服务提供商
    //
    // 为什么不再直接改 cmd_config.json：
    // 1. AstrBot 启动时把配置读进内存，运行中任何一次 save_config 都会用内存副本
    //    全量覆盖文件——App 刚写进去的 provider 就这么没了；
    // 2. 4.29 起 provider 被拆成 provider + provider_sources 两层，结构随版本变，
    //    直接拼 JSON 迟早对不上；
    // 3. 直接写文件必须重启才生效，用户点完"保存"看到的是旧状态。
    // 走 /api/v1/providers 由 AstrBot 自己落盘并热加载，三个问题一起消失。

    /** AstrBot 里真实存在的服务提供商（已经过 provider_sources 合并）。 */
    data class RemoteProvider(
        val id: String,
        val apiBase: String,
        val model: String,
        val keyHint: String,
        val enabled: Boolean,
        val providerType: String,
    )

    suspend fun listProviders(cookie: String): Result<List<RemoteProvider>> = withContext(Dispatchers.IO) {
        val response = request("GET", "/providers", null, cookie)
            ?: return@withContext Result.failure(IllegalStateException("读取模型列表失败：AstrBot 没有响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "读取模型列表失败（HTTP ${response.code}）" }),
            )
        }

        val array = (response.data as? JSONObject)?.optJSONArray("providers") ?: JSONArray()
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    RemoteProvider(
                        id = id,
                        apiBase = item.optString("api_base"),
                        model = item.optString("model"),
                        keyHint = maskKey(firstKey(item)),
                        enabled = item.optBoolean("enable", true),
                        providerType = item.optString("provider_type"),
                    ),
                )
            }
        }
        Result.success(items)
    }

    /**
     * 写入（或替换）一个 OpenAI 兼容的对话模型。
     *
     * 先删同 id 的旧配置再新建：旧配置可能是 4.29 之前写进 `provider` 的扁平结构，
     * 也可能是拆分后的 `provider + provider_sources`。重建一次能保证落到 AstrBot
     * 当前版本认可的结构上，不会留下混合结构被下一次迁移弄丢。
     * 删建之间只有毫秒级空窗，且 id 是本 App 独占的。
     */
    suspend fun saveChatProvider(
        cookie: String,
        id: String,
        apiBase: String,
        apiKey: String,
        model: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 旧配置可能不存在，删除失败不算错误
        request("DELETE", "/providers/by-id?provider_id=${encode(id)}", null, cookie)

        val config = JSONObject()
            .put("id", id)
            .put("type", "openai_chat_completion")
            .put("provider", "openai")
            .put("provider_type", "chat_completion")
            .put("api_base", apiBase.trim())
            .put("key", JSONArray().put(apiKey.trim()))
            .put("model", model.trim())
            .put("enable", true)

        val body = JSONObject().put("config", config).toString()
        val response = request("POST", "/providers", body, cookie)
            ?: return@withContext Result.failure(
                IllegalStateException("保存失败：AstrBot 没有响应，可能还在启动中"),
            )

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "保存失败（HTTP ${response.code}）" }),
            )
        }
        Result.success(Unit)
    }

    /** 真正发一次请求验证 Key 可用，返回可读结论。 */
    suspend fun testProvider(cookie: String, id: String): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("provider_id", id).toString()
        val response = request("POST", "/providers/test", body, cookie)
            ?: return@withContext Result.failure(IllegalStateException("测试失败：AstrBot 没有响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "测试失败（HTTP ${response.code}）" }),
            )
        }

        val data = response.data as? JSONObject
            ?: return@withContext Result.failure(IllegalStateException("测试返回格式异常"))
        val status = data.optString("status")
        val error = data.optString("error").ifBlank {
            data.optString("message")
        }
        val modelName = data.optString("model")

        if (status == "available") {
            Result.success("连接成功，${modelName.ifBlank { "模型" }}可用")
        } else {
            Result.failure(
                IllegalStateException(error.ifBlank { "模型不可用（$status）" }),
            )
        }
    }

    /**
     * 加载失败的插件：目录名 → 错误信息。
     *
     * 插件目录存在不等于加载成功——缺依赖、版本不兼容都会让它出现在这里。
     * App 的"已安装"列表只看了目录，所以必须再问一次运行时状态。
     */
    suspend fun listFailedPlugins(cookie: String): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val response = request("GET", "/plugins/failed", null, cookie)
                ?: return@withContext Result.failure(IllegalStateException("读取插件状态失败：AstrBot 没有响应"))
            if (!response.ok) {
                return@withContext Result.failure(
                    IllegalStateException(response.message.ifBlank { "读取插件状态失败（HTTP ${response.code}）" }),
                )
            }

            val failed = linkedMapOf<String, String>()
            fun readMessage(value: Any?): String = when (value) {
                is JSONObject -> value.optString("error")
                    .ifBlank { value.optString("message") }
                    .ifBlank { value.optString("exception") }
                    .ifBlank { value.optString("traceback").take(200) }
                is String -> value
                null -> ""
                else -> value.toString()
            }

            when (val data = response.data) {
                is JSONObject -> data.keys().forEach { key ->
                    failed[key] = readMessage(data.opt(key))
                }
                is JSONArray -> for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                        .ifBlank { item.optString("dir_name") }
                        .ifBlank { item.optString("plugin_id") }
                    if (name.isNotBlank()) failed[name] = readMessage(item)
                }
                else -> Unit
            }
            Result.success(failed)
        }

    /**
     * 读整份系统配置（只读）。
     *
     * 用来回答"现在真正生效的默认人格是哪一个"。只读取、不回写：
     * 这份配置里的 Key 是脱敏的，整份 PUT 回去会把真实 Key 覆盖掉。
     */
    suspend fun getSystemConfig(cookie: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val response = request("GET", "/system-config", null, cookie)
            ?: return@withContext Result.failure(IllegalStateException("读取系统配置失败：AstrBot 没有响应"))
        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "读取系统配置失败（HTTP ${response.code}）" }),
            )
        }
        Result.success(response.body ?: JSONObject())
    }

    /**
     * 启用/禁用一个模型。
     *
     * AstrBot 选模型时，如果没设"默认模型"，会退回到**第一个启用的** chat provider。
     * 所以"切换当前模型"可以安全地实现为：启用目标、禁用其余——不用去改系统配置
     * （那条路要全量 PUT 配置，而 GET 回来的 Key 是脱敏的，回写会把 Key 覆盖掉）。
     */
    suspend fun setProviderEnabled(cookie: String, id: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("provider_id", id)
                .put("enabled", enabled)
                .toString()
            val response = request("PATCH", "/providers/enabled", body, cookie)
                ?: return@withContext Result.failure(IllegalStateException("切换失败：AstrBot 没有响应"))

            if (!response.ok) {
                return@withContext Result.failure(
                    IllegalStateException(response.message.ifBlank { "切换失败（HTTP ${response.code}）" }),
                )
            }
            Result.success(Unit)
        }

    suspend fun deleteProvider(cookie: String, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val response = request("DELETE", "/providers/by-id?provider_id=${encode(id)}", null, cookie)
            ?: return@withContext Result.failure(IllegalStateException("删除失败：AstrBot 没有响应"))

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "删除失败（HTTP ${response.code}）" }),
            )
        }
        Result.success(Unit)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun firstKey(obj: JSONObject): String {
        val array = obj.optJSONArray("key") ?: return obj.optString("key")
        return array.optString(0)
    }

    private fun maskKey(key: String): String {
        if (key.isBlank()) return ""
        if (key.length <= 8) return "****"
        return key.take(4) + "****" + key.takeLast(4)
    }

    // ---------------------------------------------------------------- 定时任务

    /** 任务列表。接口返回可能是数组，也可能包一层 `{jobs: []}`，两种都认。 */
    suspend fun listCronJobs(cookie: String): Result<List<CronJob>> = withContext(Dispatchers.IO) {
        val response = request("GET", "/cron/jobs", null, cookie)
            ?: return@withContext Result.failure(
                IllegalStateException("读取定时任务失败：后台没有响应，容器在运行吗？"),
            )

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "读取定时任务失败" }),
            )
        }

        val items = when (val data = response.data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("jobs") ?: JSONArray()
            else -> JSONArray()
        }

        val jobs = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(parseCronJob(item))
            }
        }
        Result.success(jobs)
    }

    suspend fun createCronJob(
        cookie: String,
        name: String,
        cronExpression: String,
        session: String,
        note: String,
        enabled: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("name", name)
            .put("cron_expression", cronExpression)
            .put("session", session)
            .put("note", note)
            .put("enabled", enabled)
            .toString()

        write("POST", "/cron/jobs", cookie, body, "创建定时任务")
    }

    suspend fun setCronEnabled(cookie: String, id: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            write(
                "PATCH",
                "/cron/jobs/$id",
                cookie,
                JSONObject().put("enabled", enabled).toString(),
                if (enabled) "启用定时任务" else "停用定时任务",
            )
        }

    suspend fun deleteCronJob(cookie: String, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            write("DELETE", "/cron/jobs/$id", cookie, null, "删除定时任务")
        }

    suspend fun runCronNow(cookie: String, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            write("POST", "/cron/jobs/$id/run", cookie, null, "执行定时任务")
        }

    /** 写操作统一出口：失败时把服务端的话原样带出来，比"操作失败"有用得多。 */
    private fun write(
        method: String,
        path: String,
        cookie: String,
        body: String?,
        action: String,
    ): Result<Unit> {
        val response = request(method, path, body, cookie)
            ?: return Result.failure(IllegalStateException("$action 失败：后台没有响应"))

        if (!response.ok) {
            return Result.failure(
                IllegalStateException(response.message.ifBlank { "$action 失败" }),
            )
        }
        return Result.success(Unit)
    }

    private fun parseCronJob(item: JSONObject): CronJob {
        val payload = item.optJSONObject("payload")
        return CronJob(
            id = item.optString("id"),
            name = item.optString("name").ifBlank { "未命名任务" },
            cronExpression = item.optString("cron_expression"),
            note = item.optString("note").ifBlank { payload?.optString("note").orEmpty() },
            session = item.optString("session").ifBlank { payload?.optString("session").orEmpty() },
            enabled = item.optBoolean("enabled", true),
            runOnce = item.optBoolean("run_once", false),
            nextRunTime = item.optString("next_run_time").takeIf { it.isNotBlank() },
        )
    }

/**
     * 用量统计：今日 token / 调用次数 / 按模型分布。
     *
     * 注意它只给 token，不给金额——钱是 App 侧按牌价表估的（见 UsagePricing）。
     * `days` 只接受 1/3/7，服务端会自己纠正其它值。
     */
    suspend fun providerTokenStats(cookie: String, days: Int = 1): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            val response = request("GET", "/stats/provider-tokens?days=$days", null, cookie)
                ?: return@withContext Result.failure(
                    IllegalStateException("读取用量失败：后台没有响应，容器在运行吗？"),
                )

            if (!response.ok) {
                return@withContext Result.failure(
                    IllegalStateException(response.message.ifBlank { "读取用量失败" }),
                )
            }

            val data = response.data as? JSONObject
                ?: return@withContext Result.failure(IllegalStateException("用量返回格式异常"))

            Result.success(data)
        }
    // ---------------------------------------------------------------- 试聊

    /**
     * 新建一个 webchat 会话，返回 session_id。
     *
     * 平台固定 webchat：它和 QQ 那边的会话完全隔离，试聊不会打扰群里，
     * 也不会把测试内容写进真实对话记录。
     */
    suspend fun newChatSession(cookie: String): Result<String> = withContext(Dispatchers.IO) {
        val response = requestUrl(
            "GET",
            "$legacyBase/chat/new_session?platform_id=webchat",
            null,
            cookie,
        ) ?: return@withContext Result.failure(
            IllegalStateException("新建会话失败：后台没有响应，容器在运行吗？"),
        )

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "新建会话失败" }),
            )
        }

        val sessionId = (response.data as? JSONObject)?.optString("session_id").orEmpty()
        if (sessionId.isBlank()) {
            Result.failure(IllegalStateException("新建会话失败：后台没有返回 session_id"))
        } else {
            Result.success(sessionId)
        }
    }

    /**
     * 发一条消息，边收边回调。
     *
     * 用 SSE 流式读而不是等整段返回：试聊最长可能要几十秒（推理模型更久），
     * 等完再显示和卡死没区别。服务端靠 `: heartbeat` 保活，所以读超时设 30 秒
     * 而不是默认的 15 秒——模型思考时可能十几秒没有任何输出。
     *
     * [onUpdate] 会在 IO 线程回调，调用方自己切回主线程。
     */
    suspend fun sendChatMessage(
        cookie: String,
        sessionId: String,
        message: String,
        onUpdate: (ChatStreamState) -> Unit,
    ): Result<ChatStreamState> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val body = JSONObject()
                .put("message", message)
                .put("session_id", sessionId)
                .put("enable_streaming", true)
                .toString()

            connection = (URL("$legacyBase/chat/send").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cookie", cookie)
                }
            connection.outputStream.use { it.write(body.toByteArray()) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                    .take(200)
                return@withContext Result.failure(
                    IllegalStateException(detail.ifBlank { "发送失败（HTTP $code）" }),
                )
            }

            var state = ChatStreamState()
            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    state = ChatStream.consume(state, line)
                    onUpdate(state)
                    // 回复已经结束了就别等连接自己关，用户不该为几行元数据多等
                    if (state.done) break
                }
            }

            val failure = state.error
            if (failure != null) Result.failure(IllegalStateException(failure))
            else Result.success(state)
        } catch (error: Throwable) {
            Result.failure(IllegalStateException(error.message ?: "发送失败", error))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    // ---------------------------------------------------------------- 行为配置

    /**
     * 读取完整的系统配置。
     *
     * 注意这是**整份**配置（几百个字段），因为写回去的接口是整份替换，
     * 拿不到完整内容就没法安全地改其中一个键。
     */
    suspend fun readSystemConfig(cookie: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val response = request("GET", "/system-config", null, cookie)
            ?: return@withContext Result.failure(
                IllegalStateException("读取配置失败：后台没有响应，容器在运行吗？"),
            )

        if (!response.ok) {
            return@withContext Result.failure(
                IllegalStateException(response.message.ifBlank { "读取配置失败" }),
            )
        }

        val data = response.data as? JSONObject
            ?: return@withContext Result.failure(IllegalStateException("配置返回格式异常"))

        Result.success(data)
    }

    /**
     * 整份写回系统配置。
     *
     * `PUT /system-config` 是完整替换，不是局部更新——调用方必须拿
     * [readSystemConfig] 的结果改完再传进来，不能只传改动的字段。
     * 服务端会做校验并热重载管道，不需要重启容器。
     *
     * 返回服务端的话术（"保存成功~"，或者带连通性警告的提示），
     * 直接展示比我们自己编一句更准确。
     */
    suspend fun writeSystemConfig(cookie: String, config: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            val response = request("PUT", "/system-config", config.toString(), cookie)
                ?: return@withContext Result.failure(
                    IllegalStateException("保存失败：后台没有响应"),
                )

            if (!response.ok) {
                return@withContext Result.failure(
                    IllegalStateException(response.message.ifBlank { "保存失败" }),
                )
            }

            Result.success(response.message.ifBlank { "已保存" })
        }

    // ---------------------------------------------------------------- 传输

    private class ApiResponse(
        val code: Int,
        val body: JSONObject?,
        val cookie: String,
    ) {
        /**
         * AstrBot 的响应体统一是 `{status, message, data}`，
         * 出错时可能仍是 HTTP 200，只在 status 标 error，所以两个都要看。
         */
        val ok: Boolean
            get() = code in 200..299 &&
                body?.optString("status").orEmpty().lowercase() != "error"

        val message: String
            get() = body?.optString("message").orEmpty().ifBlank {
                body?.optString("detail").orEmpty()
            }

        val data: Any? get() = body?.opt("data")
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        cookie: String?,
    ): ApiResponse? = requestUrl(method, base + path, body, cookie)

    private fun requestUrl(
        method: String,
        url: String,
        body: String?,
        cookie: String?,
    ): ApiResponse? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText)

            // 登录时 JWT 在 Set-Cookie 里，不在 body 里，必须从这里取。
            // 注意 headerFields 的 key 大小写由服务器决定（常见 "Set-cookie"），
            // 直接按 "Set-Cookie" 取会漏。
            val setCookies = connection.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value.orEmpty() }

            val cookieValue = setCookies
                .mapNotNull { it.substringBefore(";").trim().takeIf { part -> part.contains("=") } }
                .joinToString("; ")

            ApiResponse(
                code = code,
                body = runCatching { JSONObject(text.orEmpty()) }.getOrNull(),
                cookie = cookieValue,
            )
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

/** 插件配置里的一个可编辑项。 */
data class PluginConfigItem(
    val key: String,
    val label: String,
    val hint: String,
    val type: String,
    val value: Any?,
    val options: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
)

/** 一次读取的结果。 */
data class PluginConfigSnapshot(
    val pluginId: String,
    val items: List<PluginConfigItem>,
    /** false = 这个插件没有提供配置结构，不是读取失败。 */
    val hasSchema: Boolean = true,
)

/** 对话里的一条消息。 */
data class ConversationMessage(
    val role: String,
    val content: String,
)

/** 一个会话的摘要 + 完整历史。 */
data class ConversationSummary(
    val cid: String,
    /** 会话标识，形如 `aiocqhttp:GroupMessage:123456`。创建定时任务时要填它。 */
    val umo: String,
    val userId: String,
    val title: String,
    val messageType: String,
    val updatedAt: Long,
    val history: List<ConversationMessage>,
)

/** 一条定时任务。字段名与 AstrBot 的 /cron/jobs 返回体一致。 */
data class CronJob(
    val id: String,
    val name: String,
    val cronExpression: String,
    val note: String,
    val session: String,
    val enabled: Boolean,
    val runOnce: Boolean,
    val nextRunTime: String?,
)
