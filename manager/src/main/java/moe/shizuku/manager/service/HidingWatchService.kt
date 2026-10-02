package moe.shizuku.manager.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.manage.ForceDark
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.manage.HidingGrants
import moe.shizuku.manager.manage.Signal
import moe.shizuku.manager.receiver.HidingRestoreReceiver
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.createChannelCompat

/**
 * The watch: while an app on one of the hiding lists is in front, the settings it objects to are
 * hidden, and they go back the moment it leaves.
 *
 * It carries force dark as well, because the question underneath is the same one. An app that
 * cannot see in the dark is an app whose switches have to be held while it is in front, and one
 * watch that asks what is in front beats two watches asking twice.
 *
 * This is the part that makes the lists worth having. A setting is the device's, so hiding one is
 * hiding it from everybody; what makes that liveable is doing it for exactly as long as the app
 * that cares is on screen, and that is a question only the foreground can answer.
 *
 * It runs in the foreground itself, which is not decoration: a process the platform is free to
 * kill between two polls would leave the device in its hidden state with nothing left to notice
 * that nobody is using the app any more. The ongoing notification is how the user can always see
 * that hiding is on, and it is where the way out is.
 *
 * Polling rather than listening, because there is nothing to listen to: no API for the foreground
 * app is available to the shell, and the usage-stats app op that would answer the same question
 * is one the platform refuses to change for adb. One filtered dumpsys is about twenty
 * milliseconds, which is what makes this affordable at this interval.
 */
class HidingWatchService : Service() {

    companion object {
        private const val TAG = AppConstants.TAG

        private const val CHANNEL_ID = "hiding"
        private const val NOTIFICATION_ID = 1452

        private const val POLL_MS = 1200L

        /** How often the state is looked at while hiding is paused and there is nothing to do. */
        private const val IDLE_MS = 1500L

        /**
         * How often a dead server is asked for, at most.
         *
         * Long enough that a start which cannot work yet is not asked for every second, short
         * enough that the transport coming back is noticed while the user is still holding the
         * phone.
         */
        private const val START_RETRY_MS = 20_000L

        /**
         * Called after a list changes: starts the watch when anything is on a list and stops it,
         * putting everything back, when nothing is.
         *
         * Runs shell commands, so it does not belong on the main thread.
         */
        fun refresh(context: Context) {
            val intent = Intent(context, HidingWatchService::class.java)
            if (!Hiding.hasWorkToDo() && !ForceDark.hasWorkToDo()) {
                // Neither feature has an app left to act for, so nothing has a reason to stay
                // hidden or forced dark - and nothing for a watch to watch.
                Hiding.restoreAll()
                ForceDark.restore()
                runCatching { context.stopService(intent) }
                return
            }

            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Diag.warn(TAG, "could not start the hiding watch", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, HidingWatchService::class.java)) }
        }

        /**
         * Whether hiding is live right now.
         *
         * The home screen says so, because the consequence is not obvious from the lists
         * themselves: while an app on one of them is open, Shizuku is expected to fall over and
         * come back, and somebody watching the status card deserves to have been told.
         */
        @JvmStatic
        fun isRunning(): Boolean = running.get()

        /**
         * The app a list is hiding for right now, or null.
         *
         * Read from outside this service because the rest of the app has to be told about it:
         * a start cannot work while the debugging toggle is being held off, and the attempt does
         * not merely fail - it writes the toggle back on, undoing the hide it could not use.
         */
        @JvmStatic
        fun holdingFor(): String? = holding.get()

        /** Whether hiding is holding a toggle off, and a restart attempt would therefore fight it. */
        @JvmStatic
        fun holding(): Boolean = running.get() && holding.get() != null

        private val holding = AtomicReference<String?>(null)

        private val running = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    /** When a start was last asked for, so a server that cannot come back is not nagged. */
    private var lastStartAttempt = 0L

    /**
     * What was hidden at the last pass that differed, so the log says the story once.
     *
     * Written because of the one thing this feature cannot be watched through: hiding a
     * transport turns off adb, which is the cable every log reader is holding. The hidden
     * window is therefore invisible while it lasts, and logcat is the only record that survives
     * it. A line per change rather than per pass, so a session is a handful of lines and not one
     * every second.
     */
    private var lastLoggedState: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        channel()

        // Asked for once, quietly, the first time the watch runs. Both are shell commands, so
        // there is no dialog to show and nothing for the user to decide: a list was switched on,
        // and without these two it cannot see the foreground without the shell.
        scope.launch { HidingGrants.ensureQuietly() }

        // Posted before the first pass, because a foreground service that has not posted its
        // notification yet is one the platform will kill.
        val notification = notification(
            hidingFor = null,
            paused = Hiding.isPaused(),
            forcingDark = ForceDark.isApplied()
        )
        // The type argument arrived with Android 10 (API 29); below it the two-argument call
        // is the only one there is, and it is also the only one that is legal there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        watching = scope.launch { watch() }
    }

    /**
     * Sticky, because the watch is the only thing that knows the settings are hidden.
     *
     * A process the platform kills for memory leaves the device lying about itself, and a watch
     * that did not come back would leave it that way until somebody opened this app. Sticky is
     * the platform asking for it to be started again, which is exactly what is wanted here.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        holding.set(null)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        var paused = false

        while (scope.isActive) {
            if (!Hiding.hasWorkToDo() && !ForceDark.hasWorkToDo()) {
                holding.set(null)
                Hiding.restoreAll()
                ForceDark.restore()
                notify(notification(hidingFor = null, paused = false))
                stopSelf()
                return
            }

            // Nothing can see the foreground, so nothing can know the app that made this
            // necessary is still open - and a device left hidden by a watch that stopped
            // watching is worse than not hiding at all. Everything goes back and it stands down.
            //
            // Only reached without usage access: with it, the platform answers this question
            // whether or not Shizuku is running, which is what lets a list hide the very
            // transport Shizuku is using.
            if (!Hiding.canWatch()) {
                Hiding.restoreAll()
                notify(notification(hidingFor = null, paused = false, unavailable = true))
                stopSelf()
                return
            }

            // Force dark is a rule of its own, and keeps to it whether or not hiding is paused:
            // the pause is about the settings somebody asked to have back right now, and an app
            // on this list is still an app that cannot see in the dark either way.
            runCatching {
                if (ForceDark.hasWorkToDo()) ForceDark.applyFor(Hiding.foregroundPackage())
                else ForceDark.restore()
            }.onFailure { Diag.warn(TAG, "the force dark pass failed", it) }

            if (Hiding.isPaused()) {
                // Restored once on the way in, not once a second: hiding is suspended, so there
                // is nothing to look at until somebody resumes it.
                if (!paused) {
                    holding.set(null)
                    Hiding.restoreAll()
                    notify(notification(hidingFor = null, paused = true))
                    paused = true
                }
                delay(IDLE_MS)
                continue
            }

            paused = false
            val hidingFor = runCatching { Hiding.reconcile() }
                .onFailure { Diag.warn(TAG, "the hiding pass failed", it) }
                .getOrNull()
            holding.set(hidingFor)
            notify(notification(hidingFor, paused = false, forcingDark = ForceDark.isApplied()))
            logState(hidingFor)
            askForTheServerBack(hidingFor)
            delay(POLL_MS)
        }
    }

    /**
     * Says what is hidden, once per change.
     *
     * Deliberately one line per change and not per pass: this runs every 1.2 seconds, and a log
     * that has to be read through a window of a few minutes is worth nothing if it is a hundred
     * lines of the same thing.
     */
    private fun logState(hidingFor: String?) {
        val state = Signal.entries.joinToString(" ") { signal ->
            when {
                !Hiding.isSignalEnabled(signal) -> "${signal.name}=off"
                Hiding.appsFor(signal).isEmpty() -> "${signal.name}=off-list"
                Hiding.isHidden(signal) -> "${signal.name}=HIDDEN"
                else -> "${signal.name}=visible"
            }
        }
        if (state == lastLoggedState) return
        lastLoggedState = state
        Diag.info(TAG, "hiding for ${hidingFor ?: "nothing"} | $state")
    }

    /**
     * The one piece of the server's own recovery this watch has to add.
     *
     * A list that hides the transport Shizuku is running on kills the server every time the
     * target app is opened. That part is not avoidable - the setting being hidden is the
     * connection - and the watchdog does notice and arm a retry. What it then waits for is
     * `ACTION_USER_PRESENT`: a screen unlock, which does not come, because nobody locks a phone
     * they are holding. Measured on the device: the server stayed down after the transport had
     * been put back, and the only line about it was "leaving the retry armed".
     *
     * This service is the one thing that knows the moment the target app is closed, which is the
     * moment the transport is back and a start can work. So it asks.
     */
    private fun askForTheServerBack(hidingFor: String?) {
        if (hidingFor != null) return
        if (Hiding.shellAvailable()) return
        // Never against a stop the user asked for: the starter clears that flag itself, so
        // asking would quietly turn their stop into a start.
        if (ShizukuSettings.getManuallyStopped()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastStartAttempt < START_RETRY_MS) return
        lastStartAttempt = now

        Diag.info(TAG, "nothing is hidden and the server is down: asking for a start")
        runCatching { ShizukuReceiverStarter.start(applicationContext, forceStart = true) }
            .onFailure { Diag.warn(TAG, "asking for a start failed", it) }
    }

    private fun notify(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createChannelCompat(
            CHANNEL_ID,
            getString(R.string.hiding_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
            showBadge = false
        )
    }

    /**
     * One notification for both states, because it is the same fact: whether the settings are
     * hidden right now, and the one action that changes that.
     */
    private fun notification(
        hidingFor: String?,
        paused: Boolean,
        unavailable: Boolean = false,
        forcingDark: Boolean = false
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(open)
            .setOngoing(true)

        if (unavailable) {
            builder
                .setContentTitle(getString(R.string.hiding_notification_stopped_title))
                .setContentText(getString(R.string.hiding_notification_stopped_text))
                .setOngoing(false)
                // A way back that does not need the lists to be touched again: the reason it
                // stopped was Shizuku, and Shizuku being back is the reason to start again.
                .addAction(
                    0,
                    getString(R.string.hiding_start_again),
                    broadcast(Hiding.ACTION_RESUME_HIDING, 1)
                )
            return builder.build()
        }

        if (paused) {
            builder
                .setContentTitle(getString(R.string.hiding_notification_paused_title))
                .setContentText(getString(R.string.hiding_notification_paused_text))
                .addAction(
                    0,
                    getString(R.string.hiding_resume),
                    broadcast(Hiding.ACTION_RESUME_HIDING, 1)
                )
            return builder.build()
        }

        // Two states and two sentences, because they are not the same news: a notification that
        // says the settings are hidden while they are not would have the user looking for a
        // problem that is not there.
        if (hidingFor == null) {
            // Two features, one notification, because it is the same fact either way: something is
            // being held for an app that is open, and this is how the user gets it back. Which of
            // the two is being said, though, and not assumed: this used hiding's words whenever
            // nothing was hidden yet, which was untrue for as long as the service was up only for
            // force dark - and it is up for force dark whenever that list is armed, whether or not
            // an app on it is in front.
            //
            // Neither armed reaches here only on the way to stopping, where the notification is
            // taken down with the service a moment later.
            val hiding = Hiding.isActive()
            builder
                .setContentTitle(
                    getString(
                        if (hiding) R.string.hiding_notification_idle_title
                        else R.string.force_dark_notification_title
                    )
                )
                .setContentText(
                    getString(
                        when {
                            hiding -> R.string.hiding_notification_watching
                            forcingDark -> R.string.force_dark_notification_text
                            else -> R.string.force_dark_notification_watching
                        }
                    )
                )
            return builder.build()
        }

        builder
            .setContentTitle(getString(R.string.hiding_notification_title))
            .setContentText(getString(R.string.hiding_notification_text))
            .addAction(
                0,
                getString(R.string.hiding_restore),
                broadcast(Hiding.ACTION_RESTORE_HIDING, 0)
            )

        return builder.build()
    }

    private fun broadcast(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, HidingRestoreReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

}
