package moe.shizuku.manager.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter.WorkerState
import moe.shizuku.manager.ktx.ensureWorkManager
import moe.shizuku.manager.start.AdbStartRun
import moe.shizuku.manager.start.StartTransport
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils

/**
 * Runs [AdbStartRun] as unique, retried work.
 *
 * All the starting is in [AdbStartRun]; what is left here is what only WorkManager provides: the
 * queue (one start at a time, the newest replacing the one before it), the Wi-Fi constraint, the
 * backoff between retries, and the foreground promotion the platform grants a worker.
 */
class AdbStartWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), AdbStartRun.Host {

    /** The method this start was asked for, so notifications retry the same way. */
    private var requestedMethod = ShizukuSettings.StartMethod.WIRELESS

    override suspend fun doWork(): Result {
        requestedMethod = inputData.getInt(KEY_START_METHOD, ShizukuSettings.StartMethod.WIRELESS)
        val adbRoot = inputData.getBoolean(KEY_ADB_ROOT, false)
        return when (AdbStartRun(applicationContext, this, adbRoot = adbRoot).execute(requestedMethod)) {
            AdbStartRun.Outcome.SUCCESS -> Result.success()
            AdbStartRun.Outcome.RETRY -> Result.retry()
            AdbStartRun.Outcome.FAILURE -> Result.failure()
        }
    }

    override fun notify(state: WorkerState) =
        ShizukuReceiverStarter.updateNotification(applicationContext, state, requestedMethod)

    override suspend fun promoteToForeground() = setForeground(buildForegroundInfo())

    override fun promoteToForegroundAsync() {
        setForegroundAsync(buildForegroundInfo())
    }

    override fun stoppedState(): WorkerState =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            WorkerState.AWAITING_RETRY
        } else {
            when (stopReason) {
                WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WorkerState.AWAITING_WIFI
                WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WorkerState.STOPPED
                else -> WorkerState.AWAITING_RETRY
            }
        }

    /**
     * The type has to be handed over here, not only declared in the manifest: WorkManager passes
     * on what this carries, so the two argument form asks for a foreground service with no type at
     * all, which Android 14 refuses outright and takes the process down with it. That combination is
     * the locked-device start, and therefore every start a reboot makes, which is where the crashes
     * came from: the manifest was right and the call was not.
     */
    private fun buildForegroundInfo(): ForegroundInfo {
        val notification: Notification = ShizukuReceiverStarter.buildNotification(
            applicationContext,
            null,
            requestedMethod
        )
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(
                ShizukuReceiverStarter.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ForegroundInfo(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_START_METHOD = "start_method"
        const val KEY_ADB_ROOT = "adb_root"

        /**
         * The unique name the queued start goes under. Named once because three places cancel it:
         * a stop, the notification's own Cancel, and a server that came up some other way.
         */
        const val UNIQUE_WORK_NAME = "adb_start_worker"

        /**
         * Queues one start, and says whether it was queued.
         *
         * False when this process has no WorkManager to queue it in, which is the case for a
         * process that started before the first unlock and never had one set up. The caller has
         * to say the start failed rather than let it look as if it is under way.
         */
        fun enqueue(
            context: Context,
            startMethod: Int = ShizukuSettings.StartMethod.WIRELESS,
            immediate: Boolean = false,
            adbRoot: Boolean = false
        ): Boolean {
            val usbMethod = startMethod == ShizukuSettings.StartMethod.USB
            val cb = Constraints.Builder()
            // Waiting for a network is only worth it when the start has no other way to
            // reach the port: a wireless start needs one to find the TLS port over mDNS,
            // while a classic port that TCP mode keeps open needs nothing, and a start
            // that has one would sit on this constraint forever on a device with no Wi-Fi.
            // `immediate` (a start the user asked for) skips the wait either way: they get
            // an answer now instead of a job that sits there.
            val needsNetwork = StartTransport.wifiRequired(
                EnvironmentUtils.getAdbTcpPort(),
                ShizukuSettings.getTcpMode(),
                ShizukuSettings.getForceWirelessDebugging()
            )
            if (needsNetwork && !immediate)
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            val constraints = cb.build()

            val inputData = workDataOf(
                KEY_START_METHOD to startMethod,
                KEY_ADB_ROOT to adbRoot
            )

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                // Retry about once a minute, doubling as it keeps failing: a boot start
                // usually just needs to wait for Wi-Fi to associate, and giving up after
                // one attempt is what left Shizuku down until the next manual start.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            // A process that started before the first unlock has no WorkManager, and asking it
            // for one throws. A start that cannot be queued has failed, and says so here rather
            // than letting the exception out of a call the caller cannot catch it from.
            if (!context.ensureWorkManager()) {
                Diag.warn("AdbStartWorker", "No WorkManager to queue a start with")
                return false
            }
            return runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                true
            }.getOrElse {
                Diag.error("AdbStartWorker", "Could not queue the start", it)
                false
            }
        }
        const val CHANNEL_ID = AdbStartRun.CHANNEL_ID
        const val NOTIFICATION_ID = AdbStartRun.NOTIFICATION_ID
    }
}
