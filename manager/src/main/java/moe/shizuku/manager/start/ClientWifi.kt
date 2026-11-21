package moe.shizuku.manager.start

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.manage.WifiControl
import moe.shizuku.manager.utils.Diag

/**
 * The device's own Wi-Fi connection, as far as the forced wireless start is concerned.
 *
 * A local-only hotspot is a second use of the one radio. Where the radio can do both at once
 * nothing here matters, but a head unit such as the FYT 7870 (Android 13, one `wlan0`) tears the
 * Wi-Fi client down to bring the hotspot up: the phone it was about to join disappears and the car
 * loses its internet for as long as the hotspot is held. And the hotspot buys nothing there: the
 * framework starts adbd's wireless server only once it has checked the Wi-Fi network the device is
 * connected to ("Not connected to any wireless network. Not enabling adbwifi."), and a hotspot is
 * not one. The one thing that makes wireless debugging work on such a device is the Wi-Fi the
 * hotspot would remove.
 *
 * So on a device where the hotspot displaces the client and Wi-Fi is wanted, the forced start does
 * not raise a hotspot at all; it waits for Wi-Fi (see [ForcedWirelessDebugging]), and the classic
 * port that outlives a reboot (see [AdbPortPersistence]) is what starts it without a network.
 * Nothing here changes a device whose radio does both.
 */
object ClientWifi {

    private const val POLL_MS = 500L

    /** How long the platform is given to bring the client back by itself once the hotspot is closed. */
    private const val RESTORE_GRACE_MS = 15_000L

    /** `Settings.Global.WIFI_ON`, the toggle as the platform keeps it across the radio's own states. */
    private const val WIFI_ON = "wifi_on"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun wifi(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** Whether the Wi-Fi client is switched on, or null when the platform would not say. */
    fun isEnabled(context: Context): Boolean? =
        runCatching { wifi(context)?.isWifiEnabled }.getOrNull()

    /**
     * Whether the user wants Wi-Fi on, or null when nothing can be read.
     *
     * Not the same question as [isEnabled]: right after a boot the radio is still coming up when a
     * start begins, and while a hotspot holds the radio the client reads as off although the toggle
     * is on. The toggle is what decides whether a hotspot would be taking something from the user.
     */
    fun wantsWifi(context: Context): Boolean? {
        val radio = runCatching {
            wifi(context)?.wifiState?.let {
                it == WifiManager.WIFI_STATE_ENABLED || it == WifiManager.WIFI_STATE_ENABLING
            }
        }.getOrNull()
        val toggle = runCatching {
            Settings.Global.getInt(context.applicationContext.contentResolver, WIFI_ON, -1)
                .takeIf { it >= 0 }
        }.getOrNull()
        return wifiWanted(radio, toggle)
    }

    /**
     * Whether the client is joined to a network. The connectivity service is asked rather than the
     * Wi-Fi one: it needs no location permission, and a hotspot is not a network in its list.
     */
    @Suppress("DEPRECATION")
    fun isConnected(context: Context): Boolean = runCatching {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrDefault(false)

    /**
     * Whether bringing a hotspot up takes the Wi-Fi client down, or null when that cannot be
     * known.
     *
     * What this build was seen to do comes first: a platform can report that its radio does both
     * and still drop the client, and a start on such a device has already paid for finding out
     * once (see [rememberDisplacement]). Otherwise the platform answers from Android 11 on; before
     * it the answer is "unknown", which leaves the start as it always was.
     */
    fun hotspotDisplacesClient(context: Context): Boolean? {
        if (ShizukuSettings.getHotspotDisplacesWifiBuild() == Build.FINGERPRINT) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return staApConcurrency(context)?.not()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun staApConcurrency(context: Context): Boolean? =
        runCatching { wifi(context)?.isStaApConcurrencySupported }.getOrNull()

    /** Records that a hotspot took the client down on this build, whatever the platform says. */
    fun rememberDisplacement() {
        ShizukuSettings.setHotspotDisplacesWifiBuild(Build.FINGERPRINT)
    }

    /** Waits up to [timeoutMs] for the client to join a network; true as soon as it has. */
    suspend fun awaitConnected(context: Context, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isConnected(context)) return true
            if (System.currentTimeMillis() >= end) return false
            delay(POLL_MS)
        }
    }

    /**
     * Makes sure the Wi-Fi client is on again after a hotspot, in the background: the attempt
     * that raised the hotspot is over and has nothing left to wait on.
     *
     * The platform normally restarts the client when the hotspot goes ("SoftAp disabled, start
     * client mode"), so that is waited for first. If it does not, the API call is tried (it only
     * works for an app the platform still allows to toggle Wi-Fi), then the shell, which is
     * there once the server is up or the phone is rooted.
     */
    fun restoreAfterHotspot(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val end = System.currentTimeMillis() + RESTORE_GRACE_MS
            while (System.currentTimeMillis() < end) {
                if (isEnabled(appContext) != false) {
                    Diag.info(AppConstants.TAG, "Wi-Fi is on after the hotspot")
                    return@launch
                }
                delay(POLL_MS)
            }

            Diag.warn(AppConstants.TAG, "Wi-Fi did not come back after the hotspot closed; switching it on")
            @Suppress("DEPRECATION")
            val viaApi = runCatching { wifi(appContext)?.setWifiEnabled(true) == true }
                .getOrDefault(false)
            if (viaApi && isEnabled(appContext) != false) {
                Diag.info(AppConstants.TAG, "Wi-Fi switched back on through the Wi-Fi service")
                return@launch
            }
            val viaShell = WifiControl.enable()
            Diag.info(
                AppConstants.TAG,
                when (viaShell) {
                    true -> "Wi-Fi switched back on through the shell"
                    false -> "the shell could not switch Wi-Fi back on"
                    null -> "Wi-Fi is off and there is no shell yet to switch it on with"
                }
            )
        }
    }
}

/**
 * Whether the user wants Wi-Fi, from the radio's state ([radioOnOrComingOn]: enabled or enabling)
 * and the platform's toggle ([wifiOnSetting], `Settings.Global.WIFI_ON`: 1 is on, 2 is on in
 * airplane mode, 0 and 3 are off). Either one saying "on" is enough, because each can lag the
 * other: the radio is still coming up after a boot, and a hotspot holding the radio reads as a
 * client that is off while the toggle stays on. Null when neither could be read.
 */
internal fun wifiWanted(radioOnOrComingOn: Boolean?, wifiOnSetting: Int?): Boolean? = when {
    radioOnOrComingOn == true -> true
    wifiOnSetting == 1 || wifiOnSetting == 2 -> true
    radioOnOrComingOn == false || wifiOnSetting != null -> false
    else -> null
}

/**
 * Whether a forced start should leave the hotspot out and wait for Wi-Fi instead: only when Wi-Fi
 * is wanted and the hotspot would take it down. An unknown answer is "no", so a device that cannot
 * say is treated as it always was.
 */
internal fun shouldAvoidHotspot(wantsWifi: Boolean?, hotspotDisplacesClient: Boolean?): Boolean =
    wantsWifi == true && hotspotDisplacesClient == true

/**
 * Whether a hotspot that has just come up was seen to take the client down: the client was on
 * (not merely coming up, which reads as off for a while by itself) before the hotspot, and is off
 * now. An unknown reading on either side is "no", so a guess never marks a device.
 */
internal fun displacementObserved(wifiEnabledBefore: Boolean?, wifiEnabledNow: Boolean?): Boolean =
    wifiEnabledBefore == true && wifiEnabledNow == false
