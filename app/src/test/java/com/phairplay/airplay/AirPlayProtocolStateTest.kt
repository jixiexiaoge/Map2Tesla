package com.phairplay.airplay

import com.phairplay.service.ProtocolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayProtocolStateTest {
    @Test
    fun lateDiscoveryRegistrationDoesNotDisconnectMirroringIpad() {
        val state = AirPlayProtocolState()
        assertEquals(ProtocolState.CONNECTED, state.update(ProtocolState.CONNECTED))
        assertNull(state.update(ProtocolState.ADVERTISING, fromDiscovery = true))
        assertNull(state.update(ProtocolState.CONNECTED))
    }

    @Test
    fun discoveryRestartDoesNotCloseWaitingBrowserEntry() {
        val state = AirPlayProtocolState()
        state.update(ProtocolState.CONNECTED)
        assertEquals(ProtocolState.ADVERTISING, state.update(ProtocolState.ADVERTISING))
        assertNull(state.update(ProtocolState.DISABLED, fromDiscovery = true))
        assertNull(state.update(ProtocolState.ADVERTISING, fromDiscovery = true))
        assertEquals(ProtocolState.CONNECTED, state.update(ProtocolState.CONNECTED))
    }

    @Test
    fun discoveryErrorsDoNotTerminateAnActiveMediaSession() {
        val state = AirPlayProtocolState()
        state.update(ProtocolState.CONNECTED)
        assertNull(state.update(ProtocolState.ERROR, fromDiscovery = true))
        assertEquals(ProtocolState.ERROR, state.update(ProtocolState.ERROR))
    }

    @Test
    fun initialAdvertisementAndActualSessionStopStillPropagate() {
        val state = AirPlayProtocolState()
        assertEquals(ProtocolState.ADVERTISING, state.update(ProtocolState.ADVERTISING, fromDiscovery = true))
        assertEquals(ProtocolState.CONNECTED, state.update(ProtocolState.CONNECTED))
        assertEquals(ProtocolState.ADVERTISING, state.update(ProtocolState.ADVERTISING))
        assertEquals(ProtocolState.DISABLED, state.update(ProtocolState.DISABLED))
    }
}
