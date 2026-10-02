package moe.shizuku.manager.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.service.WatchdogService

sealed class SettingsPage(
    private val action: String,
    private val fragmentArg: String? = null
) {

    sealed class Developer(
        action: String = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        fragmentArg: String? = null
    ) : SettingsPage(action, fragmentArg) {

        object HighlightUsbDebugging : Developer(fragmentArg = "enable_adb")
        object HighlightWirelessDebugging : Developer(fragmentArg = "toggle_adb_wireless")

        object WirelessDebugging : Developer() {
            override fun buildIntent(context: Context): Intent {
                if (Build.BRAND in setOf("xiaomi", "redmi", "poco")) {
                    HighlightWirelessDebugging.buildIntent(context)
                }

                return Intent(TileService.ACTION_QS_TILE_PREFERENCES).apply {
                    val packageName = "com.android.settings"
                    setPackage(packageName)
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(
                            packageName,
                            "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"
                        )
                    )
                    addFlags(defaultFlags)
                }
            }

            override fun launch(context: Context) {
                runCatching {
                    context.startActivity(buildIntent(context))
                }.recoverCatching {
                    HighlightWirelessDebugging.launch(context)
                }.onFailure { e ->
                    Log.e("SettingsUtils", "Failed to start Settings activity", e)
                }
            }
        }
        
    }

    sealed class Notifications(
        action: String = Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        fragmentArg: String? = null
    ) : SettingsPage(action, fragmentArg) {
        override fun buildIntent(context: Context): Intent {
            return super.buildIntent(context).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }

        object NotificationSettings : Notifications()
        object NotificationChannel : Notifications() {
            override fun buildIntent(context: Context): Intent {
                return super.buildIntent(context).apply {
                    putExtra(Settings.EXTRA_CHANNEL_ID, WatchdogService.CRASH_CHANNEL_ID)
                }
            }
        }
    }

    object InternetPanel : SettingsPage(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    object Accessibility : SettingsPage(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * This app's own page in Settings.
     *
     * It is where "Allow restricted settings" lives, in the menu in the corner: the one way to
     * open Android's restricted-settings gate on a device with no computer attached to run the
     * equivalent app-ops command on it. Android offers that menu entry only once the
     * restriction has been met at least once, which is the case by the time this is opened - the
     * accessibility list has just refused the switch.
     */
    object ApplicationDetails : SettingsPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
        override fun buildIntent(context: Context): Intent {
            return super.buildIntent(context).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }
    }

    /**
     * Android's own list of the apps allowed to ask which app is in front.
     *
     * The second way to give the hiding lists that grant, beside asking Shizuku: a device with
     * no running server still has this page, and it is where somebody who never set Shizuku up
     * would go anyway.
     */
    object UsageAccess : SettingsPage(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    protected val defaultFlags =
        Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_NO_HISTORY or
        Intent.FLAG_ACTIVITY_CLEAR_TASK or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

    open fun buildIntent(context: Context): Intent = Intent(action).apply {
        fragmentArg?.let {
            val fragmentArgKey = ":settings:fragment_args_key"
            putExtra(fragmentArgKey, it)
        }
        flags = defaultFlags
    }

    open fun launch(context: Context) {
        runCatching {
            context.startActivity(buildIntent(context))
        }.onFailure { e ->
            Log.e("SettingsUtils", "Failed to start Settings activity", e)
        }
    }

}