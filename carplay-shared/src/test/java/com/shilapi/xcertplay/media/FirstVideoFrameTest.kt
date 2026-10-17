package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstVideoFrameTest {
    @Test fun oneNotificationPerStreamNotPerDecoder() {
        // Given
        val first = FirstVideoFrame()
        var callbacks = 0
        // When
        repeat(3) { first.rendered { callbacks++ } }
        // Then
        assertEquals(1, callbacks)
        FirstVideoFrame().rendered { callbacks++ }
        assertEquals(2, callbacks)
    }

    @Test fun failingCallbackIsNotRepeated() {
        // Given
        val first = FirstVideoFrame()
        var callbacks = 0
        // When
        runCatching { first.rendered { callbacks++; error("consumer failed") } }
        first.rendered { callbacks++ }
        // Then
        assertEquals(1, callbacks)
    }
}
