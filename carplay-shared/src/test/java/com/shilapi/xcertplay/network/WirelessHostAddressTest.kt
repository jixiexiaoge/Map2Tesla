package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessHostAddressTest {
    @Test fun manualApPrefersScopedLinkLocalEvenWhenIpv4ComesFirst() {
        val result = wirelessHostAddress(listOf(ip("192.168.43.1"), ip("fe80::1234")), 7) as Inet6Address
        assertTrue(result.isLinkLocalAddress)
        assertEquals(7, result.scopeId)
    }

    @Test fun replacesScopeFromAnotherInterface() {
        val wrongScope = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        assertEquals(8, (wirelessHostAddress(listOf(wrongScope), 8) as Inet6Address).scopeId)
    }

    @Test fun fallsBackToIpv4WithoutUsableLinkLocal() {
        val ipv4 = ip("192.168.43.1")
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("::1"), ip("2001:db8::1"), ipv4), 7))
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("fe80::1234"), ipv4), 0))
        assertNull(wirelessHostAddress(listOf(ip("0.0.0.0"), ip("127.0.0.1"), ip("224.0.0.251")), 7))
    }

    @Test fun boundIpv4ControlProbeNeverSelectsIpv6OrUnusableAddresses() {
        // Given
        val peer = ip("192.168.43.2")
        val candidates = listOf(ip("fe80::2"), ip("0.0.0.0"), ip("127.0.0.1"), ip("224.0.0.251"), peer)
        // When / Then
        assertEquals(peer, carPlayControlAddress(candidates, ip("192.168.43.1")))
        assertNull(carPlayControlAddress(listOf(ip("fe80::2")), ip("192.168.43.1")))
    }

    @Test fun boundIpv6ControlProbeReplacesThePeersScopeWithLocalScope() {
        // Given
        val local = Inet6Address.getByAddress(null, ip("fe80::1").address, 7)
        val peer = Inet6Address.getByAddress(null, ip("fe80::2").address, 42)
        // When
        val result = carPlayControlAddress(listOf(ip("192.168.43.2"), peer), local) as Inet6Address
        // Then
        assertEquals(7, result.scopeId)
        assertArrayEquals(peer.address, result.address)
    }

    @Test fun boundIpv6ControlProbeRejectsUnscopedOrWrongFamilyPaths() {
        // Given
        val local = Inet6Address.getByAddress(null, ip("fe80::1").address, 7)
        // When / Then
        assertNull(carPlayControlAddress(listOf(ip("192.168.43.2"), ip("2001:db8::2")), local))
        assertNull(carPlayControlAddress(listOf(ip("fe80::2")), ip("fe80::1")))
        assertNull(wirelessHostAddress(listOf(ip("169.254.1.1"), ip("fe80::1")), -1))
    }

    @Test fun unboundControlProbePreservesLinkLocalPreference() {
        val peer = ip("fe80::2")
        assertEquals(peer, carPlayControlAddress(listOf(ip("192.168.43.2"), peer), null))
    }

    private fun ip(value: String) = InetAddress.getByName(value)
}
