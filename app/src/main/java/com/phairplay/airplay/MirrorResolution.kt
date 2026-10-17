package com.phairplay.airplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.phairplay.util.Logger

/**
 * Picks the mirroring resolution advertised to the sender in AirPlay's `/info` `displays` record.
 *
 * WHY: the sender encodes at whatever size we advertise, so asking for more than the phone's
 * H.264 decoder can sustain does not degrade gracefully — it stutters or drops frames for the
 * whole session. The "force high resolution" setting used to hand 2560×1440 to every device
 * unconditionally. On the reference phone (Exynos 850) the decoder is capped at 489,600
 * macroblocks per second, which is exactly 1920×1088 at 60 fps; 2560×1440 at 60 fps needs
 * 864,000 and cannot be sustained.
 *
 * So the request is treated as a preference and checked against the decoder before use.
 */
object MirrorResolution {

    const val HIGH_WIDTH = 2560
    const val HIGH_HEIGHT = 1440
    const val STANDARD_WIDTH = 1920
    const val STANDARD_HEIGHT = 1080

    /** Frame rate the advertised size has to be sustainable at — AirPlay mirrors at 60 fps. */
    const val TARGET_FPS = 60.0

    /**
     * Returns the size to advertise.
     *
     * [preferHigh] is the user's "force high resolution" setting. It is honoured only when
     * [supportsSize] agrees the decoder can sustain it; otherwise this falls back to 1080p
     * and says so in the log.
     */
    fun resolve(
        preferHigh: Boolean,
        supportsSize: (width: Int, height: Int, fps: Double) -> Boolean = ::decoderSupports
    ): Pair<Int, Int> {
        if (!preferHigh) return STANDARD_WIDTH to STANDARD_HEIGHT

        if (supportsSize(HIGH_WIDTH, HIGH_HEIGHT, TARGET_FPS)) {
            return HIGH_WIDTH to HIGH_HEIGHT
        }

        Logger.w(
            "High resolution requested but this device's H.264 decoder cannot sustain " +
                "${HIGH_WIDTH}x$HIGH_HEIGHT @ ${TARGET_FPS.toInt()}fps — advertising " +
                "${STANDARD_WIDTH}x$STANDARD_HEIGHT instead"
        )
        return STANDARD_WIDTH to STANDARD_HEIGHT
    }

    /**
     * Asks the platform whether any hardware H.264 decoder can sustain [width]x[height] at [fps].
     *
     * Software decoders are ignored: they will happily claim large sizes and then miss the
     * frame deadline. On any error this returns false — falling back to 1080p is the safe
     * answer when we cannot tell.
     */
    private fun decoderSupports(width: Int, height: Int, fps: Double): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && !isSoftwareCodec(it) }
            .filter { MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
            .any { info ->
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .videoCapabilities
                    ?.areSizeAndRateSupported(width, height, fps) == true
            }
    } catch (e: Exception) {
        Logger.w("Could not query decoder capabilities — assuming no high-resolution support: ${e.message}")
        false
    }

    /**
     * MediaCodecInfo.isSoftwareOnly() only exists from API 29. Below that, fall back to the
     * naming convention the platform itself uses for its bundled software codecs
     * ("OMX.google.*" pre-Q, "c2.android.*" for Codec2).
     */
    private fun isSoftwareCodec(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            val name = info.name.lowercase()
            name.startsWith("omx.google.") || name.startsWith("c2.android.")
        }
}
