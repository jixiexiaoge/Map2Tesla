package com.navipilot.looplink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopLinkDiagnosticsTest {
    @Test
    fun protocolHeartbeatsDoNotHideUserVisibleProgress() {
        // Given
        LoopLinkState.clearDiagnostics()
        LoopLinkState.addDiagnostic("USB ready")
        // When
        repeat(120) { LoopLinkState.addDiagnostic("TRACE airplay heartbeat=$it") }
        // Then
        assertTrue(LoopLinkState.diagnostics.single().endsWith("USB ready"))
        LoopLinkState.clearDiagnostics()
    }

    @Test
    fun redactsCredentialValuesAndDeviceAddresses() {
        val message = LoopLinkDiagnosticSanitizer.redact(
            "private_key=secret token=abc123 ssid=PhoneHotspot " +
                "mac=02:11:22:33:44:55 challenge=0123456789abcdef0123456789abcdef",
        )

        assertFalse(message.contains("secret"))
        assertFalse(message.contains("abc123"))
        assertFalse(message.contains("PhoneHotspot"))
        assertFalse(message.contains("02:11:22:33:44:55"))
        assertFalse(message.contains("0123456789abcdef"))
        assertTrue(message.contains("private_key=[已隐藏]"))
        assertTrue(message.contains("token=[已隐藏]"))
        assertTrue(message.contains("[设备地址]"))
    }

    @Test
    fun diagnosticsAreBoundedAndCanBeCleared() {
        LoopLinkState.clearDiagnostics()
        repeat(105) { LoopLinkState.addDiagnostic("event-$it") }

        assertTrue(LoopLinkState.diagnostics.size <= 100)
        assertTrue(LoopLinkState.diagnostics.last().endsWith("event-104"))

        LoopLinkState.clearDiagnostics()
        assertTrue(LoopLinkState.diagnostics.isEmpty())
    }
}
