package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings

class WatchdogToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = BuildConfig.APPLICATION_ID
        val enable = when (intent.action) {
            "$id.WATCHDOG_ON" -> true
            "$id.WATCHDOG_OFF" -> false
            "$id.WATCHDOG_TOGGLE" -> !ShizukuSettings.getWatchdog()
            else -> return
        }
        ShizukuSettings.setWatchdog(context, enable)
    }
}
