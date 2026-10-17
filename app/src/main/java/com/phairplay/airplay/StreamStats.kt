package com.phairplay.airplay

/**
 * StreamStats — live counters for the optional on-screen debug overlay (Settings → "Debug overlay").
 *
 * The mirror video server ([com.phairplay.airplay.handshake.MirrorStreamServer]) and audio server
 * ([com.phairplay.airplay.handshake.AudioStreamServer]) write these volatile fields as they run;
 * [com.phairplay.ui.StreamingScreen] polls [summary] a few times a second to render the HUD. Plain
 * volatile ints keep the hot path allocation-free — no flows or locks on the per-frame path.
 *
 * [overlayEnabled] is set from [com.phairplay.settings.AppSettings.showDebugOverlay] when the AirPlay
 * receiver starts, so the HUD appears for the next mirroring session after the toggle is flipped.
 */
object StreamStats {
    /** Master switch, mirrored from the user setting; the overlay only draws when true. */
    @Volatile var overlayEnabled = false

    // ─── Video (MirrorStreamServer) ──────────────────────────────────────────
    @Volatile var videoRes = ""        // e.g. "1920x1080"
    @Volatile var videoFps = 0         // frames/sec over the last sample window
    @Volatile var videoQueue = 0       // current decode-queue depth
    @Volatile var videoDropPct = 0     // cumulative % of frames dropped under load

    // Actual decoded video dimensions (from the SPS, so portrait phone streams are portrait here).
    // StreamingScreen reads these to aspect-fit the Surface instead of stretching to 16:9.
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0

    // ─── Sender-reported (mirror stream types 0x02 / 0x05) ───────────────────
    // The sender's own view of the stream. Comparing clientFps with videoFps says where a
    // problem is: equal and low means the network or the sender, ours lower means our decoder.
    @Volatile var clientFps = 0            // fps the sender's encoder reports producing
    @Volatile var clientRttMs = 0          // round-trip time the sender measures to us
    @Volatile var clientLossPct = 0.0      // frame loss the sender reports, percent
    @Volatile var clientTxMbps = 0.0       // bitrate the sender is currently using
    @Volatile var clientCapacityMbps = 0.0 // link capacity the sender estimates
    @Volatile var clientHeartbeats = 0     // once-per-second keepalives received
    @Volatile var clientScreenLocked = false  // sender appends a 25 kB trailer while locked

    // ─── Audio (AudioStreamServer) ───────────────────────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates

    /** Clears per-stream counters (call when a mirror session ends). Keeps [overlayEnabled]. */
    fun resetStreams() {
        videoRes = ""; videoFps = 0; videoQueue = 0; videoDropPct = 0
        videoWidth = 0; videoHeight = 0
        clientFps = 0; clientRttMs = 0; clientLossPct = 0.0
        clientTxMbps = 0.0; clientCapacityMbps = 0.0
        clientHeartbeats = 0; clientScreenLocked = false
        audioActive = false; audioQueue = 0; audioDupPct = 0
    }

    /**
     * Human-readable multi-line HUD text. Numbers are formatted with [Locale.ROOT] so the
     * overlay and the logs agree regardless of the phone's locale.
     */
    fun summary(): String =
        "PhairPlay · debug\n" +
        "VIDEO  ${videoRes.ifEmpty { "—" }}   ${videoFps} fps   q ${videoQueue}   drop ${videoDropPct}%\n" +
        "SENDER ${clientFps} fps   rtt ${clientRttMs}ms   loss ${fmt("%.2f", clientLossPct)}%\n" +
        "LINK   ${fmt("%.1f", clientTxMbps)}/${fmt("%.1f", clientCapacityMbps)} Mbps" +
        (if (clientScreenLocked) "   sender screen locked" else "") + "\n" +
        "AUDIO  " + (if (audioActive) "on   q ${audioQueue}   dup ${audioDupPct}%" else "off")

    private fun fmt(pattern: String, value: Double): String =
        String.format(java.util.Locale.ROOT, pattern, value)
}
