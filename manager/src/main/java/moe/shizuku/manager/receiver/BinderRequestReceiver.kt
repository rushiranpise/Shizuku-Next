package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.shell.ShellBinderRequestHandler
import moe.shizuku.manager.utils.ShizukuStateMachine

class BinderRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") return

        ShellBinderRequestHandler.handleRequest(context, intent)

        // Auto wake-up: an app asked for the binder while the server is down.
        // Start it in the background, but only when the user enabled start on
        // boot and did not deliberately stop Shizuku.
        if (!ShizukuStateMachine.isRunning() &&
            !ShizukuSettings.getManuallyStopped() &&
            ShizukuSettings.getStartOnBoot(context)
        ) {
            ShizukuReceiverStarter.start(context)
        } else if (!ShizukuStateMachine.isRunning()) {
            // Nothing happens here otherwise, which is why a background start can look like
            // the request went nowhere: say which setting stopped it. A deliberate Stop is
            // the usual one, and it stays in force until a start is asked for from the app.
            Log.i(
                AppConstants.TAG,
                "Background start not attempted: " +
                    if (ShizukuSettings.getManuallyStopped()) "the service was stopped deliberately"
                    else "start on boot is off"
            )
        }
    }
}
