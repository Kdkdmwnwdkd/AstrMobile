package com.astrmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astrmobile.core.container.Bootstrapper
import com.astrmobile.core.container.ContainerState
import com.astrmobile.core.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContainer = (application as AstrMobileApp).container
    private val controller = appContainer.controller
    private val bootstrapper = appContainer.bootstrapper
    private val settingsRepository = appContainer.settings

    val containerState: StateFlow<ContainerState> = controller.state
    val logs: StateFlow<List<String>> = controller.logs

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _installStage = MutableStateFlow<Bootstrapper.Stage>(Bootstrapper.Stage.Idle)
    val installStage: StateFlow<Bootstrapper.Stage> = _installStage.asStateFlow()

    private val _environmentReady = MutableStateFlow(bootstrapper.isReady())
    val environmentReady: StateFlow<Boolean> = _environmentReady.asStateFlow()

    private val _installLogs = MutableStateFlow<List<String>>(emptyList())
    val installLogs: StateFlow<List<String>> = _installLogs.asStateFlow()

    init {
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        _environmentReady.value = bootstrapper.isReady()
        controller.refreshInstallState()
    }

    fun install() {
        if (_installStage.value is Bootstrapper.Stage.Running) return
        _installLogs.value = emptyList()
        val current = settings.value
        viewModelScope.launch {
            bootstrapper.install(
                onStage = { stage ->
                    _installStage.value = stage
                    if (stage is Bootstrapper.Stage.Done) {
                        _environmentReady.value = true
                    }
                },
                onLine = { line ->
                    _installLogs.value = (_installLogs.value + line).takeLast(400)
                    // 同时并入容器日志：安装失败时用户导出的就是这一份完整现场
                    controller.pushLog(line)
                },
                aptMirror = current.aptMirror,
                pipIndex = current.pipIndex,
                githubProxy = current.githubProxy,
            )
            refreshEnvironment()
        }
    }

    fun cancelInstall() {
        bootstrapper.cancel()
        _installLogs.value = (_installLogs.value + "[APP] 已请求取消安装").takeLast(400)
    }

    fun startContainer() {
        val current = settings.value
        controller.aptMirror = current.aptMirror
        viewModelScope.launch {
            controller.start().onFailure { error ->
                _installLogs.value = (_installLogs.value + (error.message ?: "启动失败")).takeLast(400)
            }
        }
    }

    fun stopContainer() {
        viewModelScope.launch { controller.stop() }
    }

    fun prepareReinstall() {
        // 用户从控制台点"重新安装"时，环境通常仍不完整；把上一轮的 Done 状态清掉，
        // 否则向导会一直停在"环境已就绪"，看不到安装按钮。
        if (!_environmentReady.value) {
            _installStage.value = Bootstrapper.Stage.Idle
            _installLogs.value = emptyList()
        }
    }

    fun clearLogs() = controller.clearLogs()

    fun containerPorts(): Pair<Int, Int> = Pair(6185, 6099)

    fun applySettings(aptMirror: String, pipIndex: String, githubProxy: String) {
        viewModelScope.launch {
            settingsRepository.setAptMirror(aptMirror)
            settingsRepository.setPipIndex(pipIndex)
            settingsRepository.setGithubProxy(githubProxy)
        }
    }

    fun setAutoStart(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoStart(value) }
    }

    fun setKeepAwake(value: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepAwake(value) }
    }

    fun resetEnvironment() {
        viewModelScope.launch {
            runCatching { controller.stop() }
            withContext(Dispatchers.IO) {
                val paths = appContainer.paths
                // 先删标记：即使删除中途被中断，下次也能重新安装而不是被判为"已安装"
                runCatching { paths.installStateFile.delete() }
                runCatching { File(paths.rootfsDir, ".rootfs-installed").delete() }
                runCatching { paths.rootfsDir.deleteRecursively() }
                runCatching { paths.containerLog.writeText("") }
            }
            _installStage.value = Bootstrapper.Stage.Idle
            _installLogs.value = emptyList()
            _environmentReady.value = false
            controller.refreshInstallState()
        }
    }

    suspend fun currentSettings(): AppSettings = settings.value

    override fun onCleared() {
        super.onCleared()
        // 进程存活期间容器继续跑，由前台服务负责保活，这里不主动停止。
    }
}
