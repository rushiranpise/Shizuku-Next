package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.os.SystemProperties
import android.provider.Settings
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.start.StartTransport
import com.topjohnwu.superuser.Shell

private val appContext = ShizukuApplication.appContext

private const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

object EnvironmentUtils {

    /**
     * The two debug switches Android 17 redacts to 0 for third-party apps.
     *
     * A read of 0 there no longer means off, and neither does a read after this app has written
     * the switch on. The other debugging switch, wireless debugging, is not redacted.
     */
    private val REDACTED_DEBUG_SETTINGS = setOf(
        Settings.Global.ADB_ENABLED,
        DEVELOPMENT_SETTINGS_ENABLED
    )

    @JvmStatic
    fun isWatch(): Boolean {
        return (appContext.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_WATCH)
    }

    @JvmStatic
    fun isTelevision(): Boolean {
        return (appContext.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_TELEVISION ||
                appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
    }

    fun isTlsSupported(): Boolean {
        return if (isTelevision())
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            else Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    /** Whether a start has to wait for a network before it can reach the TLS port. */
    fun isWifiRequired(): Boolean = StartTransport.wifiRequired(
        getAdbTcpPort(),
        ShizukuSettings.getTcpMode(),
        ShizukuSettings.getForceWirelessDebugging()
    )

    /** When root was last asked for, -1 before it ever was. See [isRooted]. */
    @Volatile
    private var lastRootProbeAt = -1L

    /**
     * How long a "not root" answer stands before the question is asked again.
     *
     * Long enough that a screen which refreshes cannot spawn a shell on every refresh, short
     * enough that somebody who has just put root back sees it used on their next start.
     */
    private const val ROOT_REPROBE_MS = 10_000L

    /**
     * Whether this app can escalate to root right now.
     *
     * Asked through a shell, because that is the honest question: a device whose root manager
     * grants this app is one a start can escalate on, and one where it does not is not.
     *
     * The catch is on the library's side: `Shell.getShell()` builds a shell once and hands the
     * same one back for the life of the process, and a shell built while root was away is a plain
     * one. So this used to answer "no root" until the process died, whatever came back
     * afterwards. That answer is what the start method reads, so a start that fell back to
     * wireless while root was gone stayed on wireless after root returned - the Root method was
     * never offered again and the watchdog kept restarting over wireless, which is the whole of
     * the bug. A cached shell that is not root is therefore dropped before the question is asked
     * again, at most once per [ROOT_REPROBE_MS].
     */
    fun isRooted(): Boolean {
        val cached = Shell.getCachedShell()
        if (cached != null && cached.isRoot) return true

        val now = SystemClock.elapsedRealtime()
        if (lastRootProbeAt >= 0 && now - lastRootProbeAt < ROOT_REPROBE_MS) return false
        lastRootProbeAt = now

        // Closed so the build below asks the root manager rather than reusing a plain shell.
        if (cached != null) runCatching { cached.close() }
        return runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        if (port == -1 && isTelevision() && !isTlsSupported()) port = ShizukuSettings.getTcpPort()
        return port
    }

    /**
     * The port adbd's wireless (TLS) server listens on, or -1 when the property does not say.
     *
     * The platform sets it when the wireless server comes up and clears it with the daemon, and
     * init resets every `service.*` property at boot, so a value here belongs to this boot - which a
     * remembered port cannot promise. Whether an app may read it is the platform's and the vendor's
     * decision (SELinux), and an unreadable property reads as unset, so -1 means "not known" and not
     * "no wireless server".
     */
    fun getAdbTlsPort(): Int = SystemProperties.getInt("service.adb.tls.port", -1)

    /** The platform's count of boots, or -1 where it is not available. */
    fun getBootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /**
     * Whether the platform's Developer options switch is on.
     *
     * This is the platform setting, not whether this app makes the screen reachable: the
     * context helper `isDeveloperOptionsEnabled` answers that, including this app's own hiding
     * setting. Android 17 redacts the raw value, so it reads as on there.
     */
    fun isDeveloperOptionsSwitchOn(): Boolean =
        isDebugSwitchOn(DEVELOPMENT_SETTINGS_ENABLED)

    /**
     * Whether USB debugging is on.
     *
     * Android 17 (SDK 37) redacts `Settings.Global.ADB_ENABLED` to 0 for third-party apps,
     * so a 0 there no longer means the toggle is off. Believing it made the app announce
     * that USB debugging was disabled on devices where it was on, and made the TCP-mode
     * path refuse to run at all. On that platform the setting says nothing and the
     * toggle is assumed on, which is what the redaction is asking apps to do.
     */
    fun isAdbEnabled(): Boolean = isDebugSwitchOn(Settings.Global.ADB_ENABLED)

    fun isUsbDebuggingEnabled(): Boolean = isAdbEnabled()

    /**
     * Whether wireless debugging is switched on.
     *
     * The platform keeps it in the `adb_wifi_enabled` global setting - the one its own Developer
     * options page writes, and the one this app already writes when a start needs it - and offers
     * no API for reading it back. Zero is "not on", which is also what a phone that has never
     * touched the setting reports.
     */
    fun isWirelessDebuggingEnabled(): Boolean = isDebugSwitchOn(ADB_WIFI_ENABLED)

    /**
     * How a platform debugging switch reads when the raw setting is [rawOn].
     *
     * Android 17 redacts two of the three settings to 0, so on that platform a 0 means the value
     * cannot be read rather than off. Those two read as on, which is the answer the redaction
     * leaves available. Everything else keeps the raw value.
     */
    internal fun platformDebugSwitchOn(key: String, rawOn: Boolean, sdkInt: Int): Boolean =
        rawOn || (sdkInt >= 37 && key in REDACTED_DEBUG_SETTINGS)

    /** Whether [key] is one of the settings Android 17 redacts on this platform. */
    internal fun isDebugSwitchReadRedacted(
        key: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean = sdkInt >= 37 && key in REDACTED_DEBUG_SETTINGS

    private fun isDebugSwitchOn(key: String): Boolean {
        val raw = runCatching {
            Settings.Global.getInt(appContext.contentResolver, key, 0) != 0
        }.getOrDefault(false)
        return platformDebugSwitchOn(key, raw, Build.VERSION.SDK_INT)
    }

    /**
     * Whether this platform will not run wireless debugging without a wireless network.
     *
     * Android 17 (SDK 37) is that release: its AdbWifiNetworkMonitor answers "Not connected to any
     * wireless network. Not enabling adbwifi." and stops adbd again within milliseconds, measured
     * with this app's own local-only hotspot up. Older releases start the server and stop it only
     * when their own constraint check fires, which is the race a no-network start rides - so the
     * behaviour written for Android 17 is kept to Android 17, and older platforms keep what they
     * had.
     */
    fun isAdbWifiOverhauled(): Boolean = Build.VERSION.SDK_INT >= 37

    fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
