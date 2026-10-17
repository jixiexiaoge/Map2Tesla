package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioBufferProgressTest {
    @Test fun remotePlaybackFlushStartsANewLocalHeadTimeline() {
        // Given
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        buffer.queuedBytes(500)
        // When
        buffer.reset()
        buffer.written(400)
        // Then
        assertEquals(400, buffer.queuedBytes(0))
        assertEquals(0, buffer.queuedBytes(100))
    }

    @Test fun finalShortBurstStartsAfterBoundedWaitWithoutWaitingForAnotherPacket() {
        // Given
        val buffer = AudioBufferProgress(4)
        val lastWrite = 100L
        // When / Then
        assertFalse(buffer.shouldStartTail(false, 400, true, lastWrite, lastWrite + 499_999_999))
        assertTrue(buffer.shouldStartTail(false, 400, true, lastWrite, lastWrite + 500_000_000))
        assertFalse(buffer.shouldStartTail(true, 400, true, lastWrite, lastWrite + 500_000_000))
        assertFalse(buffer.shouldStartTail(false, 0, true, lastWrite, lastWrite + 500_000_000))
        assertFalse(buffer.shouldStartTail(false, 400, false, lastWrite, lastWrite + 500_000_000))
    }

    @Test fun musicRebuffersOnlyAfterHardwareAndIncomingQueuesDrain() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertFalse(buffer.shouldRebuffer("media", true, true, true, 999))
        assertFalse(buffer.shouldRebuffer("media", true, true, false, 1000))
        assertFalse(buffer.shouldRebuffer("media", true, false, true, 1000))
        assertTrue(buffer.shouldRebuffer("media", true, true, true, 1000))
        assertFalse(buffer.shouldRebuffer("media", false, true, true, 1000))
        buffer.written(400)
        assertEquals(400, buffer.queuedBytes(1000))
        assertFalse(buffer.shouldRebuffer("media", true, true, true, 1000))
    }

    @Test fun doesNotAddRebufferDelayToCallsOrSpeech() {
        val buffer = AudioBufferProgress(2)
        for (type in listOf("telephony", "speechrecognition", "default", "alert")) {
            assertFalse(buffer.shouldRebuffer(type, true, true, true, 0))
        }
    }

    @Test fun unsignedPlaybackHeadWrapKeepsQueuedAudio() {
        val buffer = AudioBufferProgress(2)
        repeat(8) { buffer.written(1_073_741_824) }
        buffer.written(40)
        assertEquals(42, buffer.queuedBytes(-1))
        assertEquals(40, buffer.queuedBytes(0))
        assertEquals(0, buffer.queuedBytes(20))
    }
}
