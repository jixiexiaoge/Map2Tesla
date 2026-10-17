package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2pConfigurationMemoryTest {
    @Test fun decodesOnlyRadioConfigurationAndSessionVersion() {
        // Given
        val encoded = "frequency|2437|2437|5180|$version"
        // When
        val result = requireNotNull(P2pConfigurationMemory.decode(encoded))
        // Then
        assertEquals(encoded, result.encoded)
        assertEquals(P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2437), result.request)
        assertEquals(5180, result.stationMHz)
    }

    @Test fun systemSelectionPreservesUnknownStationAsNull() {
        // Given / When
        val result = requireNotNull(P2pConfigurationMemory.decode("system|0|5180|0|$version"))
        // Then
        assertEquals(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT), result.request)
        assertNull(result.stationMHz)
    }

    @Test fun malformedOrUnsafeConfigurationIsIgnored() {
        // Given
        val invalid = listOf(
            null,
            "",
            "frequency|5500|5500|0|$version",
            "frequency|0|2437|0|$version",
            "system|2437|2437|0|$version",
            "system|0|5955|0|$version",
            "system|0|0|0|$version",
            "system|0|5180|-1|$version",
            "system|0|5180|5191|$version",
            "system|0|5180|0|not-a-version",
            "frequency|text|2437|0|$version",
            "unknown|2437|2437|0|$version",
            "frequency|2437|2437|0|$version|unexpected",
        )
        // When / Then
        invalid.forEach { assertNull(it, P2pConfigurationMemory.decode(it)) }
    }

    private val version = "28c5f598-f6db-489f-a063-6e90be7b638b"
}
