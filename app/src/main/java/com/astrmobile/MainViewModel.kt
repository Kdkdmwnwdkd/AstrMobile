package com.astrmobile

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astrmobile.core.container.AstrBotApi
import com.astrmobile.core.container.BehaviorConfig
import com.astrmobile.core.container.BehaviorSettings
import com.astrmobile.core.container.BackupRepository
import com.astrmobile.core.container.BackupResult
import com.astrmobile.core.container.AstrBotConfigReader
import com.astrmobile.core.container.Bootstrapper
import com.astrmobile.core.container.ContainerState
import com.astrmobile.core.container.CheckItem
import com.astrmobile.core.container.ConfiguredModel
import com.astrmobile.core.container.DiagnosticsExporter
import com.astrmobile.core.container.ConversationSummary
import com.astrmobile.core.container.CronJob
import com.astrmobile.core.container.Diagnostics
import com.astrmobile.core.container.DashboardCredentials
import com.astrmobile.core.container.EmbeddingConfig
import com.astrmobile.core.container.InstalledPlugin
import com.astrmobile.core.container.KnowledgeBase
import com.astrmobile.core.container.KnowledgeBaseRepository
import com.astrmobile.core.container.KnowledgeDocument
import com.astrmobile.core.container.MarketPlugin
import com.astrmobile.core.container.ModelConfigRepository
import com.astrmobile.core.container.PersonaItem
import com.astrmobile.core.container.PersonaTemplates
import com.astrmobile.core.container.PluginConfigSnapshot
import com.astrmobile.core.container.PluginRepository
import com.astrmobile.core.container.NapCatApi
import com.astrmobile.core.container.NapCatConfigReader
import com.astrmobile.core.container.UsageSnapshot
import com.astrmobile.core.container.VisionApi
import com.astrmobile.core.data.AppSettings
import android.net.Uri
import com.astrmobile.core.data.VisionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 保存模型后的结果：区分“已经生效”和“还要重启”。 */
data class ModelSaveOutcome(
    val needsRestart: Boolean,
    val message: String,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContainer = (application as AstrMobileApp).container
    private val controller = appContainer.controller
    private val bootstrapper = appContainer.bootstrapper
    private val settingsRepository = appContainer.settings
    private val visionStore = appContainer.vision

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

    /** 当前安装协程。取消要连协程一起取消，否则下载循环不会停。 */
    private var installJob: Job? = null

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
        installJob?.cancel()
        installJob = viewModelScope.launch {
            try {
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
            } finally {
                // 取消时也要刷新：用户回到界面能看到"未安装"，而不是卡在进度条上
                refreshEnvironment()
            }
        }
    }

    fun cancelInstall() {
        bootstrapper.cancel()
        // 下载阶段没有子进程可杀，只能靠取消协程让读写循环退出；已下载的部分会留着续传
        installJob?.cancel()
        _installLogs.value = (_installLogs.value + "[APP] 已请求取消安装").takeLast(400)
    }

    /**
     * 用本地文件替代下载。
     *
     * 系统包 100MB，镜像全挂时用户没法自己解压，只能从电脑传一个过来。
     * 先把内容拷进缓存，校验通过才算数；校验不过立刻失败，不留坏包。
     */
    fun importRootfs(uri: Uri) {
        viewModelScope.launch {
            _installStage.value = Bootstrapper.Stage.Running("导入系统包", 0.04f, "正在读取所选文件")
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(appContainer.paths.runtimeDir, "imported-rootfs.tar.xz")
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选文件，请换一个文件管理器重试")

                    check(temp.length() > 0L) { "所选文件是空的" }
                    _installStage.value = Bootstrapper.Stage.Running(
                        "导入系统包",
                        0.05f,
                        "正在校验（${temp.length() / 1024 / 1024}MB）",
                    )
                    bootstrapper.adoptImportedRootfs(temp).getOrThrow()
                    runCatching { temp.delete() }
                }
            }
            outcome
                .onSuccess {
                    _installStage.value = Bootstrapper.Stage.Idle
                    _installLogs.value = (_installLogs.value + "[APP] 系统包已导入，可以开始安装")
                        .takeLast(400)
                }
                .onFailure { error ->
                    _installStage.value = Bootstrapper.Stage.Failed(
                        "导入失败",
                        error.message ?: "文件不可用",
                    )
                }
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

    /**
     * 读模型配置。
     *
     * 优先问 AstrBot 自己（`/api/v1/providers`）：这是运行时真实生效的那份，
     * 已经过 provider_sources 合并，不会出现“文件里有、实际没加载”的假象。
     * 容器没起、后台密码改过、老版本没有该接口时，回退到直接读配置文件。
     */
    suspend fun readModels(): List<ConfiguredModel> = withContext(Dispatchers.IO) {
        readRemoteModels() ?: runCatching {
            ModelConfigRepository.read(appContainer.paths)
        }.getOrDefault(emptyList())
    }

    /** 读不到（未运行/未登录）时返回 null，交给调用方回退。 */
    private suspend fun readRemoteModels(): List<ConfiguredModel>? {
        val cookie = loginAstrBot().getOrNull() ?: return null
        val providers = AstrBotApi().listProviders(cookie).getOrNull() ?: return null
        return providers
            .filter { it.providerType.isBlank() || it.providerType == "chat_completion" }
            // 启用的排前面：那才是实际在用的
            .sortedWith(compareByDescending<AstrBotApi.RemoteProvider> { it.enabled }.thenBy { it.id })
            .map {
                ConfiguredModel(
                    id = it.id,
                    apiBase = it.apiBase,
                    model = it.model,
                    keyHint = it.keyHint,
                    enabled = it.enabled,
                )
            }
    }

    /**
     * 保存模型：先走 AstrBot 的 provider 接口（热生效、由它自己落盘），
     * 失败才退回“写配置文件 + 提示重启”。
     *
     * 接口地址和模型名都相同的已有配置会被覆盖，其余模型保持不动，
     * 所以可以存多个，互不影响。
     */
    suspend fun saveModel(
        apiBase: String,
        apiKey: String,
        model: String,
        makeActive: Boolean = true,
    ): Result<ModelSaveOutcome> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrNull()
        if (cookie != null) {
            val api = AstrBotApi()
            val providers = api.listProviders(cookie).getOrNull().orEmpty()
            val existing = providers.firstOrNull {
                it.apiBase.trimEnd('/').equals(apiBase.trimEnd('/'), ignoreCase = true) &&
                    it.model.trim().equals(model.trim(), ignoreCase = true)
            }
            val id = existing?.id ?: newProviderId()

            val saved = api.saveChatProvider(cookie, id, apiBase, apiKey, model)
            if (saved.isSuccess) {
                if (makeActive) activateInternal(api, cookie, id, providers)
                // 顺手留一份加密凭据：看屏幕要直连模型，而 AstrBot 返回的 Key 是脱敏的
                visionStore.save(VisionConfig(apiBase.trimEnd('/'), model.trim(), apiKey.trim()))
                val message = if (makeActive) "已保存，并设为当前使用的模型" else "已保存"
                return@withContext Result.success(ModelSaveOutcome(false, message))
            }
        }

        ModelConfigRepository.save(appContainer.paths, apiBase, apiKey, model)
            .onSuccess {
                visionStore.save(VisionConfig(apiBase.trimEnd('/'), model.trim(), apiKey.trim()))
            }
            .map { ModelSaveOutcome(true, "配置已写入，重启容器后生效") }
    }

    /** 生成一个不会撞车的 provider id；同地址同模型的会复用旧 id，不会重复添加。 */
    private fun newProviderId(): String =
        "astrmobile_" + System.currentTimeMillis().toString(36)

    /**
     * 把某个模型设为“当前使用”：启用它，并禁用其他对话模型。
     *
     * AstrBot 没设默认模型时会退回到第一个启用的 provider，因此这个做法
     * 等价于切换默认模型，又不用碰系统配置（GET 回来的 Key 是脱敏的，
     * 全量回写会把 Key 冲掉）。
     */
    private suspend fun activateInternal(
        api: AstrBotApi,
        cookie: String,
        targetId: String,
        providers: List<AstrBotApi.RemoteProvider>,
    ) {
        api.setProviderEnabled(cookie, targetId, true)
        providers
            .filter {
                it.id != targetId && it.enabled &&
                    (it.providerType.isBlank() || it.providerType == "chat_completion")
            }
            .forEach { api.setProviderEnabled(cookie, it.id, false) }
    }

    suspend fun activateModel(id: String): Result<ModelSaveOutcome> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("登录 AstrBot 后台失败，容器在运行吗？"))
        val api = AstrBotApi()
        val providers = api.listProviders(cookie).getOrElse {
            return@withContext Result.failure(it)
        }
        activateInternal(api, cookie, id, providers)
        Result.success(ModelSaveOutcome(false, "已切换，新消息会走这个模型"))
    }

    suspend fun setModelEnabled(id: String, enabled: Boolean): Result<ModelSaveOutcome> =
        withContext(Dispatchers.IO) {
            val cookie = loginAstrBot().getOrNull()
                ?: return@withContext Result.failure(IllegalStateException("登录 AstrBot 后台失败，容器在运行吗？"))
            AstrBotApi().setProviderEnabled(cookie, id, enabled)
                .map { ModelSaveOutcome(false, if (enabled) "已启用" else "已停用") }
        }

    suspend fun removeModel(id: String): Result<ModelSaveOutcome> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrNull()
        if (cookie != null) {
            val removed = AstrBotApi().deleteProvider(cookie, id)
            if (removed.isSuccess) {
                return@withContext Result.success(ModelSaveOutcome(false, "已删除"))
            }
        }
        ModelConfigRepository.remove(appContainer.paths)
            .map { ModelSaveOutcome(true, "已从配置中移除，重启后生效") }
    }

    /** 真正发一次请求验证 Key 能不能用——这是“到底生效没有”的唯一可靠答案。 */
    suspend fun testModel(id: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrNull()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "登录 AstrBot 后台失败。容器在运行吗？如果改过后台密码，先去「人格设定」里填一次。",
                ),
            )
        val providers = AstrBotApi().listProviders(cookie).getOrElse {
            return@withContext Result.failure(it)
        }
        val target = id?.let { wanted -> providers.firstOrNull { it.id == wanted } }
            ?: providers.firstOrNull { it.enabled }
            ?: providers.firstOrNull()
            ?: return@withContext Result.failure(
                IllegalStateException("还没有保存模型，先点一次「保存」"),
            )
        AstrBotApi().testProvider(cookie, target.id)
    }

    // -------------------------------------------------------------- 看屏幕

    /** 当前可用于看图的模型（保存模型时留下的那份凭据）。 */
    suspend fun visionTarget(): VisionConfig? = withContext(Dispatchers.IO) { visionStore.load() }

    /**
     * 把一张截图交给模型，让它描述画面。
     *
     * 只读本地那份凭据，不经过 AstrBot——它的接口没有"发任意图片问一句"的入口。
     * 截图在这里只存在于内存，发完即弃。
     */
    suspend fun describeScreen(jpeg: ByteArray, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            val config = visionStore.load()
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "App 里还没有可用的模型凭据。\n去「模型」页把 Key 重新保存一次，" +
                            "之后这里就能直接看图了。",
                    ),
                )
            VisionApi().describe(
                apiBase = config.apiBase,
                apiKey = config.apiKey,
                model = config.model,
                jpeg = jpeg,
                prompt = prompt,
            )
        }

    // ---------------------------------------------------------------- 插件

    suspend fun loadPluginMarket(force: Boolean = false): Result<List<MarketPlugin>> =
        withContext(Dispatchers.IO) { PluginRepository.loadMarket(appContainer.context, force) }

    suspend fun listInstalledPlugins(): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching { PluginRepository.listInstalled(appContainer.paths) }.getOrDefault(emptyList())
    }

    /**
     * 哪些插件没加载起来。
     *
     * 目录里有不等于加载成功，这是两回事：插件可能缺 Python 依赖、
     * 版本不兼容、初始化抛异常。只有运行时知道自己栽了。
     */
    suspend fun loadFailedPlugins(): Map<String, String> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrNull() ?: return@withContext emptyMap()
        AstrBotApi().listFailedPlugins(cookie).getOrDefault(emptyMap())
    }

    suspend fun installPlugin(
        plugin: MarketPlugin,
        onProgress: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        PluginRepository.install(appContainer.paths, plugin, onProgress)
    }

    suspend fun uninstallPlugin(dirName: String): Result<Unit> = withContext(Dispatchers.IO) {
        PluginRepository.uninstall(appContainer.paths, dirName)
    }

    // ---------------------------------------------------------------- 人格

    /**
     * 登录 AstrBot 后台。
     *
     * 不传密码时用设置里存的那份（默认 astrbot）——用户在人格页填过一次，
     * 其余页面就不用再填，避免「每个页面各失败一次」。
     */
    suspend fun loginAstrBot(password: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val actual = password
                ?: appContainer.settings.settings.first().astrBotPassword
            AstrBotApi().login("astrbot", actual)
        }

    suspend fun setAstrBotPassword(value: String) {
        appContainer.settings.setAstrBotPassword(value)
    }

    suspend fun listPersonas(cookie: String): Result<List<PersonaItem>> =
        withContext(Dispatchers.IO) { AstrBotApi().listPersonas(cookie) }

    /**
     * 当前真正生效的默认人格 id。
     *
     * 一般在 `agent_runner.config.persona.persona_id` 里，AstrBot 默认值叫 "default"，
     * 但用户在后台换过就会是别的 id。读不到一律退回 "default"。
     */
    suspend fun defaultPersonaId(): String = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrNull() ?: return@withContext "default"
        val body = AstrBotApi().getSystemConfig(cookie).getOrNull() ?: return@withContext "default"
        val root = body.optJSONObject("data")?.optJSONObject("config")
            ?: body.optJSONObject("config")
            ?: body.optJSONObject("data")
            ?: body
        root.optJSONObject("agent_runner")
            ?.optJSONObject("config")
            ?.optJSONObject("persona")
            ?.optString("persona_id")
            ?.takeIf { it.isNotBlank() }
            ?: "default"
    }

    /**
     * 保存人格，并从服务端读回来核对。
     *
     * AstrBot 的保存接口返回 ok 不代表内容真的写进去了（字段被忽略、写到
     * 别的人格上，都只表现为"保存成功"）。这里当面比对一次，不一致就直接
     * 报错，而不是让用户重启完发现还是老样子。
     */
    suspend fun savePersona(
        cookie: String,
        id: String,
        prompt: String,
        beginDialogs: List<String> = emptyList(),
        customErrorMessage: String? = null,
        exists: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val api = AstrBotApi()
        val dialogs = PersonaTemplates.pairsOf(beginDialogs)
        val saved = api.savePersona(
            cookie = cookie,
            id = id,
            systemPrompt = prompt,
            beginDialogs = dialogs,
            customErrorMessage = customErrorMessage,
            exists = exists,
        )
        if (saved.isFailure) return@withContext saved

        val actual = api.listPersonas(cookie).getOrNull()?.firstOrNull { it.id == id }
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "保存后找不到这条人格（$id）。AstrBot 可能拒绝了这次写入，" +
                        "去「AstrBot 后台 → 人格」看一眼。",
                ),
            )

        if (actual.systemPrompt.trim() != prompt.trim()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "人设没写进去（服务端是 ${actual.systemPrompt.trim().length} 字，" +
                        "你提交了 ${prompt.trim().length} 字）。AstrBot 没有接受这次修改。",
                ),
            )
        }
        // 示例对话条数对不上，说明服务端把 begin_dialogs 忽略了：
        // 这种情况下人会以为"改好了"，实际语气一点没变
        if (dialogs.isNotEmpty() && actual.beginDialogs.size != dialogs.size) {
            return@withContext Result.failure(
                IllegalStateException(
                    "人设保存了，但示例对话只写进去 ${actual.beginDialogs.size} 句" +
                        "（提交了 ${dialogs.size} 句）。AstrBot 的版本可能不支持这一项，" +
                        "去后台确认后再保存一次。",
                ),
            )
        }
        Result.success(Unit)
    }

    suspend fun deletePersona(cookie: String, id: String): Result<Unit> =
        withContext(Dispatchers.IO) { AstrBotApi().deletePersona(cookie, id) }

    suspend fun listConversations(
        cookie: String,
        page: Int = 1,
    ): Result<List<ConversationSummary>> =
        withContext(Dispatchers.IO) { AstrBotApi().listConversations(cookie, page = page) }

    suspend fun loadPluginConfig(
        cookie: String,
        pluginId: String,
    ): Result<PluginConfigSnapshot> = withContext(Dispatchers.IO) {
        AstrBotApi().getPluginConfig(cookie, pluginId)
    }

    suspend fun savePluginConfig(
        cookie: String,
        pluginId: String,
        config: Map<String, Any?>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        AstrBotApi().savePluginConfig(cookie, pluginId, config)
    }

    // ---------------------------------------------------------------- 备份

    suspend fun createBackup(
        onProgress: (Int, Int) -> Unit,
    ): Result<BackupResult> = withContext(Dispatchers.IO) {
        BackupRepository.create(appContainer.paths, onProgress)
    }

    suspend fun listBackups(): List<java.io.File> = withContext(Dispatchers.IO) {
        BackupRepository.list(appContainer.paths)
    }

    /**
     * 恢复备份。
     *
     * 必须先停容器：恢复开头会把 `data` 改名成 `data.previous`，而进程持有的
     * 是旧目录的 inode——容器还在跑时 rename 一样会成功，AstrBot 继续往旧目录写，
     * 结果就是“提示恢复成功，但聊天记录和插件还是旧的”。
     */
    suspend fun restoreBackup(
        file: java.io.File,
        onProgress: (Int, Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        controller.stop()
        val result = BackupRepository.restore(appContainer.paths, file, onProgress)
        // 无论成败都把容器拉起来，省得用户再点一次
        runCatching { controller.start() }
        result
    }

    fun deleteBackup(file: java.io.File): Boolean = BackupRepository.delete(file)

    fun backupDir(): java.io.File = BackupRepository.backupDir(appContainer.paths)

    /**
     * 跑一遍自检，返回逐项结果与一句总结。
     *
     * 全部读事实：容器状态、端口探活、配置文件、NapCat 登录接口、最近日志。
     */
    suspend fun diagnose(): Pair<List<CheckItem>, String> = withContext(Dispatchers.IO) {
        val items = mutableListOf<CheckItem>()
        items += Diagnostics.checkInstall(appContainer.paths)
        items += Diagnostics.checkRuntime(controller.state.value)
        items += Diagnostics.checkModel(appContainer.paths)
        items += Diagnostics.checkQq(appContainer.paths)
        items += Diagnostics.checkPlugins(appContainer.paths)
        items += Diagnostics.checkChannel(controller.logs.value)
        items to Diagnostics.summary(items)
    }

    // ---------------------------------------------------------------- 知识库

    suspend fun readEmbeddingConfig(): EmbeddingConfig? = withContext(Dispatchers.IO) {
        runCatching { KnowledgeBaseRepository.readEmbeddingConfig(appContainer.paths) }.getOrNull()
    }

    suspend fun saveEmbeddingConfig(
        apiBase: String,
        apiKey: String,
        model: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        KnowledgeBaseRepository.saveEmbeddingConfig(appContainer.paths, apiBase, apiKey, model)
    }

    suspend fun listKnowledgeBases(cookie: String): Result<List<KnowledgeBase>> =
        withContext(Dispatchers.IO) { KnowledgeBaseRepository.list(cookie) }

    suspend fun createKnowledgeBase(
        cookie: String,
        name: String,
        embeddingProviderId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        KnowledgeBaseRepository.create(cookie, name, embeddingProviderId)
    }

    suspend fun deleteKnowledgeBase(cookie: String, kbId: String): Result<Unit> =
        withContext(Dispatchers.IO) { KnowledgeBaseRepository.delete(cookie, kbId) }

    suspend fun listKnowledgeDocuments(
        cookie: String,
        kbId: String,
    ): Result<List<KnowledgeDocument>> = withContext(Dispatchers.IO) {
        KnowledgeBaseRepository.listDocuments(cookie, kbId)
    }

    suspend fun uploadKnowledgeDocument(
        cookie: String,
        kbId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        KnowledgeBaseRepository.uploadDocument(cookie, kbId, fileName, mimeType, bytes)
    }

    suspend fun deleteKnowledgeDocument(
        cookie: String,
        kbId: String,
        documentId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        KnowledgeBaseRepository.deleteDocument(cookie, kbId, documentId)
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

    fun setOfflineAlert(value: Boolean) {
        viewModelScope.launch { settingsRepository.setOfflineAlert(value) }
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

    /**
     * 生成诊断包：日志尾部 + 脱敏后的配置 + 环境信息。
     *
     * 只读操作，不碰容器；导出失败不影响正在运行的机器人。
     */
    suspend fun exportDiagnostics(): Result<File> = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val target = File(
            File(context.cacheDir, "diagnostics"),
            "AstrMobile-diagnostics-${System.currentTimeMillis()}.zip",
        )
        val meta = mapOf(
            "应用版本" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "机型" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "容器状态" to describeContainer(containerState.value),
        )
        DiagnosticsExporter.export(appContainer.paths, target, meta)
    }

    private fun describeContainer(state: ContainerState): String = when (state) {
        ContainerState.NotInstalled -> "未安装"
        ContainerState.Stopped -> "已停止"
        ContainerState.Starting -> "启动中"
        ContainerState.Stopping -> "停止中"
        is ContainerState.Running ->
            "运行中（AstrBot=${state.astrbotReady}, NapCat=${state.napcatReady}）"
        is ContainerState.Restarting ->
            "重启中（${state.attempt}/${state.maxAttempts}，${state.lastReason}）"
        is ContainerState.Failed -> "失败：${state.reason}"
    }

    private val _usage = MutableStateFlow<UsageSnapshot?>(null)
    val usage: StateFlow<UsageSnapshot?> = _usage.asStateFlow()

    private val _usageError = MutableStateFlow<String?>(null)
    val usageError: StateFlow<String?> = _usageError.asStateFlow()

    private var usageFetchedAt = 0L

    /**
     * 刷新用量。
     *
     * 60 秒内重复调用直接返回——控制台每次回到前台都会触发一次，
     * 不设节流等于每切一次页面就发一个请求。
     */
    suspend fun refreshUsage(force: Boolean = false) {
        // 容器没跑就别发请求了，但必须给一句人话。
        // 以前这里直接 return，界面会永远停在 "¥ …" —— 用户看到的是
        // 「点了没反应」，而不是「还没启动」，这才是"用量搞不起来"的来源。
        // 这一步放在节流之前：状态变化必须立刻反映到界面上。
        when (containerState.value) {
            is ContainerState.NotInstalled -> {
                _usageError.value = "环境还没装好"
                return
            }

            is ContainerState.Stopped -> {
                _usageError.value = "容器没在运行，启动后才有数据"
                return
            }

            is ContainerState.Failed -> {
                _usageError.value = "容器上次启动失败，修好才看得到用量"
                return
            }

            else -> Unit
        }

        val now = System.currentTimeMillis()
        if (!force && now - usageFetchedAt < 60_000) return

        val cookie = loginAstrBot().getOrElse {
            _usageError.value = it.message ?: "登录后台失败，容器在运行吗？"
            return
        }

        AstrBotApi().providerTokenStats(cookie, days = 1)
            .onSuccess {
                _usage.value = UsageSnapshot.parse(it, days = 1)
                _usageError.value = null
                usageFetchedAt = now
            }
            .onFailure { _usageError.value = it.message ?: "读取用量失败" }
    }

    // ---------------------------------------------------------------- 定时任务

    private val _cronJobs = MutableStateFlow<List<CronJob>>(emptyList())
    val cronJobs: StateFlow<List<CronJob>> = _cronJobs.asStateFlow()

    private val _cronBusy = MutableStateFlow(false)
    val cronBusy: StateFlow<Boolean> = _cronBusy.asStateFlow()

    private val _cronError = MutableStateFlow<String?>(null)
    val cronError: StateFlow<String?> = _cronError.asStateFlow()

    suspend fun refreshCronJobs() {
        val cookie = loginAstrBot().getOrElse {
            _cronError.value = it.message ?: "登录后台失败，容器在运行吗？"
            return
        }

        _cronBusy.value = true
        AstrBotApi().listCronJobs(cookie)
            .onSuccess {
                _cronJobs.value = it
                _cronError.value = null
            }
            .onFailure { _cronError.value = it.message ?: "读取定时任务失败" }
        _cronBusy.value = false
    }

    suspend fun createCronJob(
        name: String,
        cronExpression: String,
        session: String,
        note: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }
        val result = AstrBotApi().createCronJob(cookie, name, cronExpression, session, note)
        if (result.isSuccess) refreshCronJobs()
        result
    }

    suspend fun toggleCronJob(job: CronJob, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }
            val result = AstrBotApi().setCronEnabled(cookie, job.id, enabled)
            if (result.isSuccess) refreshCronJobs()
            result
        }

    /**
     * 修改任务：先删再建。
     *
     * 不用 PATCH：AstrBot 的更新接口对 `note` / `session` 的合并规则依版本而变
     * （有时要求包在 `payload` 里），删了重建是唯一行为确定的做法。任务 id 会变，
     * 但用户看不到 id，也没有外部引用它。
     */
    suspend fun updateCronJob(
        original: CronJob,
        name: String,
        cronExpression: String,
        session: String,
        note: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }

        val removed = AstrBotApi().deleteCronJob(cookie, original.id)
        if (removed.isFailure) return@withContext removed

        val created = AstrBotApi().createCronJob(
            cookie = cookie,
            name = name,
            cronExpression = cronExpression,
            session = session,
            note = note,
            // 编辑不该把停用的任务悄悄打开
            enabled = original.enabled,
        )
        if (created.isSuccess) refreshCronJobs()
        created
    }

    suspend fun deleteCronJob(job: CronJob): Result<Unit> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }
        val result = AstrBotApi().deleteCronJob(cookie, job.id)
        if (result.isSuccess) refreshCronJobs()
        result
    }

    suspend fun runCronJobNow(job: CronJob): Result<Unit> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }
        AstrBotApi().runCronNow(cookie, job.id)
    }

    /**
     * 可选的目标会话（UMO + 标题）。
     *
     * 让用户手写 `aiocqhttp:GroupMessage:123456` 不现实，从最近的会话里挑一个
     * 才是手机上该有的交互。取不到就返回空列表，界面退化为手动输入。
     */
    suspend fun cronSessionChoices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val cookie = loginAstrBot().getOrNull() ?: return@withContext emptyList()
        AstrBotApi().listConversations(cookie, pageSize = 50)
            .getOrDefault(emptyList())
            .mapNotNull { conversation ->
                conversation.umo.takeIf { it.isNotBlank() }
                    ?.let { umo -> umo to conversation.title }
            }
            .distinctBy { it.first }
    }

    // ---------------------------------------------------------------- 试聊

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()

    /** 当前试聊会话。为空时下一条消息会自动新建一个。 */
    private var chatSessionId: String? = null

    /**
     * 开始一段新对话。
     *
     * 只清空本地列表，实际会话 id 在下次发消息时惰性创建——用户点进来
     * 又马上退出的话，不该在后台留一堆空会话。
     */
    fun resetChat() {
        _chatMessages.value = emptyList()
        chatSessionId = null
    }

    /**
     * 发一条消息并流式接收回复。
     *
     * 先插入用户消息和一条空的机器人消息，然后随着 SSE 事件不断替换
     * 最后这条的内容。这样用户能立刻看到自己说的话和自己的提问，
     * 而不是等模型开始输出才有反馈。
     */
    suspend fun sendChat(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _chatBusy.value) return

        val cookie = loginAstrBot().getOrElse { error ->
            _chatMessages.value = _chatMessages.value + ChatMessage(
                role = ChatMessage.Role.Bot,
                text = "",
                error = error.message ?: "登录后台失败，容器在运行吗？",
            )
            return
        }

        // 没有会话就先建一个。放在这里而不是进入页面时，
        // 是为了避免"只是点进来看看"也留下一个空会话。
        val sessionId = chatSessionId ?: AstrBotApi().newChatSession(cookie)
            .getOrElse { error ->
                _chatMessages.value = _chatMessages.value + ChatMessage(
                    role = ChatMessage.Role.Bot,
                    text = "",
                    error = error.message ?: "新建会话失败",
                )
                return
            }
            .also { chatSessionId = it }

        _chatBusy.value = true
        _chatMessages.value = _chatMessages.value +
            ChatMessage(role = ChatMessage.Role.User, text = content) +
            ChatMessage(role = ChatMessage.Role.Bot, text = "", streaming = true)

        AstrBotApi().sendChatMessage(cookie, sessionId, content) { state ->
            updateLastBotMessage {
                it.copy(
                    text = state.text,
                    thinking = state.thinking,
                    streaming = !state.done,
                    error = state.error,
                )
            }
        }.onFailure { error ->
            updateLastBotMessage {
                it.copy(
                    streaming = false,
                    error = it.error ?: error.message ?: "发送失败",
                )
            }
        }

        // 不管成功失败，都要把 streaming 复位，否则光标一直闪
        updateLastBotMessage { it.copy(streaming = false) }
        _chatBusy.value = false
    }

    private fun updateLastBotMessage(transform: (ChatMessage) -> ChatMessage) {
        val current = _chatMessages.value
        val index = current.indexOfLast { it.role == ChatMessage.Role.Bot }
        if (index < 0) return
        _chatMessages.value = current.toMutableList().also { it[index] = transform(it[index]) }
    }

    // ---------------------------------------------------------------- 行为配置

    private val _behavior = MutableStateFlow<BehaviorSettings?>(null)
    val behavior: StateFlow<BehaviorSettings?> = _behavior.asStateFlow()

    private val _behaviorError = MutableStateFlow<String?>(null)
    val behaviorError: StateFlow<String?> = _behaviorError.asStateFlow()

    private val _behaviorBusy = MutableStateFlow(false)
    val behaviorBusy: StateFlow<Boolean> = _behaviorBusy.asStateFlow()

    suspend fun loadBehavior() {
        _behaviorBusy.value = true
        val cookie = loginAstrBot().getOrElse {
            _behaviorError.value = it.message ?: "登录后台失败，容器在运行吗？"
            _behaviorBusy.value = false
            return
        }

        AstrBotApi().readSystemConfig(cookie)
            .onSuccess {
                _behavior.value = BehaviorConfig.read(it)
                _behaviorError.value = null
            }
            .onFailure { _behaviorError.value = it.message ?: "读取配置失败" }
        _behaviorBusy.value = false
    }

    /**
     * 保存行为配置。
     *
     * 保存前重新读一次完整配置，而不是复用刚进页面时缓存的副本：
     * `PUT /system-config` 是整份替换，如果这期间用户在网页后台改过别的
     * 设置（比如换了模型、加了插件），用旧副本写回会把那些改动冲掉。
     * 多一次 GET 换这个保证，值。
     */
    suspend fun saveBehavior(settings: BehaviorSettings): Result<String> =
        withContext(Dispatchers.IO) {
            val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }

            _behaviorBusy.value = true
            try {
                val config = AstrBotApi().readSystemConfig(cookie)
                    .getOrElse {
                        _behaviorError.value = it.message ?: "读取配置失败"
                        return@withContext Result.failure(it)
                    }

                if (!BehaviorConfig.apply(config, settings)) {
                    return@withContext Result.success("没有改动")
                }

                AstrBotApi().writeSystemConfig(cookie, config)
                    .onSuccess {
                        _behavior.value = settings
                        _behaviorError.value = null
                    }
                    .onFailure { _behaviorError.value = it.message ?: "保存失败" }
            } finally {
                _behaviorBusy.value = false
            }
        }

    // ---------------------------------------------------------------- 用量详情

    private val _usageDetail = MutableStateFlow<UsageSnapshot?>(null)
    val usageDetail: StateFlow<UsageSnapshot?> = _usageDetail.asStateFlow()

    private val _usageDetailBusy = MutableStateFlow(false)
    val usageDetailBusy: StateFlow<Boolean> = _usageDetailBusy.asStateFlow()

    private val _usageDetailError = MutableStateFlow<String?>(null)
    val usageDetailError: StateFlow<String?> = _usageDetailError.asStateFlow()

    private var usageDetailDays = 0

    /**
     * 拉一段时间（7 / 30 天）的用量。
     *
     * 和首屏那个"今日"分开存：两边的 days 不同，共用一个 StateFlow 会导致
     * 从详情页返回时首屏数字突然变成 7 天合计。
     */
    suspend fun refreshUsageDetail(days: Int, force: Boolean = false) {
        if (!force && days == usageDetailDays && _usageDetail.value != null) return
        usageDetailDays = days

        _usageDetailBusy.value = true
        val cookie = loginAstrBot().getOrElse {
            _usageDetailError.value = it.message ?: "登录后台失败，容器在运行吗？"
            _usageDetailBusy.value = false
            return
        }

        AstrBotApi().providerTokenStats(cookie, days = days)
            .onSuccess {
                _usageDetail.value = UsageSnapshot.parse(it, days = days)
                _usageDetailError.value = null
            }
            .onFailure { _usageDetailError.value = it.message ?: "读取用量失败" }
        _usageDetailBusy.value = false
    }

    // ---------------------------------------------------------------- 会话管理

    suspend fun renameConversation(
        conversation: ConversationSummary,
        title: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("标题不能为空"))
        }
        val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }
        AstrBotApi().renameConversation(cookie, conversation.cid, conversation.userId, trimmed)
    }

    suspend fun deleteConversation(conversation: ConversationSummary): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cookie = loginAstrBot().getOrElse { return@withContext Result.failure(it) }
            AstrBotApi().deleteConversation(cookie, conversation.cid, conversation.userId)
        }

    suspend fun currentSettings(): AppSettings = settings.value

    override fun onCleared() {
        super.onCleared()
        // 进程存活期间容器继续跑，由前台服务负责保活，这里不主动停止。
    }
}
