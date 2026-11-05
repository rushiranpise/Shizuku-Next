package moe.shizuku.manager.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the accessibility keep-alive back after a restart, when the user asked it to. */
class AccessibilityBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AccessibilityKeepAlive.isAutoBoot(context)) return
        if (!AccessibilityKeepAlive.isEnabled(context)) return

        AccessibilityKeepAliveService.refresh(context)
    }
}
