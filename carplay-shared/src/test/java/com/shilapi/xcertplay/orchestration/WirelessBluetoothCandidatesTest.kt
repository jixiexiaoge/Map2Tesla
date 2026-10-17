package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WirelessBluetoothCandidatesTest {
    @Test fun pairedIphoneWithUnreportedDeviceClassIsSelected() {
        assertEquals(
            listOf(1),
            wirelessBluetoothCandidateIndices(listOf(
                WirelessBluetoothCandidate("Headphones", false, true),
                WirelessBluetoothCandidate("John's iPhone", false, false),
            )),
        )
    }

    @Test fun connectedIphoneIsTriedBeforeOtherIphones() {
        assertEquals(
            listOf(2, 0),
            wirelessBluetoothCandidateIndices(listOf(
                WirelessBluetoothCandidate("iPhone A", false, false),
                WirelessBluetoothCandidate("Earbuds", false, true),
                WirelessBluetoothCandidate("iPhone B", false, true),
            )),
        )
    }

    @Test fun onlyPairedDeviceMayHaveNoNameOrClass() {
        assertEquals(
            listOf(0),
            wirelessBluetoothCandidateIndices(listOf(
                WirelessBluetoothCandidate(null, false, false),
            )),
        )
    }

    @Test fun phoneClassIsFallbackWhenNameDoesNotContainIphone() {
        assertEquals(
            listOf(1),
            wirelessBluetoothCandidateIndices(listOf(
                WirelessBluetoothCandidate("Watch", false, true),
                WirelessBluetoothCandidate("My phone", true, false),
            )),
        )
    }

    @Test fun multipleUnidentifiableDevicesNeedAnExplicitPairingChoice() {
        assertThrows(IOException::class.java) {
            wirelessBluetoothCandidateIndices(listOf(
                WirelessBluetoothCandidate(null, false, false),
                WirelessBluetoothCandidate("Headphones", false, true),
            ))
        }
    }

    @Test fun noBondedDevicesReportsPairingFailure() {
        assertThrows(IOException::class.java) { wirelessBluetoothCandidateIndices(emptyList()) }
    }
}
