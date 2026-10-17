package com.phairplay.util

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import timber.log.Timber
import java.net.NetworkInterface
import java.util.UUID

/**
 * NetworkUtils — Helper functions for reading network interface information.
 *
 * WHY: The AirPlay protocol requires the receiver to advertise its MAC address
 * and device name via mDNS TXT records. This class provides clean, safe methods
 * to read this information from the Android system.
 *
 * HOW: All methods are static (on the companion object) — no instance needed.
 * Read the device name and MAC address once at startup and pass them to MdnsService.
 *
 * Example:
 *   val name = NetworkUtils.getDeviceName(context)   // "Galaxy A13"
 *   val mac  = NetworkUtils.getMacAddress(context)    // "aa:bb:cc:dd:ee:ff" (stable, unique per install)
 *   val uuid = NetworkUtils.getPersistentUuid(context) // stable UUID per device
 */
object NetworkUtils {

    /**
     * Returns the user-visible device name as configured in Android settings.
     *
     * This is the name that will appear in the macOS AirPlay picker, so it's
     * important that it matches what the user set in their phone's settings.
     *
     * Sources tried in order:
     * 1. Settings.Global.DEVICE_NAME (Android 5+, most TVs)
     * 2. Settings.Secure.BLUETOOTH_NAME (Bluetooth device name, often same as device name)
     * 3. Fallback: "PhairPlay" (if neither source is available)
     *
     * SECURITY: The returned value is sanitized — mDNS service names must not contain
     * certain special characters. We strip any character outside [A-Za-z0-9 _-].
     *
     * @param context Android context (needed to read system settings)
     * @return The sanitized device name, never null or empty.
     */
    fun getDeviceName(context: Context): String {
        val rawName = Settings.Global.getString(context.contentResolver, "device_name")
            ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            ?: DEFAULT_DEVICE_NAME

        // Sanitize: keep only safe characters for mDNS service names
        val sanitized = rawName.replace(Regex("[^A-Za-z0-9 _\\-]"), "").trim()

        return sanitized.ifEmpty { DEFAULT_DEVICE_NAME }
    }

    /**
     * Returns the device's Wi-Fi or Ethernet MAC address.
     *
     * The MAC address is used as the `deviceid` in AirPlay mDNS TXT records.
     * It uniquely identifies this receiver to macOS senders.
     *
     * Tries Wi-Fi first (most TVs are Wi-Fi), then falls back to any available
     * non-loopback interface, then uses a fake address as last resort.
     *
     * NOTE: On Android 10+, direct MAC access is restricted. We use NetworkInterface
     * instead of WifiManager.getConnectionInfo() which is deprecated.
     *
     * CRITICAL for a fleet: `deviceid` is the identity iOS keys an AirPlay receiver on. Modern
     * Android withholds the real hardware MAC (returns null or the constant 02:00:00:00:00:00), so
     * every install used to fall back to the SAME hardcoded aa:bb:cc:dd:ee:ff — meaning all TVs on
     * the LAN advertised one identity and iOS merged them / showed one name for all. When no unique
     * hardware MAC is available we now derive a stable, locally-administered, unicast MAC from the
     * per-install persistent UUID, so each device gets a distinct but stable deviceid.
     *
     * @param context Android context (for the per-install persistent UUID fallback).
     * @return MAC address in "aa:bb:cc:dd:ee:ff" format (lowercase, colon-separated).
     */
    fun getMacAddress(context: Context): String {
        val real = readHardwareMac()
        if (real != null && !isNonUniqueMac(real)) return real
        return deriveStableMac(context)
    }

    /** Reads a real interface MAC, or null when the OS withholds it / on error. */
    private fun readHardwareMac(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { !it.isLoopback && it.isUp && it.hardwareAddress != null }
            .mapNotNull { iface ->
                iface.hardwareAddress?.joinToString(":") { byte -> "%02x".format(byte) }
            }
            .firstOrNull { !isNonUniqueMac(it) }
    } catch (e: Exception) {
        Timber.w(e, "Could not read MAC address — will derive a stable one")
        null
    }

    /** True for MACs that don't uniquely identify this device (Android's withheld-MAC constant / old fallback). */
    private fun isNonUniqueMac(mac: String): Boolean {
        val m = mac.lowercase()
        return m == "02:00:00:00:00:00" || m == FALLBACK_MAC_ADDRESS
    }

    /**
     * Derives a stable MAC unique to this install from [getPersistentUuid]. The first octet is
     * forced to locally-administered (0x02) + unicast (low bit clear) so it's a valid, collision-free
     * address that isn't mistaken for a real vendor MAC. Stable across restarts and `install -r`
     * (survives as long as app data isn't cleared); a fresh install after uninstall gets a new one.
     */
    private fun deriveStableMac(context: Context): String {
        val msb = UUID.fromString(getPersistentUuid(context)).mostSignificantBits
        val bytes = ByteArray(6) { i -> ((msb ushr (8 * (5 - i))) and 0xFF).toByte() }
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()  // locally administered, unicast
        return bytes.joinToString(":") { "%02x".format(it) }
    }

    /**
     * Returns a stable, device-specific UUID for use in AirPlay's `pi` TXT record.
     *
     * WHY: macOS uses the `pi` (persistent identifier) to recognize a receiver
     * across app restarts. If we generate a new UUID every time, macOS may show
     * duplicate entries in the AirPlay menu.
     *
     * This UUID is generated once and stored in Android's secure settings,
     * so it persists across app restarts and even reinstalls (as long as the
     * app's data is not cleared).
     *
     * SECURITY: This UUID is not a secret — it's transmitted in plaintext via mDNS.
     * It does not contain any sensitive device information.
     *
     * @param context Android context (needed to read/write secure settings)
     * @return A stable UUID string in standard "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" format.
     */
    fun getPersistentUuid(context: Context): String {
        // Persist the UUID in the app's own private SharedPreferences.
        // (Originally this used Settings.Secure, which requires the privileged
        // WRITE_SECURE_SETTINGS permission and threw a SecurityException on normal
        // installs, aborting AirPlay receiver startup. App-private storage needs no
        // permission and still persists across restarts.)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedUuid = prefs.getString(PREF_KEY_DEVICE_UUID, null)
        if (!storedUuid.isNullOrBlank()) {
            return storedUuid
        }

        // Generate a new UUID and store it for future use
        val newUuid = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_KEY_DEVICE_UUID, newUuid).apply()
        return newUuid
    }

    // Constants
    private const val DEFAULT_DEVICE_NAME = "PhairPlay"
    private const val FALLBACK_MAC_ADDRESS = "aa:bb:cc:dd:ee:ff"
    private const val PREFS_NAME = "phairplay_prefs"
    private const val PREF_KEY_DEVICE_UUID = "phairplay_device_uuid"
}
