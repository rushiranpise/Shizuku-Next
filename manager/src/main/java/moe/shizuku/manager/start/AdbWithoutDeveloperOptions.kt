package moe.shizuku.manager.start

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings

/** Developer options' own master switch. */
private const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"

/** Wireless debugging's toggle, which Developer options drags down with it. */
private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

/**
 * Turns Developer options off and puts back the two ADB settings it takes with it.
 *
 * Apps that refuse to run on a device with Developer options on check that one flag, but
 * the ADB settings are not part of the flag: adbd keeps running if [Settings.Global.ADB_ENABLED]
 * and wireless debugging are still on, so Shizuku can start and restart itself on a device
 * that looks clean to them. It is the same thing System UI Tuner's "Enable ADB" does.
 *
 * Returns false when the writes were refused, which in practice means this app has no
 * WRITE_SECURE_SETTINGS yet (the permissions page says how to give it one).
 */
fun applyAdbWithoutDeveloperOptions(context: Context): Boolean {
    // The flag first: turning it off from here does not clear the ADB settings, but if some
    // system build does clear them, the writes below still land after it.
    val developerOptionsOff = context.writeGlobalSetting(DEVELOPMENT_SETTINGS_ENABLED, 0)
    val adb = context.writeGlobalSetting(Settings.Global.ADB_ENABLED, 1)
    val wireless = context.writeGlobalSetting(ADB_WIFI_ENABLED, 1)

    if (!developerOptionsOff || !adb || !wireless) {
        Log.w(
            AppConstants.TAG,
            "Could not keep ADB on with Developer options off " +
                "(developer options off: $developerOptionsOff, adb: $adb, wireless: $wireless)"
        )
        return false
    }
    return true
}

/**
 * Puts Developer options back the way it was before the setting was switched on.
 *
 * Returns false when the write was refused. The permission can be gone since the setting was
 * switched on - an install under a new signing key loses it - so the caller has to know
 * whether Developer options are really back rather than assume the write landed.
 */
fun restoreDeveloperOptions(context: Context): Boolean =
    context.writeGlobalSetting(DEVELOPMENT_SETTINGS_ENABLED, 1)

/**
 * Whether Developer options is currently available at all.
 *
 * The screens this app sends people to for ADB wireless debugging, its own pairing
 * tutorial live under Developer options, so while our setting hides it those actions
 * would open nothing.
 *
 * The only hiding this app knows the reason for is its own setting, so that is what is
 * asked first: with "ADB without Developer options" off, Developer options were not
 * turned off by us, and a 0 read from the setting is either the user's own choice or the
 * value Android 17 (SDK 37) redacts for third-party apps. Since Android 17 QPR1 redacts
 * `development_settings_enabled` the way it redacts `ADB_ENABLED`, believing it was what
 * made a start that failed for some other reason put "Developer options is off because ADB
 * without Developer options is enabled" on the home card and offer to turn off a setting
 * nobody had switched on.
 */
fun Context.isDeveloperOptionsEnabled(): Boolean =
    !ShizukuSettings.getAdbWithoutDeveloperOptions() &&
        (Settings.Global.getInt(contentResolver, DEVELOPMENT_SETTINGS_ENABLED, 1) != 0 ||
            Build.VERSION.SDK_INT >= 37)

/**
 * Re-applies the setting after a reboot, when the system has cleared the ADB toggles but
 * left Developer options off. Called from the boot receiver.
 */
fun reapplyAdbWithoutDeveloperOptionsIfEnabled(context: Context) {
    if (!ShizukuSettings.getAdbWithoutDeveloperOptions()) return
    applyAdbWithoutDeveloperOptions(context)
}
