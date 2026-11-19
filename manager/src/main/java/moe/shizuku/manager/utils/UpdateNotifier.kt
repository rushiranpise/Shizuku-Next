package moe.shizuku.manager.utils

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R

/** Posts the one notification that offers a newer release. */
object UpdateNotifier {

    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 1454

    /**
     * Shows the update notification, or reports that it could not be shown.
     *
     * False when notifications are switched off for the app, or the platform refuses the post:
     * the caller uses that to leave the version unrecorded, so granting the permission later
     * lets the next background check offer it again.
     */
    fun notify(context: Context, version: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return false
        manager.createChannelCompat(
            CHANNEL_ID,
            context.getString(R.string.update_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = context.getString(R.string.update_notification_text, version)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        return runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    /** Takes the offer away once it no longer applies, or once the update is installed. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
