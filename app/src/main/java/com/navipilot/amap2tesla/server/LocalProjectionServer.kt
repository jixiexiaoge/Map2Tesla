package com.navipilot.amap2tesla.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.navipilot.amap2tesla.ConnectionMode
import com.navipilot.amap2tesla.ProjectionState
import com.navipilot.amap2tesla.audio.LatestPcmStore
import com.navipilot.amap2tesla.network.TeslaVirtualIpService
import com.navipilot.amap2tesla.video.LatestFrameStore
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LocalProjectionServer private constructor(
    private val context: Context,
    val activePort: Int,
    private val frameStore: LatestFrameStore,
    private val audioStore: LatestPcmStore?,
    private val controlSink: ControlSink,
    private val statusProvider: () -> ProjectionServerStatus
) : NanoWSD("0.0.0.0", activePort) {
    private val nextGestureId = AtomicInteger(1)
    private val nextStreamId = AtomicInteger(1)
    private val controlClients = AtomicInteger(0)
    private val streamClients = AtomicInteger(0)
    private val streamConnections = AtomicInteger(0)
    private val viewportOwner = AtomicInteger(0)
    private val activeStreams = ConcurrentHashMap.newKeySet<InputStream>()

    private fun trackStream(stream: InputStream): InputStream {
        val tracked = object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    activeStreams.remove(this)
                }
            }
        }
        activeStreams.add(tracked)
        return tracked
    }

    override fun stop() {
        super.stop()
        activeStreams.toList().forEach { stream ->
            runCatching { stream.close() }.onFailure { Log.w(TAG, "Stream cleanup failed", it) }
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        return when (session.uri) {
            "/", "/index.html" -> {
                val html = context.assets.open("amap2tesla_player.html")
                newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Pragma", "no-cache")
                }
            }
            "/stream" -> {
                val streamId = nextStreamId.getAndIncrement()
                val activeClients = streamClients.incrementAndGet()
                val totalConnections = streamConnections.incrementAndGet()
                Log.w(
                    TAG,
                    "MJPEG connected stream=$streamId active=$activeClients total=$totalConnections"
                )
                newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    trackStream(MjpegInputStream(frameStore) { durationMillis, frames, bytes ->
                        val remainingClients = streamClients.updateAndGet {
                            (it - 1).coerceAtLeast(0)
                        }
                        Log.w(
                            TAG,
                            "MJPEG closed stream=$streamId durationMs=$durationMillis " +
                                "frames=$frames bytes=$bytes active=$remainingClients"
                        )
                    })
                ).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Pragma", "no-cache")
                    // Tesla Chromium is more stable when an indefinite multipart
                    // response owns its HTTP connection instead of advertising reuse.
                    addHeader("Connection", "close")
                }
            }
            "/audio.wav" -> {
                val store = audioStore
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Audio unavailable")
                newChunkedResponse(Response.Status.OK, "audio/wav", trackStream(PcmWavInputStream(store))).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Connection", "keep-alive")
                }
            }
            "/audio.pcm" -> {
                val store = audioStore
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Audio unavailable")
                newChunkedResponse(Response.Status.OK, "application/octet-stream", trackStream(PcmInputStream(store))).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Connection", "keep-alive")
                }
            }
            "/health" -> {
                val metrics = frameStore.metrics()
                val audio = audioStore?.metrics()
                val status = statusProvider()
                val json = JSONObject()
                    .put("running", status.running)
                    .put("status", status.message)
                    .put("displayId", status.displayId ?: -1)
                    .put("width", status.width)
                    .put("height", status.height)
                    .put("densityDpi", status.densityDpi)
                    .put("targetFps", status.targetFps)
                    .put("trialMode", status.trialMode)
                    .put("interactive", status.interactive)
                    .put("waitingForSender", status.waitingForSender)
                    .put("receiverName", status.receiverName ?: "")
                    .put("playerRevision", PLAYER_REVISION)
                    .put("fps", metrics.encodedFps.toDouble())
                    .put("frameSequence", metrics.sequence)
                    .put("frameAgeMs", metrics.frameAgeMillis)
                    .put("streamClients", streamClients.get())
                    .put("streamConnections", streamConnections.get())
                    .put("controlClients", controlClients.get())
                    .put("audioSequence", audio?.sequence ?: 0)
                    .put("audioSampleRate", audio?.sampleRate ?: 0)
                    .put("audioChannels", audio?.channels ?: 0)
                    .put("currentPackage", status.currentPackageName ?: "")
                    .put("currentActivity", status.currentActivityClassName ?: "")
                    .apply {
                        if (!status.appsJson.isNullOrBlank()) {
                            runCatching { put("apps", org.json.JSONArray(status.appsJson)) }
                        }
                    }
                    .toString()
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            "/viewport" -> {
                if (session.method != Method.POST) {
                    return newFixedLengthResponse(
                        Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Use POST",
                    )
                }
                val width = session.parameters["width"]?.firstOrNull()?.toIntOrNull()
                val height = session.parameters["height"]?.firstOrNull()?.toIntOrNull()
                val accepted = viewportOwner.get() == 0 && statusProvider().interactive &&
                    width != null && height != null &&
                    controlSink.onViewport(width, height)
                newFixedLengthResponse(
                    if (accepted) Response.Status.OK else Response.Status.BAD_REQUEST,
                    "application/json",
                    JSONObject().put("ok", accepted).toString(),
                ).apply {
                    addHeader("Cache-Control", "no-store")
                }
            }
            "/app-icon" -> serveAppIcon(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun serveAppIcon(session: IHTTPSession): Response {
        val packageName = session.parameters["package"]?.firstOrNull()
            ?.takeIf(PACKAGE_NAME_PATTERN::matches)
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Invalid package"
            )
        val allowedPackages = runCatching {
            val apps = org.json.JSONArray(statusProvider().appsJson.orEmpty())
            buildSet {
                for (index in 0 until apps.length()) {
                    val app = apps.optJSONObject(index) ?: continue
                    if (app.optBoolean("installed")) {
                        add(app.optString("packageName"))
                    }
                }
            }
        }.getOrDefault(emptySet())
        if (packageName !in allowedPackages) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon unavailable")
        }

        val iconBytes = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            drawable.draw(canvas)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }.also {
                bitmap.recycle()
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not render app icon for $packageName", error)
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Icon unavailable")
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(iconBytes),
            iconBytes.size.toLong()
        ).apply {
            addHeader("Cache-Control", "public, max-age=3600")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return ControlWebSocket(handshake, nextGestureId.getAndIncrement())
    }

    fun accessUrl(mode: ConnectionMode): String {
        val host = if (mode == ConnectionMode.TESLA) {
            TeslaVirtualIpService.CLIENT_ADDRESS
        } else {
            findLocalIpv4()
        }
        return "http://$host:$activePort"
    }

    private inner class ControlWebSocket(
        handshake: IHTTPSession,
        private val gestureId: Int
    ) : WebSocket(handshake) {
        override fun onOpen() {
            controlClients.incrementAndGet()
            viewportOwner.compareAndSet(0, gestureId)
            send("""{"type":"ready","gestureId":$gestureId}""")
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            Log.w(
                TAG,
                "Control socket closed gesture=$gestureId code=$code " +
                    "remote=$initiatedByRemote reason=${reason.orEmpty()}"
            )
            controlSink.onCancel(gestureId)
            viewportOwner.compareAndSet(gestureId, 0)
            controlClients.updateAndGet {
                (it - 1).coerceAtLeast(0)
            }
        }

        override fun onMessage(message: WebSocketFrame) {
            val response = try {
                val json = JSONObject(message.textPayload)
                if (json.optString("type") == "ping") {
                    send("""{"type":"pong"}""")
                    return
                }
                val type = json.optString("type")
                if (type == "viewport") {
                    val isOwner = viewportOwner.get() == gestureId ||
                        viewportOwner.compareAndSet(0, gestureId)
                    val accepted = isOwner && controlSink.onViewport(
                        json.getInt("width"),
                        json.getInt("height")
                    )
                    send(
                        JSONObject()
                            .put("type", "viewportResult")
                            .put("primary", isOwner)
                            .put("ok", accepted)
                            .toString()
                    )
                    return
                }
                val accepted = when (type) {
                    "pointer" -> {
                        val action = json.getInt("action")
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        val pointerId = json.optInt("pointerId", 0)
                        if (action !in 0..3 || !x.isFinite() || !y.isFinite() ||
                            x !in 0f..1f || y !in 0f..1f
                        ) {
                            throw IllegalArgumentException("Invalid pointer event")
                        }
                        controlSink.onPointer(
                            gestureId,
                            action,
                            x,
                            y,
                            pointerId
                        )
                    }
                    "key" -> controlSink.onKey(json.getInt("keyCode"))
                    "launchApp" -> controlSink.onLaunchPackage(
                        json.getString("packageName"),
                        json.optString("activityClassName").takeIf { it.isNotBlank() },
                    )
                    else -> throw IllegalArgumentException("Unsupported control message")
                }
                JSONObject()
                    .put("type", "controlResult")
                    .put("ok", accepted)
                    .toString()
            } catch (error: Throwable) {
                JSONObject()
                    .put("type", "error")
                    .put("reason", error.message ?: error.javaClass.simpleName)
                    .toString()
            }
            send(response)
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            Log.w(TAG, "Control socket exception gesture=$gestureId", exception)
            controlSink.onCancel(gestureId)
        }
    }

    companion object {
        private const val TAG = "Amap2TeslaServer"
        private const val SERVER_READ_TIMEOUT_MS = 0
        private const val ICON_SIZE = 96
        private const val PLAYER_REVISION = "20261016-1"
        private const val LOOPLINK_PORT = 8088
        private val PACKAGE_NAME_PATTERN =
            Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun startWithFallback(
            context: Context,
            frameStore: LatestFrameStore,
            controlSink: ControlSink,
            audioStore: LatestPcmStore? = null,
            statusProvider: () -> ProjectionServerStatus = {
                ProjectionServerStatus(
                    running = ProjectionState.running,
                    message = ProjectionState.status,
                    displayId = ProjectionState.displayId,
                    width = ProjectionState.width,
                    height = ProjectionState.height,
                    densityDpi = ProjectionState.densityDpi,
                    targetFps = ProjectionState.targetFps,
                    trialMode = ProjectionState.trialMode,
                    interactive = true,
                )
            },
            firstPort: Int = 8080,
            attempts: Int = 20
        ): LocalProjectionServer {
            var lastError: IOException? = null
            for (port in firstPort until firstPort + attempts) {
                if (port == LOOPLINK_PORT) continue
                val server = LocalProjectionServer(
                    context.applicationContext,
                    port,
                    frameStore,
                    audioStore,
                    controlSink,
                    statusProvider
                )
                try {
                    server.start(SERVER_READ_TIMEOUT_MS, false)
                    return server
                } catch (error: IOException) {
                    lastError = error
                    runCatching { server.stop() }
                }
            }

            throw lastError ?: IOException("没有可用的本地服务端口")
        }

        private fun findLocalIpv4(): String {
            val addresses = localIpv4Addresses()
                .map { it.second }

            return addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
                ?: "127.0.0.1"
        }

        private fun localIpv4Addresses(): List<Pair<String, Inet4Address>> {
            return Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    Collections.list(networkInterface.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .map { networkInterface.name to it }
                }
        }

    }
}

interface ControlSink {
    val interactive: Boolean
        get() = true

    fun onPointer(
        gestureId: Int,
        action: Int,
        x: Float,
        y: Float,
        pointerId: Int
    ): Boolean

    fun onCancel(gestureId: Int)

    fun onKey(keyCode: Int): Boolean

    fun onViewport(width: Int, height: Int): Boolean

    fun onLaunchPackage(packageName: String, activityClassName: String? = null): Boolean = false
}

data class ProjectionServerStatus(
    val running: Boolean,
    val message: String,
    val displayId: Int?,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val targetFps: Int,
    val trialMode: Boolean,
    val interactive: Boolean,
    val currentPackageName: String? = null,
    val currentActivityClassName: String? = null,
    val appsJson: String? = null,
    val waitingForSender: Boolean = false,
    val receiverName: String? = null,
)

private class MjpegInputStream(
    private val frameStore: LatestFrameStore,
    private val onClosed: (durationMillis: Long, frames: Long, bytes: Long) -> Unit,
) : InputStream() {
    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private val closeReported = AtomicBoolean(false)
    private var sequence = -1L
    private var current = ByteArray(0)
    private var offset = 0
    private var frames = 0L
    private var bytes = 0L
    @Volatile
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (closed) return -1
        while (offset >= current.size) {
            if (closed) return -1
            val snapshot = frameStore.awaitAfter(sequence, 5_000) ?: continue
            if (closed) return -1
            sequence = snapshot.sequence
            val header = (
                "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${snapshot.bytes.size}\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val trailer = "\r\n".toByteArray(Charsets.US_ASCII)
            current = ByteArray(header.size + snapshot.bytes.size + trailer.size)
            header.copyInto(current, 0)
            snapshot.bytes.copyInto(current, header.size)
            trailer.copyInto(current, header.size + snapshot.bytes.size)
            offset = 0
            frames++
        }
        val count = minOf(len, current.size - offset)
        current.copyInto(buffer, off, offset, offset + count)
        offset += count
        bytes += count
        return count
    }

    override fun close() {
        closed = true
        if (closeReported.compareAndSet(false, true)) {
            onClosed(
                android.os.SystemClock.elapsedRealtime() - startedAt,
                frames,
                bytes,
            )
        }
    }
}

private class PcmWavInputStream(
    private val audioStore: LatestPcmStore,
) : InputStream() {
    private var sequence = -1L
    private var current = ByteArray(0)
    private var offset = 0
    private var headerWritten = false
    @Volatile
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (closed) return -1
        while (offset >= current.size) {
            if (closed) return -1
            val snapshot = audioStore.awaitAfter(sequence, 5_000) ?: continue
            if (closed) return -1
            sequence = snapshot.sequence
            current = if (headerWritten) {
                snapshot.bytes
            } else {
                headerWritten = true
                wavHeader(snapshot.sampleRate, snapshot.channels) + snapshot.bytes
            }
            offset = 0
        }
        val count = minOf(len, current.size - offset)
        current.copyInto(buffer, off, offset, offset + count)
        offset += count
        return count
    }

    override fun close() {
        closed = true
    }

    private fun wavHeader(sampleRate: Int, channels: Int): ByteArray {
        val bytesPerSample = 2
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        return ByteArray(44).apply {
            putAscii(0, "RIFF")
            putInt(4, -1)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, channels)
            putInt(24, sampleRate)
            putInt(28, byteRate)
            putShort(32, blockAlign)
            putShort(34, 16)
            putAscii(36, "data")
            putInt(40, -1)
        }
    }

    private fun ByteArray.putAscii(offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }

    private fun ByteArray.putShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }
}

private class PcmInputStream(
    private val audioStore: LatestPcmStore,
) : InputStream() {
    private var sequence = -1L
    private var current = ByteArray(0)
    private var offset = 0
    @Volatile
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (closed) return -1
        while (offset >= current.size) {
            if (closed) return -1
            val snapshot = audioStore.awaitAfter(sequence, 5_000) ?: continue
            if (closed) return -1
            sequence = snapshot.sequence
            current = snapshot.bytes
            offset = 0
        }
        val count = minOf(len, current.size - offset)
        current.copyInto(buffer, off, offset, offset + count)
        offset += count
        return count
    }

    override fun close() {
        closed = true
    }
}
