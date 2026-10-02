package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig

class ManualStartReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.START") return

        // Deliberately not userInitiated, though a person did ask for this somewhere: the request
        // arrived as a broadcast from another app, so there is nobody in front of *this* screen to
        // tell. The difference is what happens on a wireless start with no Wi-Fi - a start somebody
        // tapped is answered at once with "connect to Wi-Fi", while this one waits for the network
        // like any unattended start, which is what an automation tool calling this expects. It is
        // also what this fork's base did before the flag was passed here.
        ShizukuReceiverStarter.start(context)
    }
}