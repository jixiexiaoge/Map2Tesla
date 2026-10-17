package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils

/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know PhairPlay exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Kitchen Phone")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Kitchen Phone")
 */
class MdnsService(
    private val context: Context,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "PhairPlay", Android will register us as
     * "PhairPlay (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: PhairPlay (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {}
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // Count of how many services have confirmed registration.
    // Only when both reach 2 do we emit ProtocolState.ADVERTISING.
    @Volatile
    private var registeredCount = 0

    // Guard against double-start
    @Volatile
    private var isStarted = false

    // Used for delayed registration retries and the restart-timeout fallback.
    private val handler = Handler(Looper.getMainLooper())

    // Unregistration is asynchronous: these track in-flight unregisters so [restart] can
    // wait for the old registrations to actually disappear before re-registering (otherwise
    // we race our own stale registration and the name escalates to "(2)"/"(3)").
    @Volatile
    private var pendingUnregistrations = 0
    @Volatile
    private var restartPending = false
    @Volatile
    private var restartDisplayName: String? = null
    private var restartFallback: Runnable? = null

    // Watchdog for the _airplay registration: when the requested name conflicts with another
    // record on the SAME device (e.g. the system's own Google Cast registration), MdnsAdvertiser
    // can get stuck probing forever and never delivers ANY callback. Silence past the probe
    // timeout is therefore treated as a name conflict.
    private var airPlayWatchdog: Runnable? = null

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    @Synchronized
    fun start(displayNameOverride: String? = null) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        registeredCount = 0

        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting mDNS advertising as '$effectiveName'")

        registerAirPlayService(effectiveName)
        registerRaopService(effectiveName)
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     */
    @Synchronized
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        var submitted = 0
        try {
            airPlayListener?.let { nsdManager.unregisterService(it); submitted++ }
            raopListener?.let { nsdManager.unregisterService(it); submitted++ }
        } catch (e: Exception) {
            // Unregistration errors are non-fatal: service will expire via mDNS TTL.
            // Don't count callbacks we may never get — a pending restart would stall on them
            // (the conflict-retry in registration covers any leftover stale registration).
            Logger.e("Error unregistering mDNS services (non-fatal)", e)
            submitted = 0
        } finally {
            cancelAirPlayWatchdog()
            pendingUnregistrations = submitted
            airPlayListener = null
            raopListener = null
            registeredCount = 0
            isStarted = false
            onStateChange(ProtocolState.DISABLED)
        }
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers.
     *
     * Unregistration is asynchronous, so this waits for [NsdManager] to confirm both
     * unregistrations (with a [RESTART_TIMEOUT_MS] fallback) before re-registering.
     * Re-registering while the old registration is still live made NsdManager treat it
     * as a conflict and escalate the name to "Name (2)"/"(3)" after every teardown.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    @Synchronized
    fun restart(displayNameOverride: String? = null) {
        // Two paths end a session — the TEARDOWN handler and the control-connection cleanup —
        // and the sender triggers both within milliseconds. Without coalescing, the second
        // restart's stop() ran while the first restart's registrations were still in flight,
        // orphaning them: they stayed live in NsdManager for the life of the process, so every
        // later registration collided and NsdManager escalated the name to "Galaxy A13 (2)",
        // then "(3)". Duplicate names bloat every mDNS answer and leave senders holding stale
        // records.
        if (restartPending) {
            Logger.d("Restart already pending — folding this request into it")
            restartDisplayName = displayNameOverride
            return
        }
        Logger.d("Restarting mDNS advertising")
        restartDisplayName = displayNameOverride
        restartPending = true
        stop()
        if (pendingUnregistrations <= 0) {
            completeRestart("immediate")
        } else {
            restartFallback = Runnable { completeRestart("timeout") }.also {
                handler.postDelayed(it, RESTART_TIMEOUT_MS)
            }
        }
    }

    /** Runs the deferred [restart] once unregistration completed (or timed out). */
    @Synchronized
    private fun completeRestart(reason: String) {
        if (!restartPending) return
        restartPending = false
        restartFallback?.let { handler.removeCallbacks(it) }
        restartFallback = null
        Logger.d("mDNS restart proceeding ($reason)")
        start(restartDisplayName)
    }

    /** Bookkeeping for [restart]: called from every unregistration callback. */
    @Synchronized
    private fun onUnregistrationDone() {
        if (pendingUnregistrations > 0) pendingUnregistrations--
        if (pendingUnregistrations == 0 && restartPending) completeRestart("unregistered")
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Determines the effective name to advertise.
     * Uses [override] if non-blank; otherwise reads from the Android system.
     */
    private fun resolveDisplayName(override: String?): String {
        val trimmed = override?.trim() ?: ""
        return if (trimmed.isNotEmpty()) trimmed else NetworkUtils.getDeviceName(context)
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features PhairPlay supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun registerAirPlayService(displayName: String, attempt: Int = 1) {
        val attemptName = nameForAttempt(displayName, attempt)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = attemptName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records
            setAttribute("deviceid", NetworkUtils.getMacAddress(context))
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", AIRPLAY_MODEL)
            setAttribute("srcvers", AIRPLAY_SERVER_VERSION)
            setAttribute("vv", "2")                             // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))
            setAttribute("flags", "0x4")                        // Screen-mirroring receiver
        }

        val listener = createRegistrationListener(
            serviceLabel = "_airplay._tcp",
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != attemptName) {
                    Logger.w("mDNS name collision detected: requested='$attemptName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            },
            onSuccess = {
                cancelAirPlayWatchdog()
                incrementAndCheckBothRegistered()
            },
            onFailure = { errorCode ->
                cancelAirPlayWatchdog()
                retryOrFail(serviceLabel = "_airplay._tcp", errorCode = errorCode, attempt = attempt) {
                    registerAirPlayService(displayName, attempt + 1)
                }
            }
        )
        airPlayListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

        // No callback at all within the timeout = probing stuck on a conflict → cancel the
        // pending registration and retry with a suffixed name (see field docs on the watchdog).
        cancelAirPlayWatchdog()
        airPlayWatchdog = Runnable {
            airPlayWatchdog = null
            if (isStarted && airPlayListener === listener) {
                Logger.w("_airplay._tcp '$attemptName' got no registration callback in " +
                         "${PROBE_TIMEOUT_MS}ms — probing stuck, treating as name conflict")
                runCatching { nsdManager.unregisterService(listener) }
                retryOrFail(serviceLabel = "_airplay._tcp", errorCode = -1, attempt = attempt) {
                    registerAirPlayService(displayName, attempt + 1)
                }
            }
        }.also { handler.postDelayed(it, PROBE_TIMEOUT_MS) }
    }

    private fun cancelAirPlayWatchdog() {
        airPlayWatchdog?.let { handler.removeCallbacks(it) }
        airPlayWatchdog = null
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Kitchen Phone"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String, attempt: Int = 1) {
        val macHex = NetworkUtils.getMacAddress(context).replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$macHex@${nameForAttempt(displayName, attempt)}"  // required RAOP format
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1,2,3")        // Cipher numbers (encryption types)
            setAttribute("da", "true")             // Digest authentication capable
            setAttribute("et", "0,3,5")            // Encryption types supported
            setAttribute("md", "0,1,2")            // Metadata types supported
            setAttribute("sv", "false")            // Software volume control
            setAttribute("tp", "UDP")              // Transport for audio RTP
            setAttribute("vn", "65537")            // Version number (required)
            setAttribute("vs", AIRPLAY_SERVER_VERSION)
            setAttribute("am", AIRPLAY_MODEL)
        }

        raopListener = createRegistrationListener(
            serviceLabel = "_raop._tcp",
            onRegisteredName = null,  // RAOP name has MAC prefix — not shown to users
            onSuccess = { incrementAndCheckBothRegistered() },
            onFailure = { errorCode ->
                retryOrFail(serviceLabel = "_raop._tcp", errorCode = errorCode, attempt = attempt) {
                    registerRaopService(displayName, attempt + 1)
                }
            }
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener!!)
    }

    /**
     * Retry policy for failed registrations.
     *
     * Newer Android versions do NOT auto-rename on an mDNS name conflict (despite what
     * NsdManager's docs suggest): NsdService throws a NameConflictException, which surfaces
     * here as a plain registration failure. Seen in the wild on Google TV, where the
     * system device name ("Nokia Streaming Box 8010") is already taken by the device's own
     * Google Cast registration. So we resolve conflicts ourselves: retry with a numbered
     * suffix — "Name (2)", "Name (3)" — up to [MAX_NAME_ATTEMPTS], then give up with ERROR.
     */
    private fun retryOrFail(serviceLabel: String, errorCode: Int, attempt: Int, retry: () -> Unit) {
        if (attempt < MAX_NAME_ATTEMPTS && isStarted) {
            Logger.w("mDNS $serviceLabel registration failed (errorCode=$errorCode) — " +
                     "retrying with suffixed name (attempt ${attempt + 1}/$MAX_NAME_ATTEMPTS)")
            handler.postDelayed({ if (isStarted) retry() }, RETRY_DELAY_MS)
        } else {
            isStarted = false
            onStateChange(ProtocolState.ERROR)
        }
    }

    /** "Name" for the first attempt, "Name (2)", "Name (3)" for conflict retries. */
    private fun nameForAttempt(base: String, attempt: Int): String =
        if (attempt <= 1) base else "$base ($attempt)"

    /**
     * Emits [ProtocolState.ADVERTISING] only after both services have confirmed registration.
     * This prevents a brief "advertising" state where only one of the two required services
     * is live.
     */
    @Synchronized
    private fun incrementAndCheckBothRegistered() {
        registeredCount++
        if (registeredCount >= 2) {
            onStateChange(ProtocolState.ADVERTISING)
        }
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     * @param onSuccess        Called on [onServiceRegistered].
     * @param onFailure        Called on [onRegistrationFailed].
     */
    private fun createRegistrationListener(
        serviceLabel: String,
        onRegisteredName: ((String) -> Unit)?,
        onSuccess: () -> Unit,
        onFailure: (errorCode: Int) -> Unit
    ): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // NsdManager may append " (2)" to resolve name conflicts.
                // Log the actual name so we can debug picker-visibility issues.
                Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                onRegisteredName?.invoke(serviceInfo.serviceName)
                onSuccess()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Error codes from NsdManager:
                //   FAILURE_ALREADY_ACTIVE (3) — already registered; treat as success
                //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue, including
                //     NameConflictException on newer Android (see [retryOrFail])
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Logger.w("mDNS $serviceLabel already active — treating as success")
                    onSuccess()
                } else {
                    Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
                    onFailure(errorCode)
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceLabel")
                onUnregistrationDone()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire via mDNS TTL (~4500ms by default)
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
                onUnregistrationDone()
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        /** Total registration attempts per service before giving up (1 + 2 suffix retries). */
        private const val MAX_NAME_ATTEMPTS = 3

        /** Delay before a conflict-retry registration attempt. */
        private const val RETRY_DELAY_MS = 300L

        /** How long [restart] waits for unregistration callbacks before proceeding anyway. */
        private const val RESTART_TIMEOUT_MS = 2000L

        /**
         * How long a registration may stay silent (no success/failure callback) before the
         * watchdog treats it as a stuck probe. mDNS probing normally completes in <1s
         * (3 probes × 250ms); 5s leaves ample margin on a busy network.
         */
        private const val PROBE_TIMEOUT_MS = 5000L

        /**
         * AirPlay feature bitmask: advertise screen mirroring, video, and audio support.
         * See TECHNICAL_SPEC.md §8 for the full bit-level breakdown.
         */
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"
    }
}
