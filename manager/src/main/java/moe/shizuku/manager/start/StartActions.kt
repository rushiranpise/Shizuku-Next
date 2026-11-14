package moe.shizuku.manager.start

import android.content.Context
import androidx.work.WorkManager
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingRequiredException
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.service.LockedBootStartService
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.worker.AdbStartWorker
import rikka.shizuku.Shizuku

/**
 * The one-tap repair behind the "no ADB port" card: open the port and then start over
 * it, so the user doesn't have to go and run a command on a computer.
 *
 * The port can only be created over a live connection and the only one the app can
 * raise by itself is the wireless one, which needs Wi-Fi so when there is no network
 * this reports back with the Wi-Fi kind, which turns the card's action into "Connect"
 * and leaves the user one tap from the actual fix.
 */
/**
 * Stops the server the way the app's own stop button does.
 *
 * The stop is marked as deliberate before the server goes, because the watchdog's whole job is to
 * bring a server back and an intentional stop is the one case where it must not. Whatever was
 * queued to start it is dropped for the same reason: a start sitting in WorkManager would undo
 * this a moment later.
 *
 * Here rather than only in the broadcast receiver that used to hold it, so a tile or a widget can
 * stop the service by the same rules instead of a second copy of them.
 *
 * @return whether the server is gone, or never was there.
 */
fun stopShizuku(context: Context): Boolean {
    ShizukuSettings.setManuallyStopped(true)
    WorkManager.getInstance(context).cancelUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
    // And the start the foreground service began before the unlock, if it is still going.
    LockedBootStartService.cancelRun("stopped by the user") {}

    if (!ShizukuStateMachine.isRunning()) return true

    ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
    return runCatching { Shizuku.exit() }.isSuccess
}

suspend fun openAdbPortAndStart(context: Context) {
    StartStatusReporter.starting()

    val result = runCatching {
        AdbStarter.openTcpPort(context, ShizukuSettings.getTcpPort())
    }

    if (result.getOrDefault(false)) {
        ShizukuReceiverStarter.start(
            context,
            userInitiated = true,
            startMethod = ShizukuSettings.StartMethod.USB,
            reason = "start-actions"
        )
        return
    }

    val message = context.usbStartNoPortMessage()
    when {
        result.exceptionOrNull() is AdbPairingRequiredException ->
            StartStatusReporter.failed(
                context.getString(R.string.start_failed_pairing_required),
                StartFailureKind.PAIRING
            )

        // Nothing to borrow: connecting is the fix, so offer that.
        !EnvironmentUtils.isWifiConnected() ->
            StartStatusReporter.failed(message, StartFailureKind.WIFI)

        // Wi-Fi is there and it still didn't work offer the same fix again.
        else -> StartStatusReporter.failed(message, StartFailureKind.PORT)
    }
}
