package moe.shizuku.manager.adb

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.start.ForcedWirelessDebugging
import moe.shizuku.manager.start.StartTransport
import moe.shizuku.manager.start.WirelessPortLog
import moe.shizuku.manager.start.writeGlobalSetting
import moe.shizuku.manager.utils.DebugSwitchState
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils

/**
 * Everything that has to be going on for a wireless (TLS) port to be found, for as long as one is
 * needed, and the latest port it has found.
 *
 * This used to be one `callbackFlow` that ended at the first port: mDNS was stopped, the setting's
 * observer dropped and, with the experiment on, the job asking for wireless debugging cancelled the
 * moment a port resolved. That is the wrong moment to stop. Without a network the framework keeps
 * switching `adb_wifi_enabled` back to 0, and every switch back on can give adbd a new random TLS
 * port, so a port found at that moment could be gone before the connection that needed it was made
 * - which is how a start tried port 36371 that no longer existed. Here nothing stops until [close],
 * which the start calls when it has connected (or, in TCP mode, when it has opened the classic
 * port), and discovery keeps running so that [fresh] answers with the most recent port rather than
 * the first.
 *
 * Nothing starts until the first [fresh]: a start that already has a port to try has no use for a
 * search, and with the experiment on the search is a hotspot and a lot of settings writes.
 *
 * @param forced the experiment: no network, so the hotspot and the writes stand in for the toggle.
 * @param promoteForHotspot called before the hotspot is asked for (forced only); the platform
 *   gates the request on the app being in the foreground.
 * @param onForcing called when the start begins asking for wireless debugging (forced only).
 * @param onWaitingForUnlock called when the network has to be authorized behind a lock screen.
 * @param failFastOnHotspot give up at the first refusal of the hotspot (forced only), with the
 *   reason as the failure [fresh] throws, instead of asking again and carrying on without one.
 *   For a start that began before the unlock, where a refusal is likely to be the lock's.
 */
class WirelessPortHunt(
    context: Context,
    private val forced: Boolean,
    private val promoteForHotspot: suspend () -> Unit = {},
    private val onForcing: () -> Unit = {},
    private val onWaitingForUnlock: () -> Unit = {},
    private val failFastOnHotspot: Boolean = false
) {

    /**
     * [port] is the latest one resolved (0 before any), [discovering] whether a search is running
     * and so whether waiting for [port] can end, [failure] what ended the hunt for good.
     */
    private data class State(
        val port: Int = 0,
        val discovering: Boolean = false,
        val failure: Throwable? = null
    )

    private val appContext = context.applicationContext
    private val cr = appContext.contentResolver
    private val state = MutableStateFlow(State())

    // Owned by the hunt, and cancelled by [close]: the nudge outlives any one call to [fresh]. What it
    // throws ends the hunt (see [fresh]) instead of reaching the thread's handler, which would take
    // the app down for a hotspot that misbehaved.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> fail(e) }
    )
    private val startMutex = Mutex()
    private val lock = Any()

    private var started = false

    @Volatile
    private var closed = false
    private var mdns: AdbMdns? = null
    private var nudgeJob: Job? = null
    private var observer: ContentObserver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    @Volatile
    private var awaitingAuth = false

    /**
     * The most recent wireless port that is not in [exclude], waiting up to [timeoutMs] for one to
     * be announced, or null when none was.
     *
     * The wait is for a search that is running: while discovery is paused (the network has to be
     * authorized first, possibly on a screen nobody has unlocked), the clock is too, as the
     * discovery deadline always was. Throws the failure that ended the hunt - [SecurityException]
     * when the network was refused.
     */
    suspend fun fresh(exclude: Set<Int>, timeoutMs: Long): Int? {
        if (closed) return null
        ensureStarted()
        while (true) {
            // Not under the timeout: the clock starts when a search is running (for the experiment,
            // after the hotspot attempt, which can take longer than the whole window would).
            // Unbounded, as the discovery deadline always was while the network waited to be
            // authorized: that wait can be as long as it takes somebody to unlock the phone. A
            // hotspot that goes wrong ends it through [failure], not through running out of time.
            state.first { it.discovering || it.failure != null || usable(it.port, exclude) }
            val reached = withTimeoutOrNull(timeoutMs) {
                state.first { it.failure != null || !it.discovering || usable(it.port, exclude) }
            } ?: return null
            reached.failure?.let { throw it }
            if (usable(reached.port, exclude)) return reached.port
            // Paused for authorization; wait for the search to run again.
        }
    }

    private fun usable(port: Int, exclude: Set<Int>) = port > 0 && port !in exclude

    private suspend fun ensureStarted() {
        startMutex.withLock {
            if (started || closed) return
            started = true
            // The mDNS service discovery below arrived with wireless debugging in Android 11 (API
            // 30), and so did the class that queries it: on anything older the class does not exist
            // at all and naming it is a NoClassDefFoundError rather than a start that reports no
            // port.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startLocked()
            } else {
                fail(TimeoutException("mDNS wireless discovery needs Android 11"))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun startLocked() {
        // Before anything asks for the hotspot. A local-only hotspot is a request the platform may
        // refuse outright to an app that is only a background worker, and on the phone that
        // reported this the refusal came back as ERROR_INCOMPATIBLE_MODE on a start that had no
        // reason to fail. The call resumes once the start is actually in the foreground, which is
        // the state the request is gated on.
        if (forced) promoteForHotspot()

        val discovery = AdbMdns(appContext, AdbMdns.TLS_CONNECT) { p ->
            if (p.second > 0) {
                WirelessPortLog.resolved(p.second, StartTransport.PortSource.MDNS)
                state.update { it.copy(port = p.second) }
            }
        }
        val watcher = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                when (EnvironmentUtils.wirelessDebuggingState()) {
                    // Reading 0 is the framework refusing the setting again, which is precisely the
                    // state the experiment is asking it out of, so it must not end the attempt:
                    // closing here killed the whole thing about a second in, before the hotspot was
                    // up or the writes had a chance, and the result was a start that only ever said
                    // it was waiting.
                    DebugSwitchState.OFF -> if (awaitingAuth && !forced) {
                        fail(SecurityException("Network is not authorized for wireless debugging"))
                    } else handleAuth()
                    DebugSwitchState.ON -> startDiscovery()
                    // Android 17 QPR3 hides the setting, so 0 no longer says the framework
                    // turned it off. Keep the search alive instead of treating a redacted read
                    // as an authorization failure.
                    DebugSwitchState.UNKNOWN -> if (!forced) startDiscovery()
                }
            }
        }
        synchronized(lock) {
            if (closed) return
            mdns = discovery
            observer = watcher
        }

        // Required for discovery without it the device never advertises _adb-tls-connect and the
        // search just times out. Best effort: if we may not write, discovery still gets its chance
        // before we complain.
        appContext.writeGlobalSetting("adb_wifi_enabled", 1)
        cr.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, watcher)
        // Closed while this was setting up: close() could not have seen the observer registered.
        if (closed) {
            runCatching { cr.unregisterContentObserver(watcher) }
            return
        }

        // Opt-in: keep asking for wireless debugging the way the Settings toggle cannot be asked
        // when the device has no network, and bring up an interface for discovery if asking is not
        // enough on its own. It lasts as long as the hunt, not as long as the first port: see the
        // class comment.
        //
        // Discovery waits for the hotspot in this case, and that ordering is the difference between
        // a start and a timeout: a request registered before the interface exists is kept by
        // NsdManager with nothing to query on, so it sits there silently while the port is being
        // advertised next to it. The callback fires when the hotspot attempt is over, whether it
        // came up or not - the latter keeps the discovery attempt a start without a hotspot always
        // had.
        if (forced) {
            onForcing()
            nudgeJob = scope.launch {
                ForcedWirelessDebugging.nudge(
                    appContext,
                    { message -> Diag.info(AppConstants.TAG, "Forced wireless debugging: $message") },
                    failFast = failFastOnHotspot,
                    onFailFast = { reason ->
                        Diag.warn(AppConstants.TAG, "Forced wireless debugging gave up: $reason")
                        fail(IllegalStateException("the local-only hotspot was refused: $reason"))
                    }
                ) {
                    startDiscovery()
                }
            }
        } else {
            startDiscovery()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startDiscovery() {
        val discovery = synchronized(lock) { if (closed) null else mdns } ?: return
        discovery.start()
        state.update { it.copy(discovering = true) }
    }

    private fun fail(e: Throwable) = state.update { it.copy(failure = e) }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun handleAuth() {
        // Only once: this is called again every time the setting changes, and the experiment
        // makes it change over and over. A second call would post the same "waiting for unlock"
        // notification again and leave another receiver behind.
        synchronized(lock) {
            if (unlockReceiver != null || closed) return
            val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) {
                onWaitingForUnlock()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != Intent.ACTION_USER_PRESENT) return
                        synchronized(lock) {
                            runCatching { appContext.unregisterReceiver(this) }
                            unlockReceiver = null
                        }
                        // Wireless debugging must be on for the TLS port to be advertised,
                        // otherwise mDNS discovery can never find it.
                        appContext.writeGlobalSetting("adb_wifi_enabled", 1)
                    }
                }
                unlockReceiver = receiver
                appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            } else {
                awaitingAuth = true
            }
        }

        // With the experiment on, the attempt keeps discovering: it is fighting a setting the
        // framework keeps reverting, so every revert would otherwise end the only thing that ends
        // the attempt, and the start would wait forever instead of retrying. The daemon can also be
        // up while the setting reads 0, which is the state discovery should stay out for.
        if (!forced) {
            state.update { it.copy(discovering = false) }
            synchronized(lock) { mdns }?.stop()
        }
    }

    /** Ends the hunt: discovery, the setting's observer, the asking and the unlock wait. */
    fun close() {
        val toStop: AdbMdns?
        val toUnobserve: ContentObserver?
        val toUnregister: BroadcastReceiver?
        synchronized(lock) {
            if (closed) return
            closed = true
            toStop = mdns
            toUnobserve = observer
            toUnregister = unlockReceiver
            unlockReceiver = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { toStop?.stop() }
        toUnobserve?.let { runCatching { cr.unregisterContentObserver(it) } }
        toUnregister?.let { runCatching { appContext.unregisterReceiver(it) } }
        nudgeJob?.cancel()
        scope.cancel()
    }
}
