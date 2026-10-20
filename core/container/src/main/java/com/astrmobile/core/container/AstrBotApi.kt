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
