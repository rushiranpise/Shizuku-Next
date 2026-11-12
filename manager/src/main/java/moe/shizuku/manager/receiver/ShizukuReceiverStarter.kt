package moe.shizuku.manager.receiver

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbRoot
import moe.shizuku.manager.start.StartFailureKind
import moe.shizuku.manager.service.WatchdogGuard
import moe.shizuku.manager.start.StartStatusReporter
import moe.shizuku.manager.start.StartMethodGuard
import moe.shizuku.manager.start.StartTransport
import moe.shizuku.manager.start.canWriteSecureSettings
import moe.shizuku.manager.start.needsWriteSecureSettingsFor
import moe.shizuku.manager.start.startMethodLabelRes
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.utils.createChannelCompat
import moe.shizuku.manager.ktx.ensureWorkManager
import moe.shizuku.manager.ktx.isUserUnlockedCompat
import moe.shizuku.manager.service.LockedBootStartService
import moe.shizuku.manager.start.AdbStartRun
import moe.shizuku.manager.worker.AdbStartWorker
import moe.shizuku.manager.utils.BootTimeline
import moe.shizuku.manager.utils.Diag

object ShizukuReceiverStarter {

    const val NOTIFICATION_ID = 1447
    private const val CHANNEL_ID = "AdbStartWorker"

    /** Root starts probe a shell and may wait for adbd to come back; never on the caller's thread. */
    private val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class WorkerState {
        AWAITING_WIFI,
        AWAITING_RETRY,
        CONNECTING,
        WAITING_FOR_UNLOCK,
        FORCING_WIRELESS,
        RUNNING,
        STOPPED
    }

    /**
     * Starts Shizuku with [startMethod], defaulting to the method chosen in
     * settings. Every entry point goes through here the Start button, start on
     * boot, the watchdog, the manual-start intent and the pairing flow so they
     * all behave the same instead of guessing from whichever method worked last.
     *
     * [userInitiated] means a person asked for *this* start from a control in front of them, and
     * nothing else counts. It is what decides whether a wireless start with no Wi-Fi fails at once
     * or waits: somebody looking at the screen is owed the answer now, while a start that arrived
     * from another app's intent, from the pairing flow or from a boot is nobody's tap and waits for
     * the network - which is what the unattended starts have to do, since Wi-Fi routinely arrives a
     * few seconds after they try.
     *
     * [reason] names the entry point, for the log only.
     *
     * [replaceInFlight] is for a start that has a reason to replace an attempt already under way
     * without being a user's Restart, such as a pairing that just completed.
     *
     * One start at a time: a request that arrives while another is under way joins it instead of
     * queueing its own, unless somebody tapped a start or the pairing just completed. See
     * [decideStart].
     */
    fun start(
        context: Context,
        forceStart: Boolean = false,
        userInitiated: Boolean = false,
        @ShizukuSettings.StartMethod startMethod: Int = ShizukuSettings.getStartMethod(),
        reason: String = "unspecified",
        replaceInFlight: Boolean = false
    ) {
        // Who asked, written down for every request: a server replaced out of the blue has no
        // other trace of what replaced it.
        Diag.info(
            AppConstants.TAG,
            "Start requested by $reason (forced=$forceStart, user=$userInitiated)" +
                BootTimeline.suffix()
        )

        // A start request from any entry point clears manual-stop suppression.
        ShizukuSettings.setManuallyStopped(false)
        // And it ends the watchdog's boot gate: a server asked for is a server worth keeping
        // alive, so a failed start from here may be retried.
        ShizukuSettings.setServerRanSinceBoot(true)

        // Ask the binder rather than trusting the last thing this process heard. A server
        // stopped from somewhere else, or one that died while the manager was not looking,
        // left the belief behind, and a start was then ignored in silence: a tap that does
        // nothing at all, until a reboot resets the belief. The refusal is also said out
        // loud now, because a tap that quietly does nothing is the worst of the two.
        // Asked before anything is marked or queued: a request that is only joining a start
        // must leave the watchdog's guard, the running method and the status card as that start
        // set them.
        val inFlightAgeMs = AdbStartRun.inFlightAgeMs()
            ?: ShizukuStateMachine.startingAgeMs()?.takeIf { it < ShizukuStateMachine.STARTING_GRACE_MS }
        val binderAlive = UserHandleCompat.myUserId() > 0 ||
            ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING
        // Before the first unlock nothing below may touch WorkManager: its database is in storage
        // that cannot be read yet, and asking for it takes the process down. A wireless start is
        // made in the foreground service instead; any other waits for the unlock to ask again.
        val userUnlocked = context.isUserUnlockedCompat()
        val decision = decideStart(
            binderAlive,
            forceStart,
            userInitiated,
            startInFlight = inFlightAgeMs != null,
            userUnlocked = userUnlocked,
            // A USB start has the classic port to reach or it has nothing: the port that is
            // already there is what makes it a locked start rather than one waiting for a cable.
            canStartLocked = canStartBeforeUnlock(
                startMethod,
                classicPortOpen = EnvironmentUtils.getAdbTcpPort() > 0
            ),
            replaceInFlight = replaceInFlight
        )
        when (decision) {
            StartDecision.IGNORE_RUNNING -> {
                Diag.info(AppConstants.TAG, "Start ignored: the service is already running")
                return
            }
            StartDecision.JOIN_IN_FLIGHT -> {
                Diag.info(
                    AppConstants.TAG,
                    "Start requested by $reason joined the one already in flight " +
                        "(${(inFlightAgeMs ?: 0L) / 1000} s old)"
                )
                return
            }
            StartDecision.DEFER_UNTIL_UNLOCK -> {
                Diag.info(
                    AppConstants.TAG,
                    "Start requested by $reason deferred: the user has not unlocked yet and " +
                        "method $startMethod cannot start before that" + BootTimeline.suffix()
                )
                return
            }
            StartDecision.START, StartDecision.START_LOCKED -> Unit
        }
        val lockedStart = decision == StartDecision.START_LOCKED

        // A start somebody tapped, or one following a completed pairing, gets past a start in
        // flight and replaces it by cancelling it: queued work is replaced by enqueueing over it,
        // but a start running in the foreground service is not queued work and nothing below would
        // touch it. Cancelled here, and the new start made once it has really ended - cancelling
        // does not interrupt the blocking calls in an attempt, so "asked to stop" is not yet "will
        // not send its starter", and the second starter is the one that kills the first one's
        // server.
        if (decision == StartDecision.START && LockedBootStartService.isRunning()) {
            LockedBootStartService.cancelRun("replaced by a restart requested by $reason") {
                start(
                    context,
                    forceStart,
                    userInitiated,
                    startMethod,
                    "$reason (after cancelling the start begun before the unlock)"
                )
            }
            return
        }
        // A forced start replaces whatever is there, so the binder death on the way is ours,
        // and the watchdog reads a binder death as a crash unless it is told otherwise: it
        // would answer a deliberate Restart with a crash notification and a second start
        // racing this one.
        //
        // Marked for any forced start rather than only when this process can still see the
        // server, because by the time a start request is handled the old server may already
        // be gone: there is nothing left to ping then, and the death it left behind would go
        // through unclaimed. The mark carries a deadline, so the worst a forced start with
        // nothing to replace can do is ignore one genuine death inside the next half minute.
        if (forceStart) {
            Diag.info(AppConstants.TAG, "Forced start: the server's death is expected")
            WatchdogGuard.expectDeath()
        }

        // Root can be gone since the method was chosen (an OTA, root switched off), and a
        // root start with nothing to escalate with does nothing at all so a start that
        // asks for root on a device without it falls back to wireless debugging instead of
        // failing silently. The setting stays Root, so root coming back restores the method
        // on the next start without the user having to pick it again.
        //
        // Not asked before the unlock: the guard exists to fall back when root has gone, and it
        // answers by spawning a shell. A locked start is made with the method as it is asked for,
        // and [rootStart] says so itself when root is not there, which is the same answer.
        val method = if (lockedStart) startMethod else StartMethodGuard.resolve(startMethod)

        StartStatusReporter.starting()

        // Remember how this launch was started: the status card and the notification
        // report the method the server is actually running under, which isn't always
        // the one configured for the next start.
        ShizukuSettings.setRunningStartMethod(method)

        when (method) {
            ShizukuSettings.StartMethod.ROOT -> rootStart(context, userInitiated, lockedStart)
            ShizukuSettings.StartMethod.SYSTEM -> systemStart(context)
            else -> adbStart(context, userInitiated, method, lockedStart)
        }
    }

    /**
     * The system start runs the built-in exploit (or an external command) through
     * [StarterActivity], which needs the app in the foreground a background start
     * such as boot or the watchdog cannot drive it.
     */
    private fun systemStart(context: Context) {
        // "Custom command" is the same start with the escalation left to the user: no
        // device exploit is attempted, and the activity says what to run and waits for the
        // binder instead. The setting used to be read by nothing at all, so choosing it
        // changed a preference and nothing else.
        val custom =
            ShizukuSettings.getSystemStartMethod() == ShizukuSettings.SYSTEM_START_CUSTOM

        val started = runCatching {
            context.startActivity(
                Intent(context, StarterActivity::class.java)
                    .putExtra(StarterActivity.EXTRA_IS_SYSTEM, true)
                    .putExtra(StarterActivity.EXTRA_SYSTEM_CUSTOM, custom)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

        if (!started) {
            StartStatusReporter.failed(context.getString(R.string.start_failed_system_needs_app))
        }
    }

    private fun adbStart(
        context: Context,
        userInitiated: Boolean,
        startMethod: Int,
        lockedStart: Boolean = false,
        adbRoot: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            && !EnvironmentUtils.isTelevision()
            && EnvironmentUtils.getAdbTcpPort() <= 0
        ) {
            StartStatusReporter.failed(context.getString(R.string.start_failed_unsupported))
            Diag.warn(AppConstants.TAG, "Background start not supported")
            return
        }

        // This used to refuse every ADB start without WRITE_SECURE_SETTINGS, including the
        // ones that never write anything: a USB start, a classic-port start, and a wireless
        // start whose debugging toggle is already on. On a fresh install where the grant
        // from the previous install is gone that demanded a computer before Shizuku could
        // start at all. Now only the start that actually has to switch wireless debugging on
        // is held up, and only when there is no way to write that setting at all: the app's own
        // grant, or a running server, which writes it as shell.
        if (context.needsWriteSecureSettingsFor(startMethod) && !context.canWriteSecureSettings()) {
            StartStatusReporter.failed(
                context.getString(
                    R.string.start_failed_write_secure_settings,
                    context.packageName
                ),
                // The card opens Wireless debugging so the user can switch it on by hand 
                // the permission is only how the app would have done it for them.
                StartFailureKind.SETTINGS
            )
            showPermissionErrorNotification(context, startMethod)
            return
        }

        val hasWifi = EnvironmentUtils.isWifiConnected()
        val television = EnvironmentUtils.isTelevision()

        // A wireless start needs a network the system turns wireless debugging back off
        // without one, so discovery can never find a port. Only fail the start the user
        // asked for, though: an unattended one (boot, watchdog) must keep trying instead,
        // because Wi-Fi routinely arrives a few seconds after boot and failing it there
        // just leaves Shizuku down. The worker carries a Wi-Fi constraint and retries, so
        // it starts by itself once the network is up.
        //
        // Which starts need it is asked of the same place the worker asks it, rather than being
        // read off the method: a start that chose no network (Without Wi-Fi, or the experiment
        // that makes the same start) needs none, and neither does one that TCP mode has left a
        // classic port to use. Comparing the method alone refused exactly those - a phone with
        // Wi-Fi off and the no-network mode on was sent to connect to a Wi-Fi network by the
        // wireless card, which passes the plain wireless method, while the same start arriving
        // as an intent went ahead, since an unattended start is never refused (issue #79).
        val withoutNetwork = startMethod == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK
        val wirelessStart = startMethod == ShizukuSettings.StartMethod.WIRELESS || withoutNetwork
        val needsNetwork = wirelessStart && StartTransport.wifiRequired(
            EnvironmentUtils.getAdbTcpPort(),
            ShizukuSettings.getTcpMode(),
            ShizukuSettings.getForceWirelessDebugging() || withoutNetwork
        )
        if (userInitiated && needsNetwork && !hasWifi && !television) {
            StartStatusReporter.failed(
                context.getString(R.string.start_failed_wifi_required),
                StartFailureKind.WIFI
            )
            return
        }

        // User-initiated starts never wait for Wi-Fi; unattended background restarts
        // honour the "wait for Wi-Fi" setting.
        val immediate = userInitiated || !ShizukuSettings.getWaitForWifi()

        if (lockedStart) {
            // The service does not wait for a network the way the queued work does: it makes one
            // attempt. So a start that needs Wi-Fi, has none yet and is meant to wait for it is
            // left for the unlock, where the queued work's own constraint takes over. Wi-Fi
            // routinely associates a few seconds after the boot that started this.
            if (needsNetwork && !immediate && !hasWifi && !television) {
                StartStatusReporter.clear()
                Diag.info(
                    AppConstants.TAG,
                    "Start before the unlock left for the unlock: it needs Wi-Fi and there is none yet"
                )
                return
            }
            if (!LockedBootStartService.start(context, startMethod)) {
                StartStatusReporter.clear()
            }
            return
        }

        if (!AdbStartWorker.enqueue(
                context,
                startMethod = startMethod,
                immediate = immediate,
                adbRoot = adbRoot
            )
        ) {
            StartStatusReporter.failed(context.getString(R.string.start_failed))
            return
        }
        // "Waiting for Wi-Fi" only when there is a wait: a start that goes ahead without one
        // would say it was waiting for a network it does not use, which is the line the user
        // reads after a tap that already worked.
        updateNotification(
            context,
            if (needsNetwork && !immediate) WorkerState.AWAITING_WIFI else WorkerState.RUNNING
        )
    }

    /**
     * A server that is up, and a start that was still waiting for a network.
     *
     * The two only meet when the server arrived by another route: a root or system start from the
     * card, or an `adb` connection from a computer, while this app's own wireless start sat on its
     * Wi-Fi constraint. Nothing took that queued work back, so its notification stayed on the phone
     * saying "Waiting for Wi-Fi" over a service that was already running - and the moment a network
     * did appear, the worker would have started a second server on top of the one that was there,
     * replacing the root server the user had just started by hand.
     *
     * Called from the state machine on every RUNNING transition rather than from the starts
     * themselves, because the start that won need not be one of ours.
     *
     * A worker already in flight is left alone: that one is what brought this server up, it
     * cancels its own notification when it is done, and cancelling it here would end a start
     * mid-connect - the TCP port it opens afterwards, and the settle check that reconnects a
     * server the platform took away again, both run after the binder appears.
     */
    fun onServerRunning(context: Context) {
        if (AdbStartRun.inFlight) return
        // Nothing can be queued before the unlock, and asking WorkManager for its instance then is
        // an exception (a process that woke without credential storage has none). The notification
        // is still ours to take down.
        if (context.isUserUnlockedCompat() && context.ensureWorkManager()) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
            }.onFailure { Diag.warn(AppConstants.TAG, "Could not drop the queued start", it) }
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    fun buildNotification(
        context: Context,
        msg: String? = null,
        @ShizukuSettings.StartMethod startMethod: Int = ShizukuSettings.getStartMethod()
    ): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createChannelCompat(
            CHANNEL_ID,
            context.getString(R.string.wadb_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )

        val cancelIntent = Intent(context, NotifCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val attemptNowIntent = Intent(context, NotifAttemptReceiver::class.java)
            // Keep the retry on the same method the start used.
            .putExtra(AppConstants.EXTRA_START_METHOD, startMethod)
        val attemptNowPendingIntent = PendingIntent.getBroadcast(
            context, 0, attemptNowIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(context, NotifRestoreReceiver::class.java)
        val restorePendingIntent = PendingIntent.getBroadcast(
            context, 0, restoreIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wifiIntent = SettingsPage.InternetPanel.buildIntent(context)
        val wifiPendingIntent = PendingIntent.getActivity(
            context, 0, wifiIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        // Say which method this start is using, next to the state, so the notification
        // answers "over wireless, USB, system or root?" on its own.
        nb.setContentText(
            listOfNotNull(context.getString(startMethodLabelRes(startMethod)), msg)
                .joinToString(" · ")
        )

        return nb
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_server_restart, context.getString(R.string.wadb_notification_attempt_now), attemptNowPendingIntent)
            .addAction(R.drawable.ic_close_24, context.getString(android.R.string.cancel), cancelPendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .setContentIntent(wifiPendingIntent)
            .build()
    }

    fun updateNotification(
        context: Context,
        state: WorkerState,
        @ShizukuSettings.StartMethod startMethod: Int = ShizukuSettings.getStartMethod()
    ) {
        if (state == WorkerState.STOPPED) return
        val msgId = when (state) {
            WorkerState.AWAITING_WIFI -> R.string.wadb_notification_wifi_required
            WorkerState.AWAITING_RETRY -> R.string.wadb_notification_retry
            WorkerState.CONNECTING -> R.string.wadb_notification_connecting
            WorkerState.WAITING_FOR_UNLOCK -> R.string.wadb_notification_waiting_for_unlock
            WorkerState.FORCING_WIRELESS -> R.string.wadb_notification_forcing_wireless
            else -> null
        }
        val msg = if (msgId != null) context.getString(msgId) else null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(context, msg, startMethod))
    }

    /**
     * Starts the server as uid 0, with a root manager or with adbd's own root switch.
     *
     * The root shell is tried first so a device with Magisk, KernelSU, Sui or a ROM root
     * manager keeps exactly the start it has today. When there is no root shell, a debuggable
     * build can still give adbd uid 0; the server is then started over USB and adbd is put back
     * to the shell user afterwards. A production build has neither path and says so.
     */
    private fun rootStart(
        context: Context,
        userInitiated: Boolean,
        lockedStart: Boolean
    ) {
        rootScope.launch {
            // Reached before the unlock too, where a shell may not be ready at all: libsu is given
            // one by the root manager, and a refusal there is a start that cannot be made rather
            // than an exception out of a request nobody asked a question of.
            val shell = runCatching { Shell.getShell() }.getOrNull()
            if (shell?.isRoot == true) {
                try {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                    Shell.cmd(Starter.internalCommand).exec()
                } catch (e: Exception) {
                    Diag.error(AppConstants.TAG, "Failed to start Shizuku with root", e)
                    ShizukuStateMachine.markStartFailed()
                }
                return@launch
            }

            // A shell built while root was away is plain and is reused by libsu. Drop it before
            // asking adbd, for the same reason [EnvironmentUtils.isRooted] does.
            Shell.getCachedShell()?.let { runCatching { it.close() } }

            if (lockedStart) {
                Diag.warn(
                    AppConstants.TAG,
                    "No root shell before the unlock; the unlocked start can try adb root"
                )
                StartStatusReporter.failed(context.getString(R.string.start_failed_root_unavailable))
                ShizukuStateMachine.markStartFailed()
                return@launch
            }

            if (AdbRoot.isSupported()) {
                Diag.info(AppConstants.TAG, "No root shell; starting adbd as root over USB")
                adbStart(
                    context,
                    userInitiated,
                    ShizukuSettings.StartMethod.USB,
                    lockedStart = false,
                    adbRoot = true
                )
                return@launch
            }

            Diag.warn(AppConstants.TAG, "No root shell and adb root is not available")
            StartStatusReporter.failed(context.getString(R.string.start_failed_root_unavailable))
            ShizukuStateMachine.markStartFailed()
        }
    }

    private fun showPermissionErrorNotification(
        context: Context,
        @ShizukuSettings.StartMethod startMethod: Int
    ) {

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createChannelCompat(
            CHANNEL_ID,
            context.getString(R.string.wadb_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )

        val webpageIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thedjchi/Shizuku/wiki#shizuku-isnt-starting-on-boot-for-me"))
        val pendingWebpageIntent = PendingIntent.getActivity(
            context, 0, webpageIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Name the method this start was using, like the other status notifications.
        val msg = context.getString(R.string.wadb_permission_error_notification_content) +
            " · " + context.getString(startMethodLabelRes(startMethod))

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_permission_error_notification_title))
            .setContentText(msg)
            .setSilent(true)
            .setContentIntent(pendingWebpageIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}