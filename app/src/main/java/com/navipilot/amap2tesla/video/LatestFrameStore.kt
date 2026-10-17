package com.navipilot.amap2tesla.video

class LatestFrameStore {
    private var frame: ByteArray? = null
    private var sequence = 0L
    private var latestFrameAt = 0L
    private var fpsWindowStartedAt = android.os.SystemClock.elapsedRealtime()
    private var fpsWindowFrames = 0
    private var encodedFps = 0f

    @Synchronized
    fun publish(bytes: ByteArray) {
        val now = android.os.SystemClock.elapsedRealtime()
        frame = bytes
        sequence++
        latestFrameAt = now
        fpsWindowFrames++
        val windowMillis = now - fpsWindowStartedAt
        if (windowMillis >= 1_000L) {
            encodedFps = fpsWindowFrames * 1_000f / windowMillis
            fpsWindowStartedAt = now
            fpsWindowFrames = 0
        }
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized
    fun clear() {
        frame = null
        latestFrameAt = 0L
        fpsWindowStartedAt = android.os.SystemClock.elapsedRealtime()
        fpsWindowFrames = 0
        encodedFps = 0f
    }

    @Synchronized
    fun awaitAfter(previousSequence: Long, timeoutMillis: Long): Snapshot? {
        if (sequence <= previousSequence) {
            (this as java.lang.Object).wait(timeoutMillis)
        }
        val current = frame ?: return null
        return Snapshot(sequence, current)
    }

    @Synchronized
    fun metrics(): Metrics {
        val now = android.os.SystemClock.elapsedRealtime()
        return Metrics(
            sequence = sequence,
            encodedFps = encodedFps,
            frameAgeMillis = if (latestFrameAt == 0L) -1L else now - latestFrameAt
        )
    }

    data class Snapshot(val sequence: Long, val bytes: ByteArray)

    data class Metrics(
        val sequence: Long,
        val encodedFps: Float,
        val frameAgeMillis: Long
    )
}
