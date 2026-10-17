package com.navipilot.looplink

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.navipilot.amap2tesla.video.LatestFrameStore
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.MediaTap
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoopLinkBrowserServerTest {
    @Test fun remoteOnlyVideoDoesNotStartALocalDecoderAndCloseRejectsLateFrames() {
        // Given
        val configs = CopyOnWriteArrayList<ByteArray>()
        val pictures = CopyOnWriteArrayList<ByteArray>()
        val errors = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(mediaTap = object : MediaTap {
            override fun needsDecodedVideo(type: Int) = false
            override fun onVideoConfig(type: Int, codec: VideoCodec, data: ByteArray) { configs.add(data.copyOf()) }
            override fun onVideoFrame(type: Int, annexB: ByteArray, arrivalUs: Long) { pictures.add(annexB.copyOf()) }
        }, onVideoError = { errors.add(it) })
        // When
        sink.onVideoCodec(110, VideoCodec.H265)
        sink.onVideoConfig(110, byteArrayOf(1))
        sink.onVideoFrame(110, byteArrayOf(0, 0, 1, 0x26, 1))
        sink.close()
        sink.onVideoConfig(110, byteArrayOf(2))
        sink.onVideoFrame(110, byteArrayOf(3))
        Thread.sleep(100)
        // Then
        assertEquals(1, configs.size)
        assertEquals(1, pictures.size)
        assertTrue(errors.isEmpty())
    }

    private val touches = CopyOnWriteArrayList<List<AirPlayContact>>()
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val keys = AtomicInteger()

    private fun server(frames: LatestFrameStore = LatestFrameStore()) = LoopLinkBrowserServer(
        frames, { touches.add(it); true }, "<html>USB test</html>",
        LoopLinkDisplayProfile.TESLA, { keys.incrementAndGet() }, { diagnostics.add(it) },
    ).apply { start(8_000, true) }

    private fun connection(path: String, host: String = "99.99.99.99:8088", websocket: Boolean = false): Socket {
        val socket = Socket("127.0.0.1", 8088).apply { soTimeout = 5_000 }
        val upgrade = if (websocket) {
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nOrigin: http://$host\r\n"
        } else "Connection: close\r\n"
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n$upgrade\r\n".toByteArray())
        socket.getOutputStream().flush()
        return socket
    }

    private fun headers(socket: Socket): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val value = socket.getInputStream().read()
            check(value >= 0) { "Unexpected HTTP EOF" }
            bytes.add(value.toByte())
            if (bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())) {
                return bytes.toByteArray().toString(Charsets.UTF_8)
            }
        }
    }

    private fun send(socket: Socket, value: String) {
        val data = value.toByteArray()
        require(data.size < 126)
        val header = byteArrayOf(0x81.toByte(), (0x80 or data.size).toByte(), 1, 2, 3, 4)
        val mask = header.copyOfRange(2, 6)
        socket.getOutputStream().write(header + data.mapIndexed { i, b -> (b.toInt() xor mask[i % 4].toInt()).toByte() })
        socket.getOutputStream().flush()
    }

    private fun frame(socket: Socket): Pair<Int, ByteArray> {
        val input = DataInputStream(socket.getInputStream())
        val opcode = input.readUnsignedByte() and 15
        val length = input.readUnsignedByte() and 127
        val size = when (length) { 126 -> input.readUnsignedShort(); 127 -> input.readLong().toInt(); else -> length }
        require(size in 0..4 * 1024 * 1024)
        return opcode to ByteArray(size).also(input::readFully)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    @Test fun tokenlessStatusPreservesDimensionsAndRejectsUnknownHost() {
        // Given
        val server = server()
        try {
            // When / Then
            connection("/status").use {
                assertTrue(headers(it).contains("200 OK"))
                val body = it.getInputStream().bufferedReader().readText()
                assertTrue(body.contains("\"width\":1120"))
                assertTrue(body.contains("\"revision\":\"usb-20261011-1\""))
            }
            connection("/", "untrusted.example:8088").use {
                assertTrue(headers(it).contains("403 Forbidden"))
            }
        } finally { server.stop() }
    }

    @Test fun rawVideoAndOptInPcmReachTheRealWebSocket() {
        // Given
        val server = server()
        server.onVideoActive(110, true)
        server.onVideoConfig(110, VideoCodec.H264, byteArrayOf(1, 0x64, 0, 0x1f))
        assertFalse(server.needsDecodedVideo(110))
        assertFalse(server.onAudioPcm(100, 48000, 2, byteArrayOf(1, 2, 3, 4), 0, 4))
        try {
            connection("/control", websocket = true).use {
                assertTrue(headers(it).contains("101"))
                assertEquals(1, frame(it).first)
                // When
                send(it, """{"type":"video","mode":"raw"}""")
                val configuration = frame(it)
                server.onVideoFrame(110, byteArrayOf(0, 0, 0, 1, 0x65, 0x12), 1234)
                val picture = frame(it)
                send(it, """{"type":"audio","enabled":true}""")
                await { diagnostics.any { line -> line.contains("声音已开启") } }
                assertTrue(server.onAudioPcm(100, 48000, 2, byteArrayOf(9, 1, 2, 3, 4, 9), 1, 4))
                val audio = frame(it)
                // Then
                assertEquals(2, configuration.first)
                assertArrayEquals(byteArrayOf(1, 0, 1, 0x64, 0, 0x1f), configuration.second)
                assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x65, 0x12), picture.second.copyOfRange(10, picture.second.size))
                assertArrayEquals(byteArrayOf(3, 100, 0, 0, 0xbb.toByte(), 0x80.toByte(), 2, 1, 2, 3, 4), audio.second)
                assertTrue(keys.get() > 0)
                send(it, """{"type":"audio","enabled":false}""")
                await { diagnostics.any { line -> line.contains("恢复手机播放") } }
                assertFalse(server.onAudioPcm(100, 48000, 2, byteArrayOf(1, 2), 0, 2))
            }
        } finally { server.stop() }
    }

    @Test fun lostTouchReleaseExpiresInPlaceAndDisconnectRestoresLocalAudio() {
        // Given
        val server = server()
        try {
            connection("/control", websocket = true).use {
                headers(it); frame(it)
                // When
                send(it, """{"type":"touch","contacts":[{"id":0,"x":0.8,"y":0.9}]}""")
                await { touches.isNotEmpty() }
                await { touches.last().none { contact -> contact.down } }
                // Then
                assertEquals(AirPlayContact(0, 0.8, 0.9, false), touches.last()[0])
                send(it, """{"type":"touch","contacts":[{"id":2,"x":0.8,"y":0.9}]}""")
                assertEquals(8, frame(it).first)
            }
        } finally { server.stop() }
    }

    @Test fun jpegFallbackDemandsLocalDecodingAndRetainsConnectionClose() {
        // Given
        val frames = LatestFrameStore()
        val server = server(frames)
        try {
            frames.publish(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            // When / Then
            connection("/stream").use {
                val response = headers(it)
                assertTrue(response.contains("multipart/x-mixed-replace"))
                assertTrue(response.lowercase().contains("connection: close"))
                assertTrue(server.needsDecodedVideo(110))
            }
        } finally { server.stop() }
    }
}
