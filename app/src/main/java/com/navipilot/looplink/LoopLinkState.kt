package com.navipilot.looplink

import java.time.LocalTime
import java.time.format.DateTimeFormatter

object LoopLinkState {
    @Volatile var running = false
    @Volatile var status = "LoopLink 尚未启动"
    @Volatile var lanUrl: String? = null
    @Volatile var teslaUrl: String? = null

    @Volatile private var storedDiagnostics: List<String> = emptyList()

    val diagnostics: List<String>
        get() = storedDiagnostics

    @Synchronized
    fun addDiagnostic(message: String) {
        val safeMessage = LoopLinkDiagnosticSanitizer.redact(message)
            .replace(Regex("\\s+"), " ")
            .take(MAX_MESSAGE_LENGTH)
        if (safeMessage.isBlank()) return
        if (safeMessage.startsWith("TRACE ")) return
        val previous = storedDiagnostics.lastOrNull()?.substringAfter("] ")
        val now = System.currentTimeMillis()
        if (previous == safeMessage && now - lastDiagnosticAt < DUPLICATE_WINDOW_MS) return
        lastDiagnosticAt = now
        storedDiagnostics = (storedDiagnostics + "[${CLOCK.format(LocalTime.now())}] $safeMessage")
            .takeLast(MAX_ENTRIES)
    }

    @Synchronized
    fun clearDiagnostics() {
        storedDiagnostics = emptyList()
        lastDiagnosticAt = 0L
    }

    private var lastDiagnosticAt = 0L

    private const val MAX_ENTRIES = 100
    private const val MAX_MESSAGE_LENGTH = 400
    private const val DUPLICATE_WINDOW_MS = 2_000L
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
}

internal object LoopLinkDiagnosticSanitizer {
    private val SENSITIVE_ASSIGNMENT = Regex(
        "(?i)(identity\\.pk8|certificate\\.p7b|private[ _-]?key|token|password|passphrase|" +
            "challenge|signature|ssid|serial(?:number)?|deviceid|bssid)(\\s*[:=]\\s*)[^\\s,;]+",
    )
    private val MAC_ADDRESS = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val LONG_HEX = Regex("(?i)\\b(?:0x)?[0-9a-f]{32,}\\b")
    private val LONG_BASE64 = Regex("\\b[A-Za-z0-9+/]{80,}={0,2}\\b")

    fun redact(message: String): String = message
        .replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[已隐藏]"
        }
        .replace(MAC_ADDRESS, "[设备地址]")
        .replace(LONG_HEX, "[二进制数据]")
        .replace(LONG_BASE64, "[二进制数据]")
}
