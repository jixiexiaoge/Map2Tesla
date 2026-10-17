package com.navipilot.amap2tesla.audio

class LatestPcmStore {
    private var pcm: ByteArray? = null
    private var sequence = 0L
    private var sampleRate = 0
    private var channels = 0

    @Synchronized
    fun publish(bytes: ByteArray, sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels !in 1..2 || bytes.isEmpty()) return
        this.sampleRate = sampleRate
        this.channels = channels
        pcm = bytes.copyOf()
        sequence++
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized
    fun awaitAfter(previousSequence: Long, timeoutMillis: Long): Snapshot? {
        if (sequence <= previousSequence) {
            (this as java.lang.Object).wait(timeoutMillis)
        }
        if (sequence <= previousSequence) return null
        val current = pcm ?: return null
        return Snapshot(sequence, current, sampleRate, channels)
    }

    @Synchronized
    fun metrics() = Metrics(sequence, sampleRate, channels)

    @Synchronized
    fun clear() {
        pcm = null
        sampleRate = 0
        channels = 0
        (this as java.lang.Object).notifyAll()
    }

    data class Snapshot(
        val sequence: Long,
        val bytes: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    data class Metrics(
        val sequence: Long,
        val sampleRate: Int,
        val channels: Int,
    )
}
