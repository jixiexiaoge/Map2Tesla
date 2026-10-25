package com.jixiexiaoge.drivingassist

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.jixiexiaoge.drivingassist.core.Result
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.navipilot.amap2tesla.ConnectionMode
import com.navipilot.amap2tesla.MapProjectionTarget
import com.navipilot.amap2tesla.ProjectionState
import com.navipilot.airplay.AirPlayProjectionService
import com.navipilot.airplay.AirPlayProjectionState
import com.navipilot.amap2tesla.sponsorship.SponsorshipCheckout
import com.navipilot.amap2tesla.sponsorship.SponsorshipClient
import com.navipilot.amap2tesla.sponsorship.SponsorshipOption
import com.navipilot.amap2tesla.sponsorship.SPONSORSHIP_OPTIONS
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.navipilot.amap2tesla.projection.ProjectionService
import com.navipilot.looplink.LoopLinkService
import com.navipilot.looplink.LoopLinkState
import com.navipilot.looplink.LoopLinkDisplayProfile
import com.navipilot.looplink.LoopLinkDisplaySettings
import com.shilapi.xcertplay.network.CarPlayVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

private enum class AppPage(val label: String, val icon: String) {
    SETUP("准备", "●"),
    PROJECTION("投屏", "↗"),
    CARPLAY("CarPlay", "◇"),
    LOOPLINK("USB", "⌁"),
    AIRPLAY("AirPlay", "◌"),
    SPONSORSHIP("赞助", "◎"),
}

private data class SetupCheck(
    val title: String,
    val ready: Boolean,
    val detail: String,
    val actionText: String? = null,
    val action: (() -> Unit)? = null,
)

private data class ProjectionAppChoice(
    val displayName: String,
    val packageName: String,
    val installed: Boolean,
    val badge: String,
    val target: MapProjectionTarget? = null,
    val customSlotIndex: Int? = null,
    val activityClassName: String? = null,
)

@Composable
fun ProjectionHome() {
    val context = LocalContext.current
    var currentPage by remember {
        mutableStateOf(when {
            AirPlayProjectionState.receiverRunning -> AppPage.AIRPLAY
            LoopLinkState.running -> AppPage.LOOPLINK
            ProjectionState.running && ProjectionState.currentActivityClassName == CARPLAY_HOST_ACTIVITY -> AppPage.CARPLAY
            ProjectionState.running -> AppPage.PROJECTION
            else -> AppPage.SETUP
        })
    }
    var autoStartProjection by remember { mutableStateOf(false) }
    var autoLaunchAttempted by remember { mutableStateOf(false) }
    var statusTick by remember { mutableIntStateOf(0) }
    val setupChecks = rememberSetupChecks(statusTick)
    val setupReady = setupChecks.all { it.ready }
    val airPlaySetupReady = setupChecks.filterNot { it.title == "投屏应用" }.all { it.ready }
    val airPlayReceiverReady = setupChecks.filter {
        it.title in setOf("Android 版本", "通知权限", "网络连接")
    }.all { it.ready }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            statusTick++
        }
    }
    LaunchedEffect(setupReady, airPlaySetupReady, airPlayReceiverReady, currentPage) {
        val currentPageReady = when (currentPage) {
            AppPage.SETUP -> true
            AppPage.PROJECTION -> setupReady
            AppPage.SPONSORSHIP -> true
            AppPage.CARPLAY -> airPlaySetupReady
            AppPage.LOOPLINK -> true
            AppPage.AIRPLAY -> airPlayReceiverReady
        }
        if (!currentPageReady) {
            currentPage = AppPage.SETUP
        }
    }
    LaunchedEffect(setupReady, currentPage, ProjectionState.running) {
        if (setupReady &&
            currentPage == AppPage.SETUP &&
            !ProjectionState.running &&
            !AirPlayProjectionState.receiverRunning &&
            !autoLaunchAttempted
        ) {
            autoLaunchAttempted = true
            autoStartProjection = true
            currentPage = AppPage.PROJECTION
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF04111F), Color(0xFF081A2B), Color(0xFF0B1020)),
                ),
            ),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (currentPage) {
                AppPage.SETUP -> SetupPage(
                    checks = setupChecks,
                    setupReady = setupReady,
                    onContinue = {
                        autoStartProjection = true
                        currentPage = AppPage.PROJECTION
                    },
                )
                AppPage.PROJECTION -> ProjectionPage(
                    refreshTick = statusTick,
                    autoStartRequested = autoStartProjection,
                    onAutoStartConsumed = { autoStartProjection = false },
                    onOpenSponsorship = { currentPage = AppPage.SPONSORSHIP },
                )
                AppPage.CARPLAY -> CarPlayPage(refreshTick = statusTick)
                AppPage.LOOPLINK -> LoopLinkPage(refreshTick = statusTick)
                AppPage.AIRPLAY -> AirPlayPage(
                    refreshTick = statusTick,
                )
                AppPage.SPONSORSHIP -> SponsorshipPage()
            }
        }
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.08f)),
            containerColor = Color(0xFF0B1524),
        ) {
            AppPage.entries.forEach { page ->
                val enabled = when (page) {
                    AppPage.SETUP -> true
                    AppPage.SPONSORSHIP -> true
                    AppPage.CARPLAY -> airPlaySetupReady
                    AppPage.LOOPLINK -> true
                    AppPage.AIRPLAY -> airPlayReceiverReady
                    else -> setupReady
                }
                NavigationBarItem(
                    selected = currentPage == page,
                    enabled = enabled,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF7DD3FC),
                        selectedTextColor = Color(0xFFBAE6FD),
                        indicatorColor = Color(0xFF19334D),
                        unselectedIconColor = Color(0xFF94A3B8),
                        unselectedTextColor = Color(0xFF94A3B8),
                    ),
                    onClick = {
                        if (!enabled) {
                            Toast.makeText(context, "请先完成准备页的配置", Toast.LENGTH_SHORT).show()
                            return@NavigationBarItem
                        }
                        when {
                            LoopLinkState.running &&
                                page in setOf(AppPage.PROJECTION, AppPage.CARPLAY, AppPage.AIRPLAY) ->
                                LoopLinkService.stop(context)
                            currentPage in setOf(AppPage.PROJECTION, AppPage.CARPLAY) &&
                                page != currentPage -> ProjectionService.stop(context)
                            currentPage == AppPage.AIRPLAY && page != AppPage.AIRPLAY ->
                                AirPlayProjectionService.stopReceiver(context)
                        }
                        currentPage = page
                    },
                    icon = { Text(page.icon, fontWeight = FontWeight.Bold) },
                    label = { Text(page.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun LoopLinkPage(refreshTick: Int) {
    val context = LocalContext.current
    var displayProfile by remember { mutableStateOf(LoopLinkDisplaySettings.load(context)) }
    val identityPresent =
        java.io.File(context.noBackupFilesDir, LocalMfiIdentityStore.FILE_NAME).isFile
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            stopOtherProjectionServices(context)
            LoopLinkService.start(context)
        }
    }
    val status = LoopLinkState.status.also { refreshTick }
    val running = LoopLinkState.running.also { refreshTick }
    val diagnostics = LoopLinkState.diagnostics.also { refreshTick }

    PageLayout(title = "USB CarPlay") {
        UsageNoticeCard()
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("USB 画面比例 · ${displayProfile.width}×${displayProfile.height}", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LoopLinkDisplayProfile.entries.forEach { profile ->
                        OutlinedButton(enabled = !running, onClick = {
                            displayProfile = profile
                            LoopLinkDisplaySettings.save(context, profile)
                        }) {
                            Text((if (profile == displayProfile) "✓ " else "") + profile.label)
                        }
                    }
                }
                Text("停止后可切换比例；独立于 CarPlay 宿主缩放，避免缩小后再放大。", color = Color(0xFFB8C7D9))
            }
        }
        LoopLinkControlCard(
            status = status,
            identityPresent = identityPresent,
            running = running,
            onStart = {
                if (!identityPresent) {
                    Toast.makeText(context, "请先导入本地 MFi 认证文件", Toast.LENGTH_LONG).show()
                } else {
                    val consent = CarPlayVpnService.prepare(context)
                    if (consent == null) {
                        stopOtherProjectionServices(context)
                        LoopLinkService.start(context)
                    } else {
                        vpnPermissionLauncher.launch(consent)
                    }
                }
            },
            onStop = { LoopLinkService.stop(context) },
        )
        if (running) LoopLinkUrlsCard()
        LoopLinkDiagnosticsCard(diagnostics)
    }
}

@Composable
private fun LoopLinkControlCard(
    status: String,
    identityPresent: Boolean,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("USB CarPlay 浏览器接收", color = Color.White, fontWeight = FontWeight.Bold)
            Text(status, color = if (running) Color(0xFF8FE3B1) else Color(0xFFB8C7D9))
            Text(
                "USB CarPlay 免费使用，无需会员。浏览器优先直接播放 iPhone 视频；不兼容时自动回退 MJPEG。点击网页“开启声音”可将声音转到浏览器。",
                color = Color(0xFFB8C7D9),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!identityPresent) {
                Text(
                    "请先进入 CarPlay 设置导入本地 identity.pk8 和匹配的 certificate.p7b。",
                    color = Color(0xFFFFD080),
                )
            }
            if (running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("停止 USB CarPlay")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = identityPresent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("连接 USB iPhone 并启动浏览器")
                }
            }
        }
    }
}

@Composable
private fun LoopLinkUrlsCard() {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoopLinkUrlRow(
                label = "局域网浏览器地址",
                url = LoopLinkState.lanUrl ?: "等待局域网地址…",
            )
            LoopLinkUrlRow(
                label = "Tesla 固定地址",
                url = LoopLinkState.teslaUrl ?: "http://99.99.99.99:8088/",
            )
            Text(
                "无需 token，直接输入 IP:8088。该 HTTP 服务不加密，请只在可信局域网使用。首版采用 MJPEG，兼容普通浏览器。",
                color = Color(0xFFB8C7D9),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LoopLinkUrlRow(label: String, url: String) {
    val context = LocalContext.current
    Column {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                url,
                modifier = Modifier.weight(1f),
                color = Color(0xFF8FC7FF),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
                    Toast.makeText(context, "浏览器地址已复制", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("复制")
            }
        }
    }
}

@Composable
private fun LoopLinkDiagnosticsCard(messages: List<String>) {
    val context = LocalContext.current
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "连接日志与异常",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { LoopLinkState.clearDiagnostics() }) {
                    Text("清空")
                }
                TextButton(
                    enabled = messages.isNotEmpty(),
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("LoopLink diagnostics", messages.joinToString("\n")),
                        )
                        Toast.makeText(context, "连接日志已复制", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("复制")
                }
            }
            if (messages.isEmpty()) {
                Text(
                    "启动 USB CarPlay 后，USB/MFi、CarPlay 视频解码、浏览器连接和错误信息会显示在这里。",
                    color = Color(0xFFB8C7D9),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    messages.forEach { line ->
                        val isError = listOf("失败", "错误", "异常", "未就绪")
                            .any(line::contains)
                        Text(
                            line,
                            color = if (isError) Color(0xFFFF9A9A) else Color(0xFFB8C7D9),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun stopOtherProjectionServices(context: Context) {
    ProjectionService.stop(context)
    AirPlayProjectionService.stopReceiver(context)
    TeslaVirtualIpService.stop(context)
}

@Composable
private fun rememberSetupChecks(refreshTick: Int): List<SetupCheck> {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val androidSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val notificationReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val connectionMode = ConnectionMode.load(context)
    val networkReady = refreshTick.let {
        connectionMode == ConnectionMode.TESLA || isNetworkReady(context)
    }
    val projectionAppsReady = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        0,
    ).any { it.activityInfo.packageName != context.packageName }
    val stellarInstalled = isPackageInstalled(context, STELLAR_PACKAGE) || isPackageInstalled(context, SHIZUKU_PACKAGE)
    val serviceRunning = refreshTick.let { runCatching { Shizuku.pingBinder() }.getOrDefault(false) }
    val authorized = serviceRunning && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    return listOf(
        SetupCheck(
            title = "Android 版本",
            ready = androidSupported,
            detail = if (androidSupported) "当前系统支持虚拟屏投屏" else "需要 Android 10 或更高版本",
        ),
        SetupCheck(
            title = "通知权限",
            ready = notificationReady,
            detail = if (notificationReady) "前台投屏服务通知可正常显示" else "请允许通知，投屏服务需要前台运行",
            actionText = if (!notificationReady) "授权通知" else null,
            action = if (!notificationReady) ({ notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) else null,
        ),
        SetupCheck(
            title = "网络连接",
            ready = networkReady,
            detail = when {
                connectionMode == ConnectionMode.TESLA -> "Tesla 模式将在投屏启动时自动开启手机热点"
                networkReady -> "已连接网络，浏览器可访问本机服务"
                else -> "请连接手机热点或同一局域网 Wi-Fi"
            },
            actionText = "Wi-Fi 设置",
            action = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        ),
        SetupCheck(
            title = "投屏应用",
            ready = projectionAppsReady,
            detail = if (projectionAppsReady) "可在投屏页的六个位置选择应用，无需预装车机地图" else "请先安装至少一个可启动的应用",
        ),
        SetupCheck(
            title = "Stellar / Shizuku",
            ready = stellarInstalled && serviceRunning,
            detail = when {
                serviceRunning -> "特权服务已运行"
                stellarInstalled -> "已安装，请完成无线调试启动"
                else -> "请先安装并启动 Stellar 或 Shizuku"
            },
            actionText = if (!serviceRunning) {
                if (stellarInstalled) "打开服务" else "开发者选项"
            } else {
                null
            },
            action = if (!serviceRunning) {
                {
                    if (!openPrivilegeManager(context)) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }
                }
            } else {
                null
            },
        ),
        SetupCheck(
            title = "特投屏授权",
            ready = authorized,
            detail = if (authorized) "已允许特投屏调用特权服务" else "服务运行后，点击授权按钮授予特投屏权限",
            actionText = if (serviceRunning && !authorized) "授予权限" else null,
            action = if (serviceRunning && !authorized) ({ Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }) else null,
        ),
    )
}

@Composable
private fun SetupPage(
    checks: List<SetupCheck>,
    setupReady: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val privilegeServiceRunning = checks.firstOrNull { it.title == "Stellar / Shizuku" }?.ready == true
    var showPrivilegeGuide by remember { mutableStateOf(false) }

    PageLayout(
        title = "准备",
    ) {
        HeroCard(
            eyebrow = if (setupReady) "READY" else "SETUP",
            title = if (setupReady) "环境已就绪" else "还差 ${checks.count { !it.ready }} 项配置",
            body = if (setupReady) {
                "可以开始投屏"
            } else {
                "完成以下检查后解锁全部功能"
            },
        )
        UsageNoticeCard()
        checks.forEach { check ->
            SetupStatusCard(check)
        }
        checks.filterNot { it.ready }.takeIf { it.isNotEmpty() }?.let { missingChecks ->
            GuidanceCard(
                missingChecks = missingChecks,
                onShowPrivilegeGuide = if (!privilegeServiceRunning) {
                    { showPrivilegeGuide = true }
                } else {
                    null
                },
            )
        }
        Button(
            enabled = setupReady,
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("一键投屏")
        }
    }
    if (showPrivilegeGuide) {
        PrivilegeSetupDialog(
            onDismiss = { showPrivilegeGuide = false },
            onOpenDeveloperOptions = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            },
            onOpenStellar = {
                if (!openPrivilegeManager(context)) {
                    Toast.makeText(context, "未安装 Stellar 或 Shizuku", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun SetupStatusCard(check: SetupCheck) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(check.ready)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    check.title,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(check.detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8C7D9))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (check.action != null) {
                TextButton(onClick = check.action) { Text(check.actionText ?: "处理") }
            } else {
                Text(
                    if (check.ready) "已就绪" else "待配置",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (check.ready) Color(0xFF10382F) else Color(0xFF3B3020))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = if (check.ready) Color(0xFF8FE3B1) else Color(0xFFFCD38D),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun GuidanceCard(
    missingChecks: List<SetupCheck>,
    onShowPrivilegeGuide: (() -> Unit)?,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("下一步", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "优先完成「${missingChecks.first().title}」，全部完成后将自动解锁投屏。",
                color = Color(0xFF7DD3FC),
                style = MaterialTheme.typography.bodySmall,
            )
            onShowPrivilegeGuide?.let { showGuide ->
                TextButton(onClick = showGuide) {
                    Text("查看 Stellar 首次配置步骤")
                }
            }
        }
    }
}

@Composable
private fun PrivilegeSetupDialog(
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenStellar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("首次配置 Stellar", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "这是一次性系统授权：特投屏需要 Stellar / Shizuku 的 shell 权限，" +
                        "才能创建后台虚拟屏并把浏览器触控回传给地图。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "1. 手机保持连接 Wi-Fi，点击下方“开发者选项”。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "2. 打开“无线调试”，首次使用请选择“使用配对码配对设备”或扫描二维码。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "3. 返回 Stellar，按它的“无线调试启动 / 配对 / 启动”提示完成操作；" +
                        "看到服务运行后回到特投屏，点击“授予权限”。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "以后通常只需打开 Stellar 并启动服务。手机重启、关闭无线调试或系统停止服务后，" +
                        "可能需要再次启动 Stellar。",
                    color = Color(0xFFB8C7D9),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenStellar) {
                Text("打开 Stellar")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenDeveloperOptions) {
                    Text("开发者选项")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

@Composable
internal fun ProjectionPage(
    refreshTick: Int,
    autoStartRequested: Boolean,
    onAutoStartConsumed: () -> Unit,
    onOpenSponsorship: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    val projectionApps = rememberProjectionApps(context, refreshTick)
    var selectedApp by remember { mutableStateOf(projectionApps.firstOrNull { it.installed } ?: projectionApps.first()) }
    var mode by remember { mutableStateOf(ConnectionMode.load(context)) }
    var pendingSwitchApp by remember { mutableStateOf<ProjectionAppChoice?>(null) }
    var editingCustomSlot by remember { mutableStateOf<Int?>(null) }
    val running = ProjectionState.running.also { refreshTick }
    LaunchedEffect(projectionApps) {
        if (projectionApps.none { it.packageName == selectedApp.packageName && it.installed }) {
            selectedApp = projectionApps.firstOrNull { it.installed } ?: projectionApps.first()
        } else {
            selectedApp = projectionApps.first { it.packageName == selectedApp.packageName }
        }
    }
    LaunchedEffect(ProjectionState.currentPackageName) {
        projectionApps.firstOrNull { it.packageName == ProjectionState.currentPackageName }?.let {
            selectedApp = it
        }
    }
    fun startProjectionForApp(app: ProjectionAppChoice, stopCurrent: Boolean = false) {
        if (preparing) return
        if (!app.installed) {
            Toast.makeText(context, "请先设置要投屏的 App", Toast.LENGTH_SHORT).show()
            return
        }
        preparing = true
        startJob = scope.launch {
            try {
                if (stopCurrent) {
                    ProjectionService.stop(context)
                    withTimeout(5_000) { while (ProjectionState.running) delay(50) }
                }
                awaitProjectionNetwork(context, mode)
                startProjection(context, mode, app)
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                Toast.makeText(context, "停止原投屏超时，请重试", Toast.LENGTH_LONG).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(context, "投屏启动失败：${error.rootMessage()}", Toast.LENGTH_LONG).show()
            } finally {
                preparing = false
            }
        }
    }
    fun startSelectedProjection() {
        startProjectionForApp(selectedApp)
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Tesla 网络授权未完成", Toast.LENGTH_SHORT).show()
        } else {
            startSelectedProjection()
        }
    }
    LaunchedEffect(autoStartRequested) {
        if (!autoStartRequested) return@LaunchedEffect
        onAutoStartConsumed()
        when {
            !selectedApp.installed ->
                Toast.makeText(context, "请先在六个自定义位置中选择要投屏的 App", Toast.LENGTH_SHORT).show()
            !isPrivilegeServiceRunning() ->
                Toast.makeText(context, "Stellar / Shizuku 服务已断开，请返回准备页处理", Toast.LENGTH_SHORT).show()
            !hasPrivilegePermission() ->
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            mode == ConnectionMode.TESLA && !TeslaVirtualIpService.isReady() ->
                VpnService.prepare(context)?.let(vpnPermission::launch) ?: startSelectedProjection()
            else -> startSelectedProjection()
        }
    }

    PageLayout(
        title = "投屏",
    ) {
        HeroCard(
            eyebrow = if (running) "LIVE" else "PROJECTION",
            title = if (running) "${selectedApp.displayName} 投屏运行中" else "选择喜欢的 App，自由投屏",
        )
        UsageNoticeCard()
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("投屏应用", color = Color.White, fontWeight = FontWeight.Bold)
                Text("六个位置均可自定义，点击已选应用可更换或清除。", color = Color(0xFFB8C7D9))
                AppGrid(
                    apps = projectionApps,
                    selectedApp = selectedApp,
                    running = running || preparing,
                    onSelect = { app ->
                        if (app.customSlotIndex != null && !running && (!app.installed || app.packageName == selectedApp.packageName)) {
                            editingCustomSlot = app.customSlotIndex
                        } else if (!app.installed) {
                            Toast.makeText(context, "请先设置或安装 ${app.displayName}", Toast.LENGTH_SHORT).show()
                        } else if (running && app.packageName != selectedApp.packageName) {
                            pendingSwitchApp = app
                        } else {
                            selectedApp = app
                            app.target?.let { MapProjectionTarget.save(context, it) }
                        }
                    },
                )
                Text("投屏方式", color = Color.White, fontWeight = FontWeight.Bold)
                ConnectionModeSelector(
                    selected = mode,
                    enabled = !running && !preparing,
                    onSelect = {
                        mode = it
                        ConnectionMode.save(context, it)
                    },
                )
                AccessUrlCard(mode = mode, running = running)
                Text("状态 · ${ProjectionState.status}", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = selectedApp.installed && !running && !preparing,
                        onClick = {
                            when {
                                !isPrivilegeServiceRunning() ->
                                    Toast.makeText(context, "请先在“准备”页面完成 Stellar 环境配置", Toast.LENGTH_SHORT).show()
                                !hasPrivilegePermission() ->
                                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                                mode == ConnectionMode.TESLA && !TeslaVirtualIpService.isReady() ->
                                    VpnService.prepare(context)?.let(vpnPermission::launch) ?: startSelectedProjection()
                                else -> startSelectedProjection()
                            }
                        },
                    ) { Text("免费开始投屏") }
                    OutlinedButton(enabled = running || preparing, onClick = {
                        startJob?.cancel()
                        ProjectionService.stop(context)
                    }) { Text("停止") }
                }
            }
        }
        if (selectedApp.target == MapProjectionTarget.AMAP_AUTO) {
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("高德快捷导航", color = Color.White, fontWeight = FontWeight.Bold)
                    Row {
                        Button(onClick = { sendAmapShortcut(context, 0) }) { Text("回家") }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { sendAmapShortcut(context, 1) }) { Text("公司") }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onOpenSponsorship) {
                Text("喜欢特投屏？自愿赞助支持开发")
            }
        }
    }

    pendingSwitchApp?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingSwitchApp = null },
            title = { Text("切换投屏应用？") },
            text = { Text("当前投屏会先自动断开，然后将 ${app.displayName} 推送到新的虚拟屏。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSwitchApp = null
                        selectedApp = app
                        app.target?.let { MapProjectionTarget.save(context, it) }
                        startProjectionForApp(app, stopCurrent = true)
                    },
                ) {
                    Text("切换")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitchApp = null }) {
                    Text("取消")
                }
            },
        )
    }

    editingCustomSlot?.let { slotIndex ->
        AppPickerDialog(
            slotIndex = slotIndex,
            apps = rememberLaunchableProjectionApps(context, refreshTick),
            onSelect = { app ->
                saveCustomProjectionApp(context, slotIndex, app)
                selectedApp = app.copy(customSlotIndex = slotIndex)
                editingCustomSlot = null
            },
            onClear = {
                clearCustomProjectionApp(context, slotIndex)
                if (selectedApp.customSlotIndex == slotIndex) {
                    selectedApp = projectionApps.first()
                }
                editingCustomSlot = null
            },
            onDismiss = { editingCustomSlot = null },
        )
    }
}

@Composable
private fun UsageNoticeCard() {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("本软件永久免费", color = Color(0xFF8FE3B1), fontWeight = FontWeight.Bold)
            Text("所有功能不限时、无需会员。赞助完全自愿，不影响任何功能。", color = Color(0xFFB8C7D9))
        }
    }
}

@Composable
private fun CarPlayPage(
    refreshTick: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    val installed = true
    val anyProjectionRunning = ProjectionState.running.also { refreshTick }
    val running = anyProjectionRunning &&
        ProjectionState.currentActivityClassName == CARPLAY_HOST_ACTIVITY
    var mode by remember { mutableStateOf(ConnectionMode.load(context)) }
    val bluetoothPermissionReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val deviceReadiness = inspectCarPlayDevices(context, bluetoothPermissionReady, refreshTick)
    val mfiTarget = AirPlayPersistence.loadMfiTarget(context).also { refreshTick }
    val localIdentityPresent =
        java.io.File(context.noBackupFilesDir, LocalMfiIdentityStore.FILE_NAME).isFile
    val manualHotspotConfigured =
        AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL &&
            AirPlayPersistence.loadManualHotspotSsid(context).isNotBlank()
    val componentPermissionsReady = installed &&
        requiredCarPlayPermissions(Build.VERSION.SDK_INT).all { permission ->
            context.packageManager.checkPermission(
                permission,
                context.packageName,
            ) == PackageManager.PERMISSION_GRANTED
        }
    val networkReady = isNetworkReady(context)
    val checks = listOf(
        SetupCheck(
            title = "CarPlay 协议组件",
            ready = installed,
            detail = if (installed) {
                "CarPlay 协议核心已内置，此页面使用无线 CarPlay"
            } else {
                "CarPlay 内置模块不可用"
            },
            actionText = if (!installed) "组件未安装" else null,
        ),
        SetupCheck(
            title = "组件运行权限",
            ready = componentPermissionsReady,
            detail = when {
                !installed -> "内置模块加载后检查定位、蓝牙、附近设备和麦克风权限"
                componentPermissionsReady -> "定位、蓝牙、附近设备和麦克风权限均已允许"
                else -> "请在手机端打开 CarPlay 组件并完成首次授权"
            },
            actionText = if (installed && !componentPermissionsReady) "打开组件" else null,
            action = if (installed && !componentPermissionsReady) {
                {
                    context.startActivity(
                        Intent().setClassName(context.packageName, CARPLAY_HOST_ACTIVITY),
                    )
                }
            } else {
                null
            },
        ),
        SetupCheck(
            title = "CarPlay 认证（${when (mfiTarget) {
                MfiTarget.LOCAL -> "本地"
                MfiTarget.USB_CH341 -> "USB"
                MfiTarget.I2C -> "I2C"
                MfiTarget.REMOTE -> "远程"
            }}）",
            ready = when (mfiTarget) {
                MfiTarget.LOCAL -> localIdentityPresent
                MfiTarget.USB_CH341 -> deviceReadiness.ch341Attached
                MfiTarget.I2C -> false
                MfiTarget.REMOTE -> AirPlayPersistence.loadRemoteMfiServer(context).isNotBlank()
            },
            detail = when {
                mfiTarget == MfiTarget.LOCAL && localIdentityPresent ->
                    "已导入本地认证身份；是否被 iPhone 接受仍需实际连接验证"
                mfiTarget == MfiTarget.LOCAL ->
                    "请自行准备有权使用的 identity.pk8 与 certificate.p7b，在 CarPlay 宿主设置中导入；或改选 USB 认证"
                mfiTarget == MfiTarget.REMOTE ->
                    "远程签名需要可用的认证服务器；请在 CarPlay 宿主设置中配置"
                mfiTarget == MfiTarget.I2C ->
                    "I2C 认证需连接可用的硬件协处理器并配置设备路径"
                deviceReadiness.ch341Attached ->
                    "已检测到 CH341 MFi/BAA 桥接器（VID 1A86 / PID 5512）"
                deviceReadiness.usbDeviceCount > 0 ->
                    "检测到 ${deviceReadiness.usbDeviceCount} 个 USB 设备，但未发现配置的 CH341"
                else -> "未检测到 CH341 MFi/BAA 桥接器；无线本地认证无需插入"
            },
        ),
        SetupCheck(
            title = "蓝牙连接",
            ready = deviceReadiness.bluetoothConnected,
            detail = when {
                !bluetoothPermissionReady -> "需要特投屏蓝牙权限才能检查连接状态"
                !deviceReadiness.bluetoothEnabled -> "蓝牙尚未开启"
                deviceReadiness.bluetoothConnected -> "检测到蓝牙音频或 GATT 连接，不代表 CarPlay 会话已建立"
                deviceReadiness.bluetoothBondedCount > 0 ->
                    "已有 ${deviceReadiness.bluetoothBondedCount} 个配对设备，等待 iPhone 建立连接"
                else -> "尚未与 iPhone 配对"
            },
            actionText = when {
                !bluetoothPermissionReady -> "授权检查"
                !deviceReadiness.bluetoothEnabled || deviceReadiness.bluetoothBondedCount == 0 ->
                    "蓝牙设置"
                else -> null
            },
            action = when {
                !bluetoothPermissionReady -> {
                    {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
                !deviceReadiness.bluetoothEnabled || deviceReadiness.bluetoothBondedCount == 0 -> {
                    {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                }
                else -> null
            },
        ),
        SetupCheck(
            title = "网络与热点",
            ready = if (mode == ConnectionMode.TESLA) manualHotspotConfigured else networkReady,
            detail = when {
                mode == ConnectionMode.TESLA && !manualHotspotConfigured ->
                    "Tesla 模式必须在 CarPlay 设置中选择手动热点，并填写手机热点的 SSID、密码和加密方式；否则不会启动连接"
                mode == ConnectionMode.TESLA && !TeslaVirtualIpService.isReady() ->
                    "手动热点已配置；启动投屏时才会准备手机热点与 Tesla 固定地址"
                mode == ConnectionMode.TESLA ->
                    "手动热点已配置；固定地址就绪不代表热点或 iPhone 已连接"
                networkReady -> "当前网络可用；局域网模式可选 CarPlay P2P 热点"
                else -> "请连接局域网，再启动 CarPlay"
            },
            actionText = if (mode == ConnectionMode.TESLA && !manualHotspotConfigured) {
                "配置 CarPlay"
            } else if (!networkReady) "网络设置" else null,
            action = if (mode == ConnectionMode.TESLA && !manualHotspotConfigured) {
                {
                    context.startActivity(
                        Intent().setClassName(context.packageName, CARPLAY_HOST_ACTIVITY),
                    )
                }
            } else if (!networkReady) {
                { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            } else {
                null
            },
        ),
    )
    val carPlayApp = remember(installed) {
        ProjectionAppChoice(
            displayName = "CarPlay",
            packageName = context.packageName,
            installed = installed,
            badge = "C",
            activityClassName = CARPLAY_HOST_ACTIVITY,
        )
    }

    fun startCarPlayProjection() {
        if (preparing) return
        if (!installed) {
            Toast.makeText(context, "CarPlay 内置模块不可用", Toast.LENGTH_SHORT).show()
            return
        }
        preparing = true
        startJob = scope.launch {
            try {
                if (ProjectionState.running) {
                    ProjectionService.stop(context)
                    withTimeout(5_000) { while (ProjectionState.running) delay(50) }
                }
                awaitProjectionNetwork(context, mode)
                startProjection(context, mode, carPlayApp)
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                Toast.makeText(context, "停止原投屏超时，请重试", Toast.LENGTH_LONG).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(context, "CarPlay 启动失败：${error.rootMessage()}", Toast.LENGTH_LONG).show()
            } finally {
                preparing = false
            }
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Tesla 网络授权未完成", Toast.LENGTH_SHORT).show()
        } else {
            startCarPlayProjection()
        }
    }

    PageLayout(title = "CarPlay") {
        UsageNoticeCard()
        HeroCard(
            eyebrow = if (running) "CARPLAY LIVE" else "CARPLAY HOST",
            title = when {
                !installed -> "CarPlay 内置模块不可用"
                running -> "CarPlay 已进入车机虚拟屏"
                anyProjectionRunning -> "其他投屏正在运行"
                else -> "准备无线 CarPlay 与 iPhone 连接"
            },
            body = "CarPlay 画面、连接状态和设置界面会显示在车机浏览器中，并支持浏览器触控。",
        )
        checks.forEach { check ->
            SetupStatusCard(check)
        }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("投屏方式", color = Color.White, fontWeight = FontWeight.Bold)
                ConnectionModeSelector(
                    selected = mode,
                    enabled = !anyProjectionRunning && !preparing,
                    onSelect = {
                        mode = it
                        ConnectionMode.save(context, it)
                    },
                )
                AccessUrlCard(mode = mode, running = anyProjectionRunning)
                Text(
                    "状态 · ${ProjectionState.status}",
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = installed && !running && !preparing,
                        onClick = {
                            if (anyProjectionRunning) {
                                startCarPlayProjection()
                            } else when {
                                !isPrivilegeServiceRunning() ->
                                    Toast.makeText(
                                        context,
                                        "请先在“准备”页面完成 Stellar 环境配置",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                !hasPrivilegePermission() ->
                                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                                mode == ConnectionMode.TESLA &&
                                    !TeslaVirtualIpService.isReady() ->
                                    VpnService.prepare(context)?.let(vpnPermission::launch)
                                        ?: startCarPlayProjection()
                                else -> startCarPlayProjection()
                            }
                        },
                    ) {
                        Text(
                            when {
                                anyProjectionRunning -> "切换到 CarPlay"
                                else -> "免费启动 CarPlay"
                            },
                        )
                    }
                    OutlinedButton(
                        enabled = anyProjectionRunning || preparing,
                        onClick = {
                            startJob?.cancel()
                            ProjectionService.stop(context)
                        },
                    ) {
                        Text("停止")
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = installed && !anyProjectionRunning && !preparing,
                    onClick = {
                        context.startActivity(
                            Intent().setClassName(context.packageName, CARPLAY_HOST_ACTIVITY),
                        )
                    },
                ) {
                    Text("在手机上配置认证与连接")
                }
            }
        }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("使用说明", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "1. CarPlay 免费使用；本地认证请自行准备有权使用的 identity.pk8 私钥与匹配的 certificate.p7b 证书，在手机端配置认证与连接中依次导入；或改选 USB/远程认证，并完成蓝牙、定位和麦克风授权。\n" +
                        "2. Tesla 模式须在 CarPlay 设置选择手动热点并填写手机当前热点信息。\n" +
                        "3. 与 iPhone 配对后，在车机浏览器打开上方地址；设置页和 CarPlay 画面均可触控。",
                    color = Color(0xFFB8C7D9),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AirPlayPage(
    refreshTick: Int,
) {
    val context = LocalContext.current
    val receiverRunning = AirPlayProjectionState.receiverRunning.also { refreshTick }
    val connected = AirPlayProjectionState.connected.also { refreshTick }
    val browserRunning = AirPlayProjectionState.browserProjectionRunning.also { refreshTick }
    var mode by remember { mutableStateOf(ConnectionMode.load(context)) }
    var preparingBrowser by remember { mutableStateOf(false) }
    var awaitingVpnPermission by remember { mutableStateOf(false) }
    var startRequested by remember { mutableStateOf(false) }
    var preparationError by remember { mutableStateOf<String?>(null) }
    var browserPreparation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val pageScope = rememberCoroutineScope()

    fun beginBrowserProjection() {
        if (preparingBrowser || AirPlayProjectionState.browserProjectionRunning || !startRequested) return
        preparingBrowser = true
        browserPreparation = pageScope.launch {
            var startedVpn = false
            var handedOff = false
            try {
                startedVpn = awaitProjectionNetwork(context, mode)
                AirPlayProjectionService.startBrowserProjection(context, mode)
                handedOff = true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                preparationError = error.rootMessage()
                Toast.makeText(context, error.rootMessage(), Toast.LENGTH_LONG).show()
            } finally {
                if (startedVpn && !handedOff) TeslaVirtualIpService.stop(context)
                preparingBrowser = false
            }
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingVpnPermission = false
        if (result.resultCode != Activity.RESULT_OK) {
            preparationError = "Tesla 网络授权未完成，可重试或停止后切换局域网模式"
            Toast.makeText(context, "Tesla 网络授权未完成", Toast.LENGTH_SHORT).show()
        } else if (startRequested) {
            beginBrowserProjection()
        }
    }

    fun prepareProjection() {
        if (preparingBrowser || awaitingVpnPermission || AirPlayProjectionState.browserProjectionRunning) return
        startRequested = true
        preparationError = null
        try {
            AirPlayProjectionService.startReceiver(context)
            val intent = if (mode == ConnectionMode.TESLA && !TeslaVirtualIpService.isReady()) {
                VpnService.prepare(context)
            } else null
            if (intent != null) {
                awaitingVpnPermission = true
                vpnPermission.launch(intent)
            } else {
                beginBrowserProjection()
            }
        } catch (error: Exception) {
            awaitingVpnPermission = false
            preparationError = error.rootMessage()
            Toast.makeText(context, error.rootMessage(), Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { prepareProjection() }

    PageLayout(title = "AirPlay") {
        UsageNoticeCard()
        HeroCard(
            eyebrow = if (browserRunning && connected) "LIVE" else if (browserRunning) "READY" else "AIRPLAY",
            title = when {
                browserRunning && connected -> "AirPlay 已连接，自动显示到车机"
                browserRunning -> "已就绪，选择本机 AirPlay 即可显示"
                preparingBrowser || awaitingVpnPermission -> "正在准备 AirPlay 浏览器入口"
                receiverRunning -> "等待 iPhone、iPad 或 Mac 连接"
                else -> "开启 AirPlay 屏幕镜像"
            },
        )
        GlassCard {
            Text("车机浏览器", color = Color.White, fontWeight = FontWeight.Bold)
            if (browserRunning) AirPlayAccessUrlCard(mode, true)
            Text(
                "状态 · ${preparationError ?: AirPlayProjectionState.status}",
                color = Color(0xFFB8C7D9),
            )
            Text(
                "可先在车机打开浏览器地址，无需等待发送设备。连接后自动显示，断开后保持入口并等待重连；永久免费、不限时。",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !browserRunning && !preparingBrowser && !awaitingVpnPermission,
                    onClick = { prepareProjection() },
                ) {
                    Text(if (preparingBrowser || awaitingVpnPermission) "正在准备…" else "开启接收与浏览器")
                }
                OutlinedButton(
                    enabled = receiverRunning || browserRunning || preparingBrowser || awaitingVpnPermission,
                    onClick = {
                        startRequested = false
                        browserPreparation?.cancel()
                        AirPlayProjectionService.stopReceiver(context)
                    },
                ) {
                    Text("停止全部")
                }
            }
        }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("发送设备选择此接收器", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    AirPlayProjectionState.receiverName,
                    color = Color(0xFF7DD3FC),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (receiverRunning) {
                        if (AirPlayProjectionState.pairingCode != null) {
                            "请在发送设备输入下方验证码"
                        } else if (connected) {
                            "${AirPlayProjectionState.senderName ?: "AirPlay 设备"} 已连接"
                        } else {
                            "在 iPhone、iPad 控制中心或 Mac 屏幕镜像中选择此设备"
                        }
                    } else {
                        "接收器尚未启动，请查看上方状态或点击重新开启"
                    },
                    color = Color(0xFFB8C7D9),
                    style = MaterialTheme.typography.bodySmall,
                )
                AirPlayProjectionState.pairingCode?.let { code ->
                    Text(
                        code,
                        color = Color(0xFFFDE68A),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        GlassCard {
            Text("网络设置", color = Color.White, fontWeight = FontWeight.Bold)
            ConnectionModeSelector(
                selected = mode,
                enabled = !receiverRunning && !browserRunning && !preparingBrowser && !awaitingVpnPermission,
                onSelect = {
                    mode = it
                    ConnectionMode.save(context, it)
                },
            )
            Text(
                if (mode == ConnectionMode.TESLA) {
                    "发送设备和车机需连接手机热点。VPN 固定地址不等于热点已开启；更改连接方式前请先停止全部。"
                } else {
                    "发送设备、此手机和车机浏览器需连接同一局域网；更改连接方式前请先停止全部。"
                },
                color = Color(0xFFB8C7D9),
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(
                        if (mode == ConnectionMode.TESLA) Settings.ACTION_WIRELESS_SETTINGS
                        else Settings.ACTION_WIFI_SETTINGS,
                    ))
                },
            ) { Text("打开网络设置") }
        }
        GlassCard {
            Text(
                "AirPlay 支持 iPhone、iPad 和 Mac 屏幕镜像，无需 Stellar/Shizuku。镜像为单向显示，不能反控发送设备；浏览器显示声音按钮后可点击播放。",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AirPlayAccessUrlCard(mode: ConnectionMode, running: Boolean) {
    val url = AirPlayProjectionState.accessUrl ?: defaultAccessUrl(mode)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x661E3A5F))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("浏览器地址", color = Color(0xFFB8C7D9), style = MaterialTheme.typography.labelMedium)
        Text(url, color = Color(0xFF7DD3FC), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            if (running) "可立即在车机浏览器打开，无需先连接 AirPlay 设备" else "浏览器入口准备中",
            color = Color(0xFFB8C7D9),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AppGrid(
    apps: List<ProjectionAppChoice>,
    selectedApp: ProjectionAppChoice,
    running: Boolean,
    onSelect: (ProjectionAppChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        apps.chunked(3).forEach { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowApps.forEach { app ->
                    val selected = app.packageName == selectedApp.packageName
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = 1.dp,
                                color = when {
                                    selected -> Color(0xFF38BDF8)
                                    app.installed -> Color.White.copy(alpha = 0.08f)
                                    else -> Color.White.copy(alpha = 0.04f)
                                },
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable { onSelect(app) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                selected -> Color(0x3328A5E5)
                                app.installed -> Color(0x99111827)
                                else -> Color(0x66111827)
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 104.dp)
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ProjectionAppIcon(app)
                            Spacer(Modifier.size(4.dp))
                            Text(
                                app.displayName,
                                color = if (app.installed) Color.White else Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                repeat(3 - rowApps.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProjectionAppIcon(app: ProjectionAppChoice) {
    val context = LocalContext.current
    val icon = remember(app.packageName, app.installed) {
        if (!app.installed) {
            null
        } else {
            runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        }
    }

    if (icon == null) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF1F2937)),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Color(0xFF94A3B8), style = MaterialTheme.typography.titleLarge)
        }
    } else {
        AndroidView(
            modifier = Modifier.size(44.dp),
            factory = { viewContext ->
                ImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { imageView -> imageView.setImageDrawable(icon) },
        )
    }
}

@Composable
private fun AppPickerDialog(
    slotIndex: Int,
    apps: List<ProjectionAppChoice>,
    onSelect: (ProjectionAppChoice) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置自定义 App ${slotIndex + 1}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (apps.isEmpty()) {
                    Text("没有找到可选择的应用。", color = Color(0xFF64748B))
                } else {
                    apps.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelect(app) }
                                .background(Color(0x0F0EA5E9))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0EA5E9)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(app.badge, color = Color.White, fontWeight = FontWeight.Black)
                            }
                            Column {
                                Text(app.displayName, fontWeight = FontWeight.Bold)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text("清空")
            }
        },
    )
}

@Composable
private fun ConnectionModeSelector(
    selected: ConnectionMode,
    enabled: Boolean,
    onSelect: (ConnectionMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x99111827))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConnectionMode.entries.sortedByDescending { it == ConnectionMode.TESLA }.forEach { mode ->
            val active = selected == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Color(0xFF0EA5E9) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (mode == ConnectionMode.TESLA) "Tesla 固定地址" else "局域网地址",
                    color = if (active) Color.White else Color(0xFFB8C7D9),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (!enabled) {
        Text("运行中不可切换连接方式", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccessUrlCard(
    mode: ConnectionMode,
    running: Boolean,
) {
    val url = ProjectionState.accessUrl ?: defaultAccessUrl(mode)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x661E3A5F))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("浏览器地址", color = Color(0xFFB8C7D9), style = MaterialTheme.typography.labelMedium)
        Text(url, color = Color(0xFF7DD3FC), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            if (running) {
                "在 Tesla 浏览器打开此地址"
            } else {
                "默认端口 8080"
            },
            color = Color(0xFFB8C7D9),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SponsorshipPage() {
    val context = LocalContext.current
    val client = remember { SponsorshipClient(context) }
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(SPONSORSHIP_OPTIONS) }
    var sponsorshipAvailable by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var creatingOrder by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var checkout by remember { mutableStateOf<SponsorshipCheckout?>(null) }
    var selectedSponsorship by remember { mutableStateOf<SponsorshipOption?>(null) }
    var pendingCheckout by remember { mutableStateOf<SponsorshipCheckout?>(null) }
    val pendingOrderNo = pendingCheckout?.orderNo
    var confirmingPayment by remember { mutableStateOf(false) }
    var paymentConfirmed by remember { mutableStateOf(false) }

    fun loadOptions() {
        if (loading) return
        scope.launch {
            loading = true
            sponsorshipAvailable = false
            when (val result = client.loadOptions()) {
                is Result.Success -> {
                    options = result.data
                    sponsorshipAvailable = true
                    message = null
                }
                is Result.Error -> message = "支付宝在线赞助暂不可用：${result.message}。仍可选择金额，使用微信扫码赞助；不影响任何免费功能。"
                Result.Loading -> Unit
            }
            loading = false
        }
    }

    fun confirmPayment(orderNo: String) {
        if (confirmingPayment || paymentConfirmed) return
        scope.launch {
            confirmingPayment = true
            when (val result = client.refreshOrder(orderNo)) {
                is Result.Success -> if (result.data) {
                    paymentConfirmed = true
                    checkout = null
                    pendingCheckout = null
                    message = "赞助已确认，感谢你支持特投屏持续开发！"
                } else {
                    message = "支付平台尚未确认，请稍后刷新；这不影响免费使用。"
                }
                is Result.Error -> message = "暂未确认赞助：${result.message}"
                Result.Loading -> Unit
            }
            confirmingPayment = false
        }
    }

    fun createSponsorshipOrder(option: SponsorshipOption) {
        if (creatingOrder || !sponsorshipAvailable || pendingOrderNo != null) return
        selectedSponsorship = null
        creatingOrder = true
        paymentConfirmed = false
        scope.launch {
            message = when (val result = client.createOrder(option.code)) {
                is Result.Success -> {
                    checkout = result.data
                    pendingCheckout = result.data
                    "感谢支持！可在支付页自愿完成赞助，也可直接关闭。"
                }
                is Result.Error -> result.message
                Result.Loading -> null
            }
            creatingOrder = false
        }
    }

    LaunchedEffect(Unit) { loadOptions() }
    LaunchedEffect(checkout?.orderNo) {
        val activeOrder = checkout?.orderNo ?: return@LaunchedEffect
        repeat(PAYMENT_CONFIRMATION_ATTEMPTS) {
            delay(PAYMENT_CONFIRMATION_INTERVAL_MS)
            when (val result = client.refreshOrder(activeOrder)) {
                is Result.Success -> if (result.data) {
                    paymentConfirmed = true
                    checkout = null
                    pendingCheckout = null
                    message = "赞助已确认，感谢你支持特投屏持续开发！"
                    return@LaunchedEffect
                }
                is Result.Error -> {
                    message = "暂未确认赞助：${result.message}，可稍后手动刷新。"
                    return@LaunchedEffect
                }
                Result.Loading -> Unit
            }
        }
        message = "自动查询已结束，可关闭支付页后手动刷新赞助状态。"
    }

    PageLayout(title = "赞助") {
        UsageNoticeCard()
        HeroCard(
            eyebrow = "THANK YOU",
            title = "喜欢特投屏？欢迎自愿赞助",
            body = "与 CP搭子使用同一收款渠道。选择 5、15、25、55 元，可用微信扫码赞助或支付宝在线支付。赞助完全自愿，不赞助同样能无限时使用全部功能。",
        )
        options.forEach { option ->
            Button(
                enabled = !creatingOrder && pendingOrderNo == null,
                onClick = { selectedSponsorship = option },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("自愿赞助 · ¥${option.priceCents / 100}")
            }
        }
        message?.let { Text(it, color = Color(0xFFB8C7D9)) }
        OutlinedButton(
            enabled = !loading && !confirmingPayment && !creatingOrder,
            onClick = { pendingOrderNo?.let(::confirmPayment) ?: loadOptions() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (loading || confirmingPayment) "正在查询…" else if (pendingOrderNo != null) "刷新赞助状态" else "刷新赞助选项")
        }
        pendingOrderNo?.let { orderNo ->
            Text("待确认订单：$orderNo", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = !confirmingPayment,
                onClick = { checkout = pendingCheckout },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("继续支付这笔赞助") }
            TextButton(
                enabled = !confirmingPayment,
                onClick = {
                    pendingCheckout = null
                    message = "已退出本笔查询，原订单不会自动付款；请勿重复支付。可重新选择赞助金额。"
                },
            ) { Text("重新选择金额") }
        }
        sponsorshipAcknowledgmentCard {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shilapi/xcertplay")),
            )
        }
    }

    selectedSponsorship?.let { option ->
        AlertDialog(
            onDismissRequest = { selectedSponsorship = null },
            title = { Text("自愿赞助 ¥${option.priceCents / 100}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.sponsor),
                        contentDescription = "与 CP搭子相同的微信收款码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(12.dp)
                            .heightIn(max = 280.dp),
                    )
                    Text("微信赞助：截图后用微信扫一扫的相册识别，或使用另一台手机扫码。请核对收款方，并手动输入 ${option.priceCents / 100} 元。")
                    Text("微信扫码不会自动回传支付结果，App 不会据此声称到账或解锁功能；所有功能始终免费。")
                    if (!sponsorshipAvailable) {
                        Text("支付宝在线支付暂不可用，可先使用上方微信收款码。")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = sponsorshipAvailable && !creatingOrder,
                    onClick = { createSponsorshipOrder(option) },
                ) { Text("支付宝在线支付") }
            },
            dismissButton = {
                TextButton(onClick = { selectedSponsorship = null }) { Text("关闭") }
            },
        )
    }

    checkout?.let { activeCheckout ->
        CheckoutDialog(
            checkout = activeCheckout,
            confirmingPayment = confirmingPayment,
            onPaymentReturn = { confirmPayment(activeCheckout.orderNo) },
            onDismiss = { checkout = null },
        )
    }
}

@Composable
private fun sponsorshipAcknowledgmentCard(onOpenProject: () -> Unit) {
    GlassCard {
        Text(
            "特别鸣谢",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "CarPlay 能力基于程序媛小姐姐的 xcertplay 项目进行适配与完善，感谢原作者的开源贡献。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenProject) {
            Text("查看 xcertplay 项目")
        }
    }
}

@Composable
private fun CheckoutDialog(
    checkout: SponsorshipCheckout,
    confirmingPayment: Boolean,
    onPaymentReturn: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF08111F)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1320))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("自愿赞助", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            if (confirmingPayment) "正在确认赞助结果..." else "感谢支持开发！不赞助也能免费使用全部功能。",
                            color = Color(0xFFB8C7D9),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (isPaymentReturnUrl(url)) {
                                        view.loadUrl(url)
                                        onPaymentReturn()
                                        return false
                                    }
                                    if (shouldOpenInPaymentApp(url)) {
                                        openPaymentApp(view.context, url)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (isPaymentReturnUrl(url)) {
                                        onPaymentReturn()
                                    }
                                }
                            }
                            loadUrl(checkout.checkoutUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url == null) {
                            webView.loadUrl(checkout.checkoutUrl)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PageLayout(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF38BDF8)),
                )
                Text("特投屏5.0", color = Color(0xFF7DD3FC), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(BuildConfig.VERSION_NAME, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            }
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun HeroCard(eyebrow: String, title: String, body: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF123D56), Color(0xFF17345A), Color(0xFF111C30))))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(eyebrow, color = Color(0xFFA7F3FF), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            body?.let { Text(it, color = Color(0xFFE0F2FE), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF0111F30)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun StatusDot(ready: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (ready) Color(0xFF14634F) else Color(0xFF7A5420)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (ready) "✓" else "!", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SelectionRow(label: String, current: String, options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White)
        TextButton(onClick = { expanded = true }) { Text(current) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (optionLabel, select) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = { select(); expanded = false })
            }
        }
    }
}

private fun startProjection(
    context: Context,
    mode: ConnectionMode,
    app: ProjectionAppChoice,
) {
    runCatching {
        ProjectionService.start(
            context = context,
            mode = mode,
            target = app.target ?: MapProjectionTarget.AMAP_AUTO,
            targetPackageName = app.packageName,
            targetDisplayName = app.displayName,
            targetActivityClassName = app.activityClassName,
        )
    }.onFailure { error ->
        ProjectionState.reset("启动失败：${error.rootMessage()}")
        Toast.makeText(context, "投屏启动失败：${error.rootMessage()}", Toast.LENGTH_LONG).show()
    }
}

private fun sendAmapShortcut(context: Context, destination: Int) {
    context.sendBroadcast(
        Intent("AUTONAVI_STANDARD_BROADCAST_RECV")
            .setPackage(MapProjectionTarget.AMAP_AUTO.packageName)
            .putExtra("KEY_TYPE", 10040)
            .putExtra("DEST", destination)
            .putExtra("IS_START_NAVI", 0),
    )
    Toast.makeText(context, if (destination == 0) "已发起回家导航" else "已发起公司导航", Toast.LENGTH_SHORT).show()
}

private fun isNetworkReady(context: Context): Boolean {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}

private data class CarPlayDeviceReadiness(
    val usbDeviceCount: Int,
    val ch341Attached: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothBondedCount: Int,
    val bluetoothConnected: Boolean,
)

@SuppressLint("MissingPermission")
private fun inspectCarPlayDevices(
    context: Context,
    bluetoothPermissionReady: Boolean,
    refreshTick: Int,
): CarPlayDeviceReadiness {
    refreshTick.hashCode()
    val usbDevices = context.getSystemService(UsbManager::class.java)
        ?.deviceList
        ?.values
        .orEmpty()
    val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    val bluetoothEnabled = if (bluetoothPermissionReady) {
        runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)
    } else {
        false
    }
    val bondedCount = if (bluetoothPermissionReady && bluetoothEnabled) {
        runCatching { bluetoothAdapter?.bondedDevices?.size ?: 0 }.getOrDefault(0)
    } else {
        0
    }
    val connected = if (bluetoothPermissionReady && bluetoothEnabled) {
        listOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            BluetoothProfile.GATT,
        ).any { profile ->
            runCatching {
                bluetoothAdapter?.getProfileConnectionState(profile) ==
                    BluetoothAdapter.STATE_CONNECTED
            }.getOrDefault(false)
        }
    } else {
        false
    }
    return CarPlayDeviceReadiness(
        usbDeviceCount = usbDevices.size,
        ch341Attached = usbDevices.any { device ->
            device.vendorId == XCERTPLAY_CH341_VENDOR_ID &&
                device.productId == XCERTPLAY_CH341_PRODUCT_ID
        },
        bluetoothEnabled = bluetoothEnabled,
        bluetoothBondedCount = bondedCount,
        bluetoothConnected = connected,
    )
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess
}

private fun openPrivilegeManager(context: Context): Boolean {
    listOf(STELLAR_PACKAGE, SHIZUKU_PACKAGE).forEach { packageName ->
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            context.startActivity(intent)
            return true
        }
    }
    return false
}

private fun rememberProjectionApps(context: Context, refreshTick: Int): List<ProjectionAppChoice> {
    return refreshTick.let {
        (0 until CUSTOM_PROJECTION_APP_SLOTS).map { slotIndex ->
            loadCustomProjectionApp(context, slotIndex) ?: ProjectionAppChoice(
                displayName = "自定义 ${slotIndex + 1}",
                packageName = "custom.placeholder.$slotIndex",
                installed = false,
                badge = "+",
                customSlotIndex = slotIndex,
            )
        }
    }
}

private fun rememberLaunchableProjectionApps(context: Context, refreshTick: Int): List<ProjectionAppChoice> {
    val packageManager = context.packageManager
    val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return refreshTick.let {
        packageManager.queryIntentActivities(launchIntent, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.loadLabel(packageManager).toString() }
            .map { appInfo ->
                val label = appInfo.loadLabel(packageManager).toString()
                ProjectionAppChoice(
                    displayName = label.takeIf { it.isNotBlank() } ?: appInfo.packageName,
                    packageName = appInfo.packageName,
                    installed = true,
                    badge = label.take(1).ifBlank { "A" },
                    target = MapProjectionTarget.entries.firstOrNull { it.packageName == appInfo.packageName },
                )
            }
            .toList()
    }
}

private fun loadCustomProjectionApp(context: Context, slotIndex: Int): ProjectionAppChoice? {
    val prefs = context.getSharedPreferences(PREF_CUSTOM_PROJECTION_APPS, Context.MODE_PRIVATE)
    val packageName = prefs.getString(customAppPackageKey(slotIndex), null)?.takeIf { it.isNotBlank() }
        ?: return null
    val packageManager = context.packageManager
    if (!isPackageInstalled(context, packageName)) {
        return ProjectionAppChoice(
            displayName = prefs.getString(customAppLabelKey(slotIndex), null) ?: "自定义 ${slotIndex + 1}",
            packageName = packageName,
            installed = false,
            badge = "!",
            customSlotIndex = slotIndex,
            target = MapProjectionTarget.entries.firstOrNull { it.packageName == packageName },
        )
    }
    val label = runCatching {
        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
    }.getOrDefault(prefs.getString(customAppLabelKey(slotIndex), null) ?: packageName)
    return ProjectionAppChoice(
        displayName = label,
        packageName = packageName,
        installed = true,
        badge = label.take(1).ifBlank { "A" },
        customSlotIndex = slotIndex,
        target = MapProjectionTarget.entries.firstOrNull { it.packageName == packageName },
    )
}

private fun saveCustomProjectionApp(context: Context, slotIndex: Int, app: ProjectionAppChoice) {
    val prefs = context.getSharedPreferences(PREF_CUSTOM_PROJECTION_APPS, Context.MODE_PRIVATE)
    prefs.edit().apply {
        (0 until CUSTOM_PROJECTION_APP_SLOTS).forEach { index ->
            if (index != slotIndex && prefs.getString(customAppPackageKey(index), null) == app.packageName) {
                remove(customAppPackageKey(index))
                remove(customAppLabelKey(index))
            }
        }
        putString(customAppPackageKey(slotIndex), app.packageName)
        putString(customAppLabelKey(slotIndex), app.displayName)
        apply()
    }
}

private fun clearCustomProjectionApp(context: Context, slotIndex: Int) {
    context.getSharedPreferences(PREF_CUSTOM_PROJECTION_APPS, Context.MODE_PRIVATE)
        .edit()
        .remove(customAppPackageKey(slotIndex))
        .remove(customAppLabelKey(slotIndex))
        .apply()
}

private fun customAppPackageKey(slotIndex: Int): String = "slot_${slotIndex}_package"

private fun customAppLabelKey(slotIndex: Int): String = "slot_${slotIndex}_label"

private fun isPrivilegeServiceRunning(): Boolean =
    runCatching { Shizuku.pingBinder() }.getOrDefault(false)

private fun hasPrivilegePermission(): Boolean =
    runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

private suspend fun awaitProjectionNetwork(context: Context, mode: ConnectionMode): Boolean {
    if (mode != ConnectionMode.TESLA || TeslaVirtualIpService.isReady()) return false
    try {
        TeslaVirtualIpService.start(context)
        withTimeout(10_000) {
            while (!TeslaVirtualIpService.isReady()) delay(100)
        }
        return true
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        TeslaVirtualIpService.stop(context)
        throw IllegalStateException("Tesla 固定地址准备超时，请重试", error)
    } catch (error: Exception) {
        TeslaVirtualIpService.stop(context)
        throw error
    }
}

private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: current.javaClass.simpleName
}

private const val SHIZUKU_REQUEST_CODE = 8101
private const val STELLAR_PACKAGE = "roro.stellar.manager"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val CARPLAY_HOST_ACTIVITY = "com.shilapi.xcertplay.CarPlayHostActivity"
private const val XCERTPLAY_CH341_VENDOR_ID = 0x1A86
private const val XCERTPLAY_CH341_PRODUCT_ID = 0x5512
internal fun requiredCarPlayPermissions(sdk: Int): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.RECORD_AUDIO)
    if (sdk >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}
private const val PAYMENT_CONFIRMATION_ATTEMPTS = 90
private const val PAYMENT_CONFIRMATION_INTERVAL_MS = 2_000L
private const val CUSTOM_PROJECTION_APP_SLOTS = 6
private const val DEFAULT_PROJECTION_PORT = 8080
private const val PREF_CUSTOM_PROJECTION_APPS = "custom_projection_apps"

private fun isPaymentReturnUrl(url: String): Boolean =
    url.contains("/payments/zpay/return", ignoreCase = true) ||
        url.contains("trade_status=TRADE_SUCCESS", ignoreCase = true)

private fun shouldOpenInPaymentApp(url: String): Boolean =
    !url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)

private fun openPaymentApp(context: Context, url: String) {
    val intent = if (url.startsWith("intent://", ignoreCase = true)) {
        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "未找到可处理该支付方式的 App", Toast.LENGTH_SHORT).show()
        }
}

private fun defaultAccessUrl(mode: ConnectionMode): String =
    if (mode == ConnectionMode.TESLA) {
        "http://${TeslaVirtualIpService.CLIENT_ADDRESS}:$DEFAULT_PROJECTION_PORT"
    } else {
        "http://手机局域网IP:$DEFAULT_PROJECTION_PORT"
    }
