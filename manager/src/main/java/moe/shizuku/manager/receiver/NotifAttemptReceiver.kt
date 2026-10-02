package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.worker.AdbStartWorker

class NotifAttemptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Retry the method the start was asked for, not whatever is default now.
        val startMethod = intent.getIntExtra(
            AppConstants.EXTRA_START_METHOD,
            ShizukuSettings.getStartMethod()
        )

        // User-initiated ("Retry now") never wait for Wi-Fi.
        AdbStartWorker.enqueue(context, startMethod = startMethod, immediate = true)
    }
}
