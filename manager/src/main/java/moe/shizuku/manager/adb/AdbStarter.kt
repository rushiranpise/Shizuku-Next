package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.start.ConnectFailureKind
import moe.shizuku.manager.start.MAX_FRESH_PORT_SEARCHES
import moe.shizuku.manager.start.PortRetryDecision
import moe.shizuku.manager.start.StartTransport
import moe.shizuku.manager.start.WirelessPortLog
import moe.shizuku.manager.start.classifyConnectFailure
import moe.shizuku.manager.start.decideAfterConnectFailure
import moe.shizuku.manager.start.writeGlobalSetting
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.BootTimeline
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.runShellCommand

private const val TAG = "AdbStarter"

/** The ADB TLS handshake was rejected: the device is not paired with us. */
class AdbPairingRequiredException(message: String?, cause: Throwable?) :
    Exception(message, cause)

private fun Throwable.isCertificateUnknown(): Boolean {
    val text = message ?: return false
    return text.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) ||
        text.contains("SSLV3_ALERT", ignoreCase = true)
}

/**
 * How far a start got, for the caller that has to decide what a failure means.
 *
 * A connection that failed before the starter was sent can be made again on another port. One that
 * failed after it cannot: the server may be coming up, and a second start over it is the one that
 * kills it.
 */
class StartProgress {
    @Volatile
    var starterSent = false
}

object AdbStarter {
    /**
     * @param progress told when the starter is about to be sent, so that a failure before that
     *   point can be told apart from one after it.
     * @param connectAttempts how many times the connection is tried before it is reported failed;
     *   the callers that have another port to turn to ask for fewer, rather than spend ten seconds
     *   on a port that may be the stale one.
     * @param openTcpPort open the classic ADB port as part of this start, even when
     *   the "TCP mode" setting is off. A USB start asks for exactly that: the port is
     *   what makes later USB starts work without wireless debugging or a computer.
     */
    suspend fun startAdb(
        context: Context,
        port: Int,
        log: ((String) -> Unit)? = null,
        openTcpPort: Boolean = false,
        progress: StartProgress? = null,
        connectAttempts: Int = CONNECT_ATTEMPTS
    ) {
        suspend fun AdbClient.runCommand(cmd: String) {
            if (log != null) {
                command(cmd) { log.invoke(String(it)) }
                return
            }
            // A start with no screen has nowhere to show the starter's output, which used to
            // discard the only account of what it found and whether it killed a server. The
            // lines that say so go to the log instead; they arrive in chunks, so they are
            // collected up to the newline.
            val lines = StarterLineBuffer { Diag.info(TAG, "Starter: $it") }
            command(cmd) { lines.append(String(it)) }
            lines.flush()
        }

        // Deliberately NOT resetting adb_wifi_enabled here: keeping wireless
        // debugging on lets Shizuku restart without USB debugging or Wi-Fi.
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        log?.invoke("Starting with wireless adb...\n")

        withContext(Dispatchers.IO) {
            val key = runCatching { AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku") }
                .getOrElse {
                    if (it is CancellationException) throw it
                    else throw AdbKeyException(it)
                }

            var activePort = port
            val tcpPort = ShizukuSettings.getTcpPort()
            // Classic TCP mode rides on the USB debugging toggle. Only switch adbd
            // into TCP mode when USB debugging is already on switching during a
            // wireless-only start would tie future restarts to a toggle that's off.
            val usbDebugging = EnvironmentUtils.isAdbEnabled()
            // Only a USB start opens/switches to the classic ADB port. Letting a
            // wireless start do it turned "Wireless debugging" into a TCP connection
            // that then reported itself as USB debugging.
            val keepTcpPort = openTcpPort
            var viaTcp = !EnvironmentUtils.isTlsSupported() ||
                    (activePort > 0 && activePort == EnvironmentUtils.getAdbTcpPort())

            // Reported through Diag as well as to the start screen, because which port a start
            // used and whether adbd was switched is exactly what a report about a server that
            // dies needs to say, and the screen's log is the one account nobody can attach.
            // Asked for after the fact, "was this the start that restarted adbd?" has no answer.
            Diag.info(
                TAG,
                "Start on port $activePort, TCP mode: ${ShizukuSettings.getTcpMode()}, " +
                    "USB debugging: $usbDebugging, opening the ADB port: $keepTcpPort"
            )

            if (keepTcpPort && usbDebugging && activePort != tcpPort) {
                log?.invoke("Connecting on port $activePort...")

                AdbClient("127.0.0.1", activePort, key).use { client ->
                    // Same pairing detection as the main path a TLS rejection here
                    // must not surface as a raw SSL error either.
                    client.connectForPairing()

                    log?.invoke("Successfully connected on port $activePort...")
                    log?.invoke("\nRestarting in TCP mode port: $tcpPort")
                    // Said here too, and this is the one that matters most: adbd restarts on
                    // this command, so a server already running is the thing it can take with
                    // it - the suspicion a report about repeated deaths has to settle.
                    Diag.info(
                        TAG,
                        "Switching adbd to TCP mode on port $tcpPort, which restarts adbd"
                    )

                    activePort = tcpPort
                    viaTcp = true
                    runCatching {
                        client.command("tcpip:$activePort")
                    }.onFailure { if (it !is EOFException && it !is SocketException) throw it } // Expected when ADB restarts in TCP mode

                    // adbd restarts on the new port; wait until it actually listens so
                    // the connect below targets a live socket (instead of the stale one).
                    if (!waitForPortAvailable("127.0.0.1", activePort)) {
                        Diag.warn(TAG, "Timed out waiting for ADB to listen on TCP port $activePort")
                    }
                }
            }

            log?.invoke("Connecting on port $activePort...")

            AdbClient("127.0.0.1", activePort, key).use { client ->
                // connectWithRetry reports a rejected handshake as pairing-required.
                connectWithRetry(client, connectAttempts)
                log?.invoke("Successfully connected on port $activePort...\n")
                // A start replaced while it connected must not go on to run its starter: the
                // replacement runs one of its own, and the second one kills the first's server.
                currentCoroutineContext().ensureActive()
                progress?.starterSent = true
                client.runCommand("shell:${Starter.internalCommand}")
            }

            // Remember which transport launched the server so the home status
            // card can show whether it runs via wireless or USB debugging.
            ShizukuSettings.setLastAdbTransport(
                if (viaTcp) ShizukuSettings.ADB_TRANSPORT_TCP else ShizukuSettings.ADB_TRANSPORT_TLS
            )
            // The auto-disable setting was only honored on a deliberate stop. A start after a
            // reboot never stops anything, so it never ran there. Once the server has been sent
            // over the classic port, wireless debugging is no longer carrying the start; honor
            // the setting here too. The no-network experiment is excluded for the same reason
            // the stop path excludes it: it needs the toggle to survive.
            if (viaTcp && ShizukuSettings.getAutoDisableWirelessDebugging() &&
                !ShizukuSettings.getForceWirelessDebugging()
            ) {
                val disabled = context.writeGlobalSetting("adb_wifi_enabled", 0)
                Diag.info(
                    TAG,
                    if (disabled) {
                        "Disabled wireless debugging after the TCP start"
                    } else {
                        "Could not disable wireless debugging after the TCP start"
                    }
                )
            }
            // And the port itself: a wireless one is random per boot, so this is the only thing that
            // lets the next start reach adbd without searching for it.
            ShizukuSettings.setLastAdbPort(activePort)
            // With the boot it belongs to: a wireless port is not the same port after a restart, and
            // this is what lets the next start tell a remembered port from a stale one.
            ShizukuSettings.setLastAdbPortBoot(EnvironmentUtils.getBootCount())
            Diag.info(
                TAG,
                "Server started over ${if (viaTcp) "the classic (TCP) port" else "the wireless port"}" +
                    " on port $activePort" + BootTimeline.suffix()
            )
        }
    }

    /**
     * What the phone looked like when this start last succeeded.
     *
     * Kept so the next start can say what changed in between: which adbd the server was running
     * under last time, and whether that is a different adbd now. The death this exists to explain -
     * a server that comes up and is taken away seconds later - has one strong suspect, adbd being
     * restarted underneath it, and a dead server cannot be asked anything after the fact, so the
     * comparison is made at the next start instead.
     */
    @Volatile
    private var lastServerPid = 0
    @Volatile
    private var lastAdbdPid = 0
    @Volatile
    private var lastStartAt = 0L

    /**
     * Reads both pids through the shell this app already has (the server that just came up is what
     * provides it) and writes down what it found next to what the previous start left behind.
     *
     * Best effort: a reading that fails leaves the two numbers at zero and the start it describes
     * carries on, because this is a diagnostic and not a step.
     *
     * Called once the binder has arrived rather than from [startAdb]: the server is a few seconds
     * from existing when the command returns, and a reading taken then always said zero.
     */
    internal suspend fun logStartedPids(port: Int) = withContext(Dispatchers.IO) {
        readAndLogPids(port)
    }

    private fun readAndLogPids(port: Int) {
        val serverOut = runShellCommand("pidof shizuku_server")
        val adbdOut = runShellCommand("pidof adbd")
        val server = serverOut?.trim()?.toIntOrNull() ?: 0
        val adbd = adbdOut?.trim()?.toIntOrNull() ?: 0

        // Said rather than hidden: a zero here means the shell could not answer, not that there
        // are no such processes, and the two look identical in the line below without this.
        if (server == 0 || adbd == 0) {
            Diag.warn(
                TAG,
                "Could not read the pids (shizuku_server: '${serverOut.orEmpty()}', " +
                    "adbd: '${adbdOut.orEmpty()}')"
            )
        }

        val previous = if (lastStartAt > 0L && lastServerPid != 0) {
            val ran = (SystemClock.elapsedRealtime() - lastStartAt) / 1000
            val adbdChange = when {
                adbd == 0 || lastAdbdPid == 0 -> "adbd unknown"
                adbd != lastAdbdPid -> "adbd restarted ($lastAdbdPid -> $adbd)"
                else -> "adbd unchanged ($adbd)"
            }
            ", previous server $lastServerPid ran ${ran}s, $adbdChange"
        } else {
            ""
        }

        Diag.info(TAG, "Pids on port $port: server $server, adbd $adbd$previous")

        lastServerPid = server
        lastAdbdPid = adbd
        lastStartAt = SystemClock.elapsedRealtime()
    }

    /** What came of asking for the classic port to be closed. */
    enum class CloseOutcome {
        /** Tried, and adbd is no longer listening on it. */
        CLOSED,

        /** Not attempted: wireless debugging is the mode adbd is in. */
        WIRELESS_IN_USE,

        /** Not attempted: the command is only accepted as USB debugging, which is off. */
        USB_DEBUGGING_OFF,

        /** Nothing was listening on the port. */
        NOT_LISTENING,

        /** The key this app pairs with could not be read. */
        KEY_STORE,

        /** Tried, and refused. */
        FAILED
    }

    /**
     * What is in the way of closing the port, or null when nothing is.
     *
     * Both answers are about the phone rather than about the port, because closing it is not a
     * change to a port: it is `usb:` on adbd, which puts the daemon back in USB mode. A decision
     * rather than an attempt, so the screen can say which of the two it is without a shell in the
     * way.
     */
    internal fun closeBlocker(wirelessDebugging: Boolean, usbDebugging: Boolean): CloseOutcome? =
        when {
            wirelessDebugging -> CloseOutcome.WIRELESS_IN_USE
            !usbDebugging -> CloseOutcome.USB_DEBUGGING_OFF
            else -> null
        }

    /**
     * Closes the classic ADB port, or says why it cannot be closed.
     *
     * The port is closed by sending `usb:`, and that is a switch of adbd's mode rather than of one
     * port - the same daemon, and the same switch, that wireless debugging's session lives on. Two
     * things follow, and both are why this refuses rather than forcing it:
     *
     *  * with wireless debugging on, adbd is the session that mode is using, and `usb:` takes it
     *    away to put the daemon back in USB mode: the port closes and whatever the user was in the
     *    middle of goes with it;
     *  * the command is only accepted as USB debugging, and turning that toggle on by ourselves to
     *    issue it - then back off, hoping nothing happens in between - would leave the phone's own
     *    debugging state flickering for a setting that is about a port.
     *
     * So the port is left to the next adbd restart, which closes it without touching anything on
     * the way, and the answer says which of the two it was.
     */
    suspend fun stopTcp(port: Int): CloseOutcome = withContext(Dispatchers.IO) {
        if (port <= 0) return@withContext CloseOutcome.NOT_LISTENING

        closeBlocker(
            wirelessDebugging = EnvironmentUtils.isWirelessDebuggingEnabled(),
            usbDebugging = EnvironmentUtils.isAdbEnabled()
        )?.let { blocker ->
            Diag.info(TAG, "not closing port $port: ${blocker.name}")
            return@withContext blocker
        }

        // Resolve the STOPPING state below: closing the port does not kill an already-running
        // server, and if nothing was running the state must return to STOPPED rather than stick at
        // STOPPING, which would suppress the watchdog's dead-check.
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)

        val outcome = runCatching {
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            AdbClient("127.0.0.1", port, key).use { client ->
                connectWithRetry(client)
                client.command("usb:")
            }
        }.fold(
            onSuccess = { CloseOutcome.CLOSED },
            onFailure = { throwable ->
                Diag.warn(TAG, "closing port $port failed", throwable)
                if (throwable is AdbKeyException) CloseOutcome.KEY_STORE else CloseOutcome.FAILED
            }
        )

        ShizukuStateMachine.update()
        Diag.info(TAG, "closing port $port: $outcome")
        outcome
    }

    /**
     * Opens the classic ADB port and returns whether it is up.
     *
     * The port is only ever created by a `tcpip` request sent over an existing
     * connection, and a reboot clears it (it isn't persistent), so after a reboot the
     * app has nothing to connect to until something sends that request. The wireless
     * port is the only one the app can raise on its own, so this borrows it briefly:
     * connect, ask adbd to also listen on the classic port, and leave the wireless
     * connection behind. The caller then starts over the classic port as usual.
     *
     * Returns false when there is no way to try (no Wi-Fi, or no wireless port found).
     * Throws [AdbPairingRequiredException] when the fallback needs pairing first.
     *
     * [knownWirelessPort] is a wireless port the caller already knows. When something is listening
     * on it, it is used as it is and the search is skipped: the search has failed (after its full
     * fifteen seconds) on a phone whose wireless port was answering the whole time. When nothing
     * is, or the connection to it fails the way a stale port's does, a fresh port is asked for
     * ([freshWirelessPort]) - at most [MAX_FRESH_PORT_SEARCHES] times, never the same one twice.
     *
     * [hunt] is the caller's search, kept running for as long as the caller needs a port: with the
     * experiment on, the asking for wireless debugging that keeps the port alive is part of it.
     * Without one, a search of its own is made for this call.
     */
    suspend fun openTcpPort(
        context: Context,
        port: Int,
        knownWirelessPort: Int? = null,
        hunt: WirelessPortHunt? = null
    ): Boolean = withContext(Dispatchers.IO) {
        // Wi-Fi is what keeps wireless debugging and with it the wireless port alive, and
        // without it there is usually nothing to borrow. The no-network start is the
        // exception, and the reason it exists: it brings up a local-only hotspot for that
        // interface, so the discovery below is given its chance and says so if it finds
        // nothing, rather than this refusing on the grounds that there is no Wi-Fi when the
        // port was just reached over something else.
        if (!EnvironmentUtils.isWifiConnected() &&
            !ShizukuSettings.getForceWirelessDebugging()
        ) {
            Diag.info(TAG, "Not opening the ADB port: no Wi-Fi connection to borrow")
            return@withContext false
        }

        // Best effort: without WRITE_SECURE_SETTINGS the write throws, and that must not
        // abort the attempt wireless debugging may already be on.
        context.writeGlobalSetting("adb_wifi_enabled", 1)

        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        val failed = mutableSetOf<Int>()
        var searches = 0
        var candidate = knownWirelessPort

        // The first round uses the port the caller knows; every later one is a search that the
        // previous round's failure paid for, so the rounds end with the searches.
        for (round in 0..MAX_FRESH_PORT_SEARCHES) {
            var found: Int? = candidate?.takeIf { isPortListening(it) }
            if (found == null) {
                val stale = candidate
                if (stale != null) {
                    WirelessPortLog.rejected(
                        stale, StartTransport.PortSource.REMEMBERED, "nothing answers on it"
                    )
                    failed += stale
                }
                if (searches < MAX_FRESH_PORT_SEARCHES) {
                    searches++
                    WirelessPortLog.searching(
                        if (stale != null) "port $stale does not answer" else "no wireless port is known"
                    )
                    found = freshWirelessPort(context, failed, FRESH_PORT_TIMEOUT_MS, hunt)
                }
            }
            val wirelessPort = found ?: run {
                Diag.warn(TAG, "Not opening the ADB port: no wireless debugging port was found")
                return@withContext false
            }

            // Whether `tcpip:` has gone out. Once it has, adbd is restarting to listen on the
            // new port, so a connection that dies here is the expected outcome and not a reason
            // to go looking for another port - sending the command a second time would restart
            // adbd again over a port that was already coming up.
            var tcpipSent = false
            try {
                // Which attempt this is, and over what, before adbd is asked to restart: the
                // answer to "did the port open, and did it cost a running server" starts here.
                Diag.info(
                    TAG,
                    "Opening the ADB port $port over the wireless connection on $wirelessPort, " +
                        "which restarts adbd"
                )

                AdbClient("127.0.0.1", wirelessPort, key).use { client ->
                    client.connectForPairing()
                    // adbd restarts to listen on the new port, so the connection dying here
                    // is the expected outcome, not a failure. A TLS reset is one of the ways it
                    // dies on a restart (Conscrypt reports it as an SSLException), so it is
                    // treated like the socket closing rather than as a failed handshake.
                    tcpipSent = true
                    runCatching { client.command("tcpip:$port") }
                        .onFailure {
                            if (it !is EOFException && it !is SocketException && it !is SSLException) {
                                throw it
                            }
                        }
                }
            } catch (e: AdbPairingRequiredException) {
                if (retryableOpen(e, searches, tcpipSent)) {
                    failed += wirelessPort
                    candidate = null
                    continue
                }
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (retryableOpen(e, searches, tcpipSent)) {
                    failed += wirelessPort
                    candidate = null
                    continue
                }
                Diag.warn(TAG, "Could not open the ADB port over the wireless connection", e)
                return@withContext false
            }

            val available = waitForPortAvailable("127.0.0.1", port)
            Diag.info(TAG, "ADB port $port open: $available")
            return@withContext available
        }
        false
    }

    /**
     * Whether a failed connection on the way to opening the classic port is worth another port.
     * Logged with the reason when it is, because a port that is passed over silently is the thing
     * that made the stale one impossible to see.
     */
    private fun retryableOpen(e: Exception, searchesUsed: Int, commandSent: Boolean): Boolean {
        val kind = classifyConnectFailure(e)
        val decision = decideAfterConnectFailure(
            wireless = true,
            // The rule the shared decision already knows: nothing is retried once the command
            // that restarts adbd is out. This used to say false unconditionally, which let a
            // TLS reset after `tcpip:` send the command a second time.
            commandSent = commandSent,
            failure = kind,
            searchesUsed = searchesUsed
        )
        if (decision != PortRetryDecision.SEARCH_AGAIN) return false
        Diag.warn(TAG, "Connection to the wireless port failed ($kind); asking for a fresh one")
        return true
    }

    /**
     * The latest wireless (TLS) port that is not in [exclude], as advertised over mDNS, or null if
     * none shows up within [timeoutMs].
     *
     * The one way the start asks for a port after the first guesses: the search keeps running
     * (see [WirelessPortHunt]) and answers with the most recent announcement rather than the first,
     * so a port that adbd has since replaced is not handed out. With [hunt] the caller's own search
     * answers; without one, a search is made for this call and ended with it.
     *
     * A search that failed for good (the network was refused) is a null here, not an exception: the
     * callers all have a way to carry on without a fresh port, and say so.
     */
    suspend fun freshWirelessPort(
        context: Context,
        exclude: Set<Int>,
        timeoutMs: Long,
        hunt: WirelessPortHunt? = null
    ): Int? {
        // mDNS discovery of the wireless port needs the service discovery the platform gained
        // in Android 11 (API 30), and the class that asks for it does not exist before then:
        // naming it would be a NoClassDefFoundError rather than an answer of "no port".
        // Returning null is the same answer the discovery gives when nothing advertises, and
        // it is the truth here - an older platform has no wireless debugging to advertise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val search = hunt ?: WirelessPortHunt(context, forced = false)
        return try {
            search.fresh(exclude, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn(TAG, "The search for a wireless port ended: ${e.message}")
            null
        } finally {
            if (hunt == null) search.close()
        }
    }

    /** How long one search for a fresh port waits for an announcement. */
    const val FRESH_PORT_TIMEOUT_MS = 10_000L

    /**
     * Whether something is listening on a local port at this moment.
     *
     * A port number is not a port, which is the mistake this exists to stop: `service.adb.tcp.port`
     * is a property and outlives the daemon that was listening on it, so a phone whose debugging
     * toggles were turned off when Shizuku stopped still reads the port adbd was using. A connect
     * is the only answer that tells the two apart, and it is cheap - nothing listening on the
     * loopback refuses at once.
     */
    suspend fun isPortListening(port: Int, host: String = "127.0.0.1"): Boolean =
        withContext(Dispatchers.IO) {
            if (port <= 0) return@withContext false
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            }.isSuccess
        }

    /**
     * The first of [candidates] that something is listening on, or null when none answers.
     *
     * The ports worth trying before searching for one: the port the setting names, the port the last
     * start used, and the classic port this app would open. A wireless port is random per boot, so
     * the remembered one is what turns a start that spent fifteen seconds searching into one that
     * connects - and a candidate that answers but is not adbd is no worse than a wrong guess, since
     * the handshake fails at once rather than hanging.
     */
    suspend fun firstListening(candidates: List<Int>, host: String = "127.0.0.1"): Int? =
        candidates.filter { it > 0 }.distinct().firstOrNull { isPortListening(it, host) }

    /**
     * Bounds one probe. Nothing listening on the loopback refuses at once, so this only ever covers
     * the case where a socket does not answer at all - and waiting longer than this for a port to
     * decide whether it is there is a worse answer than saying it is not.
     */
    private const val CONNECT_TIMEOUT_MS = 250

    private suspend fun waitForPortAvailable(
        host: String,
        port: Int,
        timeoutMs: Long = 15_000L
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket(host, port).use { return@withContext true }
            } catch (_: Exception) {
                delay(300)
            }
        }
        false
    }

    /**
     * Every connection to adbd goes through here, so a TLS rejection is always
     * reported as [AdbPairingRequiredException] (the device doesn't trust our key
     * yet never paired, or the pairing was invalidated) instead of a raw SSL
     * error the user can't act on.
     */
    private suspend fun AdbClient.connectForPairing() {
        try {
            connect()
        } catch (e: Exception) {
            if (e is AdbPairingRequiredException) throw e
            if (e is SSLException || e.isCertificateUnknown()) {
                // Expected whenever the device doesn't trust our key, and the UI turns it
                // into the pairing flow so log one line, not a stack trace, which reads
                // like a crash of its own.
                Diag.warn(
                    TAG,
                    "TLS handshake rejected, pairing required " +
                        e.message?.replace('\n', ' ')
                )
                throw AdbPairingRequiredException(e.message, e)
            }
            throw e
        }
    }

    private const val CONNECT_ATTEMPTS = 5

    private suspend fun connectWithRetry(client: AdbClient, maxAttempts: Int = CONNECT_ATTEMPTS) {
        var delayTime = 0L
        for (attempt in 1..maxAttempts) {
            try {
                delay(delayTime)
                client.connectForPairing()
                break
            } catch (e: Exception) {
                if (
                    attempt == maxAttempts ||
                    e is CancellationException ||
                    e is SocketTimeoutException ||
                    // Not transient: retrying can't make the device trust our key, and the
                    // retry only produces a dead-socket error that hides the real reason.
                    e is AdbPairingRequiredException
                ) throw e
                delayTime += 1000
            }
        }
    }
}
