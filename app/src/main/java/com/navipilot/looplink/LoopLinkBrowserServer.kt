package com.navipilot.looplink

import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaTap
import com.navipilot.amap2tesla.video.LatestFrameStore
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.json.JSONObject
import org.json.JSONArray
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer

internal class LoopLinkBrowserServer(
    private val frames: LatestFrameStore,
    private val sendTouch: (List<AirPlayContact>) -> Boolean,
    private val page: String,
    private val profile: LoopLinkDisplayProfile,
    private val requestKeyFrame: () -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
) : NanoWSD("0.0.0.0", PORT), MediaTap {
    private val streams = ConcurrentHashMap.newKeySet<InputStream>()
    private val streamCount = AtomicInteger()
    private val socketCount = AtomicInteger()
    private val sockets = ConcurrentHashMap.newKeySet<TouchSocket>()
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var videoCodec: VideoCodec? = null
    @Volatile private var videoActive = false
    @Volatile private var lastVideoAt = 0L
    private var lastKeyRequestAt = 0L
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "usb-browser-watchdog").apply { isDaemon = true }
    }.apply {
        scheduleWithFixedDelay({
            val now = System.nanoTime() / 1_000_000
            sockets.forEach { socket -> socket.checkAlive(now) }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    override fun needsDecodedVideo(type: Int) = type != 110 || streamCount.get() > 0

    override fun onVideoConfig(type: Int, codec: VideoCodec, data: ByteArray) {
        if (type != 110) return
        val packet = byteArrayOf(1, if (codec == VideoCodec.H265) 1 else 0) + data
        if (codecConfig?.contentEquals(packet) == true) return
        videoCodec = codec
        codecConfig = packet
        sockets.filter { it.rawVideo }.forEach { it.queue.config(packet) }
    }

    override fun onVideoFrame(type: Int, annexB: ByteArray, arrivalUs: Long) {
        if (type != 110) return
        lastVideoAt = arrivalUs / 1_000
        val viewers = sockets.filter { it.rawVideo }
        if (viewers.isEmpty()) return
        val codec = videoCodec ?: return
        val frame = try {
            LoopLinkVideoPacket.frame(codec, annexB, arrivalUs)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Rejected malformed compressed CarPlay frame", error)
            askKeyFrame()
            return
        }
        if (viewers.map { it.queue.video(frame) }.any { it }) askKeyFrame()
    }

    override fun onVideoActive(type: Int, active: Boolean) {
        if (type != 110) return
        videoActive = active
        if (!active) {
            codecConfig = null
            videoCodec = null
            lastVideoAt = 0L
            frames.clear()
            sockets.forEach { it.queue.reset(); it.touch.release() }
        }
    }

    override fun onAudioPcm(
        stream: Int, sampleRate: Int, channels: Int, data: ByteArray, offset: Int, length: Int,
    ): Boolean {
        val viewers = sockets.filter { it.remoteAudio }
        if (viewers.isEmpty() || length <= 0) return false
        val packet = ByteArray(7 + length)
        packet[0] = 3
        packet[1] = stream.toByte()
        ByteBuffer.wrap(packet, 2, 4).putInt(sampleRate)
        packet[6] = channels.toByte()
        data.copyInto(packet, 7, offset, offset + length)
        viewers.forEach { it.queue.audio(packet) }
        return true
    }

    override fun onAudioStopped(stream: Int) {
        sockets.filter { it.remoteAudio }.forEach { it.queue.audio(byteArrayOf(4, stream.toByte())) }
    }

    @Synchronized private fun askKeyFrame() {
        val now = System.nanoTime() / 1_000_000
        if (now - lastKeyRequestAt < 500) return
        lastKeyRequestAt = now
        requestKeyFrame()
    }

    override fun stop() {
        watchdog.shutdownNow()
        sockets.toList().forEach { it.shutdown() }
        super.stop()
        streams.toList().forEach { runCatching { it.close() } }
    }

    @Suppress("ReturnCount")
    override fun serveHttp(session: IHTTPSession): Response {
        if (!validHost(session)) return text(Response.Status.FORBIDDEN, "Invalid Host")
        return when (session.uri) {
            "/", "/index.html" -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                page,
            ).apply { addHeader("Cache-Control", "no-store") }
            "/stream" -> {
                if (streamCount.incrementAndGet() > MAX_STREAMS) {
                    streamCount.decrementAndGet()
                    onDiagnostic("浏览器视频连接数已达上限")
                    return text(Response.Status.SERVICE_UNAVAILABLE, "Too many streams")
                }
                onDiagnostic("浏览器视频流已连接")
                askKeyFrame()
                val source = tracked(
                    MjpegStream(frames) {
                        streamCount.decrementAndGet()
                        onDiagnostic("浏览器视频流已断开")
                    },
                )
                newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    source,
                ).apply {
                    addHeader("Cache-Control", "no-store")
                    addHeader("Connection", "close")
                }
            }
            "/status" -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                JSONObject()
                    .put("sequence", frames.metrics().sequence)
                    .put("fps", frames.metrics().encodedFps)
                    .put("frameAgeMillis", frames.metrics().frameAgeMillis)
                    .put("revision", PLAYER_REVISION)
                    .put("width", profile.width)
                    .put("height", profile.height)
                    .put("active", videoActive)
                    .put("codec", videoCodec?.name)
                    .put("videoAgeMillis", if (lastVideoAt == 0L) -1L else System.nanoTime() / 1_000_000 - lastVideoAt)
                    .put("message", LoopLinkState.status)
                    .put("diagnostics", JSONArray(LoopLinkState.diagnostics.takeLast(8)))
                    .toString(),
            ).apply { addHeader("Cache-Control", "no-store") }
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket =
        TouchSocket(handshake)

    private fun validHost(session: IHTTPSession): Boolean {
        val allowed = localIpv4Addresses() + TESLA_ADDRESS
        return LoopLinkRequestGuard.isValidRequestAuthority(
            session.headers["host"],
            session.headers["origin"],
            allowed,
            PORT,
        )
    }

    private fun tracked(source: InputStream): InputStream {
        lateinit var result: InputStream
        result = object : FilterInputStream(source) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    streams.remove(result)
                }
            }
        }
        streams.add(result)
        return result
    }

    private fun text(status: Response.IStatus, message: String) =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)

    private inner class TouchSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        @Volatile private var registered = false
        @Volatile var rawVideo = false
        @Volatile var remoteAudio = false
        @Volatile private var heardAt = System.nanoTime() / 1_000_000
        val queue = LoopLinkViewerQueue()
        val touch = LoopLinkTouchState(sendTouch)
        private var sender: Thread? = null

        fun checkAlive(now: Long) {
            touch.expire(now)
            if (registered && now - heardAt > 8_000) shutdown()
        }

        fun shutdown() {
            remoteAudio = false
            rawVideo = false
            touch.release()
            queue.close()
            sender?.interrupt()
            try { close(WebSocketFrame.CloseCode.GoingAway, "Disconnected", false) }
            catch (error: IOException) { Log.w(TAG, "Browser socket close failed", error) }
        }

        override fun onOpen() {
            val request = handshakeRequest
            if (request.uri != "/control" || !validHost(request)) {
                close(WebSocketFrame.CloseCode.PolicyViolation, "Unauthorized", false)
                return
            }
            if (socketCount.incrementAndGet() > MAX_SOCKETS) {
                socketCount.decrementAndGet()
                close(WebSocketFrame.CloseCode.PolicyViolation, "Too many clients", false)
                return
            }
            registered = true
            sockets.add(this)
            onDiagnostic("浏览器触控通道已连接")
            send("""{"type":"ready","width":${profile.width},"height":${profile.height},"revision":"$PLAYER_REVISION"}""")
            sender = Thread({
                try {
                    while (registered) send(queue.next() ?: break)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: IOException) {
                    Log.w(TAG, "Browser media sender failed", error)
                    shutdown()
                }
            }, "usb-browser-sender").apply { isDaemon = true; start() }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean,
        ) {
            if (registered) {
                remoteAudio = false
                rawVideo = false
                sockets.remove(this)
                queue.close()
                sender?.interrupt()
                touch.release()
                socketCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                registered = false
                onDiagnostic("浏览器触控通道已断开")
            }
        }

        override fun onMessage(message: WebSocketFrame) {
            try {
                require(message.opCode == WebSocketFrame.OpCode.Text) { "Text control message required" }
                require(message.textPayload.length <= MAX_CONTROL_MESSAGE_LENGTH) {
                    "Control message is too large"
                }
                val json = JSONObject(message.textPayload)
                heardAt = System.nanoTime() / 1_000_000
                when (json.optString("type")) {
                    "ping" -> Unit
                    "video" -> {
                        require(json.getString("mode") in setOf("raw", "jpeg"))
                        rawVideo = json.getString("mode") == "raw"
                        queue.reset()
                        if (rawVideo) codecConfig?.let(queue::config)
                        askKeyFrame()
                        onDiagnostic(if (rawVideo) "浏览器已选择原始视频直传" else "浏览器已切换 MJPEG 兼容画面")
                    }
                    "keyframe" -> {
                        if (rawVideo) codecConfig?.let(queue::config)
                        askKeyFrame()
                    }
                    "audio" -> {
                        remoteAudio = json.getBoolean("enabled")
                        if (!remoteAudio) queue.clearAudio()
                        onDiagnostic(if (remoteAudio) "浏览器声音已开启" else "声音已恢复手机播放")
                    }
                    "touch" -> {
                        val contacts = json.getJSONArray("contacts")
                        require(contacts.length() <= 2)
                        val points = (0 until contacts.length()).map { index ->
                            val contact = contacts.getJSONObject(index)
                            AirPlayContact(contact.getInt("id"), contact.getDouble("x"), contact.getDouble("y"), true)
                        }
                        if (!touch.submit(points, heardAt)) {
                            onDiagnostic("CarPlay 触控未送达；检查 iPhone 控制通道")
                        }
                    }
                    else -> {
                        // Previously cached players used individual pointer actions.
                        val action = json.getInt("action")
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        val id = json.optInt("id", 0)
                        require(isValidTouch(action, id, x, y)) { "Invalid touch event" }
                        val contacts = if (action == ACTION_DOWN || action == ACTION_MOVE) {
                            listOf(AirPlayContact(0, x.toDouble(), y.toDouble(), true))
                        } else emptyList()
                        if (!touch.submit(contacts, heardAt)) onDiagnostic("CarPlay 触控未送达")
                    }
                }
            } catch (error: org.json.JSONException) {
                Log.w(TAG, "Rejected invalid browser touch event", error)
                close(WebSocketFrame.CloseCode.PolicyViolation, "Invalid touch", false)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Rejected invalid browser touch event", error)
                close(WebSocketFrame.CloseCode.PolicyViolation, "Invalid touch", false)
            }
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            Log.w(TAG, "Browser touch socket failed", exception)
            onDiagnostic("浏览器触控通道异常：${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private class MjpegStream(
        private val frames: LatestFrameStore,
        private val onClose: () -> Unit,
    ) : InputStream() {
        private var current = ByteArray(0)
        private var offset = 0
        private var sequence = -1L
        @Volatile private var closed = false

        override fun read(): Int {
            val one = ByteArray(BYTE_SIZE)
            return if (read(one, 0, BYTE_SIZE) == -1) -1 else one[0].toInt() and BYTE_MASK
        }

        @Suppress("ReturnCount")
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            if (closed) return -1
            while (offset >= current.size) {
                if (closed) return -1
                val frame = try {
                    frames.awaitAfter(sequence, FRAME_WAIT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                if (closed) return -1
                if (frame == null) continue
                sequence = frame.sequence
                current = (
                    "--frame\r\nContent-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.bytes.size}\r\n\r\n"
                    ).toByteArray() + frame.bytes + "\r\n".toByteArray()
                offset = 0
            }
            val count = minOf(length, current.size - offset)
            current.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            onClose()
        }
    }

    companion object {
        const val PORT = 8088
        const val PLAYER_REVISION = "usb-20261011-1"
        private const val TESLA_ADDRESS = "99.99.99.99"
        private const val MAX_STREAMS = 2
        private const val MAX_SOCKETS = 1
        private const val MAX_CONTROL_MESSAGE_LENGTH = 1024
        private const val FRAME_WAIT_MS = 5_000L
        private const val BYTE_SIZE = 1
        private const val BYTE_MASK = 0xff
        private const val ACTION_DOWN = 0
        private const val ACTION_MOVE = 2
        private const val MAX_TOUCH_ACTION = 3
        private const val NORMALIZED_MIN = 0f
        private const val NORMALIZED_MAX = 1f
        private const val TAG = "LoopLinkServer"

        private fun localIpv4Addresses(): Set<String> =
            runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .asSequence()
                    .flatMap { Collections.list(it.inetAddresses).asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .toSet()
            }.getOrDefault(emptySet())

        private fun isValidTouch(action: Int, id: Int, x: Float, y: Float): Boolean =
            action in 0..MAX_TOUCH_ACTION && id >= 0 &&
                x.isFinite() && y.isFinite() &&
                x in NORMALIZED_MIN..NORMALIZED_MAX &&
                y in NORMALIZED_MIN..NORMALIZED_MAX
    }
}
