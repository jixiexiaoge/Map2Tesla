package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessConnectionProofTest {
    @Test fun localAndRemoteAuthenticationNeverScheduleHardwarePolling() {
        // Given / When / Then
        assertFalse(shouldPollMfiAvailability(MfiTarget.LOCAL))
        assertFalse(shouldPollMfiAvailability(MfiTarget.REMOTE))
        assertTrue(shouldPollMfiAvailability(MfiTarget.I2C))
        assertTrue(shouldPollMfiAvailability(MfiTarget.USB_CH341))
    }

    @Test fun authenticationWithoutVideoDoesNotConfirm() {
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, Any())
        proof.authenticated(1)
        assertEquals(0, saves)
    }

    @Test fun bothEventsAreRequiredInEitherOrderAndOnlySaveOnce() {
        for (videoFirst in listOf(true, false)) {
            var saves = 0
            val session = Any()
            val proof = WirelessConnectionProof<Any>()
            proof.begin(1) { saves++ }
            proof.activate(1, session)
            if (videoFirst) proof.rendered(1, session) else proof.authenticated(1)
            assertEquals(0, saves)
            if (videoFirst) proof.authenticated(1) else proof.rendered(1, session)
            proof.rendered(1, session)
            proof.authenticated(1)
            assertEquals(1, saves)
        }
    }

    @Test fun oldGenerationAndOldSessionCannotConfirmANewConnection() {
        var saves = 0
        val old = Any()
        val current = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, old)
        proof.authenticated(1)
        proof.begin(2) { saves++ }
        proof.activate(2, current)
        proof.rendered(1, old)
        proof.authenticated(1)
        proof.rendered(2, old)
        proof.authenticated(2)
        assertEquals(0, saves)
        proof.rendered(2, current)
        assertEquals(1, saves)
    }

    @Test fun endedOrClearedSessionCannotBeLearnedByLateCallbacks() {
        val session = Any()
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, session)
        proof.rendered(1, session)
        proof.end(1, session)
        proof.authenticated(1)
        proof.clear()
        proof.activate(1, session)
        proof.authenticated(1)
        proof.rendered(1, session)
        assertEquals(0, saves)
    }

    @Test fun replacementSessionNeedsItsOwnAuthenticationAndFrame() {
        val first = Any()
        val second = Any()
        var saves = 0
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, first)
        proof.authenticated(1)
        proof.activate(1, second)
        proof.rendered(1, second)
        assertEquals(0, saves)
        proof.authenticated(1)
        assertEquals(1, saves)
    }

    @Test fun repeatedSessionActivationPreservesItsRealRenderedFrame() {
        // Given
        var saves = 0
        val session = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { saves++ }
        proof.activate(1, session)
        proof.rendered(1, session)
        // When
        proof.activate(1, session)
        proof.authenticated(1)
        // Then
        assertEquals(1, saves)
    }

    @Test fun callbackCapturedForOldGenerationCannotSaveReplacementHotspot() {
        // Given
        var oldSaves = 0
        var currentSaves = 0
        val session = Any()
        val proof = WirelessConnectionProof<Any>()
        proof.begin(1) { oldSaves++ }
        proof.activate(1, session)
        val retiredCallback = { proof.rendered(1, session) }
        proof.clear()
        proof.begin(2) { currentSaves++ }
        proof.activate(2, session)
        proof.authenticated(2)
        // When
        retiredCallback()
        // Then
        assertEquals(0, oldSaves)
        assertEquals(0, currentSaves)
        proof.rendered(2, session)
        assertEquals(1, currentSaves)
    }
}
