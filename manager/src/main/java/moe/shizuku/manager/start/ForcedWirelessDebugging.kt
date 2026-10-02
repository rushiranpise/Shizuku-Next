package moe.shizuku.manager.start

import android.content.ContentResolver
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.utils.Diag

/**
 * Keeping wireless debugging on where the system would rather turn it off.
 *
 * The Settings toggle cannot be used without a Wi-Fi network: the framework starts adbd's
 * wireless TLS server when `adb_wifi_enabled` changes, then a second handler stops it
 * again because the Wi-Fi constraint is not met, and it is that handler that decides you
 * cannot have wireless debugging while offline. Writing the setting repeatedly lands
 * writes inside that window until one of them is left alone, which is a known platform
 * bug rather than anything documented and it is why this is opt-in, off by default, and
 * best-effort: nothing here is relied on for a start to succeed.
 *
 * The port still has to be found afterwards, and mDNS needs an interface to resolve over,
 * so a local-only hotspot is used when the writes alone do not get there. The hotspot is
 * only ever for that interface and is closed again as soon as the start is done.
 *
 * See thedjchi/Shizuku issue 165 for the behaviour this follows.
 */
object ForcedWirelessDebugging {

    /** The setting the framework watches. */
    private const val KEY_WIFI_ENABLED = "adb_wifi_enabled"

    /** Writes close together first: the window before the constraint check is short. */
    private const val BURST_COUNT = 20
    private const val BURST_INTERVAL_MS = 60L

    /**
     * Then steadily, at the same rate as the burst. The framework stops the daemon again
     * within about a tenth of a second of every write, and the port only becomes findable
     * once adbd has had long enough to advertise it, so what wins is the draw where that
     * check lands late. The one start this has won so far won during the burst, which is
     * why the steady part matches it rather than being twice as slow.
     */
    private const val INTERVAL_MS = 60L

    /**
     * How long a start keeps asking before it carries on without.
     *
     * Minutes rather than seconds, because whether this works at all is the framework's
     * decision, not ours: its handler stops the daemon again whenever it is not satisfied,
     * so what wins is catching it in a moment when it leaves it alone. A few seconds of
     * asking is a lottery ticket; a couple of minutes is a chance. The hotspot stays up for
     * the whole window for the same reason, since discovery needs an interface to resolve
     * the port against.
     */
    private const val WINDOW_MS = 120_000L

    private const val HOTSPOT_TIMEOUT_MS = 12_000L

    /**
     * A refusal is usually temporary. The one that matters most is a request landing while
     * the previous reservation is still being torn down, which happens when one attempt
     * replaces another, so ask again before believing it.
     */
    private const val HOTSPOT_ATTEMPTS = 3
    private const val HOTSPOT_RETRY_DELAY_MS = 1_500L

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Asks for wireless debugging until cancelled, standing in for the toggle that a
     * device without a network is not allowed to use.
     *
     * Cancelling it is the normal end: the caller cancels once the port has been found or
     * has given up. The hotspot is deliberately left up across commands, so stop it with
     * [releaseHotspot] when the start is finished.
     */
    suspend fun nudge(
        context: Context,
        log: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val cr = context.contentResolver

        // The interface comes first. On some devices a hotspot is all it takes for the
        // framework to leave wireless debugging alone, and on the rest it is what lets the
        // daemon keep its port, so there is nothing to gain by spending the first seconds
        // of an attempt that may be short asking without one.
        startHotspot(context, log)

        if (!ask(cr)) {
            log("the wireless debugging setting could not be written")
            return@withContext
        }
        log("asking for wireless debugging")

        val deadline = System.currentTimeMillis() + WINDOW_MS
        var attempts = 1

        repeat(BURST_COUNT - 1) {
            if (!isActive) return@withContext
            ask(cr)
            attempts++
            delay(BURST_INTERVAL_MS)
        }

        while (isActive && System.currentTimeMillis() < deadline) {
            ask(cr)
            attempts++
            delay(INTERVAL_MS)
        }

        Diag.info(
            AppConstants.TAG,
            "Forced wireless debugging: asked $attempts times, " +
                "hotspot ${if (reservation == null) "not running" else "running"}"
        )
    }

    /** Stops the hotspot. Safe to call when there is none. */
    fun releaseHotspot() {
        // The reservation's own class does not exist below Android 8, which is also the only
        // platform where there is never a reservation to release.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { reservation?.close() }
            .onFailure { Diag.warn(AppConstants.TAG, "Could not close the local-only hotspot", it) }
        reservation = null
    }

    fun isHotspotRunning(): Boolean = reservation != null

    /** Whether the write was allowed at all. Without it there is nothing to keep asking. */
    private fun ask(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, KEY_WIFI_ENABLED, 1) }
            .onFailure { Diag.warn(AppConstants.TAG, "Could not ask for wireless debugging", it) }
            .isSuccess

    /**
     * A local-only hotspot brings an interface up without touching the user's own
     * tethering and without needing a network to join, which is what mDNS discovery needs
     * to resolve the adbd service against.
     *
     * The reservation has to be held for the hotspot to exist, so it is parked in this
     * object; it also dies with the process, which is a fair fallback.
     */
    private suspend fun startHotspot(context: Context, log: (String) -> Unit): Boolean {
        // The local-only hotspot arrived with Android 8 (API 26) and the class it is held in
        // does not exist below it. The experiment that reaches this is only ever useful on a
        // modern platform anyway, so on an older one there is nothing to try, and that is what
        // is reported rather than a NoClassDefFoundError.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            log("the local-only hotspot needs Android 8")
            return false
        }
        if (reservation != null) return true

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            log("no Wi-Fi service to bring a hotspot up with")
            return false
        }

        repeat(HOTSPOT_ATTEMPTS) { attempt ->
            val started = attemptHotspot(wifi, attempt)
            if (started != null) {
                reservation = started
                log("local-only hotspot is up")
                return true
            }
            if (attempt < HOTSPOT_ATTEMPTS - 1) delay(HOTSPOT_RETRY_DELAY_MS)
        }

        log("the local-only hotspot did not come up")
        return false
    }

    /** One request, with the refusal reason said in words rather than a number. */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun attemptHotspot(
        wifi: WifiManager,
        attempt: Int
    ): WifiManager.LocalOnlyHotspotReservation? {
        var reason: Int? = null
        val started = withTimeoutOrNull(HOTSPOT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    try {
                        wifi.startLocalOnlyHotspot(
                            object : WifiManager.LocalOnlyHotspotCallback() {
                                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                                    if (cont.isActive) cont.resume(res)
                                }

                                override fun onStopped() {
                                    if (cont.isActive) cont.resume(null)
                                }

                                override fun onFailed(failure: Int) {
                                    reason = failure
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                            Handler(Looper.getMainLooper())
                        )
                    } catch (e: Throwable) {
                        // SecurityException when the Wi-Fi permission is not granted, and
                        // anything else the platform throws at this: the start carries on
                        // without the interface.
                        Diag.warn(AppConstants.TAG, "Could not start a local-only hotspot", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

        when {
            reason != null -> Diag.warn(
                AppConstants.TAG,
                "Local-only hotspot refused (attempt ${attempt + 1} of $HOTSPOT_ATTEMPTS): " +
                    reasonName(reason!!)
            )
            // Silence would look the same as a refusal in the log, and the difference
            // matters: a call that never answers means the Wi-Fi stack was not ready yet.
            started == null -> Diag.warn(
                AppConstants.TAG,
                "Local-only hotspot did not answer within ${HOTSPOT_TIMEOUT_MS / 1000}s " +
                    "(attempt ${attempt + 1} of $HOTSPOT_ATTEMPTS)"
            )
        }
        return started
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no channel available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "the system refused it"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "incompatible mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "tethering is disallowed"

        else -> "unknown reason $reason"
    }
}
