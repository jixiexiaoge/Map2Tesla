package com.navipilot.looplink

import java.net.URI

internal object LoopLinkRequestGuard {
    fun isValidRequestAuthority(
        hostHeader: String?,
        originHeader: String?,
        allowedHosts: Set<String>,
        port: Int,
    ): Boolean {
        val hostUri = hostHeader?.let { runCatching { URI("http://$it") }.getOrNull() }
        val hostIsValid = hostUri?.let { isValidHost(it, allowedHosts, port) } == true
        val originIsValid = originHeader?.let { isValidOrigin(it, allowedHosts, port) } ?: true
        return hostIsValid && originIsValid
    }

    private fun isValidHost(uri: URI, allowedHosts: Set<String>, port: Int): Boolean {
        val validPort = uri.port == -1 || uri.port == port
        val hasNoExtraAuthority = uri.rawUserInfo == null &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
        return uri.host in allowedHosts && validPort && hasNoExtraAuthority
    }

    private fun isValidOrigin(value: String, allowedHosts: Set<String>, port: Int): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val hasNoExtraAuthority = uri.rawUserInfo == null &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
        return uri.scheme == "http" && uri.host in allowedHosts &&
            uri.port == port && hasNoExtraAuthority
    }
}
