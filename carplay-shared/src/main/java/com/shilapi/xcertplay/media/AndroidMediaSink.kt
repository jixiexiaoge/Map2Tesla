package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 *
 * [onFirstVideoFrameRendered] runs once per screen stream on a codec callback thread, only
 * after Android reports a rendered frame. Use a new sink per media session and capture that
 * session's identity in the callback. For a shared sink, prefer the engine's session callback
 * factory, installed through [setFirstVideoFrameRenderedHandler]. Keep callbacks short.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val onFirstVideoFrameRendered: ((Int) -> Unit)? = null,
    private val onVideoStateChanged: ((String) -> Unit)? = null,
    private val onVideoError: ((String) -> Unit)? = null,
    private val mediaTap: MediaTap? = null,
) : MediaSink {
    @Volatile private var closed = false
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoConfigs = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val firstVideoFrameHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    private val firstVideoFrameSubmitted = AtomicBoolean(false)
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    fun requestKeyFrame(type: Int) = requestVideoRecovery(type)

    /** Register the current stream's keyframe request; never runs on the decoder worker. */
    @Synchronized override fun setVideoRecoveryHandler(type: Int, handler: (() -> Unit)?) {
        if (closed) return
        if (handler == null) videoRecoveryHandlers.remove(type) else videoRecoveryHandlers[type] = handler
    }

    @Synchronized override fun setFirstVideoFrameRenderedHandler(type: Int, handler: (() -> Unit)?) {
        if (closed) return
        videoDecoders.remove(type)?.close()
        pendingVideoCodec.remove(type)
        videoConfigs.remove(type)
        if (handler == null) firstVideoFrameHandlers.remove(type) else firstVideoFrameHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        val handler = videoRecoveryHandlers[type] ?: return
        if (closed || !recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try {
                    if (!closed && videoRecoveryHandlers[type] === handler) handler()
                } catch (error: Exception) {
                    Log.w("xcertplay-usb", "Video keyframe request failed", error)
                } finally {
                    recoveryPending.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            recoveryPending.set(false)
        }
    }

    @Synchronized fun setSurface(type: Int, surface: Surface) {
        if (closed) return
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    @Synchronized fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    @Synchronized fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        if (closed) return
        screenStreamActiveChanged = listener
    }

    @Synchronized override fun onVideoCodec(type: Int, codec: VideoCodec) {
        if (closed) return
        pendingVideoCodec[type] = codec
        onVideoStateChanged?.invoke("已协商 CarPlay 视频编码：$codec")
    }

    @Synchronized override fun onVideoConfig(type: Int, codecData: ByteArray) {
        if (closed) return
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoConfigs[type] = codec to codecData.copyOf()
        mediaTap?.onVideoConfig(type, codec, codecData)
        onVideoStateChanged?.invoke("收到 CarPlay 视频解码配置（${codecData.size} 字节）")
        if (mediaTap?.needsDecodedVideo(type) != false) {
            val existing = videoDecoders[type]
            if (existing == null) videoDecoder(type) else existing.configure(codec, codecData)
        }
    }

    @Synchronized override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (closed) return
        mediaTap?.onVideoFrame(type, naluBytes, System.nanoTime() / 1_000L)
        if (mediaTap?.needsDecodedVideo(type) == false) {
            videoDecoders.remove(type)?.close()
            return
        }
        if (firstVideoFrameSubmitted.compareAndSet(false, true)) {
            onVideoStateChanged?.invoke("已收到 CarPlay 视频数据，正在解码")
        }
        videoDecoder(type).submit(naluBytes)
    }

    @Synchronized override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (closed) return
        if (!active) {
            firstVideoFrameSubmitted.set(false)
            videoRecoveryHandlers.remove(type)
            firstVideoFrameHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
            videoConfigs.remove(type)
        }
        mediaTap?.onVideoActive(type, active)
        screenStreamActiveChanged?.invoke(type, active)
    }

    @Synchronized override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        if (closed) return
        audioRenderer(type, format).start()
    }

    @Synchronized override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (closed) return
        audioRenderer(type, format).submit(rtp, sample)
    }

    @Synchronized override fun onAudioStopped(type: Int) {
        audioRenderers.remove(type)?.close()
        mediaTap?.onAudioStopped(type)
    }

    @Synchronized override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        if (closed) return
        val uplink = microphoneUplinks.computeIfAbsent(type) { MicrophoneUplink(config) }
        if (!uplink.start()) microphoneUplinks.remove(type, uplink)
    }

    @Synchronized override fun onMicrophoneStopped(type: Int) {
        microphoneUplinks.remove(type)?.close()
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
        pendingVideoCodec.clear()
        videoConfigs.clear()
        firstVideoFrameHandlers.clear()
        videoRecoveryHandlers.clear()
        recoveryExecutor.shutdownNow()
        surfaces.clear()
        screenStreamActiveChanged = null
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            val sessionRendered = firstVideoFrameHandlers[type]
            VideoDecoder(
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                requestKeyFrame = { requestVideoRecovery(type) },
                onError = { message -> onVideoError?.invoke(message) },
                onFirstFrame = if (onFirstVideoFrameRendered == null && sessionRendered == null) null else { source ->
                    val current = synchronized(this) { !closed && videoDecoders[type] === source }
                    if (current) {
                        // The engine rechecks its exact binding under its own lock. Never invoke
                        // it under the sink lock: screen teardown acquires those locks in reverse.
                        sessionRendered?.invoke()
                        if (!closed && videoDecoders[type] === source) onFirstVideoFrameRendered?.invoke(type)
                    }
                },
            ).also { decoder ->
                videoConfigs[type]?.let { (codec, data) -> decoder.configure(codec, data) }
                if (mediaTap != null) requestVideoRecovery(type)
            }
        }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(type, format, advancedAudioChannelMapping, mediaTap).also { audioRenderers[type] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val onError: ((String) -> Unit)?,
    private val onFirstFrame: ((VideoDecoder) -> Unit)?,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var awaitingConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    private val firstFrame = FirstVideoFrame()
    private val callbackThread = onFirstFrame?.let {
        HandlerThread("carplay-video-render").apply { start() }
    }
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        queue.clear()
        thread.interrupt()
        callbackThread?.quitSafely()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                queue.discardFrames()
                                recover("video backlog exceeded 250 ms")
                            } else {
                                feed(job.nalus)
                            }
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) {
                        requestKeyFrameIfDue()
                    }
                } catch (error: Exception) {
                    if (running) {
                        Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                        onError?.invoke(
                            "CarPlay 视频解码错误：${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                    releaseDecoder()
                    referenceChain.reset()
                    if (running) requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
            referenceChain.reset()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        awaitingConfigLogged = false
        releaseDecoder()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        if (codec == VideoCodec.H265) {
            val csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            if (csd.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            if (sps.isNotEmpty()) format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
            if (pps.isNotEmpty()) format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
        }
        var candidate: MediaCodec? = null
        val next = try {
            createDecoder(mime).also {
                candidate = it
                it.configure(format, surface, null, 0)
                callbackThread?.let { callbacks ->
                    it.setOnFrameRenderedListener({ source, _, _ ->
                        if (running && decoder === source) {
                            runCatching { firstFrame.rendered { onFirstFrame?.invoke(this) } }
                                .onFailure { error -> Log.w(TAG, "first frame callback failed", error) }
                        }
                    }, Handler(callbacks.looper))
                }
                it.start()
            }
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.e(TAG, "video decoder configure failed mime=$mime size=${width}x$height", error)
            onError?.invoke(
                "CarPlay 视频解码器初始化失败：${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray) {
        if (outputSurface == null) return
        val config = lastConfig
        if (config == null) {
            if (!awaitingConfigLogged) {
                awaitingConfigLogged = true
                Log.i(TAG, "video frames without codec config; requesting keyframe")
            }
            requestKeyFrameIfDue()
            return
        }
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        if (annexB.isEmpty()) {
            recover("invalid video access unit")
            return
        }
        if (!referenceChain.accepts(annexB, config.codec)) return
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running },
            drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (!running) return
        if (index < 0) {
            recover("video decoder input stalled")
            return
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data; flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        if (!running) return
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    codec.releaseOutputBuffer(index, render)
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    private val stream: Int,
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val mediaTap: MediaTap?,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var prebufferWriteChunkBytes = PREBUFFER_WRITE_CHUNK_BYTES
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var fadeApplied = false
    private var remotelyPlaying = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    @Synchronized fun start() {
        if (started || !running) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!running || !started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
            }
        }
    }

    override fun close() {
        running = false
        queue.clear()
        thread.interrupt()
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) {
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Codec output may arrive after the final packet in a burst.
                codec?.let(::drainCodec)
                maintainPlaybackBuffer()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) Log.e(TAG, "audio renderer worker failed", error)
        } finally {
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        var candidate: MediaCodec? = null
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                candidate = it
                it.configure(mediaFormat, null, null, 0)
                it.start()
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val plan = MediaAudioBuffer.plan(
            format.audioType, format.sampleRate, format.channels, minBuffer, MediaAudioBuffer.DEFAULT_MILLIS,
        )
        val built = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(plan.trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = built
        val capacityBytes = built.bufferSizeInFrames * if (format.channels >= 2) 4 else 2
        prebufferWriteChunkBytes = minOf(PREBUFFER_WRITE_CHUNK_BYTES, capacityBytes.coerceAtLeast(1))
        startThresholdBytes = MediaAudioBuffer.startBytesFor(
            plan.startBytes, capacityBytes, prebufferWriteChunkBytes,
        )
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels}",
        )
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun audioAttributes(): AudioAttributes {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
            .also {
                Log.i(
                    TAG,
                    "audio route type=${format.payloadType} audioType=${format.audioType} " +
                        "mode=$mode channel=${selection.channel} " +
                        "usage=$usage contentType=$contentType",
                )
            }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        if (rtp.size <= 12) return
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = VideoInputPump.acquire(
            running = { running },
            drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (!running) return
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (mediaTap?.onAudioPcm(
                stream, format.sampleRate, if (format.channels >= 2) 2 else 1, data, offset, length,
            ) == true
        ) {
            if (!remotelyPlaying) {
                track?.pause()
                track?.flush()
                playbackStarted = false
                prebufferBytes = 0
                fadeApplied = false
                bufferProgress.reset()
            }
            remotelyPlaying = true
            return
        }
        remotelyPlaying = false
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, prebufferWriteChunkBytes)
            }
            // Nonblocking writes allow close() to stop even when the hardware stalls.
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_NON_BLOCKING)
            if (count < 0) throw IllegalStateException("AudioTrack write failed: $count")
            if (count == 0) {
                if (!playbackStarted && prebufferBytes > 0) startPlayback(track)
                Thread.sleep(AUDIO_POLL_MILLIS)
                continue
            }
            written += count
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        underrunsAtPlaybackStart = track.underrunCount
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        if (!running || remotelyPlaying) return
        val track = track ?: return
        // Poll the unsigned head regularly, including while new packets are arriving.
        val head = track.playbackHeadPosition
        bufferProgress.queuedBytes(head)
        if (bufferProgress.shouldRebuffer(
                format.audioType, playbackStarted, track.underrunCount > underrunsAtPlaybackStart,
                queue.isEmpty(), head,
            )
        ) {
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
        }
        if (bufferProgress.shouldStartTail(
                playbackStarted, prebufferBytes, queue.isEmpty(), lastPcmWriteNs, System.nanoTime(),
            )
        ) {
            startPlayback(track)
        }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
