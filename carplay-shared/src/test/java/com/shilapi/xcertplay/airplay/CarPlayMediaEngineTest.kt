package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.net.InetAddress
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CarPlayMediaEngineTest {
    private class RenderSink : MediaSink {
        val handlers = mutableMapOf<Int, (() -> Unit)?>()
        val events = mutableListOf<Pair<Int, Boolean>>()
        val recovery = mutableMapOf<Int, (() -> Unit)?>()
        override fun setVideoRecoveryHandler(type: Int, handler: (() -> Unit)?) {
            recovery[type] = handler
        }
        override fun setFirstVideoFrameRenderedHandler(type: Int, handler: (() -> Unit)?) {
            handlers[type] = handler
        }
        override fun onScreenStreamActive(type: Int, active: Boolean) {
            events += type to active
        }
    }

    @Test
    fun keyframeHandlerIsInstalledOnlyForMainAndRetiredWithItsSession() {
        // Given
        val sink = RenderSink()
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        try {
            // When
            engine.bindScreen(session, 110, ScreenStream(ByteArray(32)))
            engine.bindScreen(session, 111, ScreenStream(ByteArray(32)))
            // Then
            assertTrue(sink.recovery[110] != null)
            assertNull(sink.recovery[111])
            engine.onSessionClosed(session)
            assertNull(sink.recovery[110])
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun renderedCallbackCapturesExactSessionAndRunsOnce() {
        // Given
        val sink = RenderSink()
        val session = testSession()
        val captured = mutableListOf<AirPlaySession>()
        val rendered = mutableListOf<AirPlaySession>()
        val engine = CarPlayMediaEngine(sink, onFirstVideoFrameRendered = { bound ->
            captured += bound
            { rendered.add(bound); Unit }
        })
        try {
            // When
            engine.bindScreen(session, 110, ScreenStream(ByteArray(32)))
            assertSame(session, captured.single())
            assertTrue(rendered.isEmpty())
            repeat(2) { sink.handlers.getValue(110)?.invoke() }
            // Then
            assertSame(session, rendered.single())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun replacementRevokesOldCallbackAndOldSessionTeardownCannotRetireNewStream() {
        // Given
        val sink = RenderSink()
        val first = testSession()
        val next = testSession()
        val rendered = mutableListOf<AirPlaySession>()
        val engine = CarPlayMediaEngine(sink, onFirstVideoFrameRendered = { bound ->
            { rendered.add(bound); Unit }
        })
        try {
            engine.bindScreen(first, 110, ScreenStream(ByteArray(32)))
            val stale = checkNotNull(sink.handlers[110])
            // When
            engine.bindScreen(next, 110, ScreenStream(ByteArray(32)))
            val current = checkNotNull(sink.handlers[110])
            engine.onTeardown(first, 110)
            engine.onSessionClosed(first)
            stale()
            // Then
            assertTrue(rendered.isEmpty())
            assertSame(current, sink.handlers[110])
            current()
            assertSame(next, rendered.single())
            assertEquals(listOf(110 to true, 110 to false, 110 to true), sink.events)
        } finally {
            engine.onSessionClosed(first)
            engine.onSessionClosed(next)
            first.close()
            next.close()
        }
    }

    @Test
    fun sameSessionReplacementAndTeardownRevokeCapturedCallbacks() {
        // Given
        val sink = RenderSink()
        val session = testSession()
        var count = 0
        val engine = CarPlayMediaEngine(sink, onFirstVideoFrameRendered = { { count++ } })
        try {
            engine.bindScreen(session, 110, ScreenStream(ByteArray(32)))
            val first = checkNotNull(sink.handlers[110])
            engine.bindScreen(session, 110, ScreenStream(ByteArray(32)))
            val replacement = checkNotNull(sink.handlers[110])
            // When
            first()
            engine.onTeardown(session, 110)
            replacement()
            // Then
            assertEquals(0, count)
            assertNull(sink.handlers[110])
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun sessionCloseRevokesEveryScreenAndRejectsLateSetup() {
        // Given
        val sink = RenderSink()
        val session = testSession()
        var count = 0
        val engine = CarPlayMediaEngine(sink, onFirstVideoFrameRendered = { { count++ } })
        try {
            engine.bindScreen(session, 110, ScreenStream(ByteArray(32)))
            engine.bindScreen(session, 111, ScreenStream(ByteArray(32)))
            val handlers = sink.handlers.values.filterNotNull()
            // When
            engine.onSessionClosed(session)
            handlers.forEach { it() }
            // Then
            assertEquals(0, count)
            assertNull(sink.handlers[110])
            assertNull(sink.handlers[111])
            assertNull(engine.onScreen(session, 110, emptyMap()))
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun failingFactoryDoesNotReplaceExistingScreenBinding() {
        // Given
        val sink = RenderSink()
        val first = testSession()
        val next = testSession()
        var count = 0
        val engine = CarPlayMediaEngine(sink, onFirstVideoFrameRendered = { session ->
            check(session !== next) { "factory failed" }
            val callback: () -> Unit = { count++ }
            callback
        })
        try {
            engine.bindScreen(first, 110, ScreenStream(ByteArray(32)))
            val current = checkNotNull(sink.handlers[110])
            // When
            val failure = runCatching { engine.bindScreen(next, 110, ScreenStream(ByteArray(32))) }
            current()
            // Then
            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertSame(current, sink.handlers[110])
            assertEquals(1, count)
            assertEquals(listOf(110 to true), sink.events)
        } finally {
            engine.onSessionClosed(first)
            engine.onSessionClosed(next)
            first.close()
            next.close()
        }
    }

    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun microphoneFollowsThePhonesInputPortForAnyMainAudioCategory() {
        val requested = mapOf("audioType" to "default", "dataPort" to 12345)
        assertEquals(12345, requestedMicrophonePort(true, 100, requested))
        assertEquals(
            12345,
            requestedMicrophonePort(true, 100, mapOf("audioType" to "compatibility", "dataPort" to 12345)),
        )
        assertEquals(null, requestedMicrophonePort(false, 100, requested))
        assertEquals(null, requestedMicrophonePort(true, 101, requested))
        assertEquals(null, requestedMicrophonePort(true, 100, mapOf("dataPort" to 0)))
        assertEquals(null, requestedMicrophonePort(true, 100, mapOf("dataPort" to 65536)))
        assertEquals(null, requestedMicrophonePort(true, 100, emptyMap()))
    }

    @Test
    fun opusMicrophoneRateFollowsTheSelectedFormatBit() {
        assertEquals(16_000, AudioStreamCodec.opusCaptureRate(0x10000000L))
        assertEquals(24_000, AudioStreamCodec.opusCaptureRate(0x20000000L))
        assertEquals(48_000, AudioStreamCodec.opusCaptureRate(0x40000000L))
    }

    @Test
    fun microphoneStartsAfterSetupResponseWithoutWaitingForDownlinkAudio() {
        val events = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
                events += "start:$type:${config.audioType}"
            }

            override fun onMicrophoneStopped(type: Int) {
                events += "stop:$type"
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val pendingField = CarPlayMediaEngine::class.java.getDeclaredField("pendingMicrophone").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, MicrophoneConfig>
        pending[CarPlayMediaEngine.StreamKey(session, 100)] = MicrophoneConfig(
            audioType = "default",
            sampleRate = 16_000,
            channels = 1,
            payloadType = 100,
            frameMillis = 20,
            host = InetAddress.getLoopbackAddress(),
            port = 12345,
            key = ByteArray(32),
        )

        engine.onSetupResponseSent(session)
        engine.onSetupResponseSent(session)
        assertEquals(listOf("start:100:default"), events)

        engine.onTeardown(session, 100)
        session.close()
        assertEquals(listOf("start:100:default", "stop:100"), events)
    }

    @Test
    fun sessionCloseStopsAnActiveMicrophone() {
        val events = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
                events += "start"
            }

            override fun onMicrophoneStopped(type: Int) {
                events += "stop"
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val pendingField = CarPlayMediaEngine::class.java.getDeclaredField("pendingMicrophone").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, MicrophoneConfig>
        pending[CarPlayMediaEngine.StreamKey(session, 100)] = MicrophoneConfig(
            audioType = "default",
            sampleRate = 16_000,
            channels = 1,
            payloadType = 100,
            frameMillis = 20,
            host = InetAddress.getLoopbackAddress(),
            port = 12345,
            key = ByteArray(32),
        )

        engine.onSetupResponseSent(session)
        engine.onSessionClosed(session)
        session.close()

        assertEquals(listOf("start", "stop"), events)
        assertTrue(pending.isEmpty())
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
