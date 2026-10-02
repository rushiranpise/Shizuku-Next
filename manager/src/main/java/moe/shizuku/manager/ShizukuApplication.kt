package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.manage.Activities
import moe.shizuku.manager.manage.ForceDark
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.receiver.WatchdogAlarmReceiver
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.AppLocale
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

        if(ShizukuSettings.getWatchdog()) WatchdogService.start(context)
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

    override fun onCreate() {
        super.onCreate()
        application = this
        appContext = applicationContext
        init(this)
    }

}
