package com.astrmobile.core.container

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class ModelProbeTest {

    private lateinit var server: HttpServer
    private var port = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
    }

    @After
    fun tearDown() {
        runCatching { server.stop(0) }
    }

    private fun respond(path: String, code: Int, body: String, assertAuth: Boolean = false) {
        server.createContext(path) { exchange ->
            if (assertAuth) {
                val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                if (auth != "Bearer sk-good") {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @Test
    fun `正常返回时给出模型数量`() = runBlocking {
        respond(
            "/models",
            200,
            """{"object":"list","data":[{"id":"a"},{"id":"b"}]}""",
            assertAuth = true,
        )

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-good")

        assertTrue(result.ok)
        assertEquals("可用 · 2 个模型", result.detail)
    }

    @Test
    fun `key 不对时给出明确原因`() = runBlocking {
        respond("/models", 200, """{"data":[]}""", assertAuth = true)

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-wrong")

        assertFalse(result.ok)
        assertTrue(result.detail.contains("Key 不被接受"))
    }

    @Test
    fun `端点没有 models 接口时不说成配置错误`() = runBlocking {
        // 很多中转不实现 /models，但这不代表不能用
        respond("/models", 404, "not found")

        val result = ModelProbe.probe("http://127.0.0.1:$port", "sk-good")

        assertFalse(result.ok)
        assertTrue(result.detail.contains("没有 /models 接口"))
        assertTrue(result.detail.contains("试聊"))
    }

    @Test
    fun `服务端 500 时带出状态码`() = runBlocking {
        respond("/models", 500, "internal error")

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
        respond("/models", 200, """{"data":[{"id":"a"}]}""")

        val result = ModelProbe.probe("http://127.0.0.1:$port/", "sk-good")

        assertTrue(result.ok)
    }

    @Test
    fun `连不上时报连接失败而不是崩`() = runBlocking {
        // 端口没监听：拿一个刚关掉的端口
        val dead = server.address.port
        server.stop(0)

        val result = ModelProbe.probe("http://127.0.0.1:$dead", "sk-good", timeoutMs = 1_500)

        assertFalse(result.ok)
        assertTrue(result.detail.contains("连不上"))
    }
}
