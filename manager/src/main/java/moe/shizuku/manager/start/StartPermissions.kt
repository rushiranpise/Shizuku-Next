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
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.launch
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
fun Context.writeGlobalSetting(key: String, value: Int): Boolean = runCatching {
    Settings.Global.putInt(contentResolver, key, value)
    true
}.getOrElse {
    Diag.warn(AppConstants.TAG, "Could not write the $key setting (WRITE_SECURE_SETTINGS missing?)")
    false
}

fun Context.writeGlobalLongSetting(key: String, value: Long): Boolean = runCatching {
    Settings.Global.putLong(contentResolver, key, value)
    true
}.getOrElse {
    Diag.warn(AppConstants.TAG, "Could not write the $key setting (WRITE_SECURE_SETTINGS missing?)")
    false
}

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
