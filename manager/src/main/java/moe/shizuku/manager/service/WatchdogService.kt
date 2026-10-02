package moe.shizuku.manager.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.receiver.WatchdogAlarmReceiver
import moe.shizuku.manager.start.runningMethodLabel
import moe.shizuku.manager.start.runningMethodSuffix
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.createChannelCompat
import java.util.concurrent.atomic.AtomicBoolean
import moe.shizuku.manager.utils.Diag

class WatchdogService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingRestart = false

    /** Set while the user owes a notification that an outage is over. */
    @Volatile
    private var awaitingRecovery = false

    /** Only one restart at a time, and not one every few seconds for the same outage. */
    private val restartInFlight = AtomicBoolean(false)

    @Volatile
    private var lastRestartAt = 0L

    /**
     * Counts failed starts, and stops the retrying once a run of them means the start is the
     * thing that is broken. See [StartCircuitBreaker] for the failure it answers.
     */
    private val breaker = StartCircuitBreaker(BREAKER_MAX_ATTEMPTS, BREAKER_WINDOW_MS)

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        // Deliberately not cleared when the server is seen running again: the binder that
        // reports that is the sticky one, and during a replacement it arrives while the old
        // server is still up, an instant before the death the mark was set for. Clearing
        // there wiped the mark and the death came through as a crash after all. The mark
        // carries its own deadline, so a stale one drops itself.
        when (state) {
            ShizukuStateMachine.State.CRASHED ->
                // Three ways for this to be somebody's decision rather than a crash: the
                // server is being replaced, or it was stopped on purpose, or a start that
                // asked for it is already on its way. Reporting any of those as a crash and
                // starting again over the top of it is what made a deliberate restart look
                // like Shizuku falling over, twice.
                when {
                    WatchdogGuard.consumeExpectedDeath() ->
                        Diag.debug(TAG, "Server went down as expected, nothing to report")

                    ShizukuSettings.getManuallyStopped() ->
                        Diag.debug(TAG, "Server was stopped on purpose, nothing to report")

                    else -> {
                        Diag.warn(TAG, "Server died: reporting it and starting it again")
                        showCrashNotification()
                        awaitingRecovery = true
                        attemptRestart()
                    }
                }

            ShizukuStateMachine.State.RUNNING -> {
                // Server is back, so there is nothing left for the screen-on retry to do,
                // and any outage the user was told about is over.
                pendingRestart = false
                // A server that came back - however it came back - is proof the breaker was
                // counting failures that were not permanent, so the count starts over. The
                // reset is unconditional on purpose: a server that is up now may have come back
                // while the breaker was open, and a count left standing would stop the next
                // genuine crash from being recovered.
                resetBreaker()
                if (awaitingRecovery) {
                    awaitingRecovery = false
                    showRecoveryNotification()
                }
            }

            else -> Unit
        }
    }

    /**
     * Screen-on receiver: when the user turns the screen on after a crash, trigger
     * a fresh restart. Crash-time restart attempts frequently fail (mDNS / wireless
     * debugging don't work with the screen off) and WorkManager then accumulates
     * exponential backoff, making the restart indefinitely slow.
     *
     * One of two triggers now, and it was the only one: see [startRecoveryLoop] for what
     * happened to every server that died while the phone was already awake.
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            if (pendingRestart) {
                Diag.debug(TAG, "Screen unlocked with pending restart retrying now")
                attemptRestart()
            } else {
                // Self-heal on unlock: catches deaths whose CRASHED transition was
                // never observed (e.g. the manager process was dead at the time).
                checkServerAndRestartIfDead()
            }
        }
    }

    /**
     * Event-based crash detection alone is not enough: the CRASHED transition is
     * lost if the manager process was dead when the server died, and the state
     * machine boots as STOPPED after every process restart. So whenever the
     * watchdog (re)starts, on screen unlock, and on the recovery loop's own timer,
     * probe whether the server is actually running and restart it if not, unless the
     * user stopped it on purpose (manual stop sets the suppression flag; any start
     * request or a confirmed RUNNING state clears it).
     */
    private fun checkServerAndRestartIfDead() {
        serviceScope.launch {
            // Grace period so a sticky binder delivered right after process start
            // can flip the state to RUNNING before we probe it.
            delay(BINDER_GRACE_MS)
            if (ShizukuSettings.getManuallyStopped()) return@launch
            // A replacement is in flight: the old server being gone right now is the point
            // of it, and starting another one would race it.
            if (WatchdogGuard.isExpectingDeath()) return@launch
            when (ShizukuStateMachine.get()) {
                // A start/stop appears to be in flight. Give it ample time to
                // resolve instead of skipping outright a state stuck at
                // STARTING/STOPPING from a silently failed operation would
                // otherwise disable this check forever.
                ShizukuStateMachine.State.STARTING,
                ShizukuStateMachine.State.STOPPING -> {
                    delay(IN_FLIGHT_GRACE_MS)
                    if (ShizukuSettings.getManuallyStopped()) return@launch
                }
                else -> Unit
            }
            if (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING) {
                Diag.debug(TAG, "Server not running while watchdog active attempting restart")
                attemptRestart()
            }
        }
    }

    /**
     * The recovery that does not wait for an unlock, and the reason it exists.
     *
     * The unlock trigger is a real signal - mDNS and wireless debugging genuinely do not work
     * with the screen off - but it was the only retry there was, and that is not enough. A
     * server that dies while the phone is in the user's hand, being used, is never unlocked, so
     * nothing retried it and Shizuku stayed down until the app was opened; the start-up probe
     * inside this service is what made opening the app look like the only cure.
     *
     * Reported from the device and reproduced: with the manager open and the server killed, the
     * watchdog's own attempt collapsed and the log said only "leaving the retry armed", waiting
     * for an unlock that was never going to come.
     *
     * So the same probe runs on a timer for as long as the watchdog is up. It is cheap - one
     * binder ping - it skips everything a deliberate stop or an in-flight replacement skips, and
     * [attemptRestart] keeps its own cooldown, so a server that cannot come back yet is retried
     * rather than nagged.
     */
    private fun startRecoveryLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(RECOVERY_POLL_MS)
                if (ShizukuSettings.getManuallyStopped()) continue
                // The local answer first because it is free; the probe below asks the binder.
                if (ShizukuStateMachine.isRunning()) continue
                checkServerAndRestartIfDead()
            }
        }
    }

    private fun attemptRestart() {
        if (WatchdogGuard.isExpectingDeath() || ShizukuSettings.getManuallyStopped()) {
            Diag.debug(TAG, "Restart attempt skipped: one is expected, or the stop was deliberate")
            return
        }

        // The breaker is open, so the last [BREAKER_MAX_ATTEMPTS] starts all failed. Trying
        // again is what the user was watching happen, so this is where it stops: the notice
        // has been posted, and the way back is a start they asked for themselves.
        if (breaker.open) {
            Diag.debug(TAG, "Restart attempt skipped: the breaker is open")
            return
        }

        // A list is holding the debugging toggle off for an app the user has open. A start could
        // not work - it needs that very toggle - and the attempt would write the toggle back on,
        // which is the opposite of what the list is for. Waited out rather than tried: the watch
        // asks for a start itself the moment nothing is hidden any more.
        if (HidingWatchService.holding()) {
            Diag.debug(TAG, "Restart attempt skipped: hiding is holding the debugging toggle off")
            pendingRestart = true
            return
        }

        // The binder listener and the unlock probe can land here for the same outage, and a
        // restart that keeps failing must not turn into a start every few seconds.
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartAt < RESTART_COOLDOWN_MS) {
            Diag.debug(TAG, "Restart attempt skipped: one was made moments ago")
            return
        }
        if (!restartInFlight.compareAndSet(false, true)) {
            Diag.debug(TAG, "Restart attempt skipped: one is already in flight")
            return
        }
        lastRestartAt = now
        recordAttempt(now)

        // Cancel any prior WorkManager attempt so we don't inherit exponential backoff
        WorkManager.getInstance(applicationContext).cancelUniqueWork("adb_start_worker")

        serviceScope.launch {
            try {
                val tcpPort = EnvironmentUtils.getAdbTcpPort()
                val usbMethod =
                    ShizukuSettings.getStartMethod() == ShizukuSettings.StartMethod.USB
                if (usbMethod && tcpPort > 0 && EnvironmentUtils.isUsbDebuggingEnabled()) {
                    // Direct TCP restart for the USB method fastest path, no mDNS
                    // needed. A wireless setup must restart over TLS below: taking the
                    // classic port here is what made a wireless setup come back
                    // reporting itself as USB debugging after a crash.
                    pendingRestart = false
                    AdbStarter.startAdb(applicationContext, tcpPort)
                    Starter.waitForBinder()
                } else {
                    // mDNS-based restart via WorkManager. Mark pending so the
                    // screen-on receiver can retry if this attempt fails.
                    pendingRestart = true
                    ShizukuReceiverStarter.start(applicationContext, forceStart = true)
                }
            } catch (e: Exception) {
                Diag.warn(TAG, "Direct restart failed, falling back", e)
                pendingRestart = true
                ShizukuReceiverStarter.start(applicationContext, forceStart = true)
            } finally {
                restartInFlight.set(false)
            }

            // A start reports success as soon as the binder appears, and the interesting case
            // is the one that appears and then goes away again, so the restart is checked
            // rather than trusted: until the server is actually there, the retry stays armed.
            delay(RECOVERY_CHECK_MS)
            ShizukuStateMachine.update()
            if (ShizukuStateMachine.isRunning()) {
                pendingRestart = false
            } else if (!ShizukuSettings.getManuallyStopped()) {
                Diag.warn(TAG, "Restart did not bring the server back, leaving the retry armed")
                pendingRestart = true
            }
        }
    }

    /**
     * Counts one restart attempt, and says so when it is the one that opens the breaker.
     *
     * Attempts from any trigger count, including a user's own tap. Five deliberate retries in a
     * row inside one window would have to mean four of them failed within twenty seconds of each
     * other, so the breaker is not going to open under somebody who is simply trying again.
     */
    private fun recordAttempt(now: Long) {
        if (!breaker.record(now)) return
        pendingRestart = false
        Diag.warn(
            TAG,
            "Giving up: $BREAKER_MAX_ATTEMPTS starts in under " +
                "${BREAKER_WINDOW_MS / 1000}s and the server is still down"
        )
        showGaveUpNotification()
    }

    /** A server that is running again clears the count, and re-arms the breaker with it. */
    private fun resetBreaker() {
        breaker.reset()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        sendWatchdogChangedBroadcast(applicationContext, true)
        // The state it starts in is worth a line: a watchdog that came up after the server
        // was already gone is the case it cannot see a transition for, and that is what the
        // unlock probe is for.
        Diag.info(TAG, "Watchdog started, server is ${ShizukuStateMachine.get()}")
        // A notice that the watchdog was not running is answered by the watchdog running: this is
        // every path back in, so clearing it here covers the user's tap, a boot and a start asked
        // for by anything else, without each of them having to remember to.
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID_NOT_RUNNING)
        ShizukuStateMachine.addListener(stateListener)
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        startRecoveryLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_NOW) {
            // The breaker exists to stop the app retrying on its own, not to refuse the user.
            // A start from here goes through the same path as the home screen's button, so the
            // server is asked for afresh and the count starts over - and if that start works,
            // the RUNNING transition clears the breaker and posts nothing further.
            Diag.info(TAG, "Start asked for from the notification, resetting the breaker")
            resetBreaker()
            ShizukuReceiverStarter.start(
                applicationContext,
                forceStart = true,
                userInitiated = true
            )
            return START_STICKY
        }
        if (intent?.action == "ACTION_STOP_SERVICE") {
            // User explicitly turned the watchdog off via the notification persist
            // the setting directly instead of calling setWatchdog() (which would
            // redundantly call stop() while we're already stopping via stopSelf).
            ShizukuSettings.getPreferences().edit()
                .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, false).apply()
            // This path writes the preference directly rather than going through
            // ShizukuSettings.setWatchdog, so it has to cancel the backstop itself: an alarm left
            // armed would find the watchdog gone and start it again, switching back on the thing
            // the user just switched off.
            WatchdogAlarmReceiver.cancel(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID_WATCHDOG,
                buildNotification()
            )
        }
        checkServerAndRestartIfDead()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        pendingRestart = false
        ShizukuStateMachine.removeListener(stateListener)
        runCatching { unregisterReceiver(screenOnReceiver) }
        isRunning.set(false)
        sendWatchdogChangedBroadcast(applicationContext, false)
        // Do NOT persist watchdog=false here: onDestroy runs both when the user
        // manually stops Shizuku (temporary) and when the notification stop button
        // is used (permanent). Only the notification stop button and the settings
        // toggle should persist the preference.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // The v2 suffix is the point, not an oversight: a channel's settings are frozen
        // once it exists, so turning the badge off on the old id would do nothing for
        // anyone who already has it. The service is always running, so a dot over it says
        // nothing except that something is permanently there.
        val channelId = "shizuku_watchdog_v2"
        val channelName = "Watchdog"

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createChannelCompat(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW,
            showBadge = false
        )

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchdogService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Say how Shizuku is running, like every other status notification does.
        val runningMethod = runningMethodLabel()

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_running))
            .apply { if (runningMethod != null) setContentText(runningMethod) }
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(
                R.drawable.ic_close_24,
                getString(R.string.watchdog_turn_off),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun showCrashNotification() {
        val channelId = CRASH_CHANNEL_ID
        val channelName = "Crash Reports"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createChannelCompat(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)

        val learnMoreIntent = Intent(Intent.ACTION_VIEW).apply {
            setData(Uri.parse("https://github.com/thedjchi/Shizuku/wiki#shizuku-keeps-stopping-randomly"))
        }
        val learnMorePendingIntent = PendingIntent.getActivity(this, 0, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(applicationContext)
        val disablePendingIntent = PendingIntent.getActivity(this, 0, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_shizuku_crashed_title))
            .setContentText(
                getString(R.string.watchdog_shizuku_crashed_text) + runningMethodSuffix()
            )
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(learnMorePendingIntent)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }

    /**
     * The other half of the crash notification, and only ever the other half: it is posted
     * when a restart answered an outage the user was already told about, so nothing about a
     * service that came and went on its own reaches anyone.
     */
    private fun showRecoveryNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createChannelCompat(CRASH_CHANNEL_ID, "Crash Reports", NotificationManager.IMPORTANCE_DEFAULT)

        val notification = NotificationCompat.Builder(this, CRASH_CHANNEL_ID)
            .setContentTitle(getString(R.string.watchdog_shizuku_recovered_title))
            .setContentText(
                getString(R.string.watchdog_shizuku_recovered_text) + runningMethodSuffix()
            )
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID_RECOVERY, notification)
        // The alarm it answers is spent either way.
        nm.cancel(NOTIFICATION_ID_CRASH)
    }

    /**
     * The other outcome of an outage, and the one that used to be silent.
     *
     * Says how many starts were tried rather than only that it stopped trying, because the
     * count is the evidence: one failed start is a phone having a bad moment, five in twenty
     * seconds is a reason that will not fix itself. The action is a plain Start, which goes
     * through the same path as the home screen's button and so clears [gaveUp] with it - the
     * breaker is meant to stop the app retrying on its own, never to take the decision away.
     */
    private fun showGaveUpNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createChannelCompat(CRASH_CHANNEL_ID, "Crash Reports", NotificationManager.IMPORTANCE_DEFAULT)

        val notification = NotificationCompat.Builder(this, CRASH_CHANNEL_ID)
            .setContentTitle(getString(R.string.watchdog_given_up_title))
            .setContentText(
                getString(R.string.watchdog_given_up_text, BREAKER_MAX_ATTEMPTS) +
                    runningMethodSuffix()
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.watchdog_given_up_text, BREAKER_MAX_ATTEMPTS) +
                        runningMethodSuffix()
                )
            )
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .addAction(
                0,
                getString(R.string.watchdog_given_up_action_start),
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, WatchdogService::class.java).setAction(ACTION_START_NOW),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        nm.notify(NOTIFICATION_ID_GAVE_UP, notification)
        // It replaces the crash notice rather than stacking under it: both describe the same
        // outage, and this is the later and more useful word on it.
        nm.cancel(NOTIFICATION_ID_CRASH)
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val BINDER_GRACE_MS = 3000L
        private const val IN_FLIGHT_GRACE_MS = 90_000L

        /** Long enough for a wireless start to have got somewhere, short enough to be news. */
        private const val RECOVERY_CHECK_MS = 15_000L

        /** Two restarts within this are the same outage, not two. */
        private const val RESTART_COOLDOWN_MS = 15_000L

        /**
         * How often a server that is down and not coming back is tried again.
         *
         * Half a minute: long enough that a start which needs something the device does not
         * have yet is not asked for constantly, short enough that it lands while the user is
         * still the one who noticed.
         */
        private const val RECOVERY_POLL_MS = 30_000L

        /**
         * How many failed starts inside [BREAKER_WINDOW_MS] mean the start itself is broken.
         *
         * Five, matching the same idea upstream in ReShizukuX: enough that a phone which
         * merely has not got its Wi-Fi back yet is not cut off, few enough that the loop is
         * stopped in the first couple of minutes rather than after a night of it.
         */
        private const val BREAKER_MAX_ATTEMPTS = 5

        /**
         * The span those attempts have to fall inside, and it has to be wide enough for the
         * retries themselves: the poll is 30s and the cooldown 15s, so a genuine outage being
         * retried lands roughly one attempt per poll - a little under three attempts a minute,
         * which is about a minute and a half to open the breaker. Narrower than this and the
         * poll's own spacing would never fill it.
         */
        private const val BREAKER_WINDOW_MS = 120_000L

        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002
        private const val NOTIFICATION_ID_RECOVERY = 1003
        private const val NOTIFICATION_ID_GAVE_UP = 1004
        /** Posted by the alarm backstop when a revive was refused; cleared by [onCreate]. */
        const val NOTIFICATION_ID_NOT_RUNNING = 1005
        const val CRASH_CHANNEL_ID = "crash_reports"
        const val ACTION_WATCHDOG_CHANGED = "WATCHDOG_CHANGED"

        /** A start the user asked for from the notification, which the breaker must not block. */
        const val ACTION_START_NOW = "ACTION_START_NOW"
        const val EXTRA_WATCHDOG_STATUS = "status"

        private val isRunning = AtomicBoolean(false)

        // Broadcast so automation apps (e.g. MacroDroid/Tasker) can react to
        // the watchdog being enabled or disabled.
        @JvmStatic
        fun sendWatchdogChangedBroadcast(context: Context, enabled: Boolean) {
            val intent = Intent("${context.packageName}.$ACTION_WATCHDOG_CHANGED").apply {
                putExtra(EXTRA_WATCHDOG_STATUS, if (enabled) 1 else 0)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        }

        /**
         * Asks the platform to start the watchdog, and says whether it agreed.
         *
         * The answer matters to exactly one caller. Everywhere else the request comes from
         * something in front of the user - the settings switch, the app starting, a reboot - and
         * the platform allows those starts, so the failure is worth a log line and nothing more.
         * The alarm backstop is the exception: it runs in the background, where an app targeting
         * Android 12 or later may not start a foreground service at all, and it carries the one
         * duty that has no alternative - bringing the watchdog back. A silent refusal there would
         * look exactly like the outage it exists to end, so it is told, and it can tell the user.
         *
         * @return false when the start was refused rather than made.
         */
        @JvmStatic
        fun start(context: Context): Boolean {
            return try {
                // ContextCompat, not the plain call: the two-argument startForegroundService
                // only exists from Android 8 (API 26), and a NoSuchMethodError is an Error the
                // catch below cannot see. The compat helper falls back to startService on the
                // platforms that predate it.
                ContextCompat.startForegroundService(context, Intent(context, WatchdogService::class.java))
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on Android 12+, which is an ordinary
                // outcome for a background caller rather than an error in this app.
                Diag.error("ShizukuApplication", "Failed to start WatchdogService: ${e.message}")
                false
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}
