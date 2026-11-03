package moe.shizuku.manager.start

import android.content.ContentResolver
import moe.shizuku.manager.utils.AppStrings
import moe.shizuku.manager.R
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

    /**
     * Writes close together first: the window before the constraint check is short.
     *
     * The shape is one somebody measured rather than one chosen here: a macro that writes this
     * setting and then waits 20ms, eighty times over, switches wireless debugging on reliably,
     * while the same loop waiting 10ms - or any other value tried - sometimes does not (reported
     * on issue #79). So the burst is that loop: eighty passes at 20ms. It was twenty at 60ms
     * before, which is both fewer and slower than the shape known to work, and the setting only
     * goes in when the framework has just taken it back out, so the cadence is what decides
     * whether a write lands in the gap.
     */
    private const val BURST_COUNT = 80
    private const val BURST_INTERVAL_MS = 20L

    /**
     * Then steadily, at the same rate as the burst. The framework stops the daemon again
     * within about a tenth of a second of every write, and the port only becomes findable
     * once adbd has had long enough to advertise it, so what wins is the draw where that
     * check lands late - which is a question of cadence, and 20ms is the cadence that was
     * measured to work.
     */
    private const val INTERVAL_MS = 20L

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

    /** Why the last hotspot request failed, in words; read when a start gives up on the first. */
    @Volatile
    private var lastRefusal: String? = null

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
        log: (String) -> Unit = {},
        /**
         * Give up when the hotspot is refused, instead of asking again and carrying on without one.
         *
         * For a start that began before the unlock, where the refusal is likely to be the lock's:
         * one request, no retries, and the reason handed to [onFailFast] with nothing else done -
         * neither discovery nor the asking for wireless debugging is started, since the caller is
         * about to end the attempt.
         */
        failFast: Boolean = false,
        onFailFast: (String) -> Unit = {},
        /**
         * Called once the hotspot attempt is over, whether it came up or not.
         *
         * The caller uses it to wait before asking for discovery: a request registered before
         * the interface exists is one NsdManager keeps without ever sending a query on it, which
         * is how an offline start sat out its whole deadline on a phone whose port was there.
         * Called after a failure as well, because a start that could not have a hotspot still
         * gets the discovery attempt it would have had anyway.
         */
        onHotspotSettled: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val cr = context.contentResolver

        // The interface comes first. On some devices a hotspot is all it takes for the
        // framework to leave wireless debugging alone, and on the rest it is what lets the
        // daemon keep its port, so there is nothing to gain by spending the first seconds
        // of an attempt that may be short asking without one.
        val hotspotUp = startHotspot(context, log, if (failFast) 1 else HOTSPOT_ATTEMPTS)
        if (!hotspotUp && failFast) {
            onFailFast(lastRefusal ?: "no hotspot")
            return@withContext
        }
        onHotspotSettled()

        // Every pass reads before it writes, which is the whole of what issue #79 needed: a write
        // of 1 while the setting already reads 1 does nothing to the daemon (SettingsProvider does
        // not notify on the same value), so a start whose wireless debugging was already up - the
        // phone that reported this spammed the setting on itself with a macro first - asked two
        // thousand times anyway and announced from its notification the whole time that it was
        // switching wireless debugging on. What the asking is for is the framework turning it back
        // off, so the read is what decides whether there is anything to ask for.
        var passes = 0
        var writes = 0

        fun pass(): Boolean {
            passes++
            if (isEnabled(cr)) return true
            writes++
            return writeOnce(cr)
        }

        if (!pass()) {
            log(AppStrings.get(R.string.fwd_setting_not_written))
            return@withContext
        }
        log(if (writes == 0) AppStrings.get(R.string.fwd_already_on) else AppStrings.get(R.string.fwd_asking))

        val deadline = System.currentTimeMillis() + WINDOW_MS

        repeat(BURST_COUNT - 1) {
            if (!isActive) return@withContext
            pass()
            delay(BURST_INTERVAL_MS)
        }

        while (isActive && System.currentTimeMillis() < deadline) {
            pass()
            delay(INTERVAL_MS)
        }

        Diag.info(
            AppConstants.TAG,
            "Forced wireless debugging: asked $writes times over $passes passes, " +
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

    /** Writes the setting once, and answers whether the write was allowed at all. */
    private fun writeOnce(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, KEY_WIFI_ENABLED, 1) }
            .onFailure { Diag.warn(AppConstants.TAG, "Could not ask for wireless debugging", it) }
            .isSuccess

    /** Whether the setting reads as on, which is the platform's own record of the toggle. */
    private fun isEnabled(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.getInt(cr, KEY_WIFI_ENABLED, 0) == 1 }
            .getOrDefault(false)

    /**
     * A local-only hotspot brings an interface up without touching the user's own
     * tethering and without needing a network to join, which is what mDNS discovery needs
     * to resolve the adbd service against.
     *
     * The reservation has to be held for the hotspot to exist, so it is parked in this
     * object; it also dies with the process, which is a fair fallback.
     */
    private suspend fun startHotspot(
        context: Context,
        log: (String) -> Unit,
        attempts: Int = HOTSPOT_ATTEMPTS
    ): Boolean {
        // The local-only hotspot arrived with Android 8 (API 26) and the class it is held in
        // does not exist below it. The experiment that reaches this is only ever useful on a
        // modern platform anyway, so on an older one there is nothing to try, and that is what
        // is reported rather than a NoClassDefFoundError.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            log(AppStrings.get(R.string.fwd_hotspot_needs_android8))
            return false
        }
        if (reservation != null) return true

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            log(AppStrings.get(R.string.fwd_no_wifi_service))
            return false
        }

        lastRefusal = null
        repeat(attempts) { attempt ->
            val started = attemptHotspot(wifi, attempt, attempts)
            if (started != null) {
                reservation = started
                log(AppStrings.get(R.string.fwd_hotspot_up))
                return true
            }
            if (attempt < attempts - 1) delay(HOTSPOT_RETRY_DELAY_MS)
        }

        log(AppStrings.get(R.string.fwd_hotspot_failed))
        return false
    }

    /** One request, with the refusal reason said in words rather than a number. */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun attemptHotspot(
        wifi: WifiManager,
        attempt: Int,
        attempts: Int
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
                        lastRefusal = "${e.javaClass.simpleName}: ${e.message}"
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

        when {
            reason != null -> Diag.warn(
                AppConstants.TAG,
                "Local-only hotspot refused (attempt ${attempt + 1} of $attempts): " +
                    reasonName(reason!!).also { lastRefusal = it }
            )
            // Silence would look the same as a refusal in the log, and the difference
            // matters: a call that never answers means the Wi-Fi stack was not ready yet.
            started == null -> Diag.warn(
                AppConstants.TAG,
                "Local-only hotspot did not answer within ${HOTSPOT_TIMEOUT_MS / 1000}s " +
                    "(attempt ${attempt + 1} of $attempts)"
            ).also { lastRefusal = "no answer within ${HOTSPOT_TIMEOUT_MS / 1000}s" }
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
