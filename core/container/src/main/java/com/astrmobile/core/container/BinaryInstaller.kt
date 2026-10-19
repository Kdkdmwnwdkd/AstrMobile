package com.astrmobile.core.container

import android.system.Os
import com.astrmobile.core.common.AppLog
import java.io.File

/**
 * 把 APK 中 nativeLibraryDir 里的 ELF 文件映射成可执行入口。
 *
 * 背景：Android 10+ 禁止从应用私有目录执行二进制（W^X），但
 * `nativeLibraryDir` 下的 .so 具备可执行权限，因此只能“软链过去”，
 * 不能复制到 filesDir 再执行。NapCat / proot 的方案都依赖这个技巧。
 */
class BinaryInstaller(private val paths: ContainerPaths) {

    data class Mapping(val source: String, val target: File, val required: Boolean)

    private val mappings = listOf(
        Mapping("libproot.so", paths.prootBin, true),
        Mapping("libloader.so", paths.loaderBin, true),
        Mapping("libbusybox.so", paths.busyboxBin, true),
        Mapping("libbash.so", paths.bashBin, false),
        Mapping("liblibtalloc.so.2.so", File(paths.binDir, "libtalloc.so.2"), true),
    )

    /** 返回本次安装中缺失的必需文件，空列表表示全部就绪。 */
    fun install(): List<String> {
        paths.ensureDirectories()
        val missing = mutableListOf<String>()

        mappings.forEach { mapping ->
            val source = File(paths.nativeLibDir, mapping.source)
            if (!source.exists()) {
                if (mapping.required) missing += mapping.source
                return@forEach
            }
            if (!mapping.target.exists() || !isUsable(mapping.target)) {
                linkOrCopy(source, mapping.target)
            }
            if (mapping.target.name != "libtalloc.so.2") {
                runCatching { Os.chmod(mapping.target.absolutePath, 0b111101101) } // 0755
            }
        }
        return missing
    }

    private fun isUsable(target: File): Boolean {
        // 软链指向的目标可能随升级变化，这里只检查链接本身是否存在且非空。
        return runCatching { target.exists() && target.length() >= 0 }.getOrDefault(false)
    }

    private fun linkOrCopy(source: File, target: File) {
        if (target.exists() || target.isSymLinkSafe()) {
            runCatching { target.delete() }
        }
        val linked = runCatching {
            Os.symlink(source.absolutePath, target.absolutePath)
        }.isSuccess

        if (!linked) {
            // 少数 ROM 对 symlink 有限制，退化成复制；复制件不可执行时容器会给出明确报错。
            runCatching { source.copyTo(target, overwrite = true) }
            runCatching { Os.chmod(target.absolutePath, 0b111101101) }
            AppLog.app("symlink 失败，已退化为复制：${target.name}")
        }
    }

    private fun File.isSymLinkSafe(): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(toPath())
    }.getOrDefault(false)
}
