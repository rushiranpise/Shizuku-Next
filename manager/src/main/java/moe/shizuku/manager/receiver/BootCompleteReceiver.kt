package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.isUserUnlockedCompat
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.start.reapplyAdbWithoutDeveloperOptionsIfEnabled
import moe.shizuku.manager.utils.BootTimeline
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> onLockedBoot(context)
            Intent.ACTION_BOOT_COMPLETED -> onBootCompleted(context)
        }
    }

    /**
     * The first signal of a boot, delivered before the user has unlocked.
     *
     * A wireless start is begun here, in [LockedBootStartService]: it needs the saved settings and
     * key (device-protected storage), the system settings and the network, none of which wait for
     * the unlock, and it goes through no WorkManager, whose database does. Every other method keeps
     * waiting for the unlock, which [UserUnlockedStarter] is registered for. The start is asked for
     * through [ShizukuReceiverStarter] like any other, so that it is one start at a time and shows
     * up in the log as a request with a reason.
     *
     * The watchdog is not started here: it is not direct-boot aware, so the platform cannot find it
     * before the unlock. [UserUnlockedStarter] starts it then, and the alarm backstop below is the
     * one thing of the watchdog's that can be armed now.
     */
    private fun onLockedBoot(context: Context) {
        Diag.info(TAG, "Locked boot completed received${BootTimeline.suffix()}")

        // Settings.Global writes go to the system, not to this app's storage, so they do not
        // depend on the unlock, and the toggles they put back are what a start needs first.
        reapplyAdbWithoutDeveloperOptionsIfEnabled(context)

        val startOnBoot = ShizukuSettings.getStartOnBoot(context)
        when (decideStartNow(
            userUnlocked = context.isUserUnlockedCompat(),
            startWanted = startOnBoot,
            manuallyStopped = ShizukuSettings.getManuallyStopped(),
            canStartLocked = canStartBeforeUnlock(
                ShizukuSettings.getStartMethod(),
                // A classic port left open across the reboot is all a USB start needs.
                classicPortOpen = EnvironmentUtils.getAdbTcpPort() > 0
            )
        )) {
            StartNowDecision.START_LOCKED ->
                ShizukuReceiverStarter.start(context, reason = "locked-boot")

            StartNowDecision.SKIP_STOPPED ->
                Diag.info(TAG, "Start before the unlock skipped: the service was stopped deliberately")

            StartNowDecision.SKIP_NOT_WANTED ->
                Diag.info(TAG, "Start before the unlock skipped: start on boot is off")

            // The method needs the unlock (or the user unlocked already, and BOOT_COMPLETED is
            // about to ask): nothing to do here.
            StartNowDecision.DEFER_UNTIL_UNLOCK, StartNowDecision.START -> Unit
        }

        WatchdogAlarmReceiver.schedule(context)
    }

    private fun onBootCompleted(context: Context) {
        Diag.info(TAG, "Boot completed received${BootTimeline.suffix()}")

        // A reboot clears the ADB toggles, so the setting that keeps them on has to be put
        // back before anything tries to use them.
        reapplyAdbWithoutDeveloperOptionsIfEnabled(context)

        // The receiver is enabled by either start on boot or ADB without Developer options,
        // so its presence says nothing about this setting; ask the setting itself.
        if (ShizukuSettings.getStartOnBoot(context)) ShizukuReceiverStarter.start(context, reason = "boot")
        if (ShizukuSettings.getWatchdog()) WatchdogService.start(context)
        // A backstop for the unlock hook: a process woken before the unlock may have missed it.
        // Starting a watch that is already running only delivers it another start command.
        HidingWatchService.startIfArmed(context, "boot completed")
        // Alarms do not survive a reboot, so the watchdog's backstop has to be armed again here
        // or the first process death after a restart is the one nothing comes back from. The
        // call checks the setting itself, so it is safe to make either way.
        WatchdogAlarmReceiver.schedule(context)
    }

    private companion object {
        const val TAG = "BootCompleteReceiver"
    }
}
