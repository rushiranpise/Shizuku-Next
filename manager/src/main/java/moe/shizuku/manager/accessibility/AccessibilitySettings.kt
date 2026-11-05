package moe.shizuku.manager.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.utils.newShellProcess

/** One service the accessibility list can offer, with the labels the row shows. */
data class AccessibilityServiceEntry(
    val id: String,
    val label: String,
    val appLabel: String,
    val packageName: String
)

/**
 * Reads and writes the list of enabled accessibility services.
 *
 * The list lives in [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES], a colon-separated run of
 * flattened component names. Writing it is the same thing the accessibility settings screen does;
 * it simply needs a permission an app is not given, so the write goes through this app's own
 * resolver when it holds WRITE_SECURE_SETTINGS and through the Shizuku shell when it does not.
 *
 * The write is a whole list, not one entry: Android has no per-service switch, so enabling one
 * means reading what is on, changing the one name, and putting the result back.
 */
object AccessibilitySettings {

    private const val TAG = "AccessibilitySettings"
    private const val SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private const val ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED

    /** Every installed accessibility service, enabled or not, sorted by the name on the row. */
    fun installed(context: Context): List<AccessibilityServiceEntry> {
        val pm = context.packageManager
        val intent = Intent(AccessibilityService.SERVICE_INTERFACE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        }

        return resolved.mapNotNull { it.serviceInfo }
            .map { info ->
                AccessibilityServiceEntry(
                    id = ComponentName(info.packageName, info.name).flattenToString(),
                    label = info.loadLabel(pm).toString(),
                    appLabel = info.applicationInfo?.loadLabel(pm)?.toString()
                        ?: info.packageName,
                    packageName = info.packageName
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }

    /** The flattened names that are on right now. */
    fun enabled(context: Context): Set<String> {
        val raw = Settings.Secure.getString(context.contentResolver, SERVICES)
            ?: return emptySet()
        return raw.split(':').filter { it.isNotBlank() }.toSet()
    }

    /** Adds or removes one service, leaving the rest of the list as it was. */
    fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean {
        val current = enabled(context)
        if (enabled == (id in current)) return true
        val next = if (enabled) current + id else current - id
        return write(context, next)
    }

    /** Puts back every pinned service that is missing, and says whether anything was written. */
    fun restoreMissing(context: Context): Boolean {
        val pinned = AccessibilityKeepAlive.pinned(context)
        if (pinned.isEmpty()) return false

        val current = enabled(context)
        val missing = pinned - current
        if (missing.isEmpty()) return false

        return write(context, current + missing)
    }

    /**
     * Writes the whole list, and the master switch with it.
     *
     * The direct write is tried first because it is atomic; the shell fallback is one `settings`
     * command on the same device, which is what the accessibility screen itself does.
     */
    fun write(context: Context, ids: Set<String>): Boolean {
        val value = ids.joinToString(":")

        val direct = runCatching {
            Settings.Secure.putString(context.contentResolver, SERVICES, value) &&
                Settings.Secure.putInt(
                    context.contentResolver,
                    ENABLED,
                    if (value.isEmpty()) 0 else 1
                )
        }.getOrDefault(false)
        if (direct) return true

        // Component names cannot contain a quote, so single-quoting the list is enough.
        val quoted = value.replace("'", "")
        val listCommand = if (quoted.isEmpty()) {
            "settings delete secure enabled_accessibility_services"
        } else {
            "settings put secure enabled_accessibility_services '$quoted'"
        }
        val master = if (quoted.isEmpty()) 0 else 1
        val process = newShellProcess(
            "$listCommand; settings put secure accessibility_enabled $master"
        ) ?: return false

        return try {
            process.waitFor() == 0
        } catch (e: Throwable) {
            Log.w(TAG, "Accessibility list could not be written through the shell", e)
            false
        }
    }
}
