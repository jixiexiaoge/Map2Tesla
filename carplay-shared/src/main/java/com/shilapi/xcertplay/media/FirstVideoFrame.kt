package com.shilapi.xcertplay.media

/** One notification per stream, even when its decoder is recreated during recovery. */
internal class FirstVideoFrame {
    private var notified = false

    @Synchronized fun rendered(callback: () -> Unit) {
        if (notified) return
        notified = true
        callback()
    }
}
