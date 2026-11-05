package moe.shizuku.manager.accessibility

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.createChannelCompat

/**
 * Keeps pinned accessibility services switched on.
 *
 * Android, and battery savers in particular, can drop a service out of the enabled list without
 * asking. A service that was locked is put back the next time this looks, which is why the
 * notification is ongoing: while it is up, the user can see that something is being held on.
 *
 * Three triggers rather than a poll alone. The content observer catches a change as it happens,
 * the screen-on broadcast catches the moment a service is most likely to have been killed in the
 * background, and the minute heartbeat catches changes that produce neither. The check itself is
 * one preference read and one secure-settings read, and writes only when something is missing.
 */
class AccessibilityKeepAliveService : Service() {

    companion object {
        private const val TAG = "AccessibilityKeepAlive"
        private const val CHANNEL_ID = "accessibility"
        private const val NOTIFICATION_ID = 1453
        private const val CHECK_MS = 60_000L
        private const val MIN_GAP_MS = 3_000L

        /** Starts the daemon when there is something to keep on, and stops it when there is not. */
        fun refresh(context: Context) {
            val intent = Intent(context, AccessibilityKeepAliveService::class.java)
            if (!AccessibilityKeepAlive.isEnabled(context) ||
                AccessibilityKeepAlive.pinned(context).isEmpty()
            ) {
                runCatching { context.stopService(intent) }
                return
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Could not start the accessibility keep-alive", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AccessibilityKeepAliveService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeat: Job? = null
    private var observer: ContentObserver? = null
    private var lastCheck = 0L

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON ||
                intent.action == Intent.ACTION_USER_PRESENT
            ) {
                schedule(1_500L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        channel()
        startAsForeground()

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                schedule(500L)
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer!!
        )

        ContextCompat.registerReceiver(
            this,
            screenOn,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        schedule(0L)
        heartbeat = scope.launch {
            while (isActive) {
                delay(CHECK_MS)
                check()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (heartbeat?.isActive != true) {
            heartbeat = scope.launch {
                while (isActive) {
                    delay(CHECK_MS)
                    check()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        runCatching { unregisterReceiver(screenOn) }
        heartbeat?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun schedule(delayMs: Long) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            check()
        }
    }

    private fun check() {
        // A restore writes the list, which notifies the observer, which would check again. Two
        // seconds apart is enough for that second look to see the list already repaired.
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck < MIN_GAP_MS) return
        lastCheck = now

        if (!AccessibilityKeepAlive.isEnabled(this) ||
            AccessibilityKeepAlive.pinned(this).isEmpty()
        ) {
            stopSelf()
            return
        }

        val restored = runCatching { AccessibilitySettings.restoreMissing(this) }
            .onFailure { Log.w(TAG, "Accessibility check failed", it) }
            .getOrDefault(false)
        if (restored) Log.i(TAG, "Restored a pinned accessibility service")
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createChannelCompat(
            CHANNEL_ID,
            getString(R.string.accessibility_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
            showBadge = false
        )
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.accessibility_notification_title))
            .setContentText(getString(R.string.accessibility_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
