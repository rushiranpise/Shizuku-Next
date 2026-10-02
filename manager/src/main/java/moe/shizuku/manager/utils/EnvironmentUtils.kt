package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemProperties
import android.provider.Settings
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.start.StartTransport
import com.topjohnwu.superuser.Shell

private val appContext = ShizukuApplication.appContext

object EnvironmentUtils {

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

    fun isRooted(): Boolean {
        return Shell.getShell().isRoot
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        if (port == -1 && isTelevision() && !isTlsSupported()) port = ShizukuSettings.getTcpPort()
        return port
    }

    /**
     * Whether USB debugging is on.
     *
     * Android 17 (SDK 37) redacts `Settings.Global.ADB_ENABLED` to 0 for third-party apps,
     * so a 0 there no longer means the toggle is off. Believing it made the app announce
     * that USB debugging was disabled on devices where it was on, and made the TCP-mode
     * path refuse to run at all. On that platform the setting says nothing and the
     * toggle is assumed on, which is what the redaction is asking apps to do.
     */
    fun isAdbEnabled(): Boolean {
        if (Settings.Global.getInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0) > 0) {
            return true
        }
        return Build.VERSION.SDK_INT >= 37
    }

    fun isUsbDebuggingEnabled(): Boolean = isAdbEnabled()

    fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
