package com.astrmobile.core.container

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * 极简 HTTP 服务端，支持 Range。
 *
 * 不用 `com.sun.net.httpserver`：Android 单元测试跑在 android.jar 的 stub 上，
 * 那个包不在类路径里。也不用 MockWebServer：项目本身只用 HttpURLConnection，
 * 为一份测试引一个网络库不划算。
 */
private class RangeHttpServer(
    private val body: ByteArray,
    private val supportsRange: Boolean = true,
    private val statusOverride: Int? = null,
    private val honouredRequests: MutableList<String?> = mutableListOf(),
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

    fun stop() = runCatching { socket.close() }

    private fun handle(client: Socket) = client.use {
        val reader = client.getInputStream().bufferedReader()
        reader.readLine() // 请求行
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                range = line.substringAfter(':').trim()
            }
        }
        honouredRequests.add(range)

        val output = client.getOutputStream()
        val forced = statusOverride
        if (forced != null) {
            output.write(
                "HTTP/1.1 $forced Nope\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(),
            )
            output.flush()
            return@use
        }

        val start = range
            ?.removePrefix("bytes=")
            ?.substringBefore('-')
            ?.toIntOrNull()

        if (start != null && supportsRange) {
            if (start >= body.size) {
                output.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */${body.size}\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                output.flush()
                return@use
            }
            val slice = body.copyOfRange(start, body.size)
            output.write(
                ("HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Range: bytes $start-${body.size - 1}/${body.size}\r\n" +
                    "Content-Length: ${slice.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            output.write(slice)
        } else {
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            output.write(body)
        }
        output.flush()
    }
}

class RootfsDownloaderTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        File.createTempFile("rootfs", "").let {
            it.delete()
            it.mkdirs()
            tempDirs.add(it)
            it
        }

    @After
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun downloaderFor(
        dir: File,
        urls: List<String>,
        expected: String,
    ) = RootfsDownloader(
        cacheDir = dir,
        candidates = urls,
        expectedSha256 = expected,
        targetName = "test.tar.xz",
    )

    @Test
    fun `下载完整文件并校验通过`() = runBlocking {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val server = RangeHttpServer(body).apply { start() }
        try {
            val dir = tempDir()
            val expected = sha256Of(body)
            val result = downloaderFor(dir, listOf("http://127.0.0.1:${server.port}/x"), expected).ensure()

            assertTrue(result.isSuccess)
            val file = result.getOrThrow()
            assertEquals(body.size.toLong(), file.length())
            assertArrayEquals(body, file.readBytes())
            assertFalse(File(dir, "test.tar.xz.part").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `有半成品时带 Range 续传`() = runBlocking {
        val body = ByteArray(200_000) { (it % 199).toByte() }
        val server = RangeHttpServer(body).apply { start() }
        try {
            val dir = tempDir()
            // 先放 120KB 的半成品，模拟上次下到一半断网
            File(dir, "test.tar.xz.part").writeBytes(body.copyOfRange(0, 120_000))

            val result = downloaderFor(dir, listOf("http://127.0.0.1:${server.port}/x"), sha256Of(body)).ensure()

            assertTrue(result.isSuccess)
            assertArrayEquals(body, result.getOrThrow().readBytes())
            assertEquals("bytes=120000-", server.honouredRequests.first())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `服务器不支持 Range 时从头重下`() = runBlocking {
        val body = ByteArray(50_000) { (it % 97).toByte() }
        val server = RangeHttpServer(body, supportsRange = false).apply { start() }
        try {
            val dir = tempDir()
            // 半成品内容故意写错：如果客户端坚持续传，拼出来的文件校验必然失败
            File(dir, "test.tar.xz.part").writeBytes(ByteArray(1000))

            val result = downloaderFor(dir, listOf("http://127.0.0.1:${server.port}/x"), sha256Of(body)).ensure()

            assertTrue(result.isSuccess)
            assertArrayEquals(body, result.getOrThrow().readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `半成品过大触发 416 时丢掉重下`() = runBlocking {
        val body = ByteArray(40_000) { (it % 89).toByte() }
        val server = RangeHttpServer(body).apply { start() }
        try {
            val dir = tempDir()
            File(dir, "test.tar.xz.part").writeBytes(ByteArray(90_000))

            val result = downloaderFor(dir, listOf("http://127.0.0.1:${server.port}/x"), sha256Of(body)).ensure()

            assertTrue(result.isSuccess)
            assertArrayEquals(body, result.getOrThrow().readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `校验不通过会删掉半成品`() = runBlocking {
        val body = ByteArray(20_000) { 7 }
        val server = RangeHttpServer(body).apply { start() }
        try {
            val dir = tempDir()
            val wrong = "0".repeat(64)
            val result = downloaderFor(dir, listOf("http://127.0.0.1:${server.port}/x"), wrong).ensure()

            assertTrue(result.isFailure)
            // 留着这份坏包，下次续传永远对不上，等于把用户卡死
            assertFalse(File(dir, "test.tar.xz.part").exists())
            assertFalse(File(dir, "test.tar.xz").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `前一个源 500 时自动换下一个`() = runBlocking {
        val body = ByteArray(10_000) { 3 }
        val broken = RangeHttpServer(body, statusOverride = 500).apply { start() }
        val good = RangeHttpServer(body).apply { start() }
        try {
            val dir = tempDir()
            val urls = listOf(
                "http://127.0.0.1:${broken.port}/x",
                "http://127.0.0.1:${good.port}/x",
            )
            val result = downloaderFor(dir, urls, sha256Of(body)).ensure()

            assertTrue(result.isSuccess)
            assertArrayEquals(body, result.getOrThrow().readBytes())
        } finally {
            broken.stop()
            good.stop()
        }
    }

    @Test
    fun `全部源失败时返回可读错误`() = runBlocking {
        val dir = tempDir()
        // 端口 1 基本不会有人监听，连接会被立刻拒绝
        val result = downloaderFor(dir, listOf("http://127.0.0.1:1/x"), "0".repeat(64)).ensure()

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("下载失败"))
        assertTrue(message.contains("从文件导入"))
    }

    @Test
    fun `本地已有校验通过的归档时不再发请求`() = runBlocking {
        val body = ByteArray(5_000) { 1 }
        val server = RangeHttpServer(body).apply { start() }
        try {
            val dir = tempDir()
            File(dir, "test.tar.xz").writeBytes(body)

            val result = downloaderFor(dir, listOf("http://127.0.0.1:${server.port}/x"), sha256Of(body)).ensure()

            assertTrue(result.isSuccess)
            assertTrue(server.honouredRequests.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `本地归档损坏时重新下载`() = runBlocking {
        val body = ByteArray(5_000) { 9 }
        val server = RangeHttpServer(body).apply { start() }
        try {
            val dir = tempDir()
            File(dir, "test.tar.xz").writeBytes(ByteArray(5_000) { 0 })

            val result = downloaderFor(dir, listOf("http://127.0.0.1:${server.port}/x"), sha256Of(body)).ensure()

            assertTrue(result.isSuccess)
            assertArrayEquals(body, result.getOrThrow().readBytes())
        } finally {
            server.stop()
        }
    }

    private fun sha256Of(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
