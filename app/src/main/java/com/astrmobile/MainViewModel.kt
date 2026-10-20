package com.astrmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astrmobile.core.container.AstrBotConfigReader
import com.astrmobile.core.container.Bootstrapper
import com.astrmobile.core.container.ContainerState
import com.astrmobile.core.container.ConfiguredModel
import com.astrmobile.core.container.DashboardCredentials
import com.astrmobile.core.container.ModelConfigRepository
import com.astrmobile.core.container.NapCatApi
import com.astrmobile.core.container.NapCatConfigReader
import com.astrmobile.core.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /** 装完插件、改完配置后需要重启容器才会生效。 */
    fun restartContainer() {
        viewModelScope.launch {
            controller.stop()
            delay(600)
            controller.start()
        }
    }

    suspend fun readDashboardCredentials(): DashboardCredentials? =
        withContext(Dispatchers.IO) { AstrBotConfigReader.readDashboard(appContainer.paths) }

    /**
     * NapCat 可能的口令，按可能性排序。
     *
     * 新版 start.sh 会把口令固定成 DEFAULT_TOKEN；旧容器里可能还留着
     * NapCat 随机生成的 8 位口令，因此再从配置文件读一次做兜底。
     */
    suspend fun readNapCatTokens(): List<String> = withContext(Dispatchers.IO) {
        val fromFile = runCatching { NapCatConfigReader.readToken(appContainer.paths) }.getOrNull()
        listOfNotNull(NapCatApi.DEFAULT_TOKEN, fromFile?.takeIf { it.isNotBlank() }).distinct()
    }

    /** 只返回配置里真实存在的口令，用于界面展示；读不到就别显示，避免给出错的值。 */
    suspend fun readNapCatToken(): String? = withContext(Dispatchers.IO) {
        runCatching { NapCatConfigReader.readToken(appContainer.paths) }.getOrNull()
    }

    suspend fun readModels(): List<ConfiguredModel> = withContext(Dispatchers.IO) {
        runCatching { ModelConfigRepository.read(appContainer.paths) }.getOrDefault(emptyList())
    }

    /** 保存模型配置。写入的是容器内的 AstrBot 配置，重启容器后生效。 */
    suspend fun saveModel(apiBase: String, apiKey: String, model: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            ModelConfigRepository.save(appContainer.paths, apiBase, apiKey, model)
        }

    suspend fun removeModel(): Result<Unit> = withContext(Dispatchers.IO) {
        ModelConfigRepository.remove(appContainer.paths)
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
