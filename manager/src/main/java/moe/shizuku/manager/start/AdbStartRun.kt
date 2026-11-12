package moe.shizuku.manager.start

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import moe.shizuku.manager.service.HidingWatchService
import androidx.core.app.NotificationCompat
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbRoot
import moe.shizuku.manager.adb.AdbRootUnavailableException
import moe.shizuku.manager.adb.StartProgress
import moe.shizuku.manager.adb.WirelessPortHunt
import moe.shizuku.manager.adb.AdbPairingRequiredException
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter.WorkerState
import moe.shizuku.manager.settings.BugReportDialogActivity
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.createChannelCompat


/**
 * One attempt to start the server over ADB, whoever is running it.
 *
 * This is what the start worker used to be: finding the port, opening the classic one in TCP mode,
 * connecting, sending the starter and waiting for the binder. It lives here, independent of
 * WorkManager, because the same attempt has to run in two places: in the worker, once the user has
 * unlocked and WorkManager's database can be read, and in a foreground service before that.
 * What differs between them is small and is the [Host]: how a notification is shown, how the
 * attempt gets to the foreground, and what a cancellation means. Retrying with a backoff is not
 * here either - [execute] makes one attempt and says how it ended.
 *
 * The count of attempts in flight is here too, because it is a fact about attempts and not about
 * the worker: a start is under way whichever host runs it.
 */
class AdbStartRun(
    context: Context,
    private val host: Host,
    /**
     * Whether this attempt began before the first unlock, in the foreground service.
     *
     * What changes is how it fails. Before the unlock a failure is likely to be about the lock -
     * a hotspot the platform will not give an app the user has not unlocked, a key it cannot read
     * yet - and none of it is the user's to act on; it also leaves the way open for the start the
     * unlock makes anyway. So the attempt gives up at the first refusal rather than spending
     * minutes on it, says why in the log and nowhere else, and does not retry: see [execute].
     */
    private val lockedStart: Boolean = false,

    /**
     * Whether this attempt has to make adbd root before sending the starter.
     *
     * Only the Root start method asks for this, and only as a fallback when no root shell
     * is available. The caller has already chosen the USB transport, so the classic port
     * is the one that is rooted and the one the server is sent over.
     */
    private val adbRoot: Boolean = false
) {

    /** What the host provides; the rest of the attempt does not care which host it is. */
    interface Host {
        /** Shows the state of the attempt, for the notification. */
        fun notify(state: WorkerState)

        /**
         * Brings the attempt to the foreground and returns once it is there. The hotspot request is
         * gated on it. A host that is already in the foreground has nothing to do.
         */
        suspend fun promoteToForeground()

        /** The same without waiting, for the attempt that is about to sit on a lock screen. */
        fun promoteToForegroundAsync()

        /** What to show when the attempt is cancelled, for this host's reasons for cancelling. */
        fun stoppedState(): WorkerState

        /** Throws if the attempt was cancelled. */
        suspend fun checkCancelled() {
            currentCoroutineContext().ensureActive()
        }
    }

    /** How an attempt ended. The worker turns it into WorkManager's result. */
    enum class Outcome {
        /** Started, or nothing to do. */
        SUCCESS,

        /** Failed in a way another attempt may get past. */
        RETRY,

        /** Failed in a way another attempt will not get past. */
        FAILURE
    }

    private val applicationContext: Context = context.applicationContext

    /** The method this start was asked for, so notifications retry the same way. */
    private var requestedMethod = ShizukuSettings.StartMethod.WIRELESS

    private fun notify(state: WorkerState) = host.notify(state)

    /**
     * Makes the attempt for [method] and says how it ended.
     *
     * [reserved] means the caller already counted this attempt as in flight (see [reserveAttempt]),
     * so that a request arriving between its decision to start and this call joins the attempt
     * instead of starting another. The count is released here either way.
     */
    suspend fun execute(method: Int, reserved: Boolean = false): Outcome {
        requestedMethod = method

        // Set before the first thing this attempt does and cleared in the `finally`, so the whole
        // attempt counts as one. See [inFlight] for who reads it and why.
        if (!reserved) beginAttempt()

        try {
            // A hiding list is holding a debugging toggle off for an app the user has open. Every
            // path below that needs one writes it back on if it finds it off, so an attempt now is
            // not a start that fails - it is a start that undoes the hide and then fails. Deferred
            // instead: the watch asks for a start itself once nothing is hidden.
            if (HidingWatchService.holding()) {
                Diag.info(
                    AppConstants.TAG,
                    "Start deferred: hiding is holding the debugging toggle off"
                )
                return Outcome.SUCCESS
            }

            return startServer()
        } finally {
            endAttempt()
            // A local-only hotspot is only ever brought up to give discovery an
            // interface, so it goes away with the attempt that needed it: leaving it up
            // would be tethering nobody asked for.
            ForcedWirelessDebugging.releaseHotspot()
        }
    }

    private suspend fun startServer(): Outcome {
        // The search for a wireless port, once there is one; see where it is made. Held here so
        // that every way out of the attempt ends it.
        var hunt: WirelessPortHunt? = null
        // The classic port adbd was rooted on, when this is the Root fallback. Set before the
        // command is sent so a refusal still gets an unroot attempt in the `finally`.
        var rootedPort: Int? = null
        try {
            notify(WorkerState.RUNNING)

            val startMethod = requestedMethod
            val experiment = ShizukuSettings.getForceWirelessDebugging()
            // The method that says "without a network" means it whatever the experiment
            // setting has been changed to since: it is offered only while that setting is on,
            // and a start that asked to go without a network and quietly used one instead
            // would be the wrong way round.
            val withoutNetwork = startMethod == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK

            // A method is a promise about which transport carries the start, so no method
            // hands over to another one: a USB start that cannot use the classic ADB port
            // says what it needs and stops. Starting without a network has its own method
            // now, chosen on purpose, rather than being something a USB start slid into when
            // the network happened to be missing.
            var usbMethod = startMethod == ShizukuSettings.StartMethod.USB
            var forcedWireless = !usbMethod && (experiment || withoutNetwork)

            // Which debugging toggle we use is the entire difference between the two
            // ADB start methods, so each one only ever touches its own:
            //   USB      -> USB debugging, never wireless debugging
            //   WIRELESS -> wireless debugging, never USB debugging. Forcing USB
            //               debugging on is what the old USB "fallback" did, and it
            //               is what killed Shizuku on some Chinese devices when the
            //               USB mode was File Transfer and the screen went off.
            // Read through the one helper rather than the setting's name again here: the port's own
            // close asks the same question, and two spellings of it would eventually disagree.
            val wirelessAlreadyEnabled = EnvironmentUtils.isWirelessDebuggingEnabled()

            // All of the writes below go through helpers that swallow a permission denial:
            // on a fresh install (or after a re-signed update) the app has no
            // WRITE_SECURE_SETTINGS yet, and a settings write *throws* without it which
            // used to abort the start and get reported as a pairing problem. A start that
            // can't nudge adbd should still try to connect.
            if (usbMethod) {
                // Written whether or not it reads as on. On Android 17 (SDK 37) that read is
                // redacted to 0 for third-party apps, so [EnvironmentUtils.isAdbEnabled] answers
                // "on" for a phone that has it off - and skipping the write on that answer left a
                // USB start on the one platform where the "Disable USB debugging" option had just
                // turned the toggle off: nothing to connect to, and the toggle never coming back.
                // Setting it to 1 when it already is 1 changes nothing, so there is no reason to
                // ask a question whose answer may be invented.
                applicationContext.writeGlobalSetting(Settings.Global.ADB_ENABLED, 1)
                // Don't let the authorized connection expire while we connect.
                applicationContext.writeGlobalLongSetting("adb_allowed_connection_time", 0L)
            } else if (withoutNetwork) {
                // "Without Wi-Fi" has no wireless network to raise adbd with, and the classic port it
                // runs over exists only while adbd does, so the USB toggle is what starts it - but
                // only when it is off. Android 17 redacts that read and answers "on" for a phone
                // that has it off, so there the write is what "off" has to mean.
                if (!EnvironmentUtils.isAdbEnabled() || Build.VERSION.SDK_INT >= 37) {
                    if (applicationContext.writeGlobalSetting(Settings.Global.ADB_ENABLED, 1)) {
                        Diag.info(
                            AppConstants.TAG,
                            "Start without Wi-Fi: switched USB debugging on for adbd"
                        )
                    }
                }
                // Android 17 refuses to run adbd's wireless server without a wireless network, so on
                // that platform the classic port is this method's only transport rather than the
                // faster one: TCP mode is switched on with it, which is the setting that keeps that
                // port open for the restarts the method exists for.
                if (EnvironmentUtils.isAdbWifiOverhauled() && !ShizukuSettings.getTcpMode()) {
                    ShizukuSettings.setTcpMode(true)
                    Diag.info(
                        AppConstants.TAG,
                        "Start without Wi-Fi: switched TCP mode on for Android 17"
                    )
                }
            } else if (wirelessAlreadyEnabled && !forcedWireless) {
                // Wireless is already active. Writing adb_wifi_enabled=1 again is a
                // no-op (SettingsProvider does not notify on the same value), so adbd
                // never reinitialises wireless and mDNS discovery finds nothing. Write
                // 0 first so the re-enable below is a real 0->1 change, forcing adbd to
                // restart wireless and emit a fresh mDNS announcement. Skipped entirely
                // when we may not write: bouncing the toggle is not worth failing for.
                //
                // Also skipped with the experiment on, where the framework is reverting
                // the setting every tenth of a second anyway: there is no need to force a
                // change, and writing 0 would only help it stop the daemon.
                if (applicationContext.writeGlobalSetting("adb_wifi_enabled", 0)) {
                    try {
                        delay(200)
                    } finally {
                        // Restore unconditionally: normally a harmless no-op, and on
                        // cancellation it avoids leaving wireless disabled.
                        applicationContext.writeGlobalSetting("adb_wifi_enabled", 1)
                    }
                }
            }
            // else: wireless is off and we are starting over it the callbackFlow
            // below writes adb_wifi_enabled=1 so the start proceeds over wireless.

            var tcpPort = EnvironmentUtils.getAdbTcpPort()
            // "TCP mode off" means don't keep a port open for wireless restarts but
            // a USB start exists to use that port, so it opens and keeps it instead of
            // closing the very thing it needs.
            if (tcpPort > 0 && !ShizukuSettings.getTcpMode() && !usbMethod) {
                // Unattended, so the answer goes to the log: a port that could not be closed is the
                // kind of thing only this would ever show.
                Diag.info(AppConstants.TAG, "closing the port: ${AdbStarter.stopTcp(tcpPort)}")
            }

            if (usbMethod && tcpPort <= 0) {
                // A reboot clears the classic ADB port (it isn't persistent) and the
                // port can only be created by a `tcpip` request over a live connection.
                // Borrow the wireless connection to reopen it, then carry on over the
                // classic port so a USB start repairs itself instead of needing a
                // computer after every reboot.
                notify(WorkerState.CONNECTING)
                if (AdbStarter.openTcpPort(applicationContext, ShizukuSettings.getTcpPort())) {
                    tcpPort = EnvironmentUtils.getAdbTcpPort()
                }
            }


            // A USB start is USB only: the classic ADB port, where the connection
            // authenticates itself and Android asks to allow USB debugging. It never
            // falls back to wireless discovery going through the wireless port is
            // what used to make a "USB" start depend on Wi-Fi and pairing.
            if (usbMethod && tcpPort <= 0) {
                StartStatusReporter.failed(
                    applicationContext.usbStartNoPortMessage(),
                    // The card can open the port for the user.
                    StartFailureKind.PORT
                )
                notify(WorkerState.AWAITING_RETRY)
                return Outcome.FAILURE
            }
            // From here the USB method always has a classic port to use.

            // A wireless start goes over the wireless (TLS) port, which discovery has to
            // find - unless TCP mode is keeping the classic port open, in which case that is the
            // port to use and hunting for a wireless one is asking for the network this start does
            // not need. See [StartTransport.classicPortInUse] for what went wrong when the two
            // answers disagreed, and for why Android 17 makes the port the only answer there is.
            //
            // "Kept open" is a question about the phone, not about the setting: the port number is
            // a property that outlives adbd, so the toggles turned off on a stop leave it behind
            // and the start has to look at whether anything is behind it. It did not, and that is
            // what the two starts measured at 20:49 on the phone did - port 5555 taken in the 18 ms
            // the state change took, no mDNS search, no `adb_wifi_enabled=1` anywhere in the log,
            // and a socket nothing was listening on. Discovered instead, the search writes
            // wireless debugging on, and adbd with it, which is how a start brings back a toggle an
            // earlier stop switched off. Tracked as issue #71.
            // Which ports are worth asking before the search: the one the platform names, the one
            // the last start reached adbd on, and the one this app would open. A wireless port is
            // random per boot, so the remembered one is what makes a wireless restart connect
            // instead of spending fifteen seconds (or the no-network method's two minutes) finding
            // a port that was there all along. Measured against the same trick in Shevery, whose
            // starts are instant for exactly this reason (issue #84).
            WirelessPortLog.reset()
            val tlsProperty = EnvironmentUtils.getAdbTlsPort()
            logTlsPropertyOnce(tlsProperty)

            // The port the last start reached adbd on, if it can still be that port. A wireless one
            // is drawn at random at every boot, so one recorded in an earlier boot is not tried at
            // all: it was the first thing a start after a restart asked, and it was not there.
            val lastPort = ShizukuSettings.getLastAdbPort()
            val verdict = StartTransport.rememberedPortVerdict(
                lastPort = lastPort,
                lastWasClassic =
                    ShizukuSettings.getLastAdbTransport() == ShizukuSettings.ADB_TRANSPORT_TCP,
                lastBoot = ShizukuSettings.getLastAdbPortBoot(),
                currentBoot = EnvironmentUtils.getBootCount(),
                appTcpPort = ShizukuSettings.getTcpPort()
            )
            when (verdict) {
                StartTransport.RememberedPort.OTHER_BOOT -> WirelessPortLog.rejected(
                    lastPort, StartTransport.PortSource.REMEMBERED,
                    "a wireless port from an earlier boot (boot " +
                        "${ShizukuSettings.getLastAdbPortBoot()}, this is ${EnvironmentUtils.getBootCount()})"
                )
                StartTransport.RememberedPort.UNKNOWN_BOOT -> WirelessPortLog.rejected(
                    lastPort, StartTransport.PortSource.REMEMBERED,
                    "a wireless port with no boot recorded for it"
                )
                else -> Unit
            }
            val candidates = StartTransport.candidatePorts(
                configured = tcpPort,
                tlsProperty = tlsProperty,
                remembered = lastPort.takeIf { verdict == StartTransport.RememberedPort.USABLE },
                appTcpPort = ShizukuSettings.getTcpPort()
            )
            var live: StartTransport.PortCandidate? = null
            for (candidate in candidates) {
                if (AdbStarter.isPortListening(candidate.port)) {
                    live = candidate
                    break
                }
                WirelessPortLog.rejected(candidate.port, candidate.source, "nothing answers on it")
            }
            val liveFound = live
            val livePort = liveFound?.port
            // Said every time, not only when one answers: "the search ran because nothing was
            // listening" and "the search ran because this was never asked" look identical in a log
            // without it, and the second was a real possibility - the watchdog's direct restart
            // path never reaches this worker at all.
            Diag.info(
                AppConstants.TAG,
                "Ports tried before the search: " +
                    candidates.joinToString(", ") { "${it.port} (${it.source.label})" }
                        .ifEmpty { "none" } +
                    if (livePort != null) "; $livePort answered" else "; none answered"
            )
            liveFound?.let { WirelessPortLog.resolved(it.port, it.source) }

            val classicPortListening = tcpPort > 0 && livePort == tcpPort
            if (tcpPort > 0 && !classicPortListening && !usbMethod) {
                Diag.info(
                    AppConstants.TAG,
                    "Port $tcpPort is not listening behind the setting; looking for a wireless one"
                )
            }
            val useClassicPort = StartTransport.classicPortInUse(
                usbMethod = usbMethod,
                tlsSupported = EnvironmentUtils.isTlsSupported(),
                tcpPort = tcpPort,
                tcpMode = ShizukuSettings.getTcpMode(),
                portListening = classicPortListening
            )

            // The search for a wireless port, kept for the whole attempt rather than for the first
            // answer: with the experiment on it is also the asking that keeps wireless debugging
            // alive, and that has to last until the connection is made (or, in TCP mode, until the
            // classic port is open). Nothing in it runs until a port is asked for. Closed below once
            // the connection is made, and in the `finally` for every other way out.
            val searchHunt = WirelessPortHunt(
                applicationContext,
                forced = forcedWireless,
                promoteForHotspot = { promoteForHotspot() },
                onForcing = { notify(WorkerState.FORCING_WIRELESS) },
                onWaitingForUnlock = { waitForUnlockInForeground() },
                failFastOnHotspot = lockedStart
            )
            hunt = searchHunt

            // A candidate that answered is used as it is, except for the USB method: that one is
            // the classic port by promise, and a remembered wireless port is not it. Where nothing
            // answered, the old decision stands and the search takes over.
            var portSource = StartTransport.PortSource.CLASSIC_PROPERTY
            var port: Int = when {
                usbMethod -> tcpPort
                liveFound != null -> {
                    portSource = liveFound.source
                    liveFound.port
                }
                useClassicPort -> tcpPort
                else -> {
                    portSource = StartTransport.PortSource.MDNS
                    try {
                        // The experiment asks for minutes, so the attempt has to live for minutes:
                        // a 15 second deadline closed it moments after the hotspot had come up,
                        // which at boot is most of those 15 seconds gone on bringing the interface
                        // up in the first place. The clock starts when discovery does.
                        val deadline = when {
                            lockedStart -> LOCKED_DISCOVERY_TIMEOUT_MS
                            forcedWireless -> FORCED_DISCOVERY_TIMEOUT_MS
                            else -> 15_000L
                        }
                        searchHunt.fresh(emptySet(), deadline)
                            ?: throw TimeoutException("Timed out during mDNS port discovery")
                    } catch (e: TimeoutException) {
                        // Nothing advertised a wireless port. TCP mode keeps the classic port
                        // open so that a start can happen without a network at all, and
                        // refusing it here would throw the mode away exactly when it is
                        // needed: a reboot with no Wi-Fi to associate with.
                        val fallback = StartTransport.classicPortFallback(tcpPort) ?: throw e
                        Diag.info(
                            AppConstants.TAG,
                            "Wireless discovery found nothing; starting over the classic ADB port $fallback"
                        )
                        portSource = StartTransport.PortSource.CLASSIC_PROPERTY
                        fallback
                    }
                }
            }

            // TCP mode promises a classic ADB port, and only a USB start ever opened one: a
            // wireless start, which is what the no-network method is, left TCP mode meaning
            // "keep whatever port there is", and after a reboot with no Wi-Fi there is none,
            // so the unattended restarts the mode exists for had nothing to connect to. The
            // port is opened over the wireless connection, which still needs the no-network
            // start's hotspot, so it is best effort either way.
            //
            // Before the start, not after it: the `tcpip` request restarts adbd, and the server
            // is a process adbd spawned, so a server started first went down with it. Measured
            // on the phone: RUNNING at 00:33:09.358, the port opened at 00:33:09.846, CRASHED at
            // 00:33:10.137 and a watchdog restart nine seconds later.
            //
            // Asked of the port itself, not of `service.adb.tcp.port`, which outlives adbd (see
            // issue #71 above). When the start is already going over the classic port there is
            // nothing to open.
            val wanted = ShizukuSettings.getTcpPort()
            if (!usbMethod && ShizukuSettings.getTcpMode() && port != wanted &&
                !AdbStarter.isPortListening(wanted)
            ) {
                // The restart can take down a server left from the previous run. That must read
                // as part of this start rather than as a crash: STARTING makes the transition on
                // the binder dying a no-op (see [ShizukuStateMachine.setDead]), where from RUNNING
                // it is CRASHED, which shows the crash notification and asks the watchdog for a
                // restart. The watchdog would have been held off anyway ([inFlight] is true for
                // all of doWork), but the notification and the broadcast would still have gone out.
                ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)

                val opened = try {
                    AdbStarter.openTcpPort(
                        applicationContext, wanted, knownWirelessPort = port, hunt = searchHunt
                    )
                } catch (e: AdbPairingRequiredException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diag.warn(AppConstants.TAG, "TCP mode: could not open the classic ADB port", e)
                    false
                }

                // What the port says, rather than what that call returned: the call waits for
                // the port to be listening and gives up if it is not listening yet, and adbd
                // can be a moment longer than it waits. It reported failure while the port
                // was open, which is backwards for a line that exists to be read.
                var readBack = EnvironmentUtils.getAdbTcpPort()
                var waited = 0
                while (readBack != wanted && waited < TCP_PORT_READBACK_TIMEOUT_MS) {
                    delay(TCP_PORT_READBACK_INTERVAL_MS)
                    waited += TCP_PORT_READBACK_INTERVAL_MS.toInt()
                    readBack = EnvironmentUtils.getAdbTcpPort()
                }

                Diag.info(
                    AppConstants.TAG,
                    if (readBack == wanted) {
                        "TCP mode: the classic ADB port $wanted is listening"
                    } else {
                        "TCP mode: adbd was asked for port $wanted and reads " +
                            if (readBack <= 0) "nothing" else "$readBack"
                    }
                )

                // adbd restarted, so the wireless port is not known to be alive any more. Keep it
                // when it is, and otherwise take the classic one; when neither answers, the port
                // stays and the start reports the failure as it always has.
                if (opened) {
                    val chosen = AdbStarter.firstListening(listOf(port, wanted))
                    Diag.info(
                        AppConstants.TAG,
                        "TCP mode: starting over port ${chosen ?: port}" +
                            if (chosen == null) " (neither $port nor $wanted answers)" else ""
                    )
                    if (chosen != null) {
                        port = chosen
                        if (chosen == wanted) portSource = StartTransport.PortSource.APP_SETTING
                    }
                }
            }
            // The classic port is open and in use: nothing is left to ask wireless debugging for.
            // Not before - the `tcpip` request above is what the asking had to last until.
            if (port == wanted) searchHunt.close()

            if (adbRoot) {
                notify(WorkerState.CONNECTING)
                // Set before the request: a timeout can mean the command took even though the
                // read-back did not confirm it, and adbd must not be left root because of that.
                rootedPort = port
                if (!AdbRoot.setRoot(port, true)) {
                    throw AdbRootUnavailableException(
                        "adbd did not become root on port $port"
                    )
                }
            }

            notify(WorkerState.CONNECTING)
            // A start that was replaced while it looked for the port stops here. Cancelling the
            // work does not interrupt the blocking calls below, so without this it went on to
            // send its own starter, and two starters ran at once.
            host.checkCancelled()
            port = connectAndStart(searchHunt, port, portSource, usbMethod, tcpPort, wanted)
            // Connected, and the starter sent: nothing is left to find a port for.
            searchHunt.close()
            Starter.waitForBinder()
            // After the binder, not before: asked for sooner, the server is not there yet and the
            // line always read zero.
            AdbStarter.logStartedPids(ShizukuSettings.getLastAdbPort())

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)

            StartStatusReporter.succeeded()

            // A start reports success the moment the binder appears, and the case worth cover is
            // the one that appears and then goes away again within seconds: the platform settles a
            // debugging toggle it has just been asked for by restarting adbd, and the server, being
            // a process adbd spawned, goes with it. Measured here (wireless starts dying with the
            // daemon) and reported from a phone that had to tap Start twice every time after
            // wireless debugging had been off or the phone had restarted (issue #79's thread) - the
            // second attempt sticks because the state has settled by then.
            //
            // Checked in the background so the success report above is not held up by the wait, and
            // connected once more if the server is gone: the same second start, made by the app
            // rather than by the person holding the phone. The watchdog does a check like this for
            // the restarts it makes, but the watchdog is a setting and this has to hold without it.
            val startedPort = port
            settleScope.launch {
                // The root fallback has already unrooted adbd, and a second start from here
                // would replace the root server with a shell-uid one. The watchdog still
                // covers a server that really dies.
                if (adbRoot) return@launch
                delay(START_SETTLE_MS)
                if (Shizuku.pingBinder()) return@launch
                // A ping that fails right after RUNNING can be momentary, so it is asked twice
                // before the server is taken to be gone.
                delay(SETTLE_RECHECK_MS)
                if (Shizuku.pingBinder()) return@launch
                // A stop asked for in between is an answer of its own: bringing the server back
                // would be undoing what the user just did.
                if (ShizukuSettings.getManuallyStopped()) return@launch
                // Another start is already bringing the server back; connecting once more would
                // start a second one over it.
                if (startInProgress()) {
                    Diag.info(
                        AppConstants.TAG,
                        "The server went away again, but another start is under way; not connecting once more"
                    )
                    return@launch
                }

                Diag.warn(
                    AppConstants.TAG,
                    "The server went away again within ${START_SETTLE_MS / 1000}s; connecting once more"
                )
                runCatching {
                    AdbStarter.startAdb(applicationContext, startedPort, openTcpPort = usbMethod)
                    Starter.waitForBinder()
                    AdbStarter.logStartedPids(ShizukuSettings.getLastAdbPort())
                }.onFailure {
                    Diag.warn(AppConstants.TAG, "The second connect did not take either", it)
                }
            }

            return Outcome.SUCCESS
        } catch (e: CancellationException) {
            val state = host.stoppedState()
            notify(state)

            throw e
        } catch (e: Exception) {
            if (lockedStart) {
                // Nothing for the user to act on, and no notification to raise about it: the
                // reason goes in the log, and the start the unlock makes (or the service's own
                // follow-up, if the unlock has already come) is the retry.
                Diag.warn(
                    AppConstants.TAG,
                    "The start before the unlock gave up: ${e.message ?: e.javaClass.simpleName}",
                    e
                )
                ShizukuStateMachine.markStartFailed()
                return Outcome.RETRY
            }
            // Surface the reason in the app; the worker may still retry.
            val (message, kind) = when (e) {
                is AdbPairingRequiredException ->
                    applicationContext.getString(R.string.start_failed_pairing_required) to
                        StartFailureKind.PAIRING

                is AdbRootUnavailableException ->
                    applicationContext.getString(R.string.start_failed_adb_root) to
                        StartFailureKind.GENERIC

                is TimeoutException ->
                    // Without WRITE_SECURE_SETTINGS the toggle above couldn't be switched
                    // on, so the honest reason is the permission, not the network.
                    applicationContext.getString(
                        if (applicationContext.hasWriteSecureSettings()) {
                            R.string.start_failed_no_port
                        } else {
                            R.string.start_failed_no_port_no_permission
                        },
                        // The name the app is installed under now: a hidden copy told to
                        // grant the build-time name is told about a package that is not there.
                        applicationContext.packageName
                    ) to StartFailureKind.GENERIC

                is SecurityException ->
                    applicationContext.getString(R.string.start_failed_no_auth) to
                        StartFailureKind.GENERIC

                // A bare "Socket closed" (or any other IO failure) tells the user nothing
                // the connection to adbd went away mid-attempt. Say that instead.
                is IOException ->
                    applicationContext.getString(R.string.start_failed_connection_lost) to
                        StartFailureKind.GENERIC

                else -> {
                    // Safety net: an adbd path we haven't mapped can still surface the
                    // TLS rejection as a plain SSL string. Never show that raw.
                    val text = e.localizedMessage.orEmpty()
                    if (text.contains("CERTIFICATE_UNKNOWN", true) ||
                        text.contains("CERTIFICATE_VERIFY_FAILED", true) ||
                        text.contains("SSLV3_ALERT", true)
                    ) {
                        applicationContext.getString(R.string.start_failed_pairing_required) to
                            StartFailureKind.PAIRING
                    } else {
                        (e.localizedMessage ?: e.javaClass.simpleName) to
                            StartFailureKind.GENERIC
                    }
                }
            }
            StartStatusReporter.failed(message, kind)

            val ignored = listOf(
                EOFException::class,
                SecurityException::class,
                TimeoutException::class,
                // The app already shows this with a Pair action; a bug-report
                // notification would just be noise.
                AdbPairingRequiredException::class,
                AdbRootUnavailableException::class
            )
            if (ignored.none { it.isInstance(e) }) showErrorNotification(applicationContext, e)

            // The poll no longer ends a start, so a failure says so itself.
            if (ShizukuStateMachine.markStartFailed() == ShizukuStateMachine.State.RUNNING) {
                return Outcome.SUCCESS
            }
            // Root was refused or adbd never came back as root. Retrying the same build
            // will not change that, so say so once instead of leaving work on a backoff.
            if (e is AdbRootUnavailableException) return Outcome.FAILURE
            notify(WorkerState.AWAITING_RETRY)
            return Outcome.RETRY
        } finally {
            rootedPort?.let { port ->
                // Unroot even when the attempt was cancelled or the start failed: the server
                // being gone does not make an adbd left as root acceptable.
                withContext(NonCancellable) {
                    if (!AdbRoot.setRoot(port, false)) {
                        Diag.warn(AppConstants.TAG, "adbd is still root after the start")
                    }
                }
            }
            hunt?.close()
        }
    }

    /**
     * Connects to [startPort] and sends the starter, turning to a fresh wireless port when the one
     * in hand turns out not to be adbd's. Returns the port the starter was sent over.
     *
     * A wireless port is only good while adbd is running the wireless server it was drawn for, and
     * that can be restarted from under a start - by the framework switching the setting back off,
     * or by a `tcpip` request - so a port that was right when it was found can be wrong a moment
     * later. Two things are asked of it before the starter goes out: that something listens, which
     * costs a connect and spares the ten seconds the connection's own retries would spend on a
     * refused one, and that the handshake completes. A port that fails either way is not asked
     * again, and at most [MAX_FRESH_PORT_SEARCHES] others are tried, within the time one search is
     * given each, before the failure is the start's. Once the starter has gone out nothing is
     * retried: a second start over a server that is coming up is what kills it.
     *
     * When no other port is announced, the port in hand gets its full set of connection attempts:
     * a wireless port that is merely slow to answer is not a reason to give up.
     */
    private suspend fun connectAndStart(
        hunt: WirelessPortHunt,
        startPort: Int,
        startSource: StartTransport.PortSource,
        usbMethod: Boolean,
        tcpPort: Int,
        wanted: Int
    ): Int {
        var port = startPort
        var source = startSource
        var searches = 0
        val failed = mutableSetOf<Int>()
        val progress = StartProgress()

        while (true) {
            // The USB method is the classic port by promise, and a start over any port that is
            // neither the one adbd reports nor the one this app opens is a wireless one.
            val wireless = !usbMethod && EnvironmentUtils.isTlsSupported() &&
                port != tcpPort && port != wanted

            val listening = !wireless || AdbStarter.isPortListening(port)
            if (decideBeforeConnect(wireless, listening, searches) == PortRetryDecision.SEARCH_AGAIN) {
                WirelessPortLog.rejected(port, source, "nothing answers on it")
                failed += port
                searches++
                WirelessPortLog.searching("port $port does not answer")
                val fresh = AdbStarter.freshWirelessPort(
                    applicationContext, failed, AdbStarter.FRESH_PORT_TIMEOUT_MS, hunt
                )
                if (fresh != null) {
                    port = fresh
                    source = StartTransport.PortSource.MDNS
                } else {
                    Diag.warn(
                        AppConstants.TAG,
                        "No other wireless port was announced; trying port $port as it is"
                    )
                    searches = MAX_FRESH_PORT_SEARCHES
                }
                host.checkCancelled()
                continue
            }

            try {
                AdbStarter.startAdb(
                    applicationContext,
                    port,
                    openTcpPort = usbMethod,
                    progress = progress,
                    // Quick to give up while there is another port to turn to; the full set once
                    // there is not.
                    connectAttempts = if (wireless && searches < MAX_FRESH_PORT_SEARCHES) 2 else 5
                )
                return port
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = classifyConnectFailure(e)
                if (decideAfterConnectFailure(wireless, progress.starterSent, kind, searches)
                    != PortRetryDecision.SEARCH_AGAIN
                ) throw e

                WirelessPortLog.rejected(port, source, "the connection failed ($kind)")
                failed += port
                searches++
                WirelessPortLog.searching("the connection on port $port failed ($kind)")
                val fresh = AdbStarter.freshWirelessPort(
                    applicationContext, failed, AdbStarter.FRESH_PORT_TIMEOUT_MS, hunt
                )
                if (fresh != null) {
                    port = fresh
                    source = StartTransport.PortSource.MDNS
                } else {
                    Diag.warn(
                        AppConstants.TAG,
                        "No other wireless port was announced; trying port $port once more"
                    )
                    searches = MAX_FRESH_PORT_SEARCHES
                }
                host.checkCancelled()
            }
        }
    }

    /** The start waiting on the lock screen: promoted to the foreground, so its prompt is shown. */
    private fun waitForUnlockInForeground() {
        host.promoteToForegroundAsync()
        notify(WorkerState.WAITING_FOR_UNLOCK)
    }

    /**
     * Puts this attempt in the foreground before it can ask for the hotspot.
     *
     * Android 12 gated the local-only hotspot behind the app being in the foreground, and a
     * WorkManager worker answering a start intent is not. Promoting here rather than leaving it
     * to the locked-screen path is what makes an unlocked offline start possible: the request
     * that used to come back as an incompatible mode is made by an app the platform can see.
     *
     * The failure is logged rather than thrown. A promotion the platform refuses means the
     * hotspot is likely to be refused too, and the start still gets to try and to report what
     * happened, which is more useful than an exception for a start that may yet work over an
     * existing port.
     */
    private suspend fun promoteForHotspot() {
        runCatching { host.promoteToForeground() }
            .onFailure {
                if (it is CancellationException) throw it
                Diag.warn(AppConstants.TAG, "could not promote the start before the hotspot", it)
            }
    }

    private fun showErrorNotification(context: Context, e: Exception) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createChannelCompat(
            CHANNEL_ID,
            context.getString(R.string.wadb_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        // Name the method this start was using, like the other status notifications.
        val msgNotif = "${context.getString(startMethodLabelRes(requestedMethod))} · " +
            "$e. ${context.getString(R.string.wadb_error_notify_dev)}"

        val intent = Intent(context, BugReportDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = nb
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_error_title))
            .setContentText(msgNotif)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msgNotif))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        /**
         * How long a start waits before checking that the server it brought up is still there.
         *
         * Long enough to cover the platform settling the debugging toggle this start just asked
         * for - the reported case was a server gone within about two seconds - and short enough
         * that a first start which has to be made twice is still over quickly.
         */
        private const val START_SETTLE_MS = 8_000L

        /**
         * The scope the settle check runs on. It outlives the worker on purpose: the check is about
         * what happens after the work this worker was asked for has finished.
         */
        private val settleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Whether a start is running in this process at this moment.
         *
         * The watchdog is the one that reads it, and waiting on this is what stops it replacing a
         * start that is still working. A no-network attempt is given minutes to find a port (see
         * [FORCED_DISCOVERY_TIMEOUT_MS]), while the watchdog's recovery poll comes every thirty
         * seconds and forces a start by cancelling the queued work - so the attempt in flight died
         * thirty seconds in, the window it needed never arrived, and the log said the same thing
         * over and over: a start cancelled, a start forced, and still no server.
         *
         * Kept here rather than asked of the watchdog's end, which queries for the work from places
         * that cannot wait on one - a state listener running on the main thread. Both ends are in
         * the same process either way: no service or worker here declares a process of its own, and
         * WorkManager runs a worker in the process that enqueued it.
         */
        private val inFlightCount = AtomicInteger(0)

        /**
         * A count rather than a flag: a replaced worker is cancelled while its successor is
         * already running, and the one that was cancelled clearing a flag would have said that
         * nothing was in flight over a start that was.
         */
        val inFlight: Boolean get() = inFlightCount.get() > 0

        /** When the oldest attempt in flight began, for the logs. Zero when none is. */
        @Volatile
        var inFlightSince: Long = 0L
            private set

        /** How long the attempts in flight have been going, or null when none is. */
        fun inFlightAgeMs(): Long? =
            if (inFlight) SystemClock.elapsedRealtime() - inFlightSince else null

        /**
         * Whether a start is under way by any route: a worker running, or the state machine inside
         * its STARTING grace (which also covers the watchdog's direct start and the root one).
         */
        fun startInProgress(): Boolean =
            inFlight || (ShizukuStateMachine.startingAgeMs()
                ?.let { it < ShizukuStateMachine.STARTING_GRACE_MS } == true)

        private val tlsPropertyLogged = AtomicBoolean(false)

        /**
         * Says once per process whether `service.adb.tls.port` gave an answer. Whether an app may
         * read it is SELinux policy and differs by vendor, and an unreadable property reads as
         * unset, so "no value" is the most this can report.
         */
        private fun logTlsPropertyOnce(value: Int) {
            if (!tlsPropertyLogged.compareAndSet(false, true)) return
            Diag.info(
                AppConstants.TAG,
                if (value > 0) "service.adb.tls.port is readable: $value"
                else "service.adb.tls.port gave no value (unset, or not readable by this app)"
            )
        }

        /**
         * Counts an attempt as in flight before it has begun, for a host that needs time between
         * deciding to start and running: the foreground service is asked for and starts a moment
         * later, and a request that arrived in that moment saw nothing under way and began a
         * second start beside it.
         */
        fun reserveAttempt() = beginAttempt()

        /** Gives back a reservation whose attempt is never going to run. */
        fun releaseReservation() = endAttempt()

        private fun beginAttempt() {
            if (inFlightCount.getAndIncrement() == 0) inFlightSince = SystemClock.elapsedRealtime()
        }

        private fun endAttempt() {
            inFlightCount.updateAndGet { if (it > 0) it - 1 else 0 }
        }

        /** The pause before the second look at a binder that did not answer. */
        private const val SETTLE_RECHECK_MS = 1_000L

        /**
         * Longer than the experiment's own asking window, so the asking and the interface
         * both see it through rather than being cut off with the attempt.
         */
        private const val FORCED_DISCOVERY_TIMEOUT_MS = 130_000L

        /**
         * How long an attempt begun before the unlock searches for a port. The long window above
         * exists for a framework that keeps switching wireless debugging off and has to be caught
         * in a moment it does not; a search that finds nothing in this time before the unlock is
         * more likely stuck on the lock than about to find it, and the unlock begins an ordinary
         * start in any case. To be checked on a device: an estimate, not a measurement.
         */
        private const val LOCKED_DISCOVERY_TIMEOUT_MS = 30_000L

        /** How long to give adbd to come back in TCP mode before saying what the port reads. */
        private const val TCP_PORT_READBACK_TIMEOUT_MS = 3000L
        private const val TCP_PORT_READBACK_INTERVAL_MS = 200L
        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448
    }
}
