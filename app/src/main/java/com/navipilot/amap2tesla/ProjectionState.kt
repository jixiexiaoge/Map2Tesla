package com.navipilot.amap2tesla

object ProjectionState {
    @Volatile
    var running = false

    @Volatile
    var status = "未启动"

    @Volatile
    var accessUrl: String? = null

    @Volatile
    var displayId: Int? = null

    @Volatile
    var width = 0

    @Volatile
    var height = 0

    @Volatile
    var densityDpi = 0

    @Volatile
    var targetFps = 0

    const val trialMode = false

    @Volatile
    var currentPackageName: String? = null

    @Volatile
    var currentActivityClassName: String? = null

    fun reset(statusText: String = "未启动") {
        running = false
        status = statusText
        accessUrl = null
        displayId = null
        width = 0
        height = 0
        densityDpi = 0
        targetFps = 0
        currentPackageName = null
        currentActivityClassName = null
    }
}
