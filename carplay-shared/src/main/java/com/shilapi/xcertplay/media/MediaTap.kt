package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/** Optional remote output. Callbacks run on media workers and must not block on network I/O. */
interface MediaTap {
    fun needsDecodedVideo(type: Int): Boolean = true
    fun onVideoConfig(type: Int, codec: VideoCodec, data: ByteArray) {}
    fun onVideoFrame(type: Int, annexB: ByteArray, arrivalUs: Long) {}
    fun onVideoActive(type: Int, active: Boolean) {}

    /** Return true only when a ready remote player has accepted this PCM instead of local playback. */
    fun onAudioPcm(stream: Int, sampleRate: Int, channels: Int, data: ByteArray, offset: Int, length: Int): Boolean = false
    fun onAudioStopped(stream: Int) {}
}
