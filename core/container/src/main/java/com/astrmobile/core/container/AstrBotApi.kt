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
            val hint = response.message.ifBlank { "登录失败" }
            return@withContext Result.failure(
                IllegalStateException("$hint（后台密码不对？默认是 astrbot）"),
            )
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
    ): Result<List<ConversationSummary>> = withContext(Dispatchers.IO) {
        val response = request(
            "GET",
            "/conversations?page=1&page_size=$pageSize&sort_by=updated_at&sort_order=desc&include_history=true",
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
            return@withContext Result.failure(
                IllegalStateException("这个插件没有可配置项（可能没提供配置结构）"),
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
    ): ApiResponse? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
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
)

/** 对话里的一条消息。 */
data class ConversationMessage(
    val role: String,
    val content: String,
)

/** 一个会话的摘要 + 完整历史。 */
data class ConversationSummary(
    val cid: String,
    val userId: String,
    val title: String,
    val messageType: String,
    val updatedAt: Long,
    val history: List<ConversationMessage>,
)
