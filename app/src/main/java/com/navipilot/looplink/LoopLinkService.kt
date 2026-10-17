package com.navipilot.looplink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.jixiexiaoge.drivingassist.MainActivity
import com.navipilot.amap2tesla.video.LatestFrameStore
import com.navipilot.amap2tesla.video.MjpegEncoder
import com.navipilot.amap2tesla.video.AirPlayFrameRenderer
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

@Suppress("TooManyFunctions")
class LoopLinkService : Service() {
    private var controller: CarPlayController? = null
    private var mediaSink: AndroidMediaSink? = null
    private var frameRenderer: AirPlayFrameRenderer? = null
    private var encoder: MjpegEncoder? = null
    private var browser: LoopLinkBrowserServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var displayProfile = LoopLinkDisplayProfile.TESLA
    @Volatile private var lastVideoErrorAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        } else if (controller == null) {
            startForeground(NOTIFICATION_ID, notification("正在启动 LoopLink 接收器"))
            try {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:usb-carplay")
                    .apply { acquire() }
                LoopLinkState.clearDiagnostics()
                recordDiagnostic("LoopLink USB CarPlay 服务正在启动")
                startReceiver()
            } catch (error: Exception) {
                Log.e(TAG, "LoopLink startup failed", error)
                updateStatus("启动失败：${error.message ?: error.javaClass.simpleName}")
                closeResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return if (intent?.action == ACTION_STOP) START_NOT_STICKY else START_STICKY
    }

    private fun startReceiver() {
        val identityFile = File(noBackupFilesDir, LocalMfiIdentityStore.FILE_NAME)
        check(identityFile.isFile) { "请先在 CarPlay 设置中导入本地 MFi 认证文件" }
        recordDiagnostic("已找到应用私有目录中的本地 MFi 身份容器")
        check(VpnService.prepare(this) == null) { "尚未授予 VPN 权限，请返回页面重新启动" }
        recordDiagnostic("VPN 授权已就绪；使用 wired NCM + Tesla 单隧道")

        displayProfile = LoopLinkDisplaySettings.load(this)
        recordDiagnostic("USB 独立显示配置：${displayProfile.width}×${displayProfile.height}；不使用宿主缩放")
        val frameStore = LatestFrameStore()
        startBrowserServer(frameStore)
        val newMedia = createMediaEngine(frameStore)
        publishAccessUrls()
        startController(identityFile, newMedia)
        updateNotification(LoopLinkState.status)
    }

    private fun createMediaEngine(frameStore: LatestFrameStore): CarPlayMediaEngine {
        val newEncoder = MjpegEncoder(
            width = displayProfile.width,
            height = displayProfile.height,
            frameStore = frameStore,
            fps = 15,
            quality = 80,
            onFirstFramePublished = { size ->
                updateStatus("浏览器视频帧已就绪（${size} 字节 JPEG）")
            },
            onEncodingError = ::reportVideoError,
        )
        encoder = newEncoder
        val newFrameRenderer = AirPlayFrameRenderer(
            width = displayProfile.width,
            height = displayProfile.height,
            outputSurface = newEncoder.surface,
            targetFps = 15,
            onFirstFrameRendered = {
                updateStatus("CarPlay 视频画面已转换，正在生成浏览器帧")
            },
            onRenderError = ::reportVideoError,
        )
        frameRenderer = newFrameRenderer
        val newMediaSink = AndroidMediaSink(
            surface = newFrameRenderer.inputSurface,
            videoWidth = displayProfile.width,
            videoHeight = displayProfile.height,
            preferSoftwareHevcDecoder = AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this),
            advancedAudioChannelMapping = false,
            onVideoStateChanged = ::updateStatus,
            onVideoError = ::reportVideoError,
            onFirstVideoFrameRendered = {
                updateStatus("CarPlay 解码器已输出首帧，正在转换浏览器画面")
            },
            mediaTap = browser,
        )
        mediaSink = newMediaSink
        return CarPlayMediaEngine(
            sink = newMediaSink,
            microphoneEnabled = false,
        )
    }

    private fun startBrowserServer(frameStore: LatestFrameStore) {
        val newBrowser = LoopLinkBrowserServer(
            frames = frameStore,
            sendTouch = { contacts -> controller?.sendTouch(contacts) ?: false },
            page = assets.open("usb_carplay_player.html").bufferedReader().use { it.readText() },
            profile = displayProfile,
            requestKeyFrame = { mediaSink?.requestKeyFrame(110) },
            onDiagnostic = ::recordDiagnostic,
        )
        browser = newBrowser
        newBrowser.start(8_000, false)
    }

    private fun publishAccessUrls() {
        LoopLinkState.running = true
        LoopLinkState.status = "网页服务已启动，等待 USB iPhone 连接"
        LoopLinkState.lanUrl = localIpv4()?.let { "http://$it:${LoopLinkBrowserServer.PORT}" }
        LoopLinkState.teslaUrl =
            "http://${TeslaVirtualIpService.CLIENT_ADDRESS}:${LoopLinkBrowserServer.PORT}"
    }

    private fun startController(identityFile: File, media: CarPlayMediaEngine) {
        val display = displayProfile.display()
        val airPlayConfig = AirPlayConfig(
            deviceName = "xcertplay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            hevc = AirPlayPersistence.loadHevcEnabled(this),
            disableAudioOutput = false,
            microphone = false,
            manufacturer = "Navipilot",
            model = "Navipilot",
            oemLabel = "Navipilot",
        )
        val runtimeConfig = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            localMfiIdentityFile = identityFile,
            identification = Iap2IdentificationConfig(
                name = "Navipilot",
                modelIdentifier = "Navipilot",
                manufacturer = "Navipilot",
                serialNumber = "Navipilot",
                firmwareVersion = "1.0.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
            ),
            label = "Navipilot",
            hostName = "Navipilot",
            wiredVpnIncludesTeslaAddress = true,
        )
        val pairings = AirPlayPersistence.loadPairings(this) { identifier, key ->
            AirPlayPersistence.savePairing(this, identifier, key)
        }
        controller = CarPlayController(
            context = applicationContext,
            config = runtimeConfig,
            airPlayConfig = airPlayConfig,
            identity = AirPlayPersistence.loadIdentity(this),
            pairings = pairings,
            listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) =
                    updateStatus("iPhone CarPlay 已连接；可在浏览器中查看并触控")

                override fun onSessionEnded(session: AirPlaySession) =
                    updateStatus("CarPlay 连接已断开，等待 iPhone 重连")

                override fun onTransportError(message: String) =
                    updateStatus("USB/CarPlay 连接错误：$message")

                override fun onDebugLog(message: String) = recordDiagnostic(message)
            },
            media = media,
            reportStatus = ::reportCarPlayStatus,
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { AirPlayPersistence.saveLockdownRecord(this, it) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
        ).also { it.start() }
    }

    private fun reportCarPlayStatus(status: CarPlayStatus) {
        val message = (status as? CarPlayStatus.Failed)?.let { "CarPlay 错误：${it.message}" }
            ?: STATUS_MESSAGES[status]
            ?: "CarPlay 准备中：${status.javaClass.simpleName}"
        updateStatus(message)
    }

    private fun updateStatus(message: String) {
        LoopLinkState.status = message
        recordDiagnostic(message)
        updateNotification(message)
    }

    private fun recordDiagnostic(message: String) {
        LoopLinkState.addDiagnostic(message)
    }

    private fun reportVideoError(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastVideoErrorAt < VIDEO_ERROR_INTERVAL_MS) return
        lastVideoErrorAt = now
        updateStatus(message)
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LoopLink", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                this,
                PENDING_INTENT_REQUEST_CODE,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle("LoopLink CarPlay")
        builder.setContentText(message.take(NOTIFICATION_TEXT_LIMIT))
        builder.setSmallIcon(android.R.drawable.ic_menu_view)
        if (pendingIntent != null) builder.setContentIntent(pendingIntent)
        builder.setOngoing(true)
        return builder.build()
    }

    private fun localIpv4(): String? =
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress && !it.isLinkLocalAddress &&
                        it.hostAddress != TeslaVirtualIpService.CLIENT_ADDRESS
                }
                ?.hostAddress
        }.getOrNull()

    override fun onDestroy() {
        closeResources()
        LoopLinkState.running = false
        LoopLinkState.status = "LoopLink 已停止"
        LoopLinkState.lanUrl = null
        LoopLinkState.teslaUrl = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun closeResources() {
        runCatching { browser?.stop() }
        browser = null
        runCatching { controller?.close() }
        controller = null
        runCatching { mediaSink?.close() }
        mediaSink = null
        runCatching { frameRenderer?.close() }
        frameRenderer = null
        runCatching { encoder?.close() }
        encoder = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val ACTION_STOP = "com.navipilot.looplink.STOP"
        private const val CHANNEL_ID = "navipilot_looplink"
        private const val NOTIFICATION_ID = 8088
        private const val TAG = "LoopLinkService"
        private const val PENDING_INTENT_REQUEST_CODE = 0
        private const val NOTIFICATION_TEXT_LIMIT = 120
        private const val VIDEO_ERROR_INTERVAL_MS = 5_000L
        private val STATUS_MESSAGES = mapOf(
            CarPlayStatus.DiscoveringMfi to "正在加载本地 MFi 认证",
            CarPlayStatus.MfiReady to
                "identity.pk8 与 certificate.p7b 已加载并通过密钥匹配校验；等待 USB iPhone",
            CarPlayStatus.DiscoveringIphone to "正在搜索 USB iPhone",
            CarPlayStatus.WaitingForIphone to "请连接 USB iPhone 并允许 USB 访问",
            CarPlayStatus.RequestingIphonePermission to "等待授予 iPhone USB 访问权限",
            CarPlayStatus.WaitingForReenumeration to "iPhone 正在重新枚举 USB 接口",
            CarPlayStatus.SelectingConfiguration to "正在协商 iPhone USB 接口",
            CarPlayStatus.OpeningDataPaths to "正在打开 CarPlay 数据通道",
            CarPlayStatus.Pairing to "正在与 iPhone 配对",
            CarPlayStatus.ConnectingControl to "正在建立 CarPlay 控制通道",
            CarPlayStatus.AttachingNetwork to "正在启动 iPhone 网络通道",
            CarPlayStatus.RunningControl to "CarPlay 控制通道已连接",
            CarPlayStatus.ControlEnded to "CarPlay 会话已结束，等待重新连接",
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LoopLinkService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LoopLinkService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
