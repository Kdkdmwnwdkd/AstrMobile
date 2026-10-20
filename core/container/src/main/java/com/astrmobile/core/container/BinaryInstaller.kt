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
            // 关键：APK 升级后 nativeLibraryDir 路径会变（/data/app/~~xxx==/...-1/lib），
            // 旧的软链会指向已不存在的目录。必须校验链接目标是否就是当前 source，
            // 不能只看 target.exists()（断裂软链会被误判为"可能需要重建"，普通旧文件则完全漏判）。
            if (needsLink(source, mapping.target)) {
                linkOrCopy(source, mapping.target)
            }
            if (mapping.required) {
                diagnoseExecutable(source)
            }
            if (mapping.target.name != "libtalloc.so.2") {
                runCatching { Os.chmod(mapping.target.absolutePath, 0b111101101) } // 0755
            }
        }
        return missing
    }

    /**
     * proot 依赖 nativeLibraryDir 里的 .so 具备可执行位。
     * 个别 ROM 会把 lib 装成 0644，此时容器会以 Permission denied 失败——
     * 提前把结论写进日志，比让用户猜"为什么起不来"有用得多。
     */
    private fun diagnoseExecutable(source: File) {
        val executable = runCatching {
            android.system.Os.access(source.absolutePath, android.system.OsConstants.X_OK)
        }.getOrDefault(false)
        if (!executable) {
            AppLog.app("警告：${source.name} 缺少可执行权限，容器可能无法启动")
        }
    }

    private fun needsLink(source: File, target: File): Boolean {
        val targetPath = target.toPath()
        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(targetPath) }
            .getOrDefault(false)
        if (!isLink) return true

        val currentPointer = runCatching { java.nio.file.Files.readSymbolicLink(targetPath).toString() }
            .getOrNull() ?: return true
        return currentPointer != source.absolutePath
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
