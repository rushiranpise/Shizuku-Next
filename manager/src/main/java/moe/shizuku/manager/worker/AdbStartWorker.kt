package moe.shizuku.manager.worker

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.service.HidingWatchService
import androidx.core.app.NotificationCompat
import androidx.lifecycle.asFlow
import androidx.work.*
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingRequiredException
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter.WorkerState
import moe.shizuku.manager.settings.BugReportDialogActivity
import moe.shizuku.manager.start.ForcedWirelessDebugging
import moe.shizuku.manager.start.StartFailureKind
import moe.shizuku.manager.start.StartStatusReporter
import moe.shizuku.manager.start.StartTransport
import moe.shizuku.manager.start.hasWriteSecureSettings
import moe.shizuku.manager.start.startMethodLabelRes
import moe.shizuku.manager.start.writeGlobalLongSetting
import moe.shizuku.manager.start.writeGlobalSetting
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.createChannelCompat

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** The method this start was asked for, so notifications retry the same way. */
    private var requestedMethod = ShizukuSettings.StartMethod.WIRELESS

    private fun notify(state: ShizukuReceiverStarter.WorkerState) =
        ShizukuReceiverStarter.updateNotification(applicationContext, state, requestedMethod)

    override suspend fun doWork(): Result {
        // A hiding list is holding a debugging toggle off for an app the user has open. Every
        // path below that needs one writes it back on if it finds it off, so an attempt now is
        // not a start that fails - it is a start that undoes the hide and then fails. Deferred
        // instead: the watch asks for a start itself once nothing is hidden.
        if (HidingWatchService.holding()) {
            Diag.info(AppConstants.TAG, "Start deferred: hiding is holding the debugging toggle off")
            return Result.success()
        }

        try {
            return startServer()
        } finally {
            // A local-only hotspot is only ever brought up to give discovery an
            // interface, so it goes away with the attempt that needed it: leaving it up
            // would be tethering nobody asked for.
            ForcedWirelessDebugging.releaseHotspot()
        }
    }

    private suspend fun startServer(): Result {
        try {
            requestedMethod = inputData.getInt(KEY_START_METHOD, ShizukuSettings.StartMethod.WIRELESS)

            notify(WorkerState.RUNNING)

            val cr = applicationContext.contentResolver
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
            val wirelessAlreadyEnabled = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
            val usbAlreadyEnabled = EnvironmentUtils.isAdbEnabled()

            // All of the writes below go through helpers that swallow a permission denial:
            // on a fresh install (or after a re-signed update) the app has no
            // WRITE_SECURE_SETTINGS yet, and a settings write *throws* without it which
            // used to abort the start and get reported as a pairing problem. A start that
            // can't nudge adbd should still try to connect.
            if (usbMethod) {
                if (!usbAlreadyEnabled) {
                    applicationContext.writeGlobalSetting(Settings.Global.ADB_ENABLED, 1)
                }
                // Don't let the authorized connection expire while we connect.
                applicationContext.writeGlobalLongSetting("adb_allowed_connection_time", 0L)
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
                AdbStarter.stopTcp(applicationContext, tcpPort)
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
                    applicationContext.getString(R.string.start_failed_usb_no_port),
                    // The card can open the port for the user.
                    StartFailureKind.PORT
                )
                notify(WorkerState.AWAITING_RETRY)
                return Result.failure()
            }
            // From here the USB method always has a classic port to use.

            // A wireless start goes over the wireless (TLS) port, which discovery has to
            // find. Taking the classic ADB port whichever TCP mode keeps open is what made
            // a "Wireless debugging" start run over USB debugging's transport and report
            // itself as USB, so the classic port is what the USB method and platforms
            // without wireless debugging use.
            val useClassicPort = usbMethod || !EnvironmentUtils.isTlsSupported()
            val port = tcpPort.takeIf { useClassicPort }
                ?: callbackFlow {
                // The mDNS service discovery below arrived with wireless debugging in Android 11
                // (API 30), and so did the class that queries it: on anything older the class
                // does not exist at all and naming it is a NoClassDefFoundError rather than a
                // start that reports no port. The branch above cannot reach here on those
                // platforms - a start that may not use the classic port already knows TLS is
                // supported, which is exactly API 30 - so this only makes that explicit and
                // keeps the reference off the older platforms.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    close(TimeoutException("mDNS wireless discovery needs Android 11"))
                    awaitClose { }
                    return@callbackFlow
                }
                val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                    if (p.second > 0) trySend(p.second)
                }

                var awaitingAuth = false
                var timeoutJob: Job? = null
                var unlockReceiver: BroadcastReceiver? = null
                var nudgeJob: Job? = null

                fun startDiscoveryWithTimeout() {
                    adbMdns.start()
                    timeoutJob?.cancel()
                    // The experiment asks for minutes, so the attempt has to live for
                    // minutes: a 15 second deadline closed it moments after the hotspot
                    // had come up, which at boot is most of those 15 seconds gone on
                    // bringing the interface up in the first place.
                    val deadline =
                        if (forcedWireless) FORCED_DISCOVERY_TIMEOUT_MS else 15_000L
                    timeoutJob = launch {
                        delay(deadline)
                        close(TimeoutException("Timed out during mDNS port discovery"))
                    }
                }

                fun handleAuth() {
                    // Only once: this is called again every time the setting changes, and
                    // the experiment below makes it change over and over. A second call
                    // would post the same "waiting for unlock" notification again and
                    // leave another receiver behind.
                    if (unlockReceiver != null) return
                    val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (km.isKeyguardLocked) {
                        val notification = ShizukuReceiverStarter.buildNotification(
                            applicationContext,
                            null,
                            requestedMethod
                        )
                        // The type has to be handed over here, not only declared in the
                        // manifest: WorkManager passes on what this carries, so the two
                        // argument form asks for a foreground service with no type at all,
                        // which Android 14 refuses outright and takes the process down with
                        // it. That combination is the locked-device start, and therefore
                        // every start a reboot makes, which is where the crashes came from:
                        // the manifest was right and the call was not.
                        val foregroundInfo = if (Build.VERSION.SDK_INT >= 34) {
                            ForegroundInfo(
                                ShizukuReceiverStarter.NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                        } else {
                            ForegroundInfo(
                                ShizukuReceiverStarter.NOTIFICATION_ID,
                                notification
                            )
                        }
                        setForegroundAsync(foregroundInfo)
                        notify(WorkerState.WAITING_FOR_UNLOCK)

                        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                        unlockReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (intent.action == Intent.ACTION_USER_PRESENT) {
                                    context.unregisterReceiver(this)
                                    unlockReceiver = null
                                    // Wireless debugging must be on for the TLS port to be
                                    // advertised, otherwise mDNS discovery can never find it.
                                    context.writeGlobalSetting("adb_wifi_enabled", 1)
                                }
                            }
                        }
                        applicationContext.registerReceiver(unlockReceiver, filter)
                    } else awaitingAuth = true

                    // With the experiment on, the attempt keeps its deadline and keeps
                    // discovering: it is fighting a setting the framework keeps reverting,
                    // so every revert would otherwise cancel the only thing that ends the
                    // attempt, and the worker would wait forever instead of retrying. The
                    // daemon can also be up while the setting reads 0, which is the state
                    // discovery should stay out for.
                    if (!forcedWireless) {
                        timeoutJob?.cancel()
                        adbMdns.stop()
                    }
                }

                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                            // Reading 0 is the framework refusing the setting again, which
                            // is precisely the state the experiment is asking it out of, so
                            // it must not end the attempt: closing here killed the whole
                            // thing about a second in, before the hotspot was up or the
                            // writes had a chance, and the result was a start that only
                            // ever said it was waiting.
                            0 -> if (awaitingAuth && !forcedWireless) {
                                close(SecurityException("Network is not authorized for wireless debugging"))
                            } else handleAuth()
                            1 -> startDiscoveryWithTimeout()
                        }
                    }
                }

                // Required for discovery without it the device never advertises
                // _adb-tls-connect and the worker just times out. Best effort: if we may
                // not write, discovery still gets its chance before we complain.
                applicationContext.writeGlobalSetting("adb_wifi_enabled", 1)
                cr.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
                startDiscoveryWithTimeout()

                // Opt-in, and only when there is no port to fall back on: keep asking for
                // wireless debugging the way the Settings toggle cannot be asked when the
                // device has no network, and bring up an interface for discovery if
                // asking is not enough on its own. Cancelled with this flow, because it
                // is only worth doing while something is trying to discover the port.
                if (forcedWireless) {
                    notify(WorkerState.FORCING_WIRELESS)
                    nudgeJob = launch {
                        ForcedWirelessDebugging.nudge(applicationContext) { message ->
                            Diag.info(AppConstants.TAG, "Forced wireless debugging: $message")
                        }
                    }
                }

                awaitClose {
                    adbMdns.stop()
                    timeoutJob?.cancel()
                    nudgeJob?.cancel()
                    cr.unregisterContentObserver(observer)
                    unlockReceiver?.let { applicationContext.unregisterReceiver(it) }
                }
            }.let { discovery ->
                try {
                    discovery.first()
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
                    fallback
                }
            }
            
            notify(WorkerState.CONNECTING)
            AdbStarter.startAdb(applicationContext, port, openTcpPort = usbMethod)
            Starter.waitForBinder()

            // TCP mode promises a classic ADB port, and only a USB start ever opened one: a
            // wireless start, which is what the no-network method is, left TCP mode meaning
            // "keep whatever port there is", and after a reboot with no Wi-Fi there is none,
            // so the unattended restarts the mode exists for had nothing to connect to. The
            // port can be opened from here because there is now a running server and, with
            // the no-network start, a hotspot still up to reach adbd over: the same borrow a
            // USB start makes to repair its own port, and it is best effort either way.
            if (!usbMethod && ShizukuSettings.getTcpMode() &&
                EnvironmentUtils.getAdbTcpPort() <= 0
            ) {
                val wanted = ShizukuSettings.getTcpPort()
                AdbStarter.openTcpPort(applicationContext, wanted)

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
            }

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)

            StartStatusReporter.succeeded()
            return Result.success()
        } catch (e: CancellationException) {
            val state = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                WorkerState.AWAITING_RETRY
            } else {
                when (stopReason) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WorkerState.STOPPED
                    else -> WorkerState.AWAITING_RETRY
                }
            }
            notify(state)

            throw e
        } catch (e: Exception) {
            // Surface the reason in the app; the worker may still retry.
            val (message, kind) = when (e) {
                is AdbPairingRequiredException ->
                    applicationContext.getString(R.string.start_failed_pairing_required) to
                        StartFailureKind.PAIRING

                is TimeoutException ->
                    // Without WRITE_SECURE_SETTINGS the toggle above couldn't be switched
                    // on, so the honest reason is the permission, not the network.
                    applicationContext.getString(
                        if (applicationContext.hasWriteSecureSettings()) {
                            R.string.start_failed_no_port
                        } else {
                            R.string.start_failed_no_port_no_permission
                        }
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
                AdbPairingRequiredException::class
            )
            if (ignored.none { it.isInstance(e) }) showErrorNotification(applicationContext, e)

            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                return Result.success()
            } else {
                notify(WorkerState.AWAITING_RETRY)
                return Result.retry()
            }
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
        const val KEY_START_METHOD = "start_method"

        /**
         * Longer than the experiment's own asking window, so the asking and the interface
         * both see it through rather than being cut off with the attempt.
         */
        private const val FORCED_DISCOVERY_TIMEOUT_MS = 130_000L

        /** How long to give adbd to come back in TCP mode before saying what the port reads. */
        private const val TCP_PORT_READBACK_TIMEOUT_MS = 3000L
        private const val TCP_PORT_READBACK_INTERVAL_MS = 200L

        fun enqueue(
            context: Context,
            startMethod: Int = ShizukuSettings.StartMethod.WIRELESS,
            immediate: Boolean = false
        ) {
            val usbMethod = startMethod == ShizukuSettings.StartMethod.USB
            val cb = Constraints.Builder()
            // Waiting for a network is only worth it when the start has no other way to
            // reach the port: a wireless start needs one to find the TLS port over mDNS,
            // while a classic port that TCP mode keeps open needs nothing, and a start
            // that has one would sit on this constraint forever on a device with no Wi-Fi.
            // `immediate` (a start the user asked for) skips the wait either way: they get
            // an answer now instead of a job that sits there.
            val needsNetwork = StartTransport.wifiRequired(
                EnvironmentUtils.getAdbTcpPort(),
                ShizukuSettings.getTcpMode(),
                ShizukuSettings.getForceWirelessDebugging()
            )
            if (needsNetwork && !immediate)
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            val constraints = cb.build()

            val inputData = workDataOf(KEY_START_METHOD to startMethod)

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                // Retry about once a minute, doubling as it keeps failing: a boot start
                // usually just needs to wait for Wi-Fi to associate, and giving up after
                // one attempt is what left Shizuku down until the next manual start.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "adb_start_worker",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448
    }
}