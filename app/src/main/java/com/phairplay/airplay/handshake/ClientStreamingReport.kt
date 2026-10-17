package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import java.util.Locale

/**
 * ClientStreamingReport — the sender's own view of the mirroring stream, sent once per second
 * as a [MirrorPacket.Type.STREAMING_REPORT] packet.
 *
 * WHY: this is the only feedback the sender volunteers, and the receiver used to discard it
 * unread. It answers the question our own counters cannot: when the picture is bad, is the
 * sender failing to produce frames, is the network dropping them, or is our decoder behind?
 * [encoderFps] versus our own decode rate separates the first case from the third, and
 * [lossAvg] with [txUsageAvg]/[txCapacityAvg] separates out the second.
 *
 * The payload is a binary plist. When the sending device's screen is locked, a fixed
 * 25,000-byte trailer of unidentified content follows it — the plist must be parsed from the
 * leading bytes only, or the parse fails and the report is lost precisely when the situation
 * is unusual.
 *
 * Field names are Apple's and undocumented. The set below was read off a live iOS session
 * (see docs/reference/streaming-report.md); [entries] keeps the raw map so a sender that
 * reports something new does not lose it.
 */
data class ClientStreamingReport(
    /** Frames per second the sender's encoder is producing (`encoderCurrentFPS`). */
    val encoderFps: Int?,
    /** Frames per second actually submitted to the stream (`submitSurfaceFPS`). */
    val submittedFps: Int?,
    /** Frames per second arriving at the encoder (`beforeEncoderFPS`). */
    val beforeEncoderFps: Int?,
    /** Round-trip time the sender measures to us, in milliseconds (`rttAvg`). */
    val rttMs: Double?,
    /** Average fraction of frames lost, 0..1 (`lossAvg`). */
    val lossAvg: Double?,
    /** Bitrate the sender is currently using, bits/sec (`txUsageAvg`). */
    val txUsageAvg: Double?,
    /** Link capacity the sender estimates, bits/sec (`txCapacityAvg`). */
    val txCapacityAvg: Double?,
    /** Frames the sender has queued but not yet sent (`queuedFramesAvg`). */
    val queuedFrames: Int?,
    /** Every key/value pair from the plist, verbatim — including ones not named above. */
    val entries: Map<String, Any?>,
    /** Size of the trailer that followed the plist; 0 when absent (sender's screen unlocked). */
    val trailerBytes: Int
) {

    /**
     * Sum of the sender's own drop counters (`encoderDropFPS`, `encoderQueueDropFPS`,
     * `sinkOverflowDropFPS`, `idleDropFPS`). Non-zero means the sender is shedding frames
     * before they ever reach the network — not our problem to fix, but worth knowing.
     */
    val senderDroppedFps: Int
        get() = DROP_KEYS.sumOf { (entries[it] as? Number)?.toInt() ?: 0 }

    /**
     * One-line summary for logs and the debug overlay.
     *
     * Formatted with [Locale.ROOT] on purpose: these lines get grepped and parsed, and a
     * locale that writes "3,31" instead of "3.31" turns a number into two fields.
     */
    fun describe(): String = buildString {
        append("sender enc=").append(encoderFps ?: "—").append("fps")
        submittedFps?.let { append(" sent=").append(it).append("fps") }
        rttMs?.let { append(" rtt=").append(fmt("%.0f", it)).append("ms") }
        lossAvg?.let { append(" loss=").append(fmt("%.2f", it * 100)).append("%") }
        if (txUsageAvg != null && txCapacityAvg != null) {
            append(" tx=").append(fmt("%.1f", txUsageAvg / 1e6))
            append("/").append(fmt("%.1f", txCapacityAvg / 1e6)).append("Mbps")
        }
        if (senderDroppedFps > 0) append(" senderDropped=").append(senderDroppedFps)
        if (trailerBytes > 0) append(" [screen locked]")
        val unknown = entries.keys - KNOWN_KEYS
        if (unknown.isNotEmpty()) append(" new=").append(unknown.sorted().joinToString(","))
    }

    companion object {
        /**
         * Size of the trailer appended while the sending device's screen is locked. The content
         * is identical every time and its meaning is unknown; it is recorded verbatim rather
         * than interpreted.
         */
        const val LOCKED_SCREEN_TRAILER_SIZE = 25_000

        private val DROP_KEYS = listOf(
            "encoderDropFPS", "encoderQueueDropFPS", "sinkOverflowDropFPS", "idleDropFPS"
        )

        /**
         * Keys observed from a live iOS sender. Anything outside this set is flagged by
         * [describe] so a new field gets noticed rather than quietly ignored.
         */
        private val KNOWN_KEYS = setOf(
            "encoderCurrentFPS", "submitSurfaceFPS", "beforeEncoderFPS", "idleEncoderFPS",
            "rttAvg", "lossAvg", "txUsageAvg", "txCapacityAvg",
            "queuedFramesAvg", "sentFramesAvg"
        ) + DROP_KEYS

        /**
         * Splits [payload] into the plist and its optional trailer, then parses the plist.
         *
         * Returns null when the payload is empty or the plist cannot be parsed — a malformed
         * report must never interrupt mirroring, so the caller logs and carries on.
         */
        fun parse(payload: ByteArray): ClientStreamingReport? {
            if (payload.isEmpty()) return null

            val trailer = if (payload.size > LOCKED_SCREEN_TRAILER_SIZE) LOCKED_SCREEN_TRAILER_SIZE else 0
            val plistSize = payload.size - trailer
            if (plistSize <= 0) return null

            return try {
                val e = PlistCodec.decode(payload.copyOfRange(0, plistSize))
                ClientStreamingReport(
                    encoderFps = e.int("encoderCurrentFPS"),
                    submittedFps = e.int("submitSurfaceFPS"),
                    beforeEncoderFps = e.int("beforeEncoderFPS"),
                    rttMs = e.double("rttAvg"),
                    lossAvg = e.double("lossAvg"),
                    txUsageAvg = e.double("txUsageAvg"),
                    txCapacityAvg = e.double("txCapacityAvg"),
                    queuedFrames = e.int("queuedFramesAvg"),
                    entries = e,
                    trailerBytes = trailer
                )
            } catch (e: Exception) {
                Logger.w("Could not parse client streaming report (${payload.size} B): ${e.message}")
                null
            }
        }

        private fun fmt(pattern: String, value: Double): String =
            String.format(Locale.ROOT, pattern, value)

        private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
        private fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
    }
}
