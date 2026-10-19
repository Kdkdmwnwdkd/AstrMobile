package com.astrmobile.core.container

/** 容器对外暴露的状态机。UI 只依赖这一个类型，避免把进程细节泄漏到界面层。 */
sealed interface ContainerState {

    /** rootfs 或工具链尚未就绪，需要先走安装流程。 */
    data object NotInstalled : ContainerState

    data object Stopped : ContainerState

    data object Starting : ContainerState

    data class Running(
        val astrbotReady: Boolean,
        val napcatReady: Boolean,
    ) : ContainerState {
        val fullyReady: Boolean get() = astrbotReady && napcatReady
    }

    /** 容器异常退出后正在按退避策略重启。 */
    data class Restarting(
        val attempt: Int,
        val maxAttempts: Int,
        val delaySeconds: Int,
        val lastReason: String,
    ) : ContainerState

    data object Stopping : ContainerState

    data class Failed(val reason: String) : ContainerState
}
