package moe.shizuku.manager.start

import android.os.Build
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbRoot
import moe.shizuku.manager.utils.EnvironmentUtils

/**
 * Checks that the configured start method can actually run here.
 *
 * Root can be gone since the method was chosen: an OTA that relocks the bootloader, a ROM that
 * no longer grants it, root switched off in the manager while the stored setting stays as it was.
 * Shizuku would then try to escalate on every start (boot, the watchdog, the Start button), find
 * nothing to escalate with, and quietly do nothing: the app looks configured but never starts.
 * A debuggable build is the exception: its adbd can be rooted without a root manager, which is
 * what the Root start method falls back to.
 *
 * Wireless debugging is the other method that can be missing. It arrived with Android 11, and a
 * stored wireless choice on an older device has nothing to connect to. That start falls back to
 * USB, which is the method older devices can use.
 *
 * So a stored Root falls back to Wireless debugging, or to USB before Android 11, while the
 * device has neither a root manager nor a debuggable adbd. [droppedRoot] says so, so the settings
 * screen can explain the fallback. The setting itself is left alone: root can come back - root
 * switched back on, a reflash, an OTA reverted - and the next start after that has to use it again
 * without the user having to re-pick Root. A stored wireless method is migrated when it is read,
 * because Android never becomes older.
 */
object StartMethodGuard {

    /**
     * Whether the last resolution fell back from Root because the device has no root.
     * In memory only the point is to explain the fallback on the screen that shows it.
     */
    @Volatile
    var droppedRoot: Boolean = false
        private set

    /** Wireless debugging exists from Android 11 onward. */
    fun supportsWirelessDebugging(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.R

    private fun isWireless(@ShizukuSettings.StartMethod method: Int): Boolean =
        method == ShizukuSettings.StartMethod.WIRELESS ||
            method == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK

    /**
     * The method to use: [configured] unless it cannot run here. Root falls back to Wireless
     * debugging, or to USB before Android 11, and a wireless method falls back to USB.
     * [configured] is kept as it is.
     *
     * Call off the main thread: probing root spawns a shell.
     */
    @ShizukuSettings.StartMethod
    fun resolve(
        @ShizukuSettings.StartMethod configured: Int = ShizukuSettings.getStartMethod()
    ): Int {
        if (isAvailable(configured)) {
            droppedRoot = false
            return configured
        }

        droppedRoot = configured == ShizukuSettings.StartMethod.ROOT
        val fallback =
            if (supportsWirelessDebugging() &&
                (droppedRoot || configured == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK)
            ) {
                ShizukuSettings.StartMethod.WIRELESS
            } else {
                ShizukuSettings.StartMethod.USB
            }

        Log.w(
            AppConstants.TAG,
            when {
                droppedRoot && supportsWirelessDebugging() ->
                    "Root is not available on this device; using Wireless debugging for this start"

                droppedRoot ->
                    "Root is not available on this device; using USB for this start"

                isWireless(configured) ->
                    "Wireless debugging needs Android 11 or newer; using USB for this start"

                else -> "Start method $configured is not available on this device; using USB"
            }
        )
        return fallback
    }

    /**
     * True when this device can run [method]. Root needs root access, and the wireless methods
     * need Android 11. USB and system have no version or privilege requirement of their own.
     */
    fun isAvailable(@ShizukuSettings.StartMethod method: Int): Boolean = when (method) {
        ShizukuSettings.StartMethod.WIRELESS -> supportsWirelessDebugging()

        ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK -> supportsWirelessDebugging()

        ShizukuSettings.StartMethod.ROOT ->
            runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false) ||
                AdbRoot.isSupported()

        else -> true
    }
}
