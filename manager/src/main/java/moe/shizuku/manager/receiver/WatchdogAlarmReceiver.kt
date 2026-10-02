package moe.shizuku.manager.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.createChannelCompat

/**
 * Brings the watchdog back after something killed it, from outside the app.
 *
 * The watchdog is a foreground service in this app's one process, and it is the whole of the
 * app's self-healing: it watches the server, and nothing watches it. That is fine while the
 * process is alive and useless the moment it is not - a task killer, or an OEM battery manager of
 * the kind that freezes an entire app on screen lock, takes [WatchdogService] down with everything
 * else, and a dead process cannot notice its own death. Samsung's "Sleeping apps" is the case
 * this was written for.
 *
 * An alarm is the one trigger that does not live in this process. It is held by the platform, and
 * the broadcast is dispatched by `system_server`, which will cold-start a fresh process to deliver
 * it even when the previous one was killed or frozen. So the interval below is a bound on how long
 * the watchdog can stay dead, which nothing inside the app could have bounded.
 *
 * This is a backstop, not a second recovery loop: it asks only whether the watchdog is alive, and
 * a running watchdog already polls the server every thirty seconds on its own. It says nothing
 * about the server, deliberately - that is the watchdog's job, and duplicating it here would give
 * two loops that can disagree.
 *
 * Deliberately a mitigation. The gap it cannot close is the interval itself: a watchdog killed
 * just after an alarm fired stays dead until the next one. Fifteen minutes is the trade - tight
 * enough to be worth having, loose enough not to be a battery complaint on its own.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Doze batches alarms and a restart can redeliver one, so two firings close together are
        // one event; acting twice would start the service twice for no reason and re-arm twice.
        val now = System.currentTimeMillis()
        synchronized(FIRED_LOCK) {
            if (now - lastFiredAt < DEBOUNCE_MS) {
                Diag.debug(TAG, "Re-arm fired again within ${DEBOUNCE_MS / 1000}s, ignored")
                return
            }
            lastFiredAt = now
        }

        val app = context.applicationContext
        if (!ShizukuSettings.getWatchdog()) {
            // Switched off since it was armed, so there is nothing to bring back and nothing to
            // re-arm. Not an error: the switch was turned off and the alarm had not been spent.
            Diag.debug(TAG, "Watchdog is off, not re-arming")
            return
        }

        if (!WatchdogService.isRunning()) {
            if (WatchdogService.start(app)) {
                Diag.warn(TAG, "The watchdog is not running; the alarm started it again")
            } else {
                // The platform refused, which is the expected answer for a background caller: a
                // foreground service may not be started from the background unless the alarm that
                // woke us was an exact one, and this app targets Android 14 or later, where exact
                // alarms are not granted to it by default. So the one duty of this receiver is the
                // one the platform can decline, and a silent refusal would be indistinguishable
                // from the outage it was meant to end - hence the notice. Tapping it opens the
                // app, and starting the watchdog from there is a start the platform allows, so the
                // way back costs the user one tap and exists even when this cannot work.
                Diag.error(TAG, "The watchdog is not running and the platform refused to start it")
                showNotRunningNotification(app)
            }
        }
        // Exact alarms do not repeat, and a repeating one would not be exact anyway, so each
        // firing arms the next. Armed even after a refusal: the next firing is another chance,
        // and the notice is what covers the case where every one of them is refused.
        schedule(app)
    }

    /**
     * The only way back when the revive was refused.
     *
     * A plain notification, posted from a receiver, which needs no service of any kind - which is
     * the whole point, since the thing it is reporting is that a service could not be started. Its
     * content intent opens the app, because starting the watchdog is something the app
     * already does when it comes up, and a tap is a user action on a notification: the start is
     * then the user's own, which the platform allows.
     */
    private fun showNotRunningNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        manager.createChannelCompat(
            WatchdogService.CRASH_CHANNEL_ID,
            "Crash Reports",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notification = NotificationCompat.Builder(context, WatchdogService.CRASH_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.watchdog_not_running_title))
            .setContentText(context.getString(R.string.watchdog_not_running_text))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(WatchdogService.NOTIFICATION_ID_NOT_RUNNING, notification) }
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"

        /**
         * The gap between the watchdog dying and this noticing, and the reason it is not smaller.
         *
         * An inexact alarm in Doze is delivered at the platform's convenience, so a fifteen-minute
         * ask already costs battery in a way a five-minute one would make worse for no real gain:
         * a server that is down is already being retried by the watchdog whenever it *is* alive,
         * and this only covers the case where nothing is alive to retry anything. Fifteen minutes
         * is also the tightest interval WorkManager will accept for periodic work, so it is the
         * same number this would have chosen had the backstop been built that way.
         */
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /** One event, not two: see the check in [onReceive]. */
        private const val DEBOUNCE_MS = 30_000L

        /** One alarm, always cancelled and re-armed under the same identity. */
        private const val REQUEST_CODE = 9001

        private val FIRED_LOCK = Any()

        @Volatile
        private var lastFiredAt = 0L

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogAlarmReceiver::class.java),
                // UPDATE_CURRENT so the same identity is reused and cancel() finds it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /**
         * Arms the next check, replacing any that was already set.
         *
         * Called wherever the watchdog is switched on or the app finds itself running: on the
         * setting itself, at boot (alarms do not survive a reboot) and at startup, because an
         * update replaces the app and takes its alarms with it.
         */
        @JvmStatic
        fun schedule(context: Context) {
            if (!ShizukuSettings.getWatchdog()) return
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val at = System.currentTimeMillis() + INTERVAL_MS
            val pending = pendingIntent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                    // This is the ordinary path, not the fallback: an app targeting Android 14 or
                    // later is not granted exact alarms at all unless it asks, and this one has no
                    // business asking - the permission is meant for alarms and calendars, and a
                    // re-arm fifteen minutes out has no need to be to the millisecond. Inexact
                    // still survives a dead process, which is the entire point; it is only the
                    // delivery time the platform gets to batch.
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
                Diag.debug(TAG, "Watchdog backstop armed for ${INTERVAL_MS / 60_000} minutes' time")
            } catch (e: Exception) {
                // A backstop that cannot be armed is worth a line and not a crash: everything it
                // covers is already covered by the watchdog whenever the watchdog is alive.
                Diag.warn(TAG, "Could not arm the watchdog backstop", e)
            }
        }

        /** Called when the watchdog is switched off, so a backstop cannot switch it back on. */
        @JvmStatic
        fun cancel(context: Context) {
            try {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return
                manager.cancel(pendingIntent(context))
            } catch (e: Exception) {
                Diag.warn(TAG, "Could not cancel the watchdog backstop", e)
            }
        }
    }
}
