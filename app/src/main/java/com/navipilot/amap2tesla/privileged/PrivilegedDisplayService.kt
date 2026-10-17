package com.navipilot.amap2tesla.privileged

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.android.dx.DexMaker
import com.android.dx.TypeId
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PrivilegedDisplayService : IPrivilegedDisplayService.Stub() {
    private val displays = ConcurrentHashMap<Int, DisplayRecord>()
    private val touchLock = Any()
    private val gestures = mutableMapOf<GestureKey, GestureState>()
    private val inputManager by lazy { initializeInputManager() }
    private val injectInputEventMethod by lazy {
        inputManager.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    }
    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }
    @Volatile
    private var connectivityTetheringCallbackClass: Class<*>? = null

    override fun probeCapabilities(): String {
        return try {
            val context = systemContext()
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val displayManager = context.getSystemService(DisplayManager::class.java)
                return if (displayManager != null) {
                    "可用：Android 10 兼容虚拟屏，uid=${Process.myUid()}"
                } else {
                    "不可用：系统未提供 DisplayManager"
                }
            }
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val hasCreateMethod = globalClass.declaredMethods.any { method ->
                method.name == "createVirtualDisplay" &&
                    method.parameterTypes.any { it == configClass }
            }
            if (!hasCreateMethod) {
                "不可用：找不到 DisplayManagerGlobal.createVirtualDisplay"
            } else {
                "可用：uid=${Process.myUid()}, sdk=${Build.VERSION.SDK_INT}, " +
                    "package=${context.packageName}, opPackage=${context.opPackageName}"
            }
        } catch (error: Throwable) {
            "不可用：${error.rootMessage()}"
        }
    }

    override fun ensureWifiHotspotEnabled(): String {
        if (isWifiHotspotEnabled()) return "READY:手机热点已开启"

        val context = systemContext()
        val tetheringResult = try {
            val tetheringManager = context.getSystemService("tethering")
                ?: error("系统未提供热点服务")
            val startMethods = tetheringManager.javaClass.methods.filter { method ->
                method.name == "startTethering" && method.parameterTypes.size == 3
            }
            val byType = startMethods.firstOrNull { method ->
                method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            val byRequest = startMethods.firstOrNull { method ->
                method.parameterTypes[0].name.endsWith("TetheringRequest")
            }
            val startMethod = byType ?: byRequest
                ?: error("系统热点服务缺少启动接口")
            val callbackResult = AtomicInteger(HOTSPOT_CALLBACK_PENDING)
            val callbackLatch = CountDownLatch(1)
            val callback = createTetheringCallback(
                startMethod.parameterTypes[2],
                callbackResult,
                callbackLatch,
            ) ?: error("无法创建热点启动回调")
            val firstArgument = if (startMethod === byType) {
                TETHERING_WIFI
            } else {
                buildWifiTetheringRequest()
                    ?: error("无法创建 Wi-Fi 热点请求")
            }

            startMethod.isAccessible = true
            startMethod.invoke(
                tetheringManager,
                firstArgument,
                Executor { command -> command.run() },
                callback,
            )
            repeat(HOTSPOT_START_ATTEMPTS) {
                if (isWifiHotspotEnabled()) {
                    Log.i(TAG, "Wi-Fi hotspot started")
                    return "READY:手机热点已自动开启"
                }
                val callbackCode = callbackResult.get()
                if (callbackCode != HOTSPOT_CALLBACK_PENDING &&
                    callbackCode != HOTSPOT_CALLBACK_STARTED
                ) {
                    error("系统拒绝开启热点，错误码 $callbackCode")
                }
                Thread.sleep(HOTSPOT_POLL_INTERVAL_MILLIS)
            }
            callbackLatch.await(100, TimeUnit.MILLISECONDS)
            error("系统已接受热点启动请求，但热点未在限定时间内开启")
        } catch (error: Throwable) {
            val message = error.rootMessage()
            Log.w(TAG, "TetheringManager hotspot start failed: $message", error)
            message
        }
        if (tetheringResult.startsWith("READY:")) return tetheringResult

        val connectivityResult = tryStartHotspotWithConnectivityManager(context)
        if (connectivityResult.startsWith("READY:")) return connectivityResult

        val wifiManagerResult = tryStartHotspotWithWifiManager(context)
        if (wifiManagerResult.startsWith("READY:")) return wifiManagerResult

        val settingsResult = tryStartHotspotThroughSettings()
        if (settingsResult.startsWith("READY:")) return settingsResult
        return "ERROR:自动开启热点失败：$tetheringResult；$connectivityResult；" +
            "$wifiManagerResult；$settingsResult"
    }

    private fun tryStartHotspotWithConnectivityManager(context: Context): String {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "ConnectivityManager 不可用"
        val method = connectivityManager.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "startTethering" && candidate.parameterCount in 4..5
        } ?: return "ConnectivityManager 兼容接口不存在"
        val callback = createConnectivityTetheringCallback(context)
            ?: return "无法创建 ConnectivityManager 热点回调"
        return runCatching {
            method.isAccessible = true
            when (method.parameterCount) {
                4 -> method.invoke(
                    connectivityManager,
                    TETHERING_WIFI,
                    false,
                    callback,
                    Handler(Looper.getMainLooper()),
                )
                5 -> method.invoke(
                    connectivityManager,
                    TETHERING_WIFI,
                    false,
                    callback,
                    Handler(Looper.getMainLooper()),
                    SHELL_PACKAGE,
                )
            }
            if (awaitWifiHotspot()) {
                "READY:手机热点已通过 ConnectivityManager 自动开启"
            } else {
                "ConnectivityManager 已接受请求但热点未开启"
            }
        }.getOrElse { error ->
            Log.w(TAG, "ConnectivityManager hotspot start failed", error)
            "ConnectivityManager 启动失败：${error.rootMessage()}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createConnectivityTetheringCallback(context: Context): Any? {
        return runCatching {
            connectivityTetheringCallbackClass?.let { callbackClass ->
                return@runCatching callbackClass.getDeclaredConstructor().newInstance()
            }
            val parentClass =
                Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
            val dexMaker = DexMaker()
            val getTypeByName = TypeId::class.java.getDeclaredMethod("get", String::class.java)
            val getTypeByClass = TypeId::class.java.getDeclaredMethod("get", Class::class.java)
            val generatedType =
                getTypeByName.invoke(null, "LNavipilotTetheringCallback;") as TypeId<Any>
            val parentType = getTypeByClass.invoke(null, parentClass) as TypeId<Any>
            dexMaker.declare(
                generatedType,
                "NavipilotTetheringCallback.generated",
                java.lang.reflect.Modifier.PUBLIC,
                parentType,
            )
            val constructor = generatedType.getConstructor()
            val parentConstructor = parentType.getConstructor()
            val code = dexMaker.declare(constructor, java.lang.reflect.Modifier.PUBLIC)
            code.invokeDirect(parentConstructor, null, code.getThis(generatedType))
            code.returnVoid()
            val generatedClass = dexMaker
                .generateAndLoad(javaClass.classLoader, context.codeCacheDir)
                .loadClass("NavipilotTetheringCallback")
            connectivityTetheringCallbackClass = generatedClass
            generatedClass.getDeclaredConstructor().newInstance()
        }.getOrElse { error ->
            Log.w(TAG, "Could not create ConnectivityManager tethering callback", error)
            null
        }
    }

    private fun tryStartHotspotWithWifiManager(context: Context): String {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return "WifiManager 不可用"
        val methods = wifiManager.javaClass.methods

        val configuredStart = methods.firstOrNull { method ->
            method.name in WIFI_MANAGER_CONFIGURED_START_METHODS &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].name.endsWith("SoftApConfiguration")
        }
        if (configuredStart != null) {
            val configuration = runCatching {
                methods.firstOrNull { method ->
                    method.name == "getSoftApConfiguration" && method.parameterCount == 0
                }?.apply { isAccessible = true }?.invoke(wifiManager)
            }.getOrNull()
            if (configuration != null) {
                val invoked = runCatching {
                    configuredStart.isAccessible = true
                    val result = configuredStart.invoke(wifiManager, configuration)
                    result !is Boolean || result
                }.getOrElse { error ->
                    Log.w(TAG, "${configuredStart.name} failed", error)
                    false
                }
                if (invoked && awaitWifiHotspot()) {
                    return "READY:手机热点已通过 ${configuredStart.name} 自动开启"
                }
            }
        }

        val legacyStart = methods.firstOrNull { method ->
            method.name == "setWifiApEnabled" &&
                method.parameterCount == 2 &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (legacyStart != null) {
            val invoked = runCatching {
                legacyStart.isAccessible = true
                val result = legacyStart.invoke(wifiManager, null, true)
                result !is Boolean || result
            }.getOrElse { error ->
                Log.w(TAG, "setWifiApEnabled failed", error)
                false
            }
            if (invoked && awaitWifiHotspot()) {
                return "READY:手机热点已通过兼容接口自动开启"
            }
        }
        return "WifiManager 兼容接口未能开启热点"
    }

    private fun awaitWifiHotspot(): Boolean {
        repeat(HOTSPOT_START_ATTEMPTS) {
            if (isWifiHotspotEnabled()) return true
            Thread.sleep(HOTSPOT_POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private fun tryStartHotspotThroughSettings(): String {
        val openResult = shell("am start -W -a android.settings.TETHER_SETTINGS")
        if (openResult.exitCode != 0) {
            return "无法打开系统热点设置：${openResult.output}"
        }
        return try {
            Thread.sleep(HOTSPOT_SETTINGS_SETTLE_MILLIS)
            val dumpResult = shell("uiautomator dump $HOTSPOT_UI_DUMP_PATH >/dev/null")
            if (dumpResult.exitCode != 0) {
                return "无法读取系统热点开关：${dumpResult.output}"
            }
            val uiResult = shell("cat $HOTSPOT_UI_DUMP_PATH")
            val switchBounds = UI_NODE_PATTERN.findAll(uiResult.output)
                .map { it.value }
                .firstOrNull { node ->
                    node.contains("class=\"android.widget.Switch\"") &&
                        node.contains("checkable=\"true\"") &&
                        node.contains("checked=\"false\"") &&
                        node.contains("clickable=\"true\"") &&
                        HOTSPOT_LABEL_PATTERN.containsMatchIn(node)
                }
                ?.let { node -> UI_BOUNDS_PATTERN.find(node) }
                ?: return "系统热点页面中没有找到可操作的热点开关"
            val (left, top, right, bottom) = switchBounds.destructured
            val leftPx = left.toInt()
            val rightPx = right.toInt()
            val x = rightPx - ((rightPx - leftPx) / 10).coerceIn(48, 96)
            val y = (top.toInt() + bottom.toInt()) / 2
            val tapResult = shell("input tap $x $y")
            if (tapResult.exitCode != 0) {
                return "系统热点开关点击失败：${tapResult.output}"
            }
            if (awaitWifiHotspot()) {
                "READY:手机热点已通过系统设置自动开启"
            } else {
                "系统热点开关已操作，但热点未开启"
            }
        } finally {
            shell("rm -f $HOTSPOT_UI_DUMP_PATH")
            shell(
                "am start -W -n $APP_PACKAGE/" +
                    "com.jixiexiaoge.drivingassist.MainActivity"
            )
        }
    }

    override fun isWifiHotspotEnabled(): Boolean {
        val context = systemContext()
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val frameworkState = runCatching {
            val method = wifiManager?.javaClass?.methods
                ?.firstOrNull { it.name == "isWifiApEnabled" && it.parameterCount == 0 }
                ?: return@runCatching false
            method.isAccessible = true
            method.invoke(wifiManager) == true
        }.getOrDefault(false)
        return frameworkState || hasWifiHotspotInterface()
    }

    private fun createTetheringCallback(
        type: Class<*>,
        result: AtomicInteger,
        latch: CountDownLatch,
    ): Any? {
        if (!type.isInterface) {
            Log.w(TAG, "Tethering callback ${type.name} is not an interface")
            return null
        }
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "${type.simpleName}(no-op)"
                "onTetheringStarted" -> {
                    result.set(HOTSPOT_CALLBACK_STARTED)
                    latch.countDown()
                    Log.i(TAG, "Tethering framework reported hotspot started")
                    null
                }
                "onTetheringFailed" -> {
                    val errorCode = (args?.getOrNull(0) as? Int) ?: HOTSPOT_CALLBACK_UNKNOWN_ERROR
                    result.set(errorCode)
                    latch.countDown()
                    Log.e(TAG, "Tethering framework rejected hotspot start: $errorCode")
                    null
                }
                else -> null
            }
        }
    }

    private fun buildWifiTetheringRequest(): Any? {
        return runCatching {
            val builderClass =
                Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass
                .getConstructor(Int::class.javaPrimitiveType)
                .newInstance(TETHERING_WIFI)
            builderClass.getMethod("build").invoke(builder)
        }.getOrElse { error ->
            Log.w(TAG, "Could not build Wi-Fi tethering request", error)
            null
        }
    }

    private fun hasWifiHotspotInterface(): Boolean {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { networkInterface ->
                val likelyHotspotInterface = HOTSPOT_INTERFACE_PATTERN.matches(networkInterface.name)
                likelyHotspotInterface &&
                    networkInterface.isUp &&
                    networkInterface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .any { it.isSiteLocalAddress }
            }
        }.getOrDefault(false)
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        refreshRate: Float,
        surface: Surface
    ): Int {
        require(width > 0 && height > 0 && densityDpi > 0)
        require(surface.isValid)
        releaseAllDisplays()

        val context = systemContext()
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            val displayManager = context.getSystemService(DisplayManager::class.java)
                ?: error("系统未提供 DisplayManager")
            val display = displayManager.createVirtualDisplay(
                name,
                width,
                height,
                densityDpi,
                surface,
                virtualDisplayFlags(),
            ) ?: error("Android 10 未能创建 VirtualDisplay")
            val displayId = display.display.displayId
            check(displayId > 0) { "系统返回了无效虚拟屏 ID：$displayId" }
            displays[displayId] = DisplayRecord(display, width, height)
            ensureDisplayEnabled(displayId)
            Log.i(TAG, "Created Android 10 display $displayId: ${width}x$height@$densityDpi")
            return displayId
        }

        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass
            .getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(name, width, height, densityDpi)

        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
            .invoke(builder, virtualDisplayFlags())
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        if (refreshRate > 0f) {
            runCatching {
                builderClass.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType)
                    .invoke(builder, refreshRate)
            }
        }
        val config = builderClass.getMethod("build").invoke(builder)

        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val createMethod = globalClass.declaredMethods.firstOrNull { method ->
            method.name == "createVirtualDisplay" &&
                method.parameterTypes.any { it == configClass }
        } ?: error("找不到兼容的 createVirtualDisplay 方法")

        createMethod.isAccessible = true
        val arguments = createMethod.parameterTypes.map { type ->
            when {
                type == configClass -> config
                type == Context::class.java -> context
                type == String::class.java -> "com.android.shell"
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }.toTypedArray()

        val display = createMethod.invoke(global, *arguments) as? VirtualDisplay
            ?: error("系统没有返回 VirtualDisplay")
        if (display.surface !== surface) {
            display.surface = surface
        }
        val displayId = display.display.displayId
        check(displayId > 0) { "系统返回了无效虚拟屏 ID：$displayId" }
        displays[displayId] = DisplayRecord(display, width, height)
        ensureDisplayEnabled(displayId)
        Log.i(TAG, "Created display $displayId: ${width}x$height@$densityDpi")
        return displayId
    }

    override fun launchPackage(displayId: Int, packageName: String): Boolean {
        if (!displays.containsKey(displayId)) return false
        ensureDisplayEnabled(displayId)
        val component = resolveLauncherComponent(packageName)
        if (component == null) {
            Log.e(TAG, "Could not resolve launcher for $packageName")
            return false
        }
        return launchResolvedComponent(displayId, component)
    }

    override fun launchActivity(
        displayId: Int,
        packageName: String,
        className: String,
    ): Boolean {
        if (!displays.containsKey(displayId)) return false
        if (!PACKAGE_NAME_PATTERN.matches(packageName) ||
            !CLASS_NAME_PATTERN.matches(className)
        ) {
            Log.w(TAG, "Refusing to launch invalid component $packageName/$className")
            return false
        }
        ensureDisplayEnabled(displayId)
        return launchResolvedComponent(displayId, "$packageName/$className")
    }

    private fun launchResolvedComponent(displayId: Int, component: String): Boolean {
        Log.i(TAG, "Launching $component on display $displayId")
        val result = shell(
            "am start -W --user 0 --display $displayId -f 0x10000000 -n ${quote(component)}"
        )
        if (result.exitCode != 0) {
            Log.e(TAG, "Launch failed: ${result.output}")
        }
        return result.exitCode == 0
    }

    override fun forceStopPackage(packageName: String): Boolean {
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) {
            Log.w(TAG, "Refusing to stop invalid package name: $packageName")
            return false
        }
        val result = shell("am force-stop --user 0 ${quote(packageName)}")
        if (result.exitCode != 0) {
            Log.e(TAG, "Force-stop failed for $packageName: ${result.output}")
        }
        return result.exitCode == 0
    }

    override fun ensurePackageOnDisplay(displayId: Int, packageName: String): Int {
        return ensureTargetOnDisplay(displayId, packageName, null)
    }

    override fun ensureActivityOnDisplay(
        displayId: Int,
        packageName: String,
        className: String,
    ): Int {
        if (!CLASS_NAME_PATTERN.matches(className)) return ENSURE_INSPECTION_FAILED
        return ensureTargetOnDisplay(displayId, packageName, className)
    }

    private fun ensureTargetOnDisplay(
        displayId: Int,
        packageName: String,
        className: String?,
    ): Int {
        if (!displays.containsKey(displayId)) return ENSURE_UNKNOWN_DISPLAY
        ensureDisplayEnabled(displayId)

        val stackList = shell("am stack list")
        if (stackList.exitCode != 0) {
            Log.e(TAG, "Could not inspect activity stacks: ${stackList.output}")
            return ENSURE_INSPECTION_FAILED
        }

        val componentNeedle = className?.let { "$packageName/$it" } ?: "$packageName/"
        val task = ROOT_TASK_PATTERN.findAll(stackList.output)
            .firstOrNull { match ->
                match.value.contains(componentNeedle, ignoreCase = true)
            }
            if (task == null) {
                val launched = if (className == null) {
                    launchPackage(displayId, packageName)
                } else {
                    launchActivity(displayId, packageName, className)
                }
                return if (launched) {
                    ENSURE_RESTORED
                } else {
                    ENSURE_RESTORE_FAILED
            }
        }

        val rootTaskId = task.groupValues[1].toIntOrNull() ?: return ENSURE_INSPECTION_FAILED
        val currentDisplayId = task.groupValues[2].toIntOrNull() ?: return ENSURE_INSPECTION_FAILED
        if (currentDisplayId == displayId) return ENSURE_UNCHANGED

        Log.w(
            TAG,
            "$packageName moved from display $displayId to $currentDisplayId; " +
                "restoring root task $rootTaskId"
        )
        val move = shell("am display move-stack $rootTaskId $displayId")
        if (move.exitCode != 0) {
            Log.e(TAG, "Could not move root task $rootTaskId: ${move.output}")
            return ENSURE_RESTORE_FAILED
        }

        // Relaunching refreshes the layer stack after some vendor task managers move the task.
        val launched = if (className == null) {
            launchPackage(displayId, packageName)
        } else {
            launchActivity(displayId, packageName, className)
        }
        return if (launched) {
            ENSURE_RESTORED
        } else {
            ENSURE_RESTORE_FAILED
        }
    }

    override fun injectTouch(
        displayId: Int,
        gestureId: Int,
        action: Int,
        x: Float,
        y: Float,
        pointerId: Int
    ): Boolean {
        val display = displays[displayId] ?: return false
        if (gestureId < 0 || pointerId !in 0 until MAX_POINTERS) return false
        if (!x.isFinite() || !y.isFinite()) return false

        synchronized(touchLock) {
            val key = GestureKey(displayId, gestureId)
            val now = SystemClock.uptimeMillis()
            val pointer = TrackedPointer(
                pointerId,
                x.coerceIn(0f, 1f) * (display.width - 1),
                y.coerceIn(0f, 1f) * (display.height - 1)
            )
            val state = gestures[key]
            val command = when (action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    val active = state ?: GestureState(now).also { gestures[key] = it }
                    if (active.pointers.containsKey(pointerId) ||
                        active.pointers.size >= MAX_POINTERS
                    ) {
                        return false
                    }
                    active.pointers[pointerId] = pointer
                    val pointerIndex = active.pointers.keys.indexOf(pointerId)
                    TouchCommand(
                        if (active.pointers.size == 1) {
                            MotionEvent.ACTION_DOWN
                        } else {
                            MotionEvent.ACTION_POINTER_DOWN or
                                (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        },
                        active
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    val active = state ?: return false
                    if (!active.pointers.containsKey(pointerId)) return false
                    active.pointers[pointerId] = pointer
                    TouchCommand(MotionEvent.ACTION_MOVE, active)
                }
                MotionEvent.ACTION_UP -> {
                    val active = state ?: return false
                    if (!active.pointers.containsKey(pointerId)) return false
                    active.pointers[pointerId] = pointer
                    val pointerIndex = active.pointers.keys.indexOf(pointerId)
                    val eventAction = if (active.pointers.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_POINTER_UP or
                            (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                    TouchCommand(eventAction, active, removePointerId = pointerId)
                }
                MotionEvent.ACTION_CANCEL -> {
                    val active = state ?: return false
                    TouchCommand(MotionEvent.ACTION_CANCEL, active, cancelGesture = true)
                }
                else -> return false
            }

            val injected = injectMotionEvent(displayId, now, command)
            if (!injected || command.cancelGesture) {
                gestures.remove(key)
            } else if (command.removePointerId != null) {
                command.state.pointers.remove(command.removePointerId)
                if (command.state.pointers.isEmpty()) gestures.remove(key)
            }
            return injected
        }
    }

    override fun cancelTouch(displayId: Int, gestureId: Int) {
        synchronized(touchLock) {
            val key = GestureKey(displayId, gestureId)
            val state = gestures.remove(key) ?: return
            injectMotionEvent(
                displayId,
                SystemClock.uptimeMillis(),
                TouchCommand(MotionEvent.ACTION_CANCEL, state, cancelGesture = true)
            )
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!displays.containsKey(displayId)) return false
        return injectKeyEventSequence(displayId, keyCode)
    }

    private fun injectKeyEventSequence(displayId: Int, keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return false
        val now = SystemClock.uptimeMillis()
        return injectKeyEvent(displayId, now, KeyEvent.ACTION_DOWN, keyCode) &&
            injectKeyEvent(displayId, now, KeyEvent.ACTION_UP, keyCode)
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(touchLock) {
            gestures.keys.removeAll { it.displayId == displayId }
        }
        displays.remove(displayId)?.virtualDisplay?.release()
    }

    private fun releaseAllDisplays() {
        displays.keys.toList().forEach(::releaseDisplay)
    }

    private fun ensureDisplayEnabled(displayId: Int) {
        val result = shell("cmd display enable-display $displayId")
        if (result.exitCode != 0 &&
            !result.output.contains("Unknown command", ignoreCase = true)
        ) {
            Log.w(TAG, "Could not enable virtual display $displayId: ${result.output}")
        }
    }

    private fun resolveLauncherComponent(packageName: String): String? {
        val result = shell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER ${quote(packageName)}"
        )
        Log.i(TAG, "resolve-activity exit=${result.exitCode}: ${result.output}")
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .map(String::trim)
            .lastOrNull { it.contains('/') && !it.contains("No activity", ignoreCase = true) }
    }

    private fun initializeInputManager(): Any {
        val context = systemContext()
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        return context.getSystemService("input")
            ?: inputManagerClass.getMethod("getInstance").invoke(null)
            ?: error("InputManager system service is unavailable")
    }

    private fun injectMotionEvent(
        displayId: Int,
        eventTime: Long,
        command: TouchCommand
    ): Boolean {
        val pointers = command.state.pointers.values.toList()
        if (pointers.isEmpty()) return false
        val properties = pointers.map { pointer ->
            MotionEvent.PointerProperties().apply {
                id = pointer.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { pointer ->
            MotionEvent.PointerCoords().apply {
                x = pointer.x
                y = pointer.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            command.state.downTime,
            eventTime,
            command.action,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        return try {
            setDisplayIdMethod.invoke(event, displayId)
            val accepted = injectInputEventMethod.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            ) == true
            if ((command.action and MotionEvent.ACTION_MASK) != MotionEvent.ACTION_MOVE) {
                Log.d(
                    TAG,
                    "Touch action=${command.action} display=$displayId " +
                        "pointers=${pointers.map { it.id }} accepted=$accepted"
                )
            }
            accepted
        } catch (error: Throwable) {
            Log.e(TAG, "Touch injection failed for display $displayId", error)
            false
        } finally {
            event.recycle()
        }
    }

    private fun injectKeyEvent(
        displayId: Int,
        eventTime: Long,
        action: Int,
        keyCode: Int
    ): Boolean {
        val event = KeyEvent(
            eventTime,
            eventTime,
            action,
            keyCode,
            0,
            0,
            0,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        return try {
            setDisplayIdMethod.invoke(event, displayId)
            injectInputEventMethod.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            ) == true
        } catch (error: Throwable) {
            Log.e(TAG, "Key injection failed for display $displayId", error)
            false
        }
    }

    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        val systemBase = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val base = systemBase.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AttributedShellContext(base)
        } else {
            ShellContext(base)
        }
    }

    private fun virtualDisplayFlags(): Int {
        return when {
            Build.VERSION.SDK_INT >= 34 -> 23883
            Build.VERSION.SDK_INT >= 33 -> 7499
            else -> 331
        }
    }

    private fun shell(command: String): ShellResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return ShellResult(-1, "命令超时：$command")
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            ShellResult(process.exitValue(), output.trim())
        } catch (error: Throwable) {
            ShellResult(-1, error.rootMessage())
        }
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class ShellResult(val exitCode: Int, val output: String)

    private data class DisplayRecord(
        val virtualDisplay: VirtualDisplay,
        val width: Int,
        val height: Int
    )

    private data class GestureKey(val displayId: Int, val gestureId: Int)

    private data class TrackedPointer(val id: Int, val x: Float, val y: Float)

    private data class GestureState(
        val downTime: Long,
        val pointers: LinkedHashMap<Int, TrackedPointer> = LinkedHashMap()
    )

    private data class TouchCommand(
        val action: Int,
        val state: GestureState,
        val removePointerId: Int? = null,
        val cancelGesture: Boolean = false
    )

    companion object {
        private const val TAG = "Amap2TeslaPrivileged"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_TIMEOUT_SECONDS = 8L
        private const val ENSURE_UNKNOWN_DISPLAY = -1
        private const val ENSURE_INSPECTION_FAILED = -2
        private const val ENSURE_RESTORE_FAILED = -3
        private const val ENSURE_UNCHANGED = 0
        private const val ENSURE_RESTORED = 1
        private const val MAX_POINTERS = 2
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val TETHERING_WIFI = 0
        private const val HOTSPOT_START_ATTEMPTS = 15
        private const val HOTSPOT_POLL_INTERVAL_MILLIS = 1_000L
        private const val HOTSPOT_CALLBACK_PENDING = Int.MIN_VALUE
        private const val HOTSPOT_CALLBACK_STARTED = 0
        private const val HOTSPOT_CALLBACK_UNKNOWN_ERROR = -1
        private const val HOTSPOT_SETTINGS_SETTLE_MILLIS = 1_500L
        private const val HOTSPOT_UI_DUMP_PATH = "/data/local/tmp/map2tesla-tether.xml"
        private const val APP_PACKAGE = "com.jixiexiaoge.map2tesla"
        private val HOTSPOT_INTERFACE_PATTERN =
            Regex("(?i)(ap\\d*|softap\\d*|swlan\\d*|wlan[1-9]\\d*)")
        private val WIFI_MANAGER_CONFIGURED_START_METHODS =
            setOf("startTetheredHotspot", "startSoftAp")
        private val UI_NODE_PATTERN = Regex("<node\\b[^>]*>")
        private val UI_BOUNDS_PATTERN =
            Regex("""bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")
        private val HOTSPOT_LABEL_PATTERN =
            Regex("(?i)(热点|个人热点|hotspot|tether)")
        private val PACKAGE_NAME_PATTERN =
            Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val CLASS_NAME_PATTERN =
            Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val ROOT_TASK_PATTERN = Regex(
            """RootTask id=(\d+).*?displayId=(\d+).*?(?=\r?\nRootTask id=|\z)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }
}

private open class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = "com.android.shell"

    override fun getOpPackageName(): String = "com.android.shell"
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private class AttributedShellContext(base: Context) : ShellContext(base) {
    private val shellAttributionSource = AttributionSource.Builder(Process.myUid())
        .setPackageName("com.android.shell")
        .build()

    override fun getAttributionTag(): String? = null

    override fun getAttributionSource(): AttributionSource = shellAttributionSource
}

private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: current.javaClass.simpleName
}
