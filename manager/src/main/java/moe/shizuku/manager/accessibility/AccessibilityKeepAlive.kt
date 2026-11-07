package moe.shizuku.manager.accessibility

import android.content.Context

/**
 * What the accessibility keep-alive remembers: whether it is armed, whether it comes back after a
 * restart, and which services were locked.
 *
 * Pinned ids are stored in the same colon-separated shape the system uses for its own list, so a
 * pinned entry can be compared with an enabled one directly.
 */
object AccessibilityKeepAlive {

    const val FILE = "accessibility_keep_alive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_BOOT = "auto_boot"
    private const val KEY_PINNED = "pinned"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAutoBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_BOOT, true)

    fun setAutoBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_BOOT, enabled).apply()
    }

    fun pinned(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_PINNED, null) ?: return emptySet()
        return raw.split(':').filter { it.isNotBlank() }.toSet()
    }

    fun isPinned(context: Context, id: String): Boolean = id in pinned(context)

    fun pin(context: Context, id: String) = writePinned(context, pinned(context) + id)

    /** Locks every service that is on now, which is what the single switch promises. */
    fun pinAll(context: Context, ids: Set<String>) = writePinned(context, ids)

    fun clearPinned(context: Context) = writePinned(context, emptySet())

    fun unpin(context: Context, id: String) = writePinned(context, pinned(context) - id)

    private fun writePinned(context: Context, ids: Set<String>) {
        prefs(context).edit().putString(KEY_PINNED, ids.joinToString(":")).apply()
    }
}
