package com.navipilot.looplink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopLinkRequestGuardTest {
    @Test
    fun acceptsLocalHostAndMatchingHttpOrigin() {
        assertTrue(
            LoopLinkRequestGuard.isValidRequestAuthority(
                hostHeader = "192.168.1.20:8088",
                originHeader = "http://192.168.1.20:8088",
                allowedHosts = setOf("192.168.1.20", "99.99.99.99"),
                port = 8088,
            ),
        )
    }

    @Test
    fun rejectsForeignHostPortAndMismatchedOrigin() {
        val allowed = setOf("192.168.1.20", "99.99.99.99")
        assertFalse(
            LoopLinkRequestGuard.isValidRequestAuthority(
                "attacker.example:8088",
                null,
                allowed,
                8088,
            ),
        )
        assertFalse(
            LoopLinkRequestGuard.isValidRequestAuthority(
                "192.168.1.20:8089",
                null,
                allowed,
                8088,
            ),
        )
        assertFalse(
            LoopLinkRequestGuard.isValidRequestAuthority(
                "192.168.1.20:8088",
                "http://attacker.example:8088",
                allowed,
                8088,
            ),
        )
    }

    @Test
    fun allowsDirectIpPortWithoutOriginHeader() {
        assertTrue(
            LoopLinkRequestGuard.isValidRequestAuthority(
                hostHeader = "99.99.99.99:8088",
                originHeader = null,
                allowedHosts = setOf("192.168.1.20", "99.99.99.99"),
                port = 8088,
            ),
        )
    }
}
