package com.jixiexiaoge.drivingassist

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayPermissionsTest {
    @Test
    fun android10DoesNotRequirePermissionsIntroducedInLaterVersions() {
        val permissions = requiredCarPlayPermissions(29)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
    }

    @Test
    fun android12And13RequireOnlyAvailableNearbyPermissions() {
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in requiredCarPlayPermissions(31))
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in requiredCarPlayPermissions(31))
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in requiredCarPlayPermissions(33))
    }
}
