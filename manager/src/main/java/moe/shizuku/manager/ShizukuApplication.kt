package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.isUserUnlockedCompat
import moe.shizuku.manager.manage.Activities
import moe.shizuku.manager.manage.ForceDark
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.receiver.UserUnlockedStarter
import moe.shizuku.manager.receiver.WatchdogAlarmReceiver
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.AppLocale
import moe.shizuku.manager.utils.BootTimeline
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class ShizukuApplication : Application() {

    companion object {

        init {
            logd("ShizukuApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }

        lateinit var application: ShizukuApplication
            private set

        lateinit var appContext: Context
            private set

    }

    private fun init(context: Context) {
        ShizukuSettings.initialize(context)
        // The first line of the boot timeline: what woke this process, and whether the user had
        // unlocked by then. A start cannot begin before this line, so how late it is is how late
        // everything after it is.
        Diag.info(
            "ShizukuApplication",
            "Process started, ${BootTimeline.sinceBoot()}, user unlocked: ${context.isUserUnlockedCompat()}"
        )
        // A reboot starts the server's run over: with start on boot off it is down on purpose,
        // and the watchdog must not read that as a crash. The app is started at boot by
        // WorkManager even when the boot receiver is disabled, so this is where it is noticed.
        ShizukuSettings.noteBootIfNeeded(context)
        // The starter writes its own log into this app's external directories when a device
        // exploit runs it, and they are only created on first use: create both now, so the
        // paths exist before anything tries to write to them. Without this a start that
        // failed had nowhere to leave its account of itself. The media directory matters
        // most, since that is the one another app's process is allowed to write.
        runCatching { getExternalFilesDir(null)?.mkdirs() }
        runCatching { getExternalMediaDirs()?.firstOrNull()?.mkdirs() }
        // The preference is the source of truth, so re-apply it to the boot receiver here:
        // installs from before this read the component back and can be stuck disabled with
        // start on boot switched on.
        ShizukuSettings.updateBootReceiver(context)
        // The chosen language, which lives in two places depending on the release: the framework
        // per-app locale from Android 13, and LocaleDelegate - what the activities wrap their
        // context with - below it. AppLocale knows which is which.
        AppLocale.initialize(this)
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())

        // Not before the first unlock: the watchdog is not direct-boot aware, so a process that woke
        // earlier cannot see the service (the platform answers "not found" and the call used to be
        // counted as a start). UserUnlockedStarter starts it at the unlock instead.
        if (ShizukuSettings.getWatchdog() && context.isUserUnlockedCompat()) WatchdogService.start(context)
        // A process that woke before the first unlock has no WorkManager to start with, so the
        // boot start is made at the unlock and not left for BOOT_COMPLETED to find.
        UserUnlockedStarter.registerIfLocked(context)
        // Re-armed on every startup as well as at boot, because an update replaces the app and
        // takes its alarms with it: without this a phone that updates while the watchdog is on
        // would lose the backstop until its next reboot, which is exactly the kind of window the
        // backstop exists to close. Safe to call when the watchdog is off - it checks.
        WatchdogAlarmReceiver.schedule(context)

        // Hiding and force dark are both rules about the app in front, and the same watch holds
        // both. A process that died while either was armed starts it again rather than leaving the
        // device with nothing holding what it set: a setting left hidden, or the force-dark
        // switches left on for a phone that is not showing the app they were meant for. Off the
        // main thread, because it asks the shell, and the platform may refuse a foreground service
        // started from the background - which is what the start it does is for.
        if (Hiding.isActive() || ForceDark.hasWorkToDo()) {
            thread(name = "hiding-watch-restart") { HidingWatchService.refresh(context) }
        }

        // An activity launched through the assistant is swapped out and swapped back within a
        // couple of seconds, and the only way that goes wrong for good is the process dying in
        // between. The backup is on disk for exactly that, and this is the only thing that reads
        // it: leaving somebody without an assistant is far worse than not launching the activity.
        thread(name = "activities-assistant-restore") { Activities.restorePending() }
    }

    /**
     * Writes an uncaught exception into the app's own log before the process dies.
     *
     * A crash otherwise reaches only logcat, which a TV without a computer attached cannot read -
     * and a crash in this process is also what makes Android switch the pairing accessibility
     * service back off the moment it is turned on, with nothing on screen to say why. The first
     * frames go into the line, because the class and message alone rarely say where it was.
     * The platform's own handler still runs afterwards, so the crash is reported as before.
     */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val frames = generateSequence(error) { it.cause }
                    .take(3)
                    .joinToString(" | caused by ") { cause ->
                        "${cause.javaClass.name}: ${cause.message} at " +
                            cause.stackTrace.take(6).joinToString(" < ")
                    }
                Diag.error("Crash", "Uncaught on ${thread.name}: $frames")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        appContext = applicationContext
        recordCrashes()
        init(this)
    }

}
