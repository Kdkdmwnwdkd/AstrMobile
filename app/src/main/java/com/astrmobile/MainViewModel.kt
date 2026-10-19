package com.astrmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astrmobile.core.container.Bootstrapper
import com.astrmobile.core.container.ContainerState
import com.astrmobile.core.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
                },
                pipIndex = current.pipIndex,
                githubProxy = current.githubProxy,
            )
            refreshEnvironment()
        }
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
            val paths = appContainer.paths
            runCatching { paths.rootfsDir.deleteRecursively() }
            runCatching { paths.installStateFile.delete() }
            runCatching { paths.containerLog.writeText("") }
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
