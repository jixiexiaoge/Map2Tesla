package com.shilapi.xcertplay.network

import android.content.Context
import java.util.UUID

/** Only radio settings are stored: never network credentials, phone identifiers or addresses. */
internal class P2pConfigurationMemory(context: Context) {
    private val prefs = context.getSharedPreferences("carplay_wifi_p2p_success", Context.MODE_PRIVATE)
    data class Record(val encoded: String, val request: P2pCreationRequest, val stationMHz: Int?)

    fun read(): Record? = runCatching { decode(prefs.getString("confirmed", null)) }.getOrNull()

    fun remember(request: P2pCreationRequest, actualMHz: Int, stationMHz: Int?): Boolean {
        val kind = if (request.mode == P2pCreationMode.SYSTEM_DEFAULT) "system" else "frequency"
        val encoded = "$kind|${request.frequencyMHz ?: 0}|$actualMHz|${stationMHz ?: 0}|${UUID.randomUUID()}"
        if (decode(encoded) == null) return false
        return synchronized(prefs) { prefs.edit().putString("confirmed", encoded).commit() }
    }

    /** A late close from an older manager must not erase a newer successful session. */
    fun forget(record: Record): Boolean = runCatching { synchronized(prefs) {
        if (prefs.getString("confirmed", null) != record.encoded) false
        else prefs.edit().remove("confirmed").commit()
    } }.getOrDefault(false)

    companion object {
        internal fun decode(encoded: String?): Record? = runCatching {
            if (encoded == null) return null
            val fields = encoded.split('|')
            if (fields.size != 5) return null
            val requested = fields[1].toInt()
            val request = when (fields[0]) {
                "system" -> if (requested == 0) P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT) else return null
                "frequency" -> P2pStartupRecovery.rememberedFrequency(requested) ?: return null
                else -> return null
            }
            val actual = fields[2].toInt()
            if (actual !in 2412..5895 || wifiFrequencyMhzToChannel(actual) == null) return null
            val station = fields[3].toInt()
            if (station != 0 && wifiFrequencyMhzToChannel(station) == null) return null
            UUID.fromString(fields[4])
            Record(encoded, request, station.takeIf { it > 0 })
        }.getOrNull()
    }
}
