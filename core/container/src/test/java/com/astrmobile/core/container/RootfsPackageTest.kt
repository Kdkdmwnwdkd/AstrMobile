package com.astrmobile.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 系统包的元数据全部是纯函数，先把这些钉死：
 * 镜像拼接错一个斜杠就会 404，而错误信息只会显示 "HTTP 404"，
 * 排查起来毫无线索。
 */
class RootfsPackageTest {

    @Test
    fun `远程文件名带校验值前缀`() {
        assertEquals("ubuntu-rootfs-91acaa78.tar.xz", RootfsPackage.remoteFile)
        assertTrue(RootfsPackage.remoteFile.startsWith("ubuntu-rootfs-"))
        assertTrue(RootfsPackage.releaseUrl.endsWith(RootfsPackage.remoteFile))
    }

    @Test
    fun `加速前缀拼成 前缀斜杠完整地址`() {
        val urls = RootfsPackage.mirrorUrls(
            prefixes = listOf("https://ghfast.top"),
            origin = "https://github.com/a/b/releases/download/latest/x.tar.xz",
            fallback = "https://raw.githubusercontent.com/c/d/x.tar.xz",
        )
        assertEquals("https://ghfast.top/https://github.com/a/b/releases/download/latest/x.tar.xz", urls.first())
    }

    @Test
    fun `直连排在加速前缀之后`() {
        val urls = RootfsPackage.mirrorUrls(
            prefixes = listOf("https://ghfast.top", ""),
            origin = "https://github.com/a/x.tar.xz",
            fallback = "https://raw.githubusercontent.com/b/x.tar.xz",
        )
        assertEquals("https://github.com/a/x.tar.xz", urls[1])
        // 原始出处永远垫底：第三方仓库随时可能改路径
        assertEquals("https://raw.githubusercontent.com/b/x.tar.xz", urls.last())
    }

    @Test
    fun `前缀结尾带斜杠不会拼出双斜杠`() {
        val urls = RootfsPackage.mirrorUrls(
            prefixes = listOf("https://ghproxy.net/"),
            origin = "https://github.com/a/x.tar.xz",
            fallback = "",
        )
        assertEquals("https://ghproxy.net/https://github.com/a/x.tar.xz", urls.first())
    }

    @Test
    fun `空地址被丢掉`() {
        val urls = RootfsPackage.mirrorUrls(
            prefixes = listOf("", "  "),
            origin = "https://github.com/a/x.tar.xz",
            fallback = "",
        )
        assertEquals(listOf("https://github.com/a/x.tar.xz"), urls)
    }

    @Test
    fun `地址去重`() {
        val urls = RootfsPackage.mirrorUrls(
            prefixes = listOf("", ""),
            origin = "https://github.com/a/x.tar.xz",
            fallback = "https://github.com/a/x.tar.xz",
        )
        assertEquals(1, urls.size)
    }

    @Test
    fun `校验值比较忽略大小写`() {
        assertTrue(RootfsPackage.matches("abcdef", "ABCDEF"))
        assertTrue(RootfsPackage.matches("abcdef", "abcdef"))
        assertFalse(RootfsPackage.matches("abcdef", "abcdee"))
    }

    @Test
    fun `空校验值一律判为不匹配`() {
        // 宁可多下一次，也不能在"没算出来"的时候放行
        assertFalse(RootfsPackage.matches("", ""))
        assertFalse(RootfsPackage.matches("", "abcdef"))
    }

    @Test
    fun `体积格式化`() {
        assertEquals("未知大小", RootfsPackage.describeBytes(0))
        assertEquals("500 KB", RootfsPackage.describeBytes(512_000))
        assertEquals("100 MB", RootfsPackage.describeBytes(100L * 1024 * 1024))
        assertEquals("1.5 GB", RootfsPackage.describeBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `sha256 与常见工具输出一致`() {
        val file = File.createTempFile("sha", ".bin")
        try {
            file.writeText("abc")
            // sha256("abc") 的公开值，用来确认大小写与补零都正确
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RootfsPackage.sha256Of(file),
            )
        } finally {
            file.delete()
        }
    }
}
