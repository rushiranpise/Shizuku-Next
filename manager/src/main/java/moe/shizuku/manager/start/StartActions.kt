package moe.shizuku.manager.start

import android.content.Context
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingRequiredException
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.EnvironmentUtils

/**
 * The one-tap repair behind the "no ADB port" card: open the port and then start over
 * it, so the user doesn't have to go and run a command on a computer.
 *
 * The port can only be created over a live connection and the only one the app can
 * raise by itself is the wireless one, which needs Wi-Fi so when there is no network
 * this reports back with the Wi-Fi kind, which turns the card's action into "Connect"
 * and leaves the user one tap from the actual fix.
 */
suspend fun openAdbPortAndStart(context: Context) {
    StartStatusReporter.starting()

    val result = runCatching {
        AdbStarter.openTcpPort(context, ShizukuSettings.getTcpPort())
    }

    if (result.getOrDefault(false)) {
        ShizukuReceiverStarter.start(
            context,
            userInitiated = true,
            startMethod = ShizukuSettings.StartMethod.USB
        )
        return
    }

    val message = context.getString(R.string.start_failed_usb_no_port)
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
