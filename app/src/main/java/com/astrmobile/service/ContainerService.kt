package com.astrmobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.astrmobile.AstrMobileApp
import com.astrmobile.MainActivity
import com.astrmobile.R
import com.astrmobile.core.container.ContainerState
import com.astrmobile.core.data.AppSettings
import com.astrmobile.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 容器保活服务。
 *
 * 这是"手机上跑机器人"能否可用的分水岭：没有前台服务，
 * 息屏几分钟后系统就会把 proot 进程回收，QQ 直接掉线。
 */
class ContainerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observeJob: Job? = null
    private lateinit var appContainer: AppContainer

    override fun onCreate() {
        super.onCreate()
        appContainer = (application as AstrMobileApp).container
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("正在启动容器…"))

        // 系统重启服务时 intent 为 null：只要用户没有主动停止（哨兵不存在），就恢复容器，
        // 否则 START_STICKY 只是把空壳服务拉起来，机器人仍然是死的。
        val shouldStart = intent?.action == ACTION_START_CONTAINER ||
            (intent == null && !appContainer.paths.stopSentinel.exists() && appContainer.bootstrapper.isReady())

        scope.launch {
            // DataStore 读取失败不应该让整个服务起不来，退回默认配置继续启动
            val settings = runCatching { appContainer.settings.settings.first() }
                .getOrElse { AppSettings() }
            if (settings.keepAwake) acquireWakeLock()
            if (shouldStart) {
                // 走 Service 启动时同样要应用用户配置的镜像源，否则会退回默认值
                appContainer.controller.aptMirror = settings.aptMirror
                appContainer.controller.start()
            }
        }
        observeState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        observeJob?.cancel()
        observeJob = scope.launch {
            // 同时跟随"保持唤醒"开关：用户关掉后要真正释放已有的 WakeLock
            launch {
                appContainer.settings.settings.collectLatest { settings ->
                    if (settings.keepAwake) acquireWakeLock() else releaseWakeLock()
                }
            }
            appContainer.controller.state.collectLatest { state ->
                val text = when (state) {
                    is ContainerState.NotInstalled -> "环境未安装"
                    is ContainerState.Stopped -> "已停止"
                    is ContainerState.Starting -> "正在启动…"
                    is ContainerState.Stopping -> "正在停止…"
                    is ContainerState.Failed -> "运行异常"
                    is ContainerState.Running -> buildString {
                        append("运行中 · AstrBot ")
                        append(if (state.astrbotReady) "在线" else "启动中")
                        append(" · QQ ")
                        append(if (state.napcatReady) "在线" else "启动中")
                    }
                    is ContainerState.Restarting ->
                        "异常退出 · ${state.delaySeconds}s 后自动重启（${state.attempt}/${state.maxAttempts}）"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "机器人运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持 AstrBot 与 QQ 协议端在后台持续运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AstrMobile · $status")
            .setContentText("点按返回控制台；机器人由 App 管理")
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AstrMobile::ContainerWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "container_status"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_CONTAINER = "com.astrmobile.action.START_CONTAINER"

        fun start(context: Context, alsoStartContainer: Boolean) {
            val intent = Intent(context, ContainerService::class.java).apply {
                if (alsoStartContainer) action = ACTION_START_CONTAINER
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ContainerService::class.java))
        }
    }
}
