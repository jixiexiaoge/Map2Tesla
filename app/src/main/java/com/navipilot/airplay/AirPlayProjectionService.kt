package com.navipilot.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jixiexiaoge.drivingassist.MainActivity
import com.jixiexiaoge.drivingassist.R
import com.navipilot.amap2tesla.ConnectionMode
import com.navipilot.amap2tesla.audio.LatestPcmStore
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.navipilot.amap2tesla.server.ControlSink
import com.navipilot.amap2tesla.server.LocalProjectionServer
import com.navipilot.amap2tesla.server.ProjectionServerStatus
import com.navipilot.amap2tesla.video.LatestFrameStore
import com.navipilot.amap2tesla.video.AirPlayFrameRenderer
import com.navipilot.amap2tesla.video.MjpegEncoder
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.service.ProtocolState

class AirPlayProjectionService : Service() {
    private val frameStore = LatestFrameStore()
    private val audioStore = LatestPcmStore()
    private var encoder: MjpegEncoder? = null
    private var frameRenderer: AirPlayFrameRenderer? = null
    private var receiver: AirPlayReceiver? = null
    private var server: LocalProjectionServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var browserMode = ConnectionMode.TESLA
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("正在准备 AirPlay 镜像接收"))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:airplay")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RECEIVER -> stopSelf()
            ACTION_STOP_BROWSER -> stopBrowserProjection("已停止车机浏览器投屏")
            ACTION_START_BROWSER -> startBrowserProjection(
                mode = intent.getStringExtra(EXTRA_CONNECTION_MODE)
                    ?.let { ConnectionMode.entries.firstOrNull { mode -> mode.name == it } }
                    ?: ConnectionMode.TESLA,
            )
            ACTION_START_RECEIVER, null -> startReceiver()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        stopBrowserProjection("AirPlay 镜像接收已停止")
        runCatching { receiver?.stop() }
        receiver = null
        runCatching { frameRenderer?.close() }
        frameRenderer = null
        runCatching { encoder?.close() }
        encoder = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        AirPlayProjectionState.reset(
            AirPlayProjectionState.status.takeIf { it.startsWith("错误：") } ?: "AirPlay 镜像接收已停止",
        )
        super.onDestroy()
    }

    private fun startReceiver() {
        if (receiver != null) return
        try {
            frameStore.clear()
            audioStore.clear()
            encoder = MjpegEncoder(
                width = MIRROR_WIDTH,
                height = MIRROR_HEIGHT,
                frameStore = frameStore,
                fps = BROWSER_FPS,
                quality = JPEG_QUALITY,
            )
            frameRenderer = AirPlayFrameRenderer(
                width = MIRROR_WIDTH,
                height = MIRROR_HEIGHT,
                outputSurface = encoder!!.surface,
                targetFps = BROWSER_FPS,
            )
            AirPlayProjectionState.receiverRunning = true
            AirPlayProjectionState.connected = false
            AirPlayProjectionState.status = "正在等待 iPhone、iPad 或 Mac 屏幕镜像"
            updateNotification(AirPlayProjectionState.status)

            receiver = AirPlayReceiver(
                context = applicationContext,
                displayName = RECEIVER_NAME,
                mirrorWidth = MIRROR_WIDTH,
                mirrorHeight = MIRROR_HEIGHT,
                audioEnabled = true,
                pinAuthEnabled = false,
                videoSurfaceProvider = { frameRenderer?.inputSurface },
                onStateChanged = ::onReceiverStateChanged,
                onSenderNameChanged = { name ->
                    AirPlayProjectionState.senderName = name.ifBlank { null }
                },
                onActualNameRegistered = { name ->
                    AirPlayProjectionState.receiverName = name
                },
                onVideoOutputSizeChanged = { width, height ->
                    frameRenderer?.setSourceSize(width, height)
                },
                onPcmAudio = { pcm, sampleRate, channels ->
                    audioStore.publish(pcm, sampleRate, channels)
                },
                onPinChanged = { code ->
                    AirPlayProjectionState.pairingCode = code
                    if (code != null) {
                        AirPlayProjectionState.status = "请在发送设备输入验证码"
                        updateNotification(AirPlayProjectionState.status)
                    }
                },
            ).also { it.start() }
        } catch (error: Throwable) {
            fail("AirPlay 镜像接收启动失败：${error.rootMessage()}")
        }
    }

    private fun onReceiverStateChanged(state: ProtocolState) {
        if (destroyed) return
        Log.i(TAG, "Receiver state=$state browserRunning=${server != null}")
        when (state) {
            ProtocolState.ADVERTISING -> {
                AirPlayProjectionState.receiverRunning = true
                AirPlayProjectionState.connected = false
                AirPlayProjectionState.senderName = null
                AirPlayProjectionState.pairingCode = null
                frameStore.clear()
                audioStore.clear()
                AirPlayProjectionState.status = "等待 AirPlay 设备连接，选择「${AirPlayProjectionState.receiverName}」即可显示"
                updateNotification(AirPlayProjectionState.status)
            }
            ProtocolState.CONNECTED -> {
                AirPlayProjectionState.receiverRunning = true
                AirPlayProjectionState.connected = true
                AirPlayProjectionState.pairingCode = null
                AirPlayProjectionState.status = if (server != null) {
                    "AirPlay 设备已连接，画面将自动显示到车机浏览器"
                } else {
                    "AirPlay 设备已连接，浏览器入口尚未开启"
                }
                updateNotification(AirPlayProjectionState.status)
            }
            ProtocolState.ERROR -> fail("AirPlay 镜像连接异常")
            ProtocolState.DISABLED -> {
                AirPlayProjectionState.receiverRunning = false
                AirPlayProjectionState.connected = false
                AirPlayProjectionState.pairingCode = null
                stopBrowserProjection("AirPlay 接收器已关闭")
            }
        }
    }

    @Synchronized
    private fun startBrowserProjection(
        mode: ConnectionMode,
    ) {
        if (server != null) return
        startReceiver()
        if (receiver == null) return
        if (mode == ConnectionMode.TESLA && !TeslaVirtualIpService.isReady()) {
            AirPlayProjectionState.status = "Tesla 固定访问地址尚未就绪"
            updateNotification(AirPlayProjectionState.status)
            return
        }

        try {
            browserMode = mode
            val newServer = LocalProjectionServer.startWithFallback(
                context = this,
                frameStore = frameStore,
                audioStore = audioStore,
                controlSink = object : ControlSink {
                    override val interactive = false

                    override fun onPointer(
                        gestureId: Int,
                        action: Int,
                        x: Float,
                        y: Float,
                        pointerId: Int,
                    ) = false

                    override fun onCancel(gestureId: Int) = Unit

                    override fun onKey(keyCode: Int) = false

                    override fun onViewport(width: Int, height: Int) = false
                },
                statusProvider = {
                    ProjectionServerStatus(
                        running = AirPlayProjectionState.browserProjectionRunning,
                        message = AirPlayProjectionState.status,
                        displayId = null,
                        width = MIRROR_WIDTH,
                        height = MIRROR_HEIGHT,
                        densityDpi = 0,
                        targetFps = BROWSER_FPS,
                        trialMode = AirPlayProjectionState.trialMode,
                        interactive = false,
                        waitingForSender = AirPlayProjectionState.waitingForSender,
                        receiverName = AirPlayProjectionState.receiverName,
                    )
                },
            )
            server = newServer
            AirPlayProjectionState.connectionMode = mode
            AirPlayProjectionState.accessUrl = newServer.accessUrl(mode)
            AirPlayProjectionState.browserProjectionRunning = true
            AirPlayProjectionState.status = if (AirPlayProjectionState.connected) {
                "AirPlay 设备已连接，画面将自动显示到车机浏览器"
            } else {
                "浏览器入口已就绪，等待 AirPlay 设备连接"
            }
            updateNotification(AirPlayProjectionState.status)
        } catch (error: Throwable) {
            fail("浏览器投屏启动失败：${error.rootMessage()}")
        }
    }

    @Synchronized
    private fun stopBrowserProjection(status: String) {
        val wasRunning = server != null
        runCatching { server?.stop() }
        server = null
        AirPlayProjectionState.browserProjectionRunning = false
        AirPlayProjectionState.accessUrl = null
        if (wasRunning && browserMode == ConnectionMode.TESLA) {
            TeslaVirtualIpService.stop(this)
        }
        if (!destroyed) {
            AirPlayProjectionState.status = status
            updateNotification(status)
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        AirPlayProjectionState.status = "错误：$message"
        updateNotification(AirPlayProjectionState.status)
        stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AirPlay 镜像",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(content: String): Notification {
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AirPlayProjectionService::class.java).setAction(ACTION_STOP_RECEIVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(activityIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(content))
    }

    companion object {
        private const val TAG = "AirPlayProjection"
        private const val CHANNEL_ID = "navipilot_airplay"
        private const val NOTIFICATION_ID = 7103
        private const val ACTION_START_RECEIVER =
            "com.jixiexiaoge.drivingassist.AIRPLAY_START_RECEIVER"
        private const val ACTION_STOP_RECEIVER =
            "com.jixiexiaoge.drivingassist.AIRPLAY_STOP_RECEIVER"
        private const val ACTION_START_BROWSER =
            "com.jixiexiaoge.drivingassist.AIRPLAY_START_BROWSER"
        private const val ACTION_STOP_BROWSER =
            "com.jixiexiaoge.drivingassist.AIRPLAY_STOP_BROWSER"
        private const val EXTRA_CONNECTION_MODE = "connection_mode"
        private const val RECEIVER_NAME = "特投屏 AirPlay"
        private const val MIRROR_WIDTH = 1280
        private const val MIRROR_HEIGHT = 720
        private const val BROWSER_FPS = 20
        private const val JPEG_QUALITY = 60

        fun startReceiver(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AirPlayProjectionService::class.java).setAction(ACTION_START_RECEIVER),
            )
        }

        fun startBrowserProjection(
            context: Context,
            mode: ConnectionMode,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AirPlayProjectionService::class.java)
                    .setAction(ACTION_START_BROWSER)
                    .putExtra(EXTRA_CONNECTION_MODE, mode.name),
            )
        }

        fun stopBrowserProjection(context: Context) {
            context.startService(
                Intent(context, AirPlayProjectionService::class.java).setAction(ACTION_STOP_BROWSER),
            )
        }


        fun stopReceiver(context: Context) {
            context.stopService(Intent(context, AirPlayProjectionService::class.java))
        }
    }
}

private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: current.javaClass.simpleName
}
