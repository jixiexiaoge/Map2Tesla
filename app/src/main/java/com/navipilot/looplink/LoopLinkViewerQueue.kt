package com.navipilot.looplink

import java.io.Closeable
import java.util.ArrayDeque

internal class LoopLinkViewerQueue : Closeable {
    private data class Packet(val data: ByteArray, val video: Boolean)
    private val lock = Object()
    private val packets = ArrayDeque<Packet>()
    private var waitingForKey = true
    private var skipRasl = true
    private var videoCount = 0
    private var videoBytes = 0L
    private var closed = false

    fun reset() = synchronized(lock) {
        packets.clear()
        videoCount = 0
        videoBytes = 0L
        waitingForKey = true
        skipRasl = true
    }

    fun config(data: ByteArray) = synchronized(lock) {
        packets.removeIf { it.video || it.data[0] == 1.toByte() }
        videoCount = 0
        videoBytes = 0L
        waitingForKey = true
        skipRasl = true
        if (!closed) { packets.addFirst(Packet(data, false)); lock.notifyAll() }
    }

    fun clearAudio() = synchronized(lock) {
        packets.removeIf { !it.video && it.data[0] in byteArrayOf(3, 4) }
    }

    /** True means the consumer needs another random-access frame. */
    fun video(frame: LoopLinkVideoPacket.Frame): Boolean = synchronized(lock) {
        if (closed) return false
        if (frame.message.size > MAX_BYTES) { reset(); return true }
        if (videoCount >= 12 || videoBytes + frame.message.size > MAX_BYTES) {
            packets.removeIf { it.video }
            videoCount = 0
            videoBytes = 0L
            waitingForKey = true
            skipRasl = true
        }
        if (waitingForKey && !frame.key) return true
        if (frame.key) { waitingForKey = false; skipRasl = true }
        else if (skipRasl && frame.rasl) return false
        else skipRasl = false
        packets.add(Packet(frame.message, true))
        videoCount++
        videoBytes += frame.message.size
        lock.notifyAll()
        false
    }

    fun audio(data: ByteArray) = synchronized(lock) {
        if (closed) return
        while (packets.count { !it.video && it.data[0] == 3.toByte() } >= 25) {
            val oldest = packets.first { !it.video && it.data[0] == 3.toByte() }
            packets.remove(oldest)
        }
        packets.add(Packet(data, false))
        lock.notifyAll()
    }

    fun next(): ByteArray? = synchronized(lock) {
        while (!closed && packets.isEmpty()) lock.wait()
        if (closed) return null
        val audio = if (packets.first.data[0] != 1.toByte()) {
            packets.firstOrNull { !it.video && it.data[0] in byteArrayOf(3, 4) }
        } else null
        val packet = if (audio != null) audio.also { packets.remove(it) } else packets.removeFirst()
        if (packet.video) { videoCount--; videoBytes -= packet.data.size }
        packet.data
    }

    override fun close() = synchronized(lock) {
        closed = true
        reset()
        lock.notifyAll()
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
