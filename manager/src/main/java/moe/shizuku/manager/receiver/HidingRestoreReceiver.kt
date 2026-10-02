package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.service.HidingWatchService
import kotlin.concurrent.thread

/**
 * The two things the hiding notification can be asked for: put the settings back, or start hiding
 * for the listed apps again.
 *
 * Restore suspends the rule rather than emptying the lists, because the lists say which apps
 * object and that has not changed; the user is asking for their settings now, while the app is
 * still open. It does the two writes and leaves the watch to notice, which keeps the one thing
 * that owns the state - the reconciliation pass - as the only thing that hides or restores.
 */
class HidingRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // A receiver is given seconds, and these are shell writes, which are blocking calls on
        // the main thread, which is where a broadcast lands.
        val pending = goAsync()

        thread(name = "hiding-notification") {
            try {
                when (intent.action) {
                    Hiding.ACTION_RESTORE_HIDING -> {
                        Hiding.setPaused(true)
                        Hiding.restoreAll()
                    }

                    Hiding.ACTION_RESUME_HIDING -> {
                        Hiding.setPaused(false)
                        Hiding.reconcile()
                    }

                    else -> return@thread
                }

                // Either action leaves the rule in a state worth watching again, and the watch
                // may have stood itself down while Shizuku was away.
                HidingWatchService.refresh(app)
            } catch (e: Throwable) {
                Log.w(AppConstants.TAG, "the hiding notification action failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
