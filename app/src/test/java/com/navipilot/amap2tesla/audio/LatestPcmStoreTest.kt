package com.navipilot.amap2tesla.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestPcmStoreTest {
    @Test
    fun publish_exposesLatestPcmWithItsFormat() {
        // Given
        val store = LatestPcmStore()

        // When
        store.publish(byteArrayOf(1, 2, 3, 4), sampleRate = 44_100, channels = 2)
        val snapshot = store.awaitAfter(previousSequence = -1, timeoutMillis = 0)

        // Then
        requireNotNull(snapshot)
        assertEquals(1L, snapshot.sequence)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), snapshot.bytes)
        assertEquals(44_100, snapshot.sampleRate)
        assertEquals(2, snapshot.channels)
    }

    @Test
    fun clear_removesAudioFormatAndFrames() {
        // Given
        val store = LatestPcmStore()
        store.publish(byteArrayOf(1, 2), sampleRate = 48_000, channels = 2)

        // When
        store.clear()

        // Then
        val metrics = store.metrics()
        assertEquals(1L, metrics.sequence)
        assertEquals(0, metrics.sampleRate)
        assertEquals(0, metrics.channels)
    }

    @Test
    fun idleReaderDoesNotReplayPreviousAudio() {
        val store = LatestPcmStore()
        store.publish(byteArrayOf(1, 2), 48_000, 1)
        assertNull(store.awaitAfter(previousSequence = 1, timeoutMillis = 1))
    }

    @Test
    fun reconnectKeepsSequenceMonotonicForExistingReaders() {
        val store = LatestPcmStore()
        repeat(10) { store.publish(byteArrayOf(1, 2), 48_000, 1) }
        store.clear()
        store.publish(byteArrayOf(3, 4), 48_000, 1)
        val next = requireNotNull(store.awaitAfter(previousSequence = 10, timeoutMillis = 1))
        assertEquals(11L, next.sequence)
        assertArrayEquals(byteArrayOf(3, 4), next.bytes)
    }
}
