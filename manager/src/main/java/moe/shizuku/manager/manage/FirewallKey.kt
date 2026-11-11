package moe.shizuku.manager.manage

import android.content.pm.PackageInfo
import moe.shizuku.manager.utils.UserHandleCompat

/**
 * The key one firewall rule is stored under: the package, and the Android user when it is not
 * the user running this app.
 *
 * A work profile or a cloned app has the same package name as the copy in the main user, but a
 * different uid. The key keeps the two apart in the list and in the preferences.
 */
object FirewallKey {

    fun of(userId: Int, packageName: String): String =
        if (userId == UserHandleCompat.myUserId()) packageName else "$userId:$packageName"

    fun fromUid(uid: Int, packageName: String): String =
        of(UserHandleCompat.getUserId(uid), packageName)

    fun userIdOf(key: String): Int {
        val separator = key.indexOf(':')
        if (separator <= 0) return UserHandleCompat.myUserId()
        return key.substring(0, separator).toIntOrNull() ?: UserHandleCompat.myUserId()
    }

    fun packageOf(key: String): String {
        val separator = key.indexOf(':')
        if (separator <= 0) return key
        return if (key.substring(0, separator).toIntOrNull() == null) {
            key
        } else {
            key.substring(separator + 1)
        }
    }
}

fun PackageInfo.firewallKey(): String =
    FirewallKey.fromUid(applicationInfo?.uid ?: 0, packageName)
