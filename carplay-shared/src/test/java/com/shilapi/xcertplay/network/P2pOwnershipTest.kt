package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class P2pOwnershipTest {
    @Test fun reinstallWithoutPreferencesCanReclaimOnlyItsOwnNamespace() {
        val prefix = P2pOwnership.prefix("com.shihab.diplay", "device-user-signing-key-id")
        val ssid = prefix + "aB23"
        assertTrue(P2pOwnership.canReclaim(true, ssid, null, prefix))
        assertFalse(P2pOwnership.canReclaim(false, ssid, null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, ssid, null, P2pOwnership.prefix("other.app", "device-user-signing-key-id")))
        assertFalse(P2pOwnership.canReclaim(true, ssid, null, P2pOwnership.prefix("com.shihab.diplay", "other-device")))
        assertTrue(ssid.length <= 32)
    }

    @Test fun legacyGroupNeedsExactRecordAndUnknownGroupsNeedExplicitReset() {
        val prefix = P2pOwnership.prefix("app", "id")
        assertTrue(P2pOwnership.canReclaim(true, "DIRECT-xcaB23", "DIRECT-xcaB23", prefix))
        assertFalse(P2pOwnership.canReclaim(true, "DIRECT-xcaB23", null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, null, null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, prefix + "", null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, prefix + "aB23-extra", null, prefix))
    }

    @Test fun cleanupRequiresTheExactCurrentSessionCredentials() {
        // Given
        val prefix = P2pOwnership.prefix("app", "id")
        val original = prefix + "aB23"
        val replacement = prefix + "cD45"
        // When / Then
        assertTrue(P2pOwnership.canRemove(true, original, "original-password", original, "original-password"))
        assertFalse(P2pOwnership.canRemove(true, replacement, "replacement-password", original, "original-password"))
        assertFalse(P2pOwnership.canRemove(true, original, "replacement-password", original, "original-password"))
        assertFalse(P2pOwnership.canRemove(false, original, "original-password", original, "original-password"))
    }

    @Test fun cleanupNeverInfersOwnershipFromMissingOrSystemGeneratedCredentials() {
        // Given / When / Then
        assertFalse(P2pOwnership.canRemove(true, "DIRECT-system", "password", null, null))
        assertFalse(P2pOwnership.canRemove(true, "DIRECT-owned", null, "DIRECT-owned", "password"))
        assertFalse(P2pOwnership.canRemove(true, "DIRECT-owned", "", "DIRECT-owned", ""))
        assertFalse(P2pOwnership.canRemove(true, null, null, null, null))
    }
}
