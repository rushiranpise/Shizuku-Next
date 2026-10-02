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
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.R
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.start.StartFailureKind
import moe.shizuku.manager.service.WatchdogGuard
import moe.shizuku.manager.start.StartStatusReporter
import moe.shizuku.manager.start.StartMethodGuard
import moe.shizuku.manager.start.hasWriteSecureSettings
import moe.shizuku.manager.start.needsWriteSecureSettingsFor
import moe.shizuku.manager.start.startMethodLabelRes
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.utils.createChannelCompat
import moe.shizuku.manager.worker.AdbStartWorker
import moe.shizuku.manager.utils.Diag

object ShizukuReceiverStarter {

    const val NOTIFICATION_ID = 1447
    private const val CHANNEL_ID = "AdbStartWorker"

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
     */
    fun start(
        context: Context,
        forceStart: Boolean = false,
        userInitiated: Boolean = false,
        @ShizukuSettings.StartMethod startMethod: Int = ShizukuSettings.getStartMethod()
    ) {
        // A start request from any entry point clears manual-stop suppression.
        ShizukuSettings.setManuallyStopped(false)

        // Ask the binder rather than trusting the last thing this process heard. A server
        // stopped from somewhere else, or one that died while the manager was not looking,
        // left the belief behind, and a start was then ignored in silence: a tap that does
        // nothing at all, until a reboot resets the belief. The refusal is also said out
        // loud now, because a tap that quietly does nothing is the worst of the two.
        val wasRunning = UserHandleCompat.myUserId() > 0 ||
            ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING
        if (wasRunning && !forceStart) {
            Diag.info(AppConstants.TAG, "Start ignored: the service is already running")
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
        // failing silently. The setting is rewritten too, so the UI agrees with what the
        // next start will do.
        val method = StartMethodGuard.resolve(startMethod)

        StartStatusReporter.starting()

        // Remember how this launch was started: the status card and the notification
        // report the method the server is actually running under, which isn't always
        // the one configured for the next start.
        ShizukuSettings.setRunningStartMethod(method)

        when (method) {
            ShizukuSettings.StartMethod.ROOT -> rootStart(context)
            ShizukuSettings.StartMethod.SYSTEM -> systemStart(context)
            else -> adbStart(context, userInitiated, method)
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

    private fun adbStart(context: Context, userInitiated: Boolean, startMethod: Int) {
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
        // is held up, and only because it cannot be done without the permission.
        if (context.needsWriteSecureSettingsFor(startMethod) && !context.hasWriteSecureSettings()) {
            StartStatusReporter.failed(
                context.getString(R.string.start_failed_write_secure_settings),
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
        if (userInitiated &&
            startMethod == ShizukuSettings.StartMethod.WIRELESS &&
            !hasWifi &&
            !television
        ) {
            StartStatusReporter.failed(
                context.getString(R.string.start_failed_wifi_required),
                StartFailureKind.WIFI
            )
            return
        }

        // User-initiated starts never wait for Wi-Fi; unattended background restarts
        // honour the "wait for Wi-Fi" setting.
        val immediate = userInitiated || !ShizukuSettings.getWaitForWifi()
        AdbStartWorker.enqueue(context, startMethod = startMethod, immediate = immediate)
        updateNotification(context, WorkerState.AWAITING_WIFI)
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

    private fun rootStart(context: Context) {
        if (!Shell.getShell().isRoot) {
            // [start] has already dropped root when the device doesn't have it, so reaching
            // here means it was revoked in between. Say so instead of returning silently:
            // the card would otherwise sit on "starting" for a start that never happened.
            Diag.warn(AppConstants.TAG, "Root was revoked before the start could use it")
            Shell.getCachedShell()?.close()
            StartStatusReporter.failed(context.getString(R.string.start_failed_root_unavailable))
            ShizukuStateMachine.update()
            return
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            Shell.cmd(Starter.internalCommand).exec()
        } catch (e: Exception) {
            Diag.error(AppConstants.TAG, "Failed to start Shizuku with root", e)
            ShizukuStateMachine.update()
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