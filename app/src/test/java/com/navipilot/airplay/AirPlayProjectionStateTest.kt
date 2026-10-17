package com.navipilot.airplay

import com.navipilot.amap2tesla.ConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class AirPlayProjectionStateTest {
    @After
    fun resetState() = AirPlayProjectionState.reset()

    @Test
    fun browserCanWaitBeforeConnectionAndAfterDisconnect() {
        // Given
        AirPlayProjectionState.reset()
        AirPlayProjectionState.receiverRunning = true
        AirPlayProjectionState.browserProjectionRunning = true
        AirPlayProjectionState.accessUrl = "http://99.99.99.99:8080"

        // When / Then
        assertTrue(AirPlayProjectionState.waitingForSender)
        AirPlayProjectionState.connected = true
        assertFalse(AirPlayProjectionState.waitingForSender)
        AirPlayProjectionState.connected = false
        assertTrue(AirPlayProjectionState.waitingForSender)
        assertTrue(AirPlayProjectionState.browserProjectionRunning)
        assertEquals("http://99.99.99.99:8080", AirPlayProjectionState.accessUrl)
        AirPlayProjectionState.reset()
        assertFalse(AirPlayProjectionState.waitingForSender)
    }

    @Test
    fun reset_clearsReceiverAndBrowserSessionState() {
        // Given
        AirPlayProjectionState.receiverRunning = true
        AirPlayProjectionState.connected = true
        AirPlayProjectionState.browserProjectionRunning = true
        AirPlayProjectionState.senderName = "Test iPhone"
        AirPlayProjectionState.pairingCode = "4827"
        AirPlayProjectionState.accessUrl = "http://99.99.99.99:8080"
        AirPlayProjectionState.connectionMode = ConnectionMode.LAN

        // When
        AirPlayProjectionState.reset("已停止")

        // Then
        assertFalse(AirPlayProjectionState.receiverRunning)
        assertFalse(AirPlayProjectionState.connected)
        assertFalse(AirPlayProjectionState.browserProjectionRunning)
        assertNull(AirPlayProjectionState.senderName)
        assertNull(AirPlayProjectionState.pairingCode)
        assertNull(AirPlayProjectionState.accessUrl)
        assertEquals("已停止", AirPlayProjectionState.status)
        assertEquals(ConnectionMode.LAN, AirPlayProjectionState.connectionMode)
        assertFalse(AirPlayProjectionState.trialMode)
    }
}
