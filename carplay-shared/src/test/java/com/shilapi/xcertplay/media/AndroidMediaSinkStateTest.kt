package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidMediaSinkStateTest {
    @Test fun closingSinkRejectsLateStreamEvents() {
        // Given
        val events = mutableListOf<Pair<Int, Boolean>>()
        var frames = 0
        val sink = AndroidMediaSink(
            onScreenStreamActiveChanged = { type, active -> events.add(type to active) },
            onFirstVideoFrameRendered = { frames++ },
        )
        sink.onScreenStreamActive(110, true)
        // When
        sink.close()
        sink.close()
        sink.onScreenStreamActive(110, true)
        sink.onVideoCodec(110, VideoCodec.H264)
        sink.onVideoConfig(110, byteArrayOf())
        sink.onVideoFrame(110, byteArrayOf())
        // Then
        assertEquals(listOf(110 to true), events)
        assertEquals(0, frames)
    }
}
