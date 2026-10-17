package com.shilapi.xcertplay.network

import java.io.IOException
import java.security.MessageDigest

/** Reinstall-stable namespace; never treats a generic DIRECT-* name as ours. */
internal object P2pOwnership {
    fun prefix(packageName: String, scopedId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$scopedId".toByteArray(Charsets.UTF_8))
        return "DIRECT-dp" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 255) } + "-"
    }

    fun canReclaim(isOwner: Boolean, ssid: String?, recorded: String?, prefix: String): Boolean =
        isOwner && !ssid.isNullOrBlank() &&
            (ssid == recorded || (ssid.startsWith(prefix) &&
                ssid.removePrefix(prefix).matches(Regex("[A-Za-z0-9]{4}"))))

    /** Stop/cancel never uses a namespace or an old preference to claim the current group. */
    fun canRemove(
        isOwner: Boolean,
        ssid: String?,
        passphrase: String?,
        expectedSsid: String?,
        expectedPassphrase: String?,
    ): Boolean = isOwner && !expectedSsid.isNullOrBlank() && ssid == expectedSsid &&
        !expectedPassphrase.isNullOrBlank() && passphrase == expectedPassphrase
}

class P2pResetRequiredException : IOException("An existing Wi-Fi Direct connection needs a reset")
