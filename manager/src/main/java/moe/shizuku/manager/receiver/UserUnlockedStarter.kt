package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.ensureWorkManager
import moe.shizuku.manager.ktx.isUserUnlockedCompat
import moe.shizuku.manager.ktx.registerNotExportedReceiver
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.service.LockedBootStartService
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.BootTimeline
import moe.shizuku.manager.utils.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Begins the boot start the moment the user unlocks, instead of when the system gets round to
 * BOOT_COMPLETED.
 *
 * The app is direct-boot aware, so the system can wake the process well before the first unlock
 * (a client asking for the binder, the locked-boot broadcast). Everything that starts a server goes
 * through WorkManager, whose database cannot be read until then, so a request made earlier is
 * deferred rather than lost, and this is what makes the request again. BOOT_COMPLETED follows some
 * seconds later and finds the start already in flight, which is what [decideStart] is for.
 *
 * ACTION_USER_UNLOCKED cannot be declared in the manifest, which is why this is a receiver
 * registered by the running process. A process that comes up after the unlock has nothing to
 * wait for and registers nothing; the watchdog and the boot receiver cover it as they always did.
 */
object UserUnlockedStarter {

    private const val TAG = "UserUnlockedStarter"

    private var registered: BroadcastReceiver? = null

    /** Waits for the unlock when there is one to wait for, and something that would want it. */
    @Synchronized
    fun registerIfLocked(context: Context) {
        if (registered != null || context.isUserUnlockedCompat()) return
        if (!ShizukuSettings.getStartOnBoot(context) && !ShizukuSettings.getWatchdog() &&
            !HidingWatchService.isArmed()
        ) return

        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                finish(app)
            }
        }
        registered = receiver
        app.registerNotExportedReceiver(
            receiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED)
        )
        Diag.info(TAG, "Waiting for the user to unlock${BootTimeline.suffix()}")
        // Unlocked between the check above and the registration: the broadcast has already gone.
        if (app.isUserUnlockedCompat()) finish(app)
    }

    @Synchronized
    private fun finish(context: Context) {
        val receiver = registered ?: return
        registered = null
        runCatching { context.unregisterReceiver(receiver) }
        onUnlocked(context)
    }

    private fun onUnlocked(context: Context) {
        Diag.info(TAG, "User unlocked${BootTimeline.suffix()}")

        // Anything that may start a server reaches WorkManager, which the system could not set up
        // while the process was running without credential storage.
        context.ensureWorkManager()

        val startOnBoot = ShizukuSettings.getStartOnBoot(context)
        when (decideStartNow(true, startOnBoot, ShizukuSettings.getManuallyStopped())) {
            StartNowDecision.START ->
                ShizukuReceiverStarter.start(context, reason = "user-unlocked")

            StartNowDecision.SKIP_STOPPED ->
                Diag.debug(TAG, "Start at unlock skipped: the service was stopped deliberately")

            StartNowDecision.SKIP_NOT_WANTED,
            StartNowDecision.DEFER_UNTIL_UNLOCK,
            StartNowDecision.START_LOCKED -> Unit
        }

        // A start the foreground service began before the unlock is the better start while it is
        // making progress, and the request above has joined it. When it is not, it is replaced.
        watchLockedRun(context, SystemClock.elapsedRealtime())

        // The watchdog put a restart off until now ("Restart deferred until the user is unlocked").
        // Starting it again re-runs its probe, which joins the start above if there is one.
        if (ShizukuSettings.getWatchdog()) WatchdogService.start(context)

        // The hiding watch could not be started before the unlock either (see
        // [HidingWatchService.startIfArmed]), and this process will not run its start-up again.
        HidingWatchService.startIfArmed(context, "user unlocked")
    }

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Gives a start begun before the unlock [LOCKED_RUN_GRACE_MS] after it to reach STARTING, and
     * replaces it with the ordinary start if it has not (see [decideLockedRunAtUnlock]).
     *
     * Everything that could ask for a start in the meantime joins the one in the service, so the
     * single slot is held by it; this is what makes sure that holding it is not the same as having
     * a start under way.
     */
    private fun watchLockedRun(context: Context, unlockedAt: Long) {
        if (!LockedBootStartService.isRunning()) return
        watchScope.launch {
            while (true) {
                val state = ShizukuStateMachine.get()
                val decision = decideLockedRunAtUnlock(
                    runInFlight = LockedBootStartService.isRunning(),
                    reachedStarting = state == ShizukuStateMachine.State.STARTING ||
                        state == ShizukuStateMachine.State.RUNNING,
                    msSinceUnlock = SystemClock.elapsedRealtime() - unlockedAt
                )
                when (decision) {
                    LockedRunAtUnlock.NONE, LockedRunAtUnlock.KEEP -> return@launch

                    LockedRunAtUnlock.WAIT -> delay(WATCH_INTERVAL_MS)

                    LockedRunAtUnlock.REPLACE -> {
                        Diag.warn(
                            TAG,
                            "The start begun before the unlock has not reached STARTING " +
                                "${LOCKED_RUN_GRACE_MS / 1000} s after it; starting normally" +
                                BootTimeline.suffix()
                        )
                        LockedBootStartService.cancelRun("it did not reach STARTING in time") {
                            ShizukuReceiverStarter.start(context, reason = "locked-start-stalled")
                        }
                        return@launch
                    }
                }
            }
        }
    }

    private const val WATCH_INTERVAL_MS = 500L

}
