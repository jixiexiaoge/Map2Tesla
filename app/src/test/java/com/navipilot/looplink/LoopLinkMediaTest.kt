package com.navipilot.looplink

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.VideoCodec
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LoopLinkMediaTest {
    @Test fun videoResyncKeepsAudioButDisablingSoundDiscardsQueuedPcm() {
        // Given
        val queue = LoopLinkViewerQueue()
        queue.config(byteArrayOf(1, 0))
        queue.audio(byteArrayOf(3, 100))
        // When / Then
        queue.config(byteArrayOf(1, 1))
        assertArrayEquals(byteArrayOf(1, 1), queue.next())
        assertArrayEquals(byteArrayOf(3, 100), queue.next())
        queue.audio(byteArrayOf(3, 100))
        queue.clearAudio()
        queue.close()
        assertNull(queue.next())
    }

    @Test fun audioDoesNotWaitBehindPicturesButConfigIsDeliveredFirst() {
        // Given
        val queue = LoopLinkViewerQueue()
        queue.config(byteArrayOf(1))
        queue.video(LoopLinkVideoPacket.Frame(byteArrayOf(2), true, false))
        queue.audio(byteArrayOf(3, 100))
        // When / Then
        assertArrayEquals(byteArrayOf(1), queue.next())
        assertArrayEquals(byteArrayOf(3, 100), queue.next())
        assertArrayEquals(byteArrayOf(2), queue.next())
        queue.close()
    }

    @Test fun allProfilesNegotiateTheirExactOutputSize() {
        // Given / When / Then
        LoopLinkDisplayProfile.entries.forEach { profile ->
            val display = profile.display()
            assertEquals(profile.width, display.widthPixels)
            assertEquals(profile.height, display.heightPixels)
            assertEquals(0, display.widthPixels % 2)
            assertEquals(0, display.heightPixels % 2)
            assertEquals(30, display.fps)
        }
        assertEquals(1120, LoopLinkDisplayProfile.TESLA.width)
        assertEquals(896, LoopLinkDisplayProfile.TESLA.height)
    }

    @Test fun annexBBecomesLengthPrefixedWithoutReencoding() {
        // Given
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0, 0, 1, 0x06, 0x34)
        // When
        val packet = LoopLinkVideoPacket.frame(VideoCodec.H264, annexB, 1234)
        // Then
        assertTrue(packet.key)
        assertEquals(2.toByte(), packet.message[0])
        assertEquals(1.toByte(), packet.message[1])
        assertEquals(1234L, ByteBuffer.wrap(packet.message, 2, 8).long)
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x65, 0x12, 0, 0, 0, 2, 0x06, 0x34), packet.message.copyOfRange(10, packet.message.size))
    }

    @Test fun malformedNalUnitsAreRejected() {
        // Given / When / Then
        for (input in listOf(byteArrayOf(1, 2), byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 1, 0x80.toByte()))) {
            assertThrows(IllegalArgumentException::class.java) {
                LoopLinkVideoPacket.frame(VideoCodec.H264, input, 0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoopLinkVideoPacket.frame(VideoCodec.H265, byteArrayOf(0, 0, 1, 0x26, 0), 0)
        }
    }

    @Test fun slowViewerDropsOldPicturesAndWaitsForNewKeyFrame() {
        // Given
        val queue = LoopLinkViewerQueue()
        val key = LoopLinkVideoPacket.Frame(byteArrayOf(2, 1), true, false)
        val delta = LoopLinkVideoPacket.Frame(byteArrayOf(2, 0), false, false)
        queue.config(byteArrayOf(1))
        assertTrue(queue.video(delta))
        // When
        assertFalse(queue.video(key))
        repeat(11) { assertFalse(queue.video(delta)) }
        assertTrue(queue.video(delta))
        assertTrue(queue.video(delta))
        assertFalse(queue.video(key))
        // Then
        assertArrayEquals(byteArrayOf(1), queue.next())
        assertArrayEquals(key.message, queue.next())
        queue.close()
        assertNull(queue.next())
    }

    @Test fun hevcRaslDoesNotFollowRandomAccessFromAnOldGop() {
        // Given
        val queue = LoopLinkViewerQueue()
        val cra = LoopLinkVideoPacket.frame(VideoCodec.H265, byteArrayOf(0, 0, 1, 0x2a, 1), 0)
        val rasl = LoopLinkVideoPacket.frame(VideoCodec.H265, byteArrayOf(0, 0, 1, 0x10, 1), 1)
        // When
        queue.video(cra)
        queue.video(rasl)
        // Then
        assertArrayEquals(cra.message, queue.next())
        queue.close()
        assertNull(queue.next())
    }

    @Test fun oversizedKeyFrameIsNotQueuedAndAudioIsBounded() {
        // Given
        val queue = LoopLinkViewerQueue()
        // When
        assertTrue(queue.video(LoopLinkVideoPacket.Frame(ByteArray(4 * 1024 * 1024 + 1), true, false)))
        repeat(30) { queue.audio(byteArrayOf(3, it.toByte())) }
        // Then
        repeat(25) { assertArrayEquals(byteArrayOf(3, (it + 5).toByte()), queue.next()) }
        queue.close()
        assertNull(queue.next())
    }

    @Test fun touchReleaseAndTimeoutKeepLastPositionsAndTwoSlots() {
        // Given
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = LoopLinkTouchState({ reports.add(it); true }, {})
        val first = AirPlayContact(0, 0.2, 0.3, true)
        val second = AirPlayContact(1, 0.8, 0.9, true)
        // When
        touch.submit(listOf(first, second), 100)
        touch.submit(listOf(second), 200)
        // Then
        assertEquals(listOf(first.copy(down = false), second), reports.last())
        touch.expire(1699)
        assertTrue(reports.last()[1].down)
        touch.expire(1700)
        assertEquals(listOf(first.copy(down = false), second.copy(down = false)), reports.last())
    }

    @Test fun quickTapIsPreservedAndInvalidTouchesCannotChangeState() {
        // Given
        val sleeps = mutableListOf<Long>()
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = LoopLinkTouchState({ reports.add(it); true }, { sleeps.add(it) })
        val finger = AirPlayContact(0, 0.5, 0.6, true)
        touch.submit(listOf(finger), 100)
        // When / Then
        assertThrows(IllegalArgumentException::class.java) { touch.submit(listOf(finger, finger), 101) }
        assertThrows(IllegalArgumentException::class.java) { touch.submit(listOf(finger.copy(x = Double.NaN)), 101) }
        assertThrows(IllegalArgumentException::class.java) { touch.submit(listOf(finger.copy(id = 2)), 101) }
        touch.submit(emptyList(), 104)
        assertEquals(listOf(26L), sleeps)
        assertEquals(finger.copy(down = false), reports.last()[0])
    }

    @Test fun failedTouchReleaseIsRetriedOnWatchdog() {
        // Given
        var accepted = true
        val reports = mutableListOf<List<AirPlayContact>>()
        val touch = LoopLinkTouchState({ reports.add(it); accepted }, {})
        touch.submit(listOf(AirPlayContact(0, 1.0, 1.0, true)), 0)
        // When
        accepted = false
        touch.release()
        accepted = true
        touch.expire(1500)
        // Then
        assertEquals(3, reports.size)
        assertEquals(AirPlayContact(0, 1.0, 1.0, false), reports.last()[0])
    }
}
