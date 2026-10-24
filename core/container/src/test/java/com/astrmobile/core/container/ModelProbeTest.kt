package com.astrmobile.core.container

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * 用 ServerSocket 起一个极简 HTTP 服务端。
 *
 * 不用 `com.sun.net.httpserver`：Android 单元测试跑在 android.jar 的 stub 上，
 * 那个包不在类路径里，编译期直接 Unresolved reference。也不想为了这点测试
 * 引入 MockWebServer——项目本身只有 HttpURLConnection，加个网络库只为测试
 * 不划算。
 *
 * 只支持 GET、只回 Connection: close，够 ModelProbe 用。
 */
private class TinyHttpServer(
    private val status: Int = 200,
    private val body: String = "",
    private val expectAuth: String? = null,
) {
    private val socket = ServerSocket(0)
    private val worker = Thread {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { handle(client) }
        }
    }.apply { isDaemon = true }

    val port: Int get() = socket.localPort

    fun start() = worker.start()

    fun stop() {
        runCatching { socket.close() }
    }

    private fun handle(client: Socket) = client.use {
        val reader = client.getInputStream().bufferedReader()
        reader.readLine() // 请求行，这里不关心

        var auth: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Authorization:", ignoreCase = true)) {
                auth = line.substringAfter(':').trim()
            }
        }

        val authorized = expectAuth == null || auth == "Bearer $expectAuth"
        val code = if (authorized) status else 401
        val payload = (if (authorized) body else "").toByteArray()

        val head = buildString {
            append("HTTP/1.1 $code X\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(payload)
            flush()
        }
    }
}

class ModelProbeTest {

    private var server: TinyHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun serve(
        status: Int = 200,
        body: String = "",
        expectAuth: String? = null,
    ): Int {
        val instance = TinyHttpServer(status, body, expectAuth)
        instance.start()
        server = instance
        return instance.port
    }

    @Test
    fun `正常返回时给出模型数量`() = runBlocking {
        val port = serve(200, """{"object":"list","data":[{"id":"a"},{"id":"b"}]}""", "sk-good")

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-good")

        assertTrue(result.ok)
        assertEquals("可用 · 2 个模型", result.detail)
    }

    @Test
    fun `key 不对时给出明确原因`() = runBlocking {
        val port = serve(200, """{"data":[]}""", "sk-good")

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-wrong")

        assertFalse(result.ok)
        assertTrue(result.detail.contains("Key 不被接受"))
    }

    @Test
    fun `端点没有 models 接口时不说成配置错误`() = runBlocking {
        // 很多中转不实现 /models，但这不代表不能用
        val port = serve(404, "not found")

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-good")

        assertFalse(result.ok)
        assertTrue(result.detail.contains("没有 /models 接口"))
        assertTrue(result.detail.contains("试聊"))
    }

    @Test
    fun `服务端 500 时带出状态码`() = runBlocking {
        val port = serve(500, "internal error")

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-good")

        assertFalse(result.ok)
        assertTrue(result.detail.startsWith("HTTP 500"))
    }

    @Test
    fun `地址不以 http 开头时不发请求直接拒绝`() = runBlocking {
        val result = ModelProbe.probe("api.example.com/v1", "sk-good")

        assertFalse(result.ok)
        assertTrue(result.detail.contains("http"))
    }

    @Test
    fun `空地址直接拒绝`() = runBlocking {
        val result = ModelProbe.probe("   ", "sk-good")

        assertFalse(result.ok)
        assertTrue(result.detail.contains("空的"))
    }

    @Test
    fun `末尾斜杠不影响拼接`() = runBlocking {
        val port = serve(200, """{"data":[{"id":"a"}]}""")

        val result = ModelProbe.probe("http://127.0.0.1:$port/", "sk-good")

        assertTrue(result.ok)
        assertEquals("可用 · 1 个模型", result.detail)
    }

    @Test
    fun `连不上时报连接失败而不是崩`() = runBlocking {
        val port = serve()
        server?.stop()

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-good", timeoutMs = 1_500)

        assertFalse(result.ok)
        assertTrue(result.detail.contains("连不上"))
    }
}
