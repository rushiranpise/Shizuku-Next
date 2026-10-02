package moe.shizuku.manager.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Creates a notification channel, but only on the platforms that have them.
 *
 * Channels arrived with Android 8 (API 26), and the class does not exist below it. A call made
 * without this guard is therefore not a harmless no-op on an older phone: it is a
 * `NoClassDefFoundError` raised the moment the method runs, which takes down whatever service or
 * receiver was posting the notification - and an `Error` is not something the surrounding
 * `catch (e: Exception)` blocks can stop. Notifications themselves are built with
 * [androidx.core.app.NotificationCompat] and need no channel at all before there were channels.
 *
 * `showBadge` is a parameter rather than a trailing lambda so that the API 26 call it stands for
 * is written down exactly once, here, next to the version check that makes it safe.
 *
 * A channel's own settings are frozen once it exists, so this only has an effect the first time a
 * given [id] is created on a device.
 */
fun NotificationManager?.createChannelCompat(
    id: String,
    name: String,
    importance: Int,
    showBadge: Boolean = true
) {
    if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(id, name, importance)
    channel.setShowBadge(showBadge)
    createNotificationChannel(channel)
}
