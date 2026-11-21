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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
     * replaces another, so ask again before believing it. A SecurityException is not one of
     * those: the permission will not appear by asking again, so that refusal ends the attempts
     * at once.
     */
    private const val HOTSPOT_ATTEMPTS = 3
    private const val HOTSPOT_RETRY_DELAY_MS = 1_500L

    /**
     * Only where the hotspot takes the Wi-Fi client down (see [ClientWifi]): how often the start
     * that waits for Wi-Fi instead looks at the connection, and how long the setting is left at 0
     * before it is written back to 1 once Wi-Fi is there. The bounce is what makes it a change the
     * framework acts on: after a run of refused writes the setting can be left reading 1 with
     * nothing behind it, and a write of the same value is never passed on.
     */
    private const val WIFI_POLL_MS = 500L
    private const val BOUNCE_MS = 200L

    /**
     * How long after the hotspot comes up a Wi-Fi client that was on is watched for going off,
     * on a device whose platform says the radio does both, and how long it has to stay off to
     * count. A client the hotspot takes down goes within a second or two of it and stays down for
     * as long as the hotspot is held; a blip is not that, and marking a device on one would take
     * the hotspot away from a phone it works on.
     */
    private const val DISPLACEMENT_WATCH_MS = 5_000L
    private const val DISPLACEMENT_CONFIRM_MS = 1_000L
    private const val DISPLACEMENT_POLL_MS = 250L

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Why the last hotspot request failed, in words; read when a start gives up on the first. */
    @Volatile
    private var lastRefusal: String? = null

    /**
     * Whether the Wi-Fi client was on when the hotspot was raised, so that [restoreClientWifi]
     * knows whether there is anything to put back.
     */
    @Volatile
    private var clientWifiWasOn = false

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
         * Called when the interface discovery runs over has changed under it, which is Wi-Fi
         * connecting while a start that left the hotspot out waits for it. A search registered
         * before the interface existed is one the platform may never send a query for, so the
         * caller starts it again.
         */
        onNetworkChanged: () -> Unit = {},
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
        //
        // Except where the hotspot would take the Wi-Fi client down with it while Wi-Fi is wanted.
        // There the hotspot gives nothing - the framework only starts the wireless server for a
        // Wi-Fi network the device is connected to, and the hotspot is what disconnects it - and
        // takes the car's internet away for as long as it is held. So no hotspot at all: the start
        // waits for Wi-Fi, which on a head unit is the phone joining a few seconds after the radio
        // comes up. A start without any network is the persistent classic port's job there.
        val wifiWanted = ClientWifi.wantsWifi(context)
        val displaces = ClientWifi.hotspotDisplacesClient(context)
        if (shouldAvoidHotspot(wifiWanted, displaces)) {
            log("this device cannot hold a hotspot next to Wi-Fi and Wi-Fi is on: waiting for Wi-Fi instead of raising a hotspot")
            onHotspotSettled()
            awaitWifiAndAsk(context, cr, log, onNetworkChanged)
            return@withContext
        }

        val wifiEnabledBefore = ClientWifi.isEnabled(context)
        val hotspotUp = startHotspot(context, log, if (failFast) 1 else HOTSPOT_ATTEMPTS)
        if (!hotspotUp && failFast) {
            onFailFast(lastRefusal ?: "no hotspot")
            return@withContext
        }
        if (hotspotUp && wifiEnabledBefore == true) clientWifiWasOn = true
        // The platform said the radio does both (or would not say). If the client goes anyway, that
        // is the device above, found out the hard way: the hotspot goes at once, Wi-Fi is put back
        // by the caller, and this build is remembered so it is not asked again. Watched alongside
        // the asking rather than before it, so a device whose radio really does both starts as
        // soon as it always did.
        val displaced = AtomicBoolean(false)
        val watch = if (hotspotUp && wifiEnabledBefore == true && displaces != true) {
            launch { if (watchForDisplacement(context)) displaced.set(true) }
        } else null
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
            watch?.cancel()
            log("the wireless debugging setting could not be written")
            return@withContext
        }
        log(if (writes == 0) "wireless debugging is already on" else "asking for wireless debugging")

        val deadline = System.currentTimeMillis() + WINDOW_MS

        var burst = 1
        while (burst < BURST_COUNT && !displaced.get()) {
            if (!isActive) return@withContext
            pass()
            delay(BURST_INTERVAL_MS)
            burst++
        }

        while (isActive && System.currentTimeMillis() < deadline && !displaced.get()) {
            pass()
            delay(INTERVAL_MS)
        }
        watch?.cancel()

        if (displaced.get()) {
            ClientWifi.rememberDisplacement()
            releaseHotspot()
            // Now rather than when the attempt ends: the Wi-Fi this start goes on to wait for has
            // to be there to connect.
            restoreClientWifi(context)
            log("the hotspot switched Wi-Fi off although the platform says the radio can do both: giving it up and waiting for Wi-Fi (remembered for this build)")
            awaitWifiAndAsk(context, cr, log, onNetworkChanged)
            return@withContext
        }

        Diag.info(
            AppConstants.TAG,
            "Forced wireless debugging: asked $writes times over $passes passes, " +
                "hotspot ${if (reservation == null) "not running" else "running"}"
        )
    }

    /**
     * The forced start on a device where a hotspot would take Wi-Fi down: no hotspot, no stream of
     * writes - without a Wi-Fi network every one of them is refused, two and a half thousand a
     * minute in the log that reported this - just the Wi-Fi connection watched for the whole
     * window. Each time it connects, the setting is bounced so the framework checks the network it
     * now has, and the caller restarts discovery over it.
     */
    private suspend fun awaitWifiAndAsk(
        context: Context,
        cr: ContentResolver,
        log: (String) -> Unit,
        onNetworkChanged: () -> Unit
    ) {
        val deadline = System.currentTimeMillis() + WINDOW_MS
        var connected = false
        var asked = 0
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            val now = ClientWifi.isConnected(context)
            if (now && !connected) {
                asked++
                log("Wi-Fi is connected: asking for wireless debugging over it")
                writeValue(cr, 0)
                delay(BOUNCE_MS)
                writeValue(cr, 1)
                onNetworkChanged()
            }
            connected = now
            delay(WIFI_POLL_MS)
        }
        Diag.info(
            AppConstants.TAG,
            "Forced wireless debugging: waited for Wi-Fi without a hotspot, asked $asked times"
        )
    }

    /**
     * Whether the Wi-Fi client goes off within [DISPLACEMENT_WATCH_MS] of the hotspot coming up
     * and stays off for [DISPLACEMENT_CONFIRM_MS]. Runs alongside the asking, so a client that
     * stays on costs nothing but the watch itself.
     */
    private suspend fun watchForDisplacement(context: Context): Boolean {
        val start = System.currentTimeMillis()
        var offSince = -1L
        while (true) {
            val now = System.currentTimeMillis()
            if (displacementObserved(true, ClientWifi.isEnabled(context))) {
                if (offSince < 0) offSince = now
                if (now - offSince >= DISPLACEMENT_CONFIRM_MS) return true
            } else {
                offSince = -1L
                if (now - start >= DISPLACEMENT_WATCH_MS) return false
            }
            if (now - start >= DISPLACEMENT_WATCH_MS + DISPLACEMENT_CONFIRM_MS) return false
            delay(DISPLACEMENT_POLL_MS)
        }
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

    /**
     * Puts the Wi-Fi client back if the hotspot took it down and the platform has not restored it.
     * Called when the attempt is over, after [releaseHotspot]; does nothing when there was no
     * hotspot over a Wi-Fi that was on.
     */
    fun restoreClientWifi(context: Context) {
        if (!clientWifiWasOn) return
        clientWifiWasOn = false
        ClientWifi.restoreAfterHotspot(context)
    }

    /** Writes the setting once, and answers whether the write was allowed at all. */
    private fun writeOnce(cr: ContentResolver): Boolean = writeValue(cr, 1)

    private fun writeValue(cr: ContentResolver, value: Int): Boolean =
        runCatching { Settings.Global.putInt(cr, KEY_WIFI_ENABLED, value) }
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
            log("the local-only hotspot needs Android 8")
            return false
        }
        if (reservation != null) return true

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            log("no Wi-Fi service to bring a hotspot up with")
            return false
        }

        lastRefusal = null
        repeat(attempts) { attempt ->
            val result = attemptHotspot(wifi, attempt, attempts)
            if (result.reservation != null) {
                reservation = result.reservation
                log("local-only hotspot is up")
                return true
            }
            if (!result.retryable) {
                log("the local-only hotspot was refused; not asking again")
                return false
            }
            if (attempt < attempts - 1) delay(HOTSPOT_RETRY_DELAY_MS)
        }

        log("the local-only hotspot did not come up")
        return false
    }

    /** One request, with the refusal reason said in words rather than a number. */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun attemptHotspot(
        wifi: WifiManager,
        attempt: Int,
        attempts: Int
    ): HotspotAttempt {
        var reason: Int? = null
        var answered = false
        var threw = false
        var retryable = true
        val started = withTimeoutOrNull(HOTSPOT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    try {
                        wifi.startLocalOnlyHotspot(
                            object : WifiManager.LocalOnlyHotspotCallback() {
                                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                                    answered = true
                                    if (cont.isActive) cont.resume(res)
                                }

                                override fun onStopped() {
                                    answered = true
                                    if (cont.isActive) cont.resume(null)
                                }

                                override fun onFailed(failure: Int) {
                                    reason = failure
                                    answered = true
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                            Handler(Looper.getMainLooper())
                        )
                    } catch (e: Throwable) {
                        // SecurityException when the Wi-Fi permission is not granted, and
                        // anything else the platform throws at this: the start carries on
                        // without the interface. A permission refusal will not change by
                        // asking again, so it is the one that ends the attempts.
                        threw = true
                        retryable = e !is SecurityException
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
            // Only a timeout gets this line; an immediate exception has its own reason.
            started == null && !answered && !threw -> Diag.warn(
                AppConstants.TAG,
                "Local-only hotspot did not answer within ${HOTSPOT_TIMEOUT_MS / 1000}s " +
                    "(attempt ${attempt + 1} of $attempts)"
            ).also { lastRefusal = "no answer within ${HOTSPOT_TIMEOUT_MS / 1000}s" }
        }
        return HotspotAttempt(started, retryable)
    }

    private class HotspotAttempt(
        val reservation: WifiManager.LocalOnlyHotspotReservation?,
        val retryable: Boolean
    )

    private fun reasonName(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no channel available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "the system refused it"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "incompatible mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "tethering is disallowed"

        else -> "unknown reason $reason"
    }
}
