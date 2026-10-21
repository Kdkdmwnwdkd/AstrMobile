package com.astrmobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.astrmobile.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 截屏结果的中转站：Service 抓完图，页面订阅取走。 */
object ScreenCaptureBus {
    private val _result = MutableStateFlow<Result<Bitmap>?>(null)
    val result: StateFlow<Result<Bitmap>?> = _result.asStateFlow()

    fun publish(value: Result<Bitmap>?) {
        _result.value = value
    }
}

/**
 * 只截一帧的前台服务。
 *
 * Android 14 起 MediaProjection 必须挂在前台服务上（类型 mediaProjection），
 * 直接在 Activity 里开 VirtualDisplay 会抛 SecurityException。
 * 截完立刻停服务，不常驻。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (data == null || resultCode == 0) {
            ScreenCaptureBus.publish(Result.failure(IllegalStateException("没有拿到录屏授权")))
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching { capture(resultCode, data) }.onFailure {
            ScreenCaptureBus.publish(Result.failure(it))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun capture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)
            ?: error("系统没有返回录屏会话")
        projection = mediaProjection

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader

        val handlerThread = HandlerThread("screen-capture").also { it.start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "astrmobile-screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )

        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            if (image == null) return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride

                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                if (cropped !== padded) padded.recycle()

                ScreenCaptureBus.publish(Result.success(cropped))
            } catch (error: Throwable) {
                ScreenCaptureBus.publish(Result.failure(error))
            } finally {
                runCatching { image.close() }
                release()
                stopSelf()
            }
        }, handler)
    }

    private fun release() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { thread?.quitSafely() }
        thread = null
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("正在截取屏幕")
        .setContentText("只截一张，用完即停")
        .setSmallIcon(R.drawable.ic_stat_bot)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "截屏", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1002
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
