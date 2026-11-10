package moe.shizuku.manager.start

import android.Manifest
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.os.Looper
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.runShellCommand
import moe.shizuku.manager.utils.Diag

/** The global setting Android keeps wireless debugging in. */
private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

/**
 * The permission the OS gates local-network discovery behind, or null where there is none.
 *
 * Discovery goes through the system's NSD service, and the platform has moved the gate on
 * it twice: Android 16 (SDK 36) asks for NEARBY_WIFI_DEVICES, Android 17 (SDK 37)
 * ACCESS_LOCAL_NETWORK. Without the grant the OS intercepts the pairing connection with
 * its own "choose a device" picker.
 */
fun localNetworkPermission(): String? = when {
    Build.VERSION.SDK_INT >= 37 -> "android.permission.ACCESS_LOCAL_NETWORK"
    Build.VERSION.SDK_INT >= 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
    else -> null
}

fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/**
 * Whether a start over [method] is going to need local-network discovery and so the
 * permission above. A wireless start discovers the TLS port; a USB start only needs that
 * discovery when the classic port is closed and it has to borrow a connection to open it.
 */
fun Context.needsLocalNetworkPermissionFor(@ShizukuSettings.StartMethod method: Int): Boolean {
    val permission = localNetworkPermission() ?: return false
    if (hasPermission(permission)) return false
    return method == ShizukuSettings.StartMethod.WIRELESS ||
        EnvironmentUtils.getAdbTcpPort() <= 0
}

/**
 * True when the system won't put the request dialog up for [permission] again the user
 * answered "don't ask again", or denied it twice, which Android treats the same way.
 *
 * Only ask this *after* a request came back denied, because before the first request the
 * same answer just means there is nothing to explain yet, and asking a request that can
 * never show anything looks like a button that does nothing.
 */
fun Context.isPermissionPermanentlyDenied(permission: String): Boolean {
    if (hasPermission(permission)) return false
    val activity = this as? Activity ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

/**
 * Opens this app's page in system settings, which is where a permission has to be changed
 * once the request dialog is out of the picture.
 */
fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Diag.warn(AppConstants.TAG, "Could not open app settings", it) }
}

/** WRITE_SECURE_SETTINGS can only be granted over ADB, so it is checked before use. */
fun Context.hasWriteSecureSettings(): Boolean =
    checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

/**
 * Whether a secure setting can be written at all, by either route.
 *
 * Directly with WRITE_SECURE_SETTINGS, which a running server hands the app and which keeps
 * working with the server stopped after that; or through a running server, which is shell and may
 * write these settings itself.
 *
 * The second route is the one that matters on a phone whose shell cannot hand permissions out:
 * `pm grant` answers "neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS", so the
 * grant can never arrive, and the experiment that needed it stayed greyed out on a running server
 * with a message telling the user to run a command that cannot work there (issue #151). Nothing
 * about writing `adb_wifi_enabled` needs the app to hold the permission when the server can write
 * it instead.
 */
fun Context.canWriteSecureSettings(): Boolean =
    hasWriteSecureSettings() || Shizuku.pingBinder()

fun Context.isWirelessDebuggingEnabled(): Boolean =
    Settings.Global.getInt(contentResolver, ADB_WIFI_ENABLED, 0) == 1

/**
 * Whether [startMethod] is going to need a secure setting written before it can work.
 *
 * Only the wireless flow ever does: it may have to switch wireless debugging on, and the
 * TLS port isn't advertised without it. USB and the classic ADB port need nothing of the
 * sort, and neither does a wireless start where the toggle is already on so those
 * shouldn't be held up for a permission they won't use.
 *
 * Starting without a network is a wireless start too, and the one this question matters
 * most for: with the toggle off there is no other way for it to come on, because the
 * Settings switch cannot be reached without a network and the write is what a device that
 * is offline needs. Left out of this check, an install that turned the experiment on
 * before Shizuku had ever started asked a settings provider that refuses every write and
 * retried every half minute forever, with nothing anywhere saying why - which is the one
 * state the experiment can never get itself out of.
 */
fun Context.needsWriteSecureSettingsFor(@ShizukuSettings.StartMethod startMethod: Int): Boolean =
    (startMethod == ShizukuSettings.StartMethod.WIRELESS ||
        startMethod == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK) &&
        !isWirelessDebuggingEnabled()

/**
 * Writes a global setting, and shrugs when the app isn't allowed to.
 *
 * Every one of these writes is a nudge to adbd switch a debugging toggle on, or bounce
 * it so it re-announces itself and without WRITE_SECURE_SETTINGS the settings provider
 * *throws* instead of ignoring it. That used to take the whole start down with it, and the
 * SecurityException was then reported as "network not authorized, re-pair the device",
 * which is nowhere near what happened. A denied nudge should fail the nudge, nothing more.
 */
fun Context.writeGlobalSetting(key: String, value: Int): Boolean =
    writeGlobal(key, value.toString()) { Settings.Global.putInt(contentResolver, key, value) }

fun Context.writeGlobalLongSetting(key: String, value: Long): Boolean =
    writeGlobal(key, value.toString()) { Settings.Global.putLong(contentResolver, key, value) }

/**
 * Writes global setting [key] as [value], directly where the app may and through the server where
 * it may not, answering whether it is now what was asked for.
 *
 * The read back is what decides, not the absence of an exception: a provider that refuses the
 * write and one that silently keeps the old value look the same from here.
 *
 * The shell write is skipped on the main thread: it blocks on the server answering, and a server
 * that has stopped answering should be a failed write rather than an ANR.
 */
private fun Context.writeGlobal(key: String, value: String, direct: () -> Unit): Boolean {
    runCatching { direct() }
    if (readGlobalSetting(key) == value) return true

    if (Looper.myLooper() == Looper.getMainLooper()) {
        Diag.warn(
            AppConstants.TAG,
            "Could not write the $key setting, and the shell fallback cannot run on the main thread"
        )
        return false
    }

    // The same fallback the Labs switches use: the server is shell, and shell writes these.
    runCatching { runShellCommand("settings put global $key $value") }
    val written = readGlobalSetting(key) == value
    if (!written) {
        Diag.warn(AppConstants.TAG, "Could not write the $key setting (WRITE_SECURE_SETTINGS missing?)")
    }
    return written
}

private fun Context.readGlobalSetting(key: String): String? = runCatching {
    Settings.Global.getString(contentResolver, key)
}.getOrNull()

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Grants us WRITE_SECURE_SETTINGS through the already running server.
 *
 * The permission can only be handed out over ADB, which used to mean every fresh install
 * and every install after a re-signed update needed a computer before wireless
 * debugging could be switched on. A running server *is* ADB (or root), so it can pass the
 * permission on the same way `pm grant` does, and the user never has to run that command.
 *
 * Safe to call whenever a server is seen running: it returns immediately once the
 * permission is there, and grants at most once per install.
 */
fun grantWriteSecureSettingsIfNeeded() {
    val context = ShizukuApplication.appContext
    if (context.hasWriteSecureSettings()) return

    scope.launch {
        // `pm grant` prints nothing when it works, so the permission itself is the answer.
        val output = runShellCommand(
            "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        )
        if (context.hasWriteSecureSettings()) {
            Diag.info(AppConstants.TAG, "Granted WRITE_SECURE_SETTINGS through the running server")
        } else {
            Diag.warn(
                AppConstants.TAG,
                "Could not grant WRITE_SECURE_SETTINGS through the server" +
                    (output?.let { ": $it" } ?: "")
            )
        }
    }
}

/**
 * The permission a local-only hotspot needs, or null where there is none.
 *
 * Android 13 (SDK 33) gated `startLocalOnlyHotspot` behind NEARBY_WIFI_DEVICES and Android 17 has
 * not moved it on, while Android 17 is also the release that asks for ACCESS_LOCAL_NETWORK instead
 * - which is the permission [localNetworkPermission] answers with there. Measured on the phone: the
 * app holds ACCESS_LOCAL_NETWORK and `startLocalOnlyHotspot` still throws "does not have nearby
 * devices permission", so the hotspot never comes up, there is no interface for mDNS to resolve a
 * port against, and every no-network start ends having found nothing, with the app's own
 * permissions page saying everything is granted. The hotspot therefore gets its own answer rather
 * than the discovery one.
 */
fun localHotspotPermission(): String? =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else null

/**
 * Whether a start over [method] is going to ask for the local-only hotspot, and so needs the
 * permission above.
 *
 * A no-network start always asks for the hotspot. A plain wireless start asks for one only with
 * the forced-wireless experiment on; every other method uses an existing interface or the classic
 * ADB port, so it would be a prompt for something the start never touches.
 */
fun Context.needsLocalHotspotPermissionFor(@ShizukuSettings.StartMethod method: Int): Boolean {
    val permission = localHotspotPermission() ?: return false
    if (hasPermission(permission)) return false
    return method == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK ||
        (method == ShizukuSettings.StartMethod.WIRELESS &&
            ShizukuSettings.getForceWirelessDebugging())
}

/**
 * Grants the hotspot's permission through the already running server.
 *
 * The home screen asks before a start that will use the hotspot, but starts from boot, the
 * watchdog or a completed pairing have no activity to ask from. A running server *is* ADB (or
 * root), so it can pass the permission on the way `pm grant` does, which is how the hiding lists
 * and WRITE_SECURE_SETTINGS already get theirs.
 *
 * Safe to call whenever a server is seen running: it returns at once when the permission is there.
 */
fun grantLocalHotspotPermissionIfNeeded() {
    val context = ShizukuApplication.appContext
    val permission = localHotspotPermission() ?: return
    if (context.hasPermission(permission)) return

    scope.launch {
        val output = runShellCommand("pm grant ${context.packageName} $permission")
        if (context.hasPermission(permission)) {
            Diag.info(AppConstants.TAG, "Granted $permission through the running server")
        } else {
            Diag.warn(
                AppConstants.TAG,
                "Could not grant $permission through the server" +
                    (output?.let { ": $it" } ?: "")
            )
        }
    }
}
