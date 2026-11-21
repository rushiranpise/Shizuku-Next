package moe.shizuku.manager.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.ktx.ensureWorkManager
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.UpdateHelper
import moe.shizuku.manager.utils.UpdateNotifier

/**
 * Looks for a newer release in the background and posts the update notification.
 *
 * A periodic worker rather than an alarm: this is a check nobody is waiting for, and WorkManager
 * is the part of the app that already knows how to run it only once the phone is unlocked and has
 * a network. The interval is long on purpose - GitHub rate-limits unauthenticated checks, and a
 * release does not arrive twice a day.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!UpdateHelper.isCheckForUpdatesEnabled()) return Result.success()

        runCatching { UpdateHelper.checkAndNotify() }
            .onFailure { Diag.warn(TAG, "Background update check failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val UNIQUE_WORK_NAME = "update_check"
        private const val INTERVAL_HOURS = 12L

        /**
         * Keeps the periodic check in step with the update mode.
         *
         * Off cancels it, and the other modes make sure it exists. KEEP rather than UPDATE:
         * this runs on every app start, and replacing the request each time would push its next
         * run back for anyone who opens the app often.
         */
        fun schedule(context: Context) {
            if (!context.ensureWorkManager()) return

            val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull()
                ?: return
            if (!UpdateHelper.isCheckForUpdatesEnabled()) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                // A notification posted before the mode was switched off no longer applies:
                // there is no check left that would ever take it away.
                UpdateNotifier.cancel(context)
                return
            }

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            runCatching {
                workManager.enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }.onFailure {
                Diag.warn(TAG, "Could not schedule background update checks", it)
            }
        }
    }
}
