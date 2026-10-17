package com.navipilot.amap2tesla.projection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.navipilot.amap2tesla.ConnectionMode
import com.navipilot.amap2tesla.MapProjectionTarget
import com.navipilot.amap2tesla.ProjectionState
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.navipilot.amap2tesla.privileged.IPrivilegedDisplayService
import com.navipilot.amap2tesla.privileged.PrivilegedDisplayService
import com.jixiexiaoge.drivingassist.BuildConfig
import com.jixiexiaoge.drivingassist.MainActivity
import com.navipilot.amap2tesla.server.ControlSink
import com.navipilot.amap2tesla.server.LocalProjectionServer
import com.navipilot.amap2tesla.server.ProjectionServerStatus
import com.navipilot.amap2tesla.video.LatestFrameStore
import com.navipilot.amap2tesla.video.MjpegEncoder
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class ProjectionService : Service() {
    private val frameStore = LatestFrameStore()
    private val pipelineExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "Amap2Tesla-pipeline").apply { isDaemon = true }
        }
    private var encoder: MjpegEncoder? = null
    private var server: LocalProjectionServer? = null
    private var privileged: IPrivilegedDisplayService? = null
    @Volatile
    private var displayId: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var displayWatchdog: ScheduledExecutorService? = null
    private var privilegedReconnect: ScheduledExecutorService? = null
    private val packageSwitchLock = Any()
    private var userServiceBound = false
    private var userServiceBinding = false
    @Volatile
    private var destroyed = false
    private var finalStatus = "已停止"
    private var connectionMode = ConnectionMode.LAN
    private var projectionTarget = MapProjectionTarget.AMAP_AUTO
    private var projectionPackageName = MapProjectionTarget.AMAP_AUTO.packageName
    private var projectionDisplayName = MapProjectionTarget.AMAP_AUTO.displayName
    private var projectionActivityClassName: String? = null
    @Volatile
    private var requestedWidth = DEFAULT_DISPLAY_WIDTH
    @Volatile
    private var requestedHeight = DEFAULT_DISPLAY_HEIGHT

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, PrivilegedDisplayService::class.java.name)
        )
            .daemon(false)
            // Stellar's Shizuku adapter caches by tag, ignoring version on a live binder.
            .tag("display-v6-${BuildConfig.VERSION_CODE}")
            .processNameSuffix("privileged-v6")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (destroyed) {
                Log.i(TAG, "Ignoring privileged service connection after projection service stopped")
                return
            }
            Log.i(TAG, "Privileged service connected: $name")
            privilegedReconnect?.shutdownNow()
            privilegedReconnect = null
            userServiceBinding = false
            userServiceBound = true
            privileged = IPrivilegedDisplayService.Stub.asInterface(binder)
            startProjectionPipeline()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (destroyed) {
                Log.i(TAG, "Ignoring privileged service disconnect after projection service stopped")
                return
            }
            Log.w(TAG, "Privileged service disconnected: $name")
            userServiceBinding = false
            userServiceBound = false
            privileged = null
            displayWatchdog?.shutdownNow()
            displayWatchdog = null
            displayId = null
            ProjectionState.displayId = null
            runCatching { encoder?.close() }
            encoder = null
            ProjectionState.status = "Stellar 服务被系统清理，正在自动恢复"
            updateNotification(ProjectionState.status)
            schedulePrivilegedReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备增强投屏"))
        wakeLock = (getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:projection")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            fail("虚拟屏投屏仅支持 Android 10 及更高版本")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                connectionMode = intent?.getStringExtra(EXTRA_CONNECTION_MODE)
                    ?.let { name -> ConnectionMode.entries.firstOrNull { it.name == name } }
                    ?: ConnectionMode.load(this)
                projectionTarget = intent?.getStringExtra(EXTRA_PROJECTION_TARGET)
                    ?.let { name -> MapProjectionTarget.entries.firstOrNull { it.name == name } }
                    ?: MapProjectionTarget.load(this)
                projectionPackageName = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: projectionTarget.packageName
                projectionDisplayName = intent?.getStringExtra(EXTRA_TARGET_DISPLAY_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: projectionTarget.displayName
                projectionActivityClassName = intent?.getStringExtra(EXTRA_TARGET_ACTIVITY_CLASS)
                    ?.takeIf { it.isNotBlank() }
                ProjectionState.currentPackageName = projectionPackageName
                ProjectionState.currentActivityClassName = projectionActivityClassName
                requestedWidth = intent?.getIntExtra(EXTRA_DISPLAY_WIDTH, DEFAULT_DISPLAY_WIDTH)
                    ?.takeIf { it >= MIN_VIEWPORT_WIDTH }
                    ?: DEFAULT_DISPLAY_WIDTH
                requestedHeight = intent?.getIntExtra(EXTRA_DISPLAY_HEIGHT, DEFAULT_DISPLAY_HEIGHT)
                    ?.takeIf { it >= MIN_VIEWPORT_HEIGHT }
                    ?: DEFAULT_DISPLAY_HEIGHT
                if (connectionMode == ConnectionMode.TESLA && !TeslaVirtualIpService.isReady()) {
                    fail("Tesla 固定访问地址尚未就绪，请重新授权 VPN")
                } else {
                    bindPrivilegedService()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        ProjectionState.status = "正在停止"
        displayWatchdog?.shutdownNow()
        displayWatchdog = null
        privilegedReconnect?.shutdownNow()
        privilegedReconnect = null
        displayId?.let { id -> runCatching { privileged?.releaseDisplay(id) } }
        displayId = null
        ProjectionState.displayId = null
        runCatching { server?.stop() }
        server = null
        runCatching { encoder?.close() }
        encoder = null
        pipelineExecutor.shutdownNow()
        if (userServiceBound || userServiceBinding) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        }
        userServiceBound = false
        userServiceBinding = false
        privileged = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ProjectionState.reset(finalStatus)
        if (connectionMode == ConnectionMode.TESLA) {
            TeslaVirtualIpService.stop(this)
        }
        super.onDestroy()
    }

    private fun bindPrivilegedService(failIfUnavailable: Boolean = true) {
        Log.i(
            TAG,
            "Binding privileged service: ping=${Shizuku.pingBinder()}, " +
                "permission=${runCatching { Shizuku.checkSelfPermission() }.getOrNull()}, " +
                "version=${runCatching { Shizuku.getVersion() }.getOrNull()}, " +
                "uid=${runCatching { Shizuku.getUid() }.getOrNull()}"
        )
        if (!Shizuku.pingBinder()) {
            if (failIfUnavailable) {
                fail("Shizuku / Stellar 未运行")
            }
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (failIfUnavailable) {
                fail("尚未授权 Shizuku / Stellar")
            }
            return
        }
        if (userServiceBinding || userServiceBound || privileged != null) return
        ProjectionState.running = true
        ProjectionState.status = "正在连接特权服务"
        userServiceBinding = true
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (error: Throwable) {
            userServiceBinding = false
            if (failIfUnavailable) {
                fail("特权服务启动失败：${error.rootMessage()}")
            } else {
                Log.w(TAG, "Privileged service reconnect failed", error)
            }
        }
    }

    private fun schedulePrivilegedReconnect() {
        if (destroyed || privilegedReconnect != null) return
        privilegedReconnect = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Amap2Tesla-privileged-reconnect").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    if (destroyed || privileged != null) return@scheduleWithFixedDelay
                    bindPrivilegedService(failIfUnavailable = false)
                },
                PRIVILEGED_RECONNECT_DELAY_SECONDS,
                PRIVILEGED_RECONNECT_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun startProjectionPipeline() {
        if (destroyed || encoder != null || pipelineExecutor.isShutdown) return
        val remote = privileged ?: return
        try {
            pipelineExecutor.execute {
                if (destroyed) return@execute
                try {
                    if (connectionMode == ConnectionMode.TESLA) {
                        ProjectionState.status = "正在自动开启手机热点"
                        updateNotification(ProjectionState.status)
                        val hotspotResult = remote.ensureWifiHotspotEnabled()
                        Log.i(TAG, "Hotspot preparation: $hotspotResult")
                        if (!hotspotResult.startsWith("READY:")) {
                            error(hotspotResult.removePrefix("ERROR:"))
                        }
                    }
                    ProjectionState.status = "正在检测虚拟屏能力"
                    val probe = remote.probeCapabilities()
                    Log.i(TAG, "Capability probe: $probe")
                    if (!probe.startsWith("可用")) error(probe)

                    installDisplay(remote, requestedWidth, requestedHeight)

                    val activeServer = server ?: LocalProjectionServer.startWithFallback(
                        this,
                        frameStore,
                        object : ControlSink {
                            override fun onPointer(
                                gestureId: Int,
                                action: Int,
                                x: Float,
                                y: Float,
                                pointerId: Int
                            ): Boolean {
                                val targetDisplayId = displayId ?: return false
                                val activeRemote = privileged ?: return false
                                return try {
                                    activeRemote.injectTouch(
                                        targetDisplayId,
                                        gestureId,
                                        action,
                                        x,
                                        y,
                                        pointerId
                                    )
                                } catch (error: Throwable) {
                                    Log.w(TAG, "Pointer injection failed", error)
                                    false
                                }
                            }

                            override fun onCancel(gestureId: Int) {
                                val targetDisplayId = displayId ?: return
                                val activeRemote = privileged ?: return
                                runCatching {
                                    activeRemote.cancelTouch(targetDisplayId, gestureId)
                                }.onFailure { error ->
                                    Log.w(TAG, "Pointer cancellation failed", error)
                                }
                            }

                            override fun onKey(keyCode: Int): Boolean {
                                val targetDisplayId = displayId ?: return false
                                val activeRemote = privileged ?: return false
                                return try {
                                    activeRemote.injectKey(targetDisplayId, keyCode)
                                } catch (error: Throwable) {
                                    Log.w(TAG, "Key injection failed", error)
                                    false
                                }
                            }

                            override fun onViewport(width: Int, height: Int): Boolean {
                                return requestViewportResize(width, height)
                            }

                            override fun onLaunchPackage(
                                packageName: String,
                                activityClassName: String?,
                            ): Boolean {
                                return switchProjectionPackage(packageName, activityClassName)
                            }
                        },
                        statusProvider = {
                            ProjectionServerStatus(
                                running = ProjectionState.running,
                                message = ProjectionState.status,
                                displayId = ProjectionState.displayId,
                                width = ProjectionState.width,
                                height = ProjectionState.height,
                                densityDpi = ProjectionState.densityDpi,
                                targetFps = ProjectionState.targetFps,
                                trialMode = ProjectionState.trialMode,
                                interactive = true,
                                currentPackageName = projectionPackageName,
                                currentActivityClassName = projectionActivityClassName,
                                appsJson = buildProjectionAppsJson(),
                            )
                        }
                    )
                    if (destroyed) {
                        activeServer.stop()
                        return@execute
                    }
                    server = activeServer
                    ProjectionState.accessUrl = activeServer.accessUrl(connectionMode)
                    ProjectionState.running = true
                    ProjectionState.status = "投屏已启动，等待车机浏览器"
                    updateNotification("车机打开 ${ProjectionState.accessUrl}")
                } catch (error: Throwable) {
                    if (!destroyed) {
                        fail(error.rootMessage())
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!destroyed) {
                fail("投屏启动任务未能提交：${error.rootMessage()}")
            }
        }
    }

    private fun requestViewportResize(width: Int, height: Int): Boolean {
        val size = normalizeViewport(width, height) ?: return false
        if (destroyed || pipelineExecutor.isShutdown) return false
        if (abs(size.width - requestedWidth) < MIN_RESIZE_DELTA &&
            abs(size.height - requestedHeight) < MIN_RESIZE_DELTA
        ) {
            return true
        }
        requestedWidth = size.width
        requestedHeight = size.height
        try {
            pipelineExecutor.execute {
                if (destroyed) return@execute
                val remote = privileged ?: return@execute
                if (requestedWidth != size.width || requestedHeight != size.height) {
                    return@execute
                }
                if (ProjectionState.width == size.width && ProjectionState.height == size.height) {
                    return@execute
                }
                val previousWidth = ProjectionState.width.takeIf { it > 0 } ?: DEFAULT_DISPLAY_WIDTH
                val previousHeight = ProjectionState.height.takeIf { it > 0 } ?: DEFAULT_DISPLAY_HEIGHT
                ProjectionState.status = "正在适配浏览器 ${size.width}×${size.height}"
                try {
                    installDisplay(remote, size.width, size.height)
                    ProjectionState.status = "投屏已启动，已适配浏览器"
                    updateNotification("已适配浏览器 ${size.width}×${size.height}")
                } catch (resizeError: Throwable) {
                    Log.e(TAG, "Viewport resize failed; restoring previous display", resizeError)
                    requestedWidth = previousWidth
                    requestedHeight = previousHeight
                    try {
                        installDisplay(remote, previousWidth, previousHeight)
                        ProjectionState.status = "浏览器尺寸适配失败，已恢复原尺寸"
                        updateNotification(ProjectionState.status)
                    } catch (restoreError: Throwable) {
                        resizeError.addSuppressed(restoreError)
                        fail("浏览器尺寸适配失败：${resizeError.rootMessage()}")
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!destroyed) {
                Log.w(TAG, "Viewport resize ignored because projection is stopping", error)
            }
            return false
        }
        return true
    }

    private fun installDisplay(
        remote: IPrivilegedDisplayService,
        width: Int,
        height: Int
    ) {
        displayWatchdog?.shutdownNow()
        displayWatchdog = null
        displayId = null
        ProjectionState.displayId = null
        frameStore.clear()

        val newEncoder = MjpegEncoder(
            width = width,
            height = height,
            frameStore = frameStore,
            fps = DISPLAY_FPS,
            quality = JPEG_QUALITY
        )
        try {
            ProjectionState.status = "正在创建 ${width}×${height} ${projectionDisplayName}虚拟屏"
            val newDisplayId = remote.createVirtualDisplay(
                "MapProjection",
                width,
                height,
                DISPLAY_DENSITY_DPI,
                DISPLAY_FPS.toFloat(),
                newEncoder.surface
            )
            if (newDisplayId < 0) error("系统未能创建虚拟 Display")

            ProjectionState.status = "正在虚拟屏中启动${projectionDisplayName}"
            if (!launchProjectionTarget(remote, newDisplayId)) {
                remote.releaseDisplay(newDisplayId)
                error("无法在虚拟屏启动${projectionDisplayName}")
            }

            val oldEncoder = encoder
            encoder = newEncoder
            displayId = newDisplayId
            ProjectionState.displayId = newDisplayId
            ProjectionState.width = width
            ProjectionState.height = height
            ProjectionState.densityDpi = DISPLAY_DENSITY_DPI
            ProjectionState.targetFps = DISPLAY_FPS
            runCatching { oldEncoder?.close() }
            Log.i(TAG, "Virtual display ready: id=$newDisplayId ${width}x$height")
            startDisplayWatchdog(newDisplayId, remote)
        } catch (error: Throwable) {
            newEncoder.close()
            throw error
        }
    }

    private fun normalizeViewport(width: Int, height: Int): DisplaySize? {
        if (width < MIN_VIEWPORT_WIDTH || height < MIN_VIEWPORT_HEIGHT) return null
        val scale = minOf(
            1.0,
            MAX_DISPLAY_WIDTH.toDouble() / width,
            MAX_DISPLAY_HEIGHT.toDouble() / height
        )
        val normalizedWidth = ((width * scale).toInt().coerceAtLeast(2) + 1) and -2
        val normalizedHeight = ((height * scale).toInt().coerceAtLeast(2) + 1) and -2
        return DisplaySize(normalizedWidth, normalizedHeight)
    }

    private fun startDisplayWatchdog(
        expectedDisplayId: Int,
        remote: IPrivilegedDisplayService
    ) {
        displayWatchdog?.shutdownNow()
        displayWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Amap2Tesla-display-watchdog").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    if (!ProjectionState.running || displayId != expectedDisplayId) {
                        return@scheduleWithFixedDelay
                    }
                    try {
                        val result = projectionActivityClassName?.let { className ->
                            remote.ensureActivityOnDisplay(
                                expectedDisplayId,
                                projectionPackageName,
                                className,
                            )
                        } ?: remote.ensurePackageOnDisplay(
                            expectedDisplayId,
                            projectionPackageName,
                        )
                        when (result) {
                            1 -> {
                                Log.w(TAG, "$projectionDisplayName task returned to display $expectedDisplayId")
                                updateNotification("已自动恢复${projectionDisplayName}虚拟屏")
                            }
                            0 -> Unit
                            else -> Log.w(TAG, "$projectionDisplayName display watchdog failed: $result")
                        }
                    } catch (error: Throwable) {
                        Log.w(TAG, "$projectionDisplayName display watchdog error", error)
                    }
                },
                WATCHDOG_INITIAL_DELAY_SECONDS,
                WATCHDOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        finalStatus = "错误：$message"
        ProjectionState.running = false
        ProjectionState.status = finalStatus
        updateNotification("启动失败：$message")
        stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Amap2Tesla 投屏",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(content: String): android.app.Notification {
        val activity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("Amap2Tesla")
            .setContentText(content)
            .setContentIntent(activity)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun launchProjectionTarget(
        remote: IPrivilegedDisplayService,
        targetDisplayId: Int,
    ): Boolean {
        return projectionActivityClassName?.let { className ->
            remote.launchActivity(targetDisplayId, projectionPackageName, className)
        } ?: remote.launchPackage(targetDisplayId, projectionPackageName)
    }

    private fun switchProjectionPackage(
        packageName: String,
        activityClassName: String?,
    ): Boolean {
        synchronized(packageSwitchLock) {
            val targetDisplayId = displayId ?: return false
            val remote = privileged ?: return false
            val previousPackage = projectionPackageName
            if (packageName == previousPackage &&
                activityClassName == projectionActivityClassName
            ) {
                return true
            }
            if (!isPackageInstalled(packageName)) {
                Log.w(TAG, "Cannot launch uninstalled package $packageName")
                return false
            }

            displayWatchdog?.shutdownNow()
            displayWatchdog = null
            Log.i(
                TAG,
                "Switching projection package from $previousPackage to $packageName " +
                    "on display $targetDisplayId"
            )
            val launched = runCatching {
                if (activityClassName == null) {
                    remote.launchPackage(targetDisplayId, packageName)
                } else {
                    remote.launchActivity(targetDisplayId, packageName, activityClassName)
                }
            }.onFailure { error ->
                Log.e(TAG, "Could not launch $packageName", error)
            }.getOrDefault(false)
            if (!launched) {
                startDisplayWatchdog(targetDisplayId, remote)
                return false
            }

            projectionPackageName = packageName
            projectionActivityClassName = activityClassName
            ProjectionState.currentPackageName = packageName
            ProjectionState.currentActivityClassName = activityClassName
            projectionDisplayName = runCatching {
                packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
            }.getOrDefault(packageName)
            startDisplayWatchdog(targetDisplayId, remote)

            if (previousPackage != this.packageName) {
                runCatching { remote.forceStopPackage(previousPackage) }
                    .onFailure { error ->
                        Log.w(TAG, "Could not stop previous package $previousPackage", error)
                    }
                    .onSuccess { stopped ->
                        if (!stopped) {
                            Log.w(TAG, "Previous package remained running: $previousPackage")
                        }
                    }
            }
            updateNotification("已在车机虚拟屏切换至 $projectionDisplayName")
            return true
        }
    }

    private fun buildProjectionAppsJson(): String {
        val array = org.json.JSONArray()
        array.put(
            org.json.JSONObject()
                .put("displayName", "CarPlay")
                .put("packageName", packageName)
                .put("activityClassName", CARPLAY_HOST_ACTIVITY)
                .put("installed", true)
                .put("badge", "C")
        )
        MapProjectionTarget.entries.forEach { target ->
            val installed = isPackageInstalled(target.packageName)
            array.put(
                org.json.JSONObject()
                    .put("displayName", target.displayName)
                    .put("packageName", target.packageName)
                    .put("installed", installed)
                    .put("badge", target.displayName.take(1))
            )
        }

        val prefs = getSharedPreferences("custom_projection_apps", Context.MODE_PRIVATE)
        for (slotIndex in 0 until 6) {
            val pkg = prefs.getString("slot_${slotIndex}_package", null)?.takeIf { it.isNotBlank() }
            if (pkg != null) {
                val installed = isPackageInstalled(pkg)
                val label = if (installed) {
                    runCatching {
                        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
                    }.getOrDefault(prefs.getString("slot_${slotIndex}_label", null) ?: pkg)
                } else {
                    prefs.getString("slot_${slotIndex}_label", null) ?: "自定义 ${slotIndex + 1}"
                }
                array.put(
                    org.json.JSONObject()
                        .put("displayName", label)
                        .put("packageName", pkg)
                        .put("installed", installed)
                        .put("badge", label.take(1).ifBlank { "A" })
                )
            } else {
                array.put(
                    org.json.JSONObject()
                        .put("displayName", "自定义 ${slotIndex + 1}")
                        .put("packageName", "custom.placeholder.$slotIndex")
                        .put("installed", false)
                        .put("badge", "+")
                )
            }
        }
        return array.toString()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
    }

    companion object {
        private const val CHANNEL_ID = "navipilot_amap_projection"
        private const val TAG = "Amap2TeslaService"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_START = "com.jixiexiaoge.drivingassist.AMAP_PROJECTION_START"
        private const val ACTION_STOP = "com.jixiexiaoge.drivingassist.AMAP_PROJECTION_STOP"
        private const val EXTRA_CONNECTION_MODE = "connection_mode"
        private const val EXTRA_PROJECTION_TARGET = "projection_target"
        private const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val EXTRA_TARGET_DISPLAY_NAME = "target_display_name"
        private const val EXTRA_TARGET_ACTIVITY_CLASS = "target_activity_class"
        private const val EXTRA_DISPLAY_WIDTH = "display_width"
        private const val EXTRA_DISPLAY_HEIGHT = "display_height"
        private const val DEFAULT_DISPLAY_WIDTH = 1180
        private const val DEFAULT_DISPLAY_HEIGHT = 920
        private const val MIN_VIEWPORT_WIDTH = 320
        private const val MIN_VIEWPORT_HEIGHT = 240
        private const val MIN_RESIZE_DELTA = 8
        private const val MAX_DISPLAY_WIDTH = 1920
        private const val MAX_DISPLAY_HEIGHT = 1080
        private const val DISPLAY_DENSITY_DPI = 280
        private const val DISPLAY_FPS = 20
        private const val JPEG_QUALITY = 60
        private const val WATCHDOG_INITIAL_DELAY_SECONDS = 2L
        private const val WATCHDOG_INTERVAL_SECONDS = 2L
        private const val PRIVILEGED_RECONNECT_DELAY_SECONDS = 1L
        private const val PRIVILEGED_RECONNECT_INTERVAL_SECONDS = 2L
        private const val CARPLAY_HOST_ACTIVITY =
            "com.shilapi.xcertplay.CarPlayHostActivity"

        fun start(
            context: Context,
            mode: ConnectionMode = ConnectionMode.load(context),
            target: MapProjectionTarget = MapProjectionTarget.load(context),
            targetPackageName: String = target.packageName,
            targetDisplayName: String = target.displayName,
            targetActivityClassName: String? = null,
            displayWidth: Int = DEFAULT_DISPLAY_WIDTH,
            displayHeight: Int = DEFAULT_DISPLAY_HEIGHT,
        ) {
            ConnectionMode.save(context, mode)
            MapProjectionTarget.save(context, target)
            ProjectionState.running = true
            ProjectionState.status = "正在启动服务"
            ProjectionState.currentPackageName = targetPackageName
            ProjectionState.currentActivityClassName = targetActivityClassName
            context.startForegroundService(
                Intent(context, ProjectionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONNECTION_MODE, mode.name)
                    .putExtra(EXTRA_PROJECTION_TARGET, target.name)
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackageName)
                    .putExtra(EXTRA_TARGET_DISPLAY_NAME, targetDisplayName)
                    .putExtra(EXTRA_TARGET_ACTIVITY_CLASS, targetActivityClassName)
                    .putExtra(EXTRA_DISPLAY_WIDTH, displayWidth)
                    .putExtra(EXTRA_DISPLAY_HEIGHT, displayHeight)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }

    }
}

private data class DisplaySize(val width: Int, val height: Int)

private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: current.javaClass.simpleName
}
