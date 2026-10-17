package com.phairplay.diagnostics

import android.content.Context
import com.phairplay.util.Logger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * SessionRecorder — captures everything that crosses the wire during one mirroring session,
 * from the sender's first RTSP request to the teardown.
 *
 * WHY: when a session misbehaves, logcat shows only what someone thought to log at the time.
 * This keeps the bytes themselves, so a session can be examined afterwards without having to
 * reproduce it. Nothing is interpreted away: every packet is written verbatim, and the
 * annotation sits alongside it rather than replacing it.
 *
 * Output is a directory per session under the app's external files dir:
 *
 * ```
 *   session-20260812-143002/
 *     manifest.json   session metadata, counts per channel, and any dropped-record count
 *     index.jsonl     one line per record: timestamp, channel, offset, length, description
 *     rtsp.bin        RTSP requests and responses, verbatim
 *     mirror.bin      mirroring data connection: 128-byte header + payload, verbatim
 *     audio.bin       audio RTP packets, verbatim
 *     timing.bin      NTP timing packets, verbatim
 * ```
 *
 * `index.jsonl` gives byte offsets into the `.bin` files, so a reader can seek to any record
 * without parsing the stream.
 *
 * **Throughput.** Mirroring produces 1–3 MB/s. Writing happens on a dedicated thread behind a
 * bounded queue so a slow filesystem can never stall the decode path — dropping frames to keep
 * a recording would defeat the purpose. If the queue does fill, the number of records that did
 * not make it is counted and written to the manifest: a recording is allowed to be incomplete,
 * but never silently so.
 */
class SessionRecorder private constructor(private val dir: File) {

    private enum class Channel(val fileName: String) {
        RTSP("rtsp.bin"), MIRROR("mirror.bin"), AUDIO("audio.bin"), TIMING("timing.bin")
    }

    private class Record(
        val channel: Channel,
        val description: String,
        val parts: List<ByteArray>,
        val wallClockMs: Long,
        val elapsedNanos: Long
    )

    private val queue = ArrayBlockingQueue<Record>(QUEUE_CAPACITY)
    private val dropped = AtomicLong()
    private val counts = LinkedHashMap<Channel, Long>()
    private val bytes = LinkedHashMap<Channel, Long>()
    private val startedAtMs = System.currentTimeMillis()
    private val startedAtNanos = System.nanoTime()

    @Volatile private var running = true
    private var writer: Thread? = null

    private fun start() {
        writer = Thread({ drain() }, "SessionRecorder").apply {
            priority = Thread.MIN_PRIORITY   // never compete with decode
            start()
        }
        Logger.i("Recording session to ${dir.absolutePath}")
    }

    // ─── Public recording API — safe to call from any thread, never blocks ───────────────

    /** Records an RTSP request or response. [description] is e.g. "→ SETUP rtsp://…". */
    fun rtsp(description: String, data: ByteArray) = submit(Channel.RTSP, description, listOf(data))

    /**
     * Records one packet from the mirroring data connection.
     * Header and payload are written back to back, exactly as they arrived.
     */
    fun mirror(description: String, header: ByteArray, payload: ByteArray) =
        submit(Channel.MIRROR, description, listOf(header, payload))

    /** Records one audio RTP packet. */
    fun audio(description: String, data: ByteArray) = submit(Channel.AUDIO, description, listOf(data))

    /** Records one NTP timing packet. */
    fun timing(description: String, data: ByteArray) = submit(Channel.TIMING, description, listOf(data))

    /** Records something that has no bytes of its own — a state change, a decision, an error. */
    fun note(description: String) = submit(Channel.RTSP, description, emptyList())

    private fun submit(channel: Channel, description: String, parts: List<ByteArray>) {
        if (!running) return
        val record = Record(channel, description, parts, System.currentTimeMillis(), System.nanoTime() - startedAtNanos)
        if (!queue.offer(record)) dropped.incrementAndGet()
    }

    // ─── Writer thread ───────────────────────────────────────────────────────────────────

    private fun drain() {
        val streams = HashMap<Channel, BufferedOutputStream>()
        val offsets = HashMap<Channel, Long>()
        val index = try {
            OutputStreamWriter(BufferedOutputStream(FileOutputStream(File(dir, "index.jsonl"))), Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.e("Session recorder could not open its index — recording disabled", e)
            running = false
            return
        }

        try {
            while (running || queue.isNotEmpty()) {
                val record = queue.poll(POLL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val stream = streams.getOrPut(record.channel) {
                    BufferedOutputStream(FileOutputStream(File(dir, record.channel.fileName)))
                }
                val length = record.parts.sumOf { it.size }
                val offset = offsets.getOrDefault(record.channel, 0L)

                record.parts.forEach { stream.write(it) }
                offsets[record.channel] = offset + length
                counts[record.channel] = (counts[record.channel] ?: 0L) + 1
                bytes[record.channel] = (bytes[record.channel] ?: 0L) + length

                index.write(
                    """{"t":${record.wallClockMs},"dt_us":${record.elapsedNanos / 1000},""" +
                        """"ch":"${record.channel.name.lowercase()}","off":$offset,"len":$length,""" +
                        """"desc":"${escape(record.description)}"}""" + "\n"
                )
            }
        } catch (e: Exception) {
            Logger.e("Session recorder write error — recording truncated", e)
        } finally {
            runCatching { index.flush(); index.close() }
            streams.values.forEach { runCatching { it.flush(); it.close() } }
        }
    }

    /** Flushes and closes the recording, then writes the manifest. Safe to call twice. */
    fun stop() {
        if (!running) return
        running = false
        runCatching { writer?.join(STOP_TIMEOUT_MS) }
        writeManifest()
        val total = counts.values.sum()
        val lost = dropped.get()
        Logger.i(
            "Session recording finished: $total records in ${dir.name}" +
                if (lost > 0) " ($lost dropped — writer could not keep up)" else ""
        )
    }

    private fun writeManifest() {
        val durationMs = System.currentTimeMillis() - startedAtMs
        val perChannel = Channel.entries.joinToString(",\n") { ch ->
            """    "${ch.name.lowercase()}": { "records": ${counts[ch] ?: 0}, "bytes": ${bytes[ch] ?: 0} }"""
        }
        val json = buildString {
            appendLine("{")
            appendLine("""  "started": "${TIMESTAMP.format(Date(startedAtMs))}",""")
            appendLine("""  "duration_ms": $durationMs,""")
            appendLine("""  "dropped_records": ${dropped.get()},""")
            appendLine("""  "channels": {""")
            appendLine(perChannel)
            appendLine("  }")
            append("}")
        }
        runCatching { File(dir, "manifest.json").writeText(json) }
            .onFailure { Logger.e("Could not write session manifest", it) }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    companion object {
        /** ~2 s of mirroring at 60 fps; beyond this the filesystem is the problem, not the queue. */
        private const val QUEUE_CAPACITY = 512
        private const val POLL_TIMEOUT_MS = 200L
        private const val STOP_TIMEOUT_MS = 5_000L
        private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val DIR_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        /**
         * The recorder for the session in progress, or null when recording is off.
         * Read on the hot path, so it is a plain volatile rather than a flow.
         */
        @Volatile
        var active: SessionRecorder? = null
            private set

        /**
         * Starts recording a new session. Returns null (and records nothing) if the output
         * directory cannot be created — diagnostics must never stop a mirror from working.
         */
        fun start(context: Context): SessionRecorder? {
            stopActive()
            return try {
                val root = File(context.getExternalFilesDir(null), "sessions")
                val dir = File(root, "session-${DIR_STAMP.format(Date())}")
                if (!dir.mkdirs() && !dir.isDirectory) {
                    Logger.w("Could not create session directory ${dir.absolutePath}")
                    return null
                }
                SessionRecorder(dir).also { it.start(); active = it }
            } catch (e: Exception) {
                Logger.e("Could not start session recording", e)
                null
            }
        }

        /** Stops the active recording, if any. */
        fun stopActive() {
            active?.stop()
            active = null
        }
    }
}
