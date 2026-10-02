package moe.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

class ManualStopReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.STOP") return

        // Mark the stop as intentional so the watchdog's dead-check doesn't undo
        // it, and drop any queued background start.
        ShizukuSettings.setManuallyStopped(true)
        WorkManager.getInstance(context).cancelUniqueWork("adb_start_worker")

        if (!ShizukuStateMachine.isRunning()) return

        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        runCatching { Shizuku.exit() }
    }
}