package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pGroup
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WirelessHotspotManagerTest {
    @Test fun unreportedP2pSecurityFallsBackOnlyToWpa2() {
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, wifiP2pSecurity(-1))
        assertEquals(Iap2WirelessSecurity.WPA_WPA2,
            wifiP2pSecurity(WifiP2pGroup.SECURITY_TYPE_WPA2_PSK))
        assertEquals(Iap2WirelessSecurity.WPA3_TRANSITION,
            wifiP2pSecurity(WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY))
        assertEquals(Iap2WirelessSecurity.WPA3_ONLY,
            wifiP2pSecurity(WifiP2pGroup.SECURITY_TYPE_WPA3_SAE))
        try {
            wifiP2pSecurity(99)
            org.junit.Assert.fail("Unsupported reported security must not be advertised as WPA2")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("99"))
        }
    }

    @Test fun liveWpaVariantsAreCompatibleButOpenSecuredMismatchIsNot() {
        // Given
        val secured = listOf(Iap2WirelessSecurity.WPA_WPA2, Iap2WirelessSecurity.WPA3_TRANSITION,
            Iap2WirelessSecurity.WPA3_ONLY)
        // When / Then
        for (actual in secured) {
            for (configured in secured) assertTrue(manualHotspotSecurityCompatible(actual, configured))
            assertFalse(manualHotspotSecurityCompatible(actual, Iap2WirelessSecurity.NONE))
            assertFalse(manualHotspotSecurityCompatible(Iap2WirelessSecurity.NONE, actual))
        }
        assertTrue(manualHotspotSecurityCompatible(Iap2WirelessSecurity.NONE, Iap2WirelessSecurity.NONE))
    }

    @Test
    fun convertsWifiP2pFrequencyToIap2Channel() {
        assertEquals(1, wifiFrequencyMhzToChannel(2412))
        assertEquals(6, wifiFrequencyMhzToChannel(2437))
        assertEquals(14, wifiFrequencyMhzToChannel(2484))
        assertEquals(36, wifiFrequencyMhzToChannel(5180))
        assertEquals(149, wifiFrequencyMhzToChannel(5745))
        assertEquals(177, wifiFrequencyMhzToChannel(5885))
        assertEquals(1, wifiFrequencyMhzToChannel(5955))
    }

    @Test
    fun rejectsUnsupportedOrInvalidFrequencies() {
        assertNull(wifiFrequencyMhzToChannel(0))
        assertNull(wifiFrequencyMhzToChannel(2413))
        assertNull(wifiFrequencyMhzToChannel(6001))
    }

    @Test
    fun convertsKnownChannelAndBandToFrequency() {
        assertEquals(2412, wifiChannelToFrequencyMhz(1, band = 1))
        assertEquals(5180, wifiChannelToFrequencyMhz(36, band = 2))
        assertEquals(5955, wifiChannelToFrequencyMhz(1, band = 4))
        assertNull(wifiChannelToFrequencyMhz(1, band = 3)) // Multi-band auto is not 6 GHz.
        assertNull(wifiChannelToFrequencyMhz(0, band = 2))
        assertNull(wifiChannelToFrequencyMhz(36, band = null))
    }

    @Test fun legacyHotspotBandUsesItsOwnConstants() {
        assertEquals(2412, wifiChannelToFrequencyMhz(1, legacyHotspotBandToSoftApBand(0)))
        assertEquals(5745, wifiChannelToFrequencyMhz(149, legacyHotspotBandToSoftApBand(1)))
        assertNull(wifiChannelToFrequencyMhz(1, legacyHotspotBandToSoftApBand(-1)))
        assertEquals("2.4 / 5 GHz (auto)", wifiBandLabel(3))
        assertEquals("6 GHz", wifiBandLabel(4))
        assertNull(legacyHotspotBandToSoftApBand(null))
    }

    @Test
    fun doesNotReportDesiredChannelWhenAndroidDoesNotExposeOne() {
        assertEquals(
            0,
            observedManualHotspotChannel(
                apChannel = 0,
                connectionFrequencyMHz = null,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
    }

    @Test
    fun reportsOnlyAnObservedActiveChannel() {
        assertEquals(
            36,
            observedManualHotspotChannel(
                apChannel = 36,
                connectionFrequencyMHz = null,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
        assertEquals(
            44,
            observedManualHotspotChannel(
                apChannel = 0,
                connectionFrequencyMHz = 5220,
                scanFrequencyMHz = null,
                apFrequencyMHz = null,
            ),
        )
    }
}
