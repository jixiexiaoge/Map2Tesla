package com.navipilot.airplay

import com.navipilot.amap2tesla.ConnectionMode

object AirPlayProjectionState {
    @Volatile
    var receiverRunning = false

    @Volatile
    var connected = false

    @Volatile
    var browserProjectionRunning = false

    val waitingForSender: Boolean
        get() = browserProjectionRunning && !connected

    @Volatile
    var status = "未启动"

    @Volatile
    var senderName: String? = null

    @Volatile
    var pairingCode: String? = null

    @Volatile
    var receiverName = "特投屏 AirPlay"

    @Volatile
    var accessUrl: String? = null

    @Volatile
    var connectionMode = ConnectionMode.TESLA

    const val trialMode = false

    fun reset(statusText: String = "未启动") {
        receiverRunning = false
        connected = false
        browserProjectionRunning = false
        status = statusText
        senderName = null
        pairingCode = null
        accessUrl = null
    }
}
