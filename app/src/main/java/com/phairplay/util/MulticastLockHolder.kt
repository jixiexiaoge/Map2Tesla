package com.phairplay.util

import android.content.Context
import android.net.wifi.WifiManager

/**
 * MulticastLockHolder — keeps the Wi-Fi chip delivering multicast packets to this app.
 *
 * WHY: mDNS discovery is multicast (224.0.0.251 / ff02::fb). To save power the Wi-Fi driver
 * filters out multicast frames that are not addressed to the device unless something holds a
 * [WifiManager.MulticastLock]. Without one, a sender's browse query can simply never reach the
 * responder.
 *
 * The symptom is a slow connection rather than a broken one, which is why it is easy to miss:
 * the sender's first query goes unanswered, and only its retry — seconds later — gets through.
 * A packet capture during a reconnect showed exactly that, a four-second silence between the
 * iPhone's first `_airplay._tcp` query and the phone's first response.
 *
 * The app declared CHANGE_WIFI_MULTICAST_STATE from the start but never acquired a lock, so the
 * permission did nothing.
 *
 * Holding the lock costs battery, so it is tied to the receiver's lifetime: acquired when the
 * AirPlay receiver starts, released when it stops.
 */
class MulticastLockHolder(private val context: Context) {

    private var lock: WifiManager.MulticastLock? = null

    /** Acquires the lock if not already held. Safe to call twice. */
    @Synchronized
    fun acquire() {
        if (lock != null) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi == null) {
                Logger.w("No WifiManager — cannot hold a multicast lock; mDNS may be slow to answer")
                return
            }
            lock = wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Logger.i("Multicast lock acquired — mDNS queries will reach the responder")
        } catch (e: Exception) {
            // Never fatal: without the lock discovery is slower, not broken.
            Logger.e("Could not acquire multicast lock", e)
            lock = null
        }
    }

    /** Releases the lock if held. Safe to call twice. */
    @Synchronized
    fun release() {
        val held = lock ?: return
        lock = null
        try {
            if (held.isHeld) held.release()
            Logger.i("Multicast lock released")
        } catch (e: Exception) {
            Logger.e("Could not release multicast lock", e)
        }
    }

    /** True while the lock is held — used by tests and diagnostics. */
    @Synchronized
    fun isHeld(): Boolean = lock?.isHeld == true

    private companion object {
        const val TAG = "PhairPlay-mDNS"
    }
}
