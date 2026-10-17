package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    init {
        require(frameBytes > 0)
    }

    fun written(bytes: Int) {
        require(bytes >= 0)
        writtenBytes += bytes
    }

    fun reset() {
        writtenBytes = 0L
        playedFrames = 0L
        lastHead = 0L
    }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun shouldRebuffer(audioType: String, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int): Boolean =
        audioType == "media" && playing && underrunSinceStart && compressedQueueEmpty && queuedBytes(rawHead) == 0L

    fun shouldStartTail(
        playing: Boolean,
        prebufferBytes: Int,
        compressedQueueEmpty: Boolean,
        lastWriteNs: Long,
        nowNs: Long,
    ): Boolean = !playing && prebufferBytes > 0 && compressedQueueEmpty &&
        nowNs - lastWriteNs >= TAIL_WAIT_NS

    private companion object {
        const val TAIL_WAIT_NS = 500_000_000L
    }
}
