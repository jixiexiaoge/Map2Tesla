package com.navipilot.amap2tesla.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.jixiexiaoge.drivingassist.MainActivity
import java.net.NetworkInterface
import java.util.Collections

class TeslaVirtualIpService : VpnService() {
    private val handler = Handler(Looper.getMainLooper())
    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private var handedOff = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备 Tesla 固定访问地址"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeVirtualInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (hasOwnedReadyAddress()) {
            updateNotification("Tesla 固定访问地址 $CLIENT_ADDRESS 已就绪")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (interfaceDescriptor == null) {
            try {
                synchronized(DESCRIPTOR_LOCK) {
                    runCatching { dormantDescriptor?.close() }
                    dormantDescriptor = null
                }
                interfaceDescriptor = Builder()
                    .setSession("Amap2Tesla fixed local address")
                    .addAddress(CLIENT_ADDRESS, 32)
                    .setMtu(1500)
                    .establish()
                    ?: error("Android 未能创建临时 VPN 接口")
                handler.postDelayed(::retireActiveVpn, ROUTE_RETIRE_DELAY_MS)
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to establish Tesla virtual IP", error)
                closeVirtualInterface()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        closeVirtualInterface()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!handedOff) {
            handler.removeCallbacksAndMessages(null)
            runCatching { interfaceDescriptor?.close() }
            interfaceDescriptor = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Amap2Tesla Tesla 网络",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(content: String): Notification {
        val activity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TeslaVirtualIpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("Amap2Tesla Tesla 模式")
            .setContentText(content)
            .setContentIntent(activity)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stop)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun retireActiveVpn() {
        val fixedAddressDescriptor = interfaceDescriptor
            ?: return failBootstrap("临时 VPN 接口已丢失")
        interfaceDescriptor = null

        try {
            synchronized(DESCRIPTOR_LOCK) {
                runCatching { dormantDescriptor?.close() }
                dormantDescriptor = fixedAddressDescriptor
            }
            handedOff = true

            Builder()
                .setSession("Amap2Tesla route retirement")
                .addAddress(RETIREMENT_ADDRESS, 32)
                .setMtu(1500)
                .establish()
                ?.use { }
                ?: error("Android 未能创建替代 VPN 接口")

            handler.postDelayed(
                {
                    if (hasOwnedReadyAddress()) {
                        Log.i(TAG, "Dormant Tesla route ready: $CLIENT_ADDRESS")
                    } else {
                        Log.e(TAG, "Dormant Tesla route did not remain registered")
                        closeVirtualInterface()
                    }
                },
                ROUTE_VERIFY_DELAY_MS
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to retire active VPN route", error)
            closeVirtualInterface()
            stopSelf()
        }
    }

    private fun failBootstrap(message: String) {
        Log.e(TAG, message)
        closeVirtualInterface()
        stopSelf()
    }

    private fun closeVirtualInterface() {
        runCatching { interfaceDescriptor?.close() }
            .onFailure { error -> Log.w(TAG, "Failed to close Tesla virtual interface", error) }
        interfaceDescriptor = null
        synchronized(DESCRIPTOR_LOCK) {
            runCatching { dormantDescriptor?.close() }
                .onFailure { error ->
                    Log.w(TAG, "Failed to close dormant Tesla interface", error)
                }
            dormantDescriptor = null
        }
        handedOff = false
    }

    companion object {
        const val CLIENT_ADDRESS = "99.99.99.99"
        private const val RETIREMENT_ADDRESS = "192.0.2.1"
        private const val ROUTE_RETIRE_DELAY_MS = 150L
        private const val ROUTE_VERIFY_DELAY_MS = 300L
        private const val TAG = "Amap2TeslaVpn"
        private const val CHANNEL_ID = "navipilot_amap_tesla_network"
        private const val NOTIFICATION_ID = 7102
        private const val ACTION_STOP = "com.jixiexiaoge.drivingassist.AMAP_TESLA_VPN_STOP"
        private val DESCRIPTOR_LOCK = Any()

        @Volatile
        private var dormantDescriptor: ParcelFileDescriptor? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TeslaVirtualIpService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TeslaVirtualIpService::class.java).setAction(ACTION_STOP)
            )
        }

        fun isReady(): Boolean {
            return runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .asSequence()
                    .flatMap { Collections.list(it.inetAddresses).asSequence() }
                    .any { it.hostAddress == CLIENT_ADDRESS }
            }.getOrDefault(false)
        }

        private fun hasOwnedReadyAddress(): Boolean {
            return synchronized(DESCRIPTOR_LOCK) {
                dormantDescriptor?.fileDescriptor?.valid() == true && isReady()
            }
        }
    }
}
