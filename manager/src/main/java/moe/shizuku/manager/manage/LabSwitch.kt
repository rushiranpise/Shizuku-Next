package moe.shizuku.manager.manage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.start.stopShizuku
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand
import rikka.shizuku.Shizuku

/**
 * Every switch in this app that is about a whole feature rather than one app.
 *
 * The Lab screen is built out of two kinds of thing: lists, where each row is an app, and modes,
 * where there is one state for the lot. The second kind is what this is: the firewall's chain, the
 * autostart list's gate, each hiding setting's mode, force dark, the watchdog, and the service
 * itself. All of them exist as a single on or off, which is what a tile in the shade and a widget
 * on the home screen can offer without a screen of their own.
 *
 * The rules stay where they already were - [FirewallChain], [AutoStartGate], [Hiding], [ForceDark],
 * [ShizukuSettings] and the start and stop paths - and this is only the name and the two calls a
 * tile and a widget need. That is deliberate: a switch outside the app must do exactly what the
 * switch inside it does, and the way to make that true is to have one implementation.
 *
 * Nothing here remembers a state, with one exception: a write to a setting Android 17 redacts is
 * the only evidence there is, so that write is kept for this process. Every other read asks the
 * thing that owns it: the platform for the chain, this app's settings for the gates, the server
 * for whether it is running. Anything else would describe the last tap rather than the phone.
 */
enum class LabSwitch(
    @StringRes val label: Int,
    val image: ImageVector,
    /**
     * The platform setting this switch is, for the three that are a setting rather than this app's
     * own state. Null for everything else.
     */
    private val settingKey: String? = null
) {

    /** The service itself: starting it, or stopping it the way the app's own stop does. */
    SERVICE(R.string.lab_switch_service, Icons.Outlined.PowerSettingsNew),

    FIREWALL(R.string.firewall_tile_label, Icons.Outlined.Shield),

    AUTOSTART(R.string.tab_autostart, Icons.Outlined.RestartAlt),

    // The three settings themselves, on and off, which is not the same question as hiding them
    // from an app: these are the platform's own switches, and turning one off here turns it off.
    DEVELOPER_OPTIONS(
        R.string.lab_switch_developer_options,
        Icons.Outlined.BugReport,
        "development_settings_enabled"
    ),
    USB_DEBUGGING(R.string.lab_switch_usb_debugging, Icons.Outlined.Cable, "adb_enabled"),
    WIRELESS_DEBUGGING(
        R.string.lab_switch_wireless_debugging,
        Icons.Outlined.Wifi,
        "adb_wifi_enabled"
    ),

    /** All six hiding settings at once, for a phone that should stop hiding everything. */
    HIDING(R.string.lab_switch_hiding, Icons.Outlined.VisibilityOff),

    HIDE_DEVELOPER_OPTIONS(
        R.string.lab_switch_hide_developer_options,
        Icons.Outlined.DeveloperMode
    ),
    HIDE_USB_DEBUGGING(R.string.lab_switch_hide_usb_debugging, Icons.Outlined.Usb),
    HIDE_WIRELESS_DEBUGGING(
        R.string.lab_switch_hide_wireless_debugging,
        Icons.Outlined.WifiOff
    ),
    HIDE_ACCESSIBILITY(R.string.hiding_signal_accessibility, Icons.Outlined.AccessibilityNew),
    HIDE_PRIVATE_DNS(R.string.hiding_signal_private_dns, Icons.Outlined.Dns),
    HIDE_VPN(R.string.hiding_signal_vpn, Icons.Outlined.VpnKey),

    FORCE_DARK(R.string.tab_force_dark, Icons.Outlined.DarkMode),

    WATCHDOG(R.string.lab_switch_watchdog, Icons.Outlined.MonitorHeart);

    /**
     * The last write this process made to a redacted setting, or null before one.
     *
     * Android 17 will not read the redacted debug settings back, so the write is the only evidence
     * there is. Keeping it makes the home buttons, tiles and widgets agree until the process ends;
     * a new process starts from the redacted read again.
     */
    @Volatile
    private var redactedWrite: Boolean? = null

    /** The one hiding setting this switch is, or null for every other kind of switch. */
    private val signal: Signal?
        get() = when (this) {
            HIDE_DEVELOPER_OPTIONS -> Signal.DEVELOPER_OPTIONS
            HIDE_USB_DEBUGGING -> Signal.USB_DEBUGGING
            HIDE_WIRELESS_DEBUGGING -> Signal.WIRELESS_DEBUGGING
            HIDE_ACCESSIBILITY -> Signal.ACCESSIBILITY
            HIDE_PRIVATE_DNS -> Signal.PRIVATE_DNS
            HIDE_VPN -> Signal.VPN
            else -> null
        }

    /**
     * Whether the feature is on, or null when nothing could be asked.
     *
     * A start that is under way reads as on and a stop that is under way reads as off: what the
     * switch is showing is what the phone is doing about it, and a service caught mid-change is
     * not in the state it is leaving.
     */
    fun isOn(): Boolean? = when (this) {
        // The binder first, because a process that has just started has not heard anything about
        // the server yet and a tile in the shade must not say Off about one that is running. A
        // start still under way counts as on, which is the state it is heading for.
        SERVICE -> Shizuku.pingBinder() || ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING

        FIREWALL -> FirewallChain.isOn()

        AUTOSTART -> AutoStartGate.isOn()

        // Any of the six being on counts, because the switch is about the feature doing
        // something: turning it off is how a phone stops hiding, and that is the direction
        // somebody reaches for this in a hurry.
        HIDING -> Signal.entries.any { Hiding.isSignalEnabled(it) }

        FORCE_DARK -> ForceDark.isEnabled()

        WATCHDOG -> ShizukuSettings.getWatchdog()

        // The platform's own settings. Read through EnvironmentUtils for the three Android 17
        // redacts; their raw value would read as off.
        DEVELOPER_OPTIONS -> readPlatformSwitch(
            EnvironmentUtils.isDeveloperOptionsSwitchOn()
        )
        USB_DEBUGGING -> readPlatformSwitch(EnvironmentUtils.isUsbDebuggingEnabled())
        WIRELESS_DEBUGGING -> readPlatformSwitch(
            EnvironmentUtils.isWirelessDebuggingEnabled()
        )

        else -> signal?.let { Hiding.isSignalEnabled(it) } ?: settingKey?.let { readGlobal(it) }
    }

    /**
     * Puts the feature in [on], answering the state to show afterwards, or null when it could not
     * be told.
     *
     * Called off the main thread: the firewall and autostart switches run shell commands, and the
     * service one can run a whole start.
     */
    fun apply(context: Context, on: Boolean): Boolean? = when (this) {
        // The start is a request rather than a result: what it answers is the request, and the
        // switch shows on as soon as the state machine does, which is inside this call.
        SERVICE -> if (on) {
            ShizukuReceiverStarter.start(context, userInitiated = true, reason = "switch")
            true
        } else if (stopShizuku(context)) {
            false
        } else {
            isOn()
        }

        FIREWALL -> if (FirewallChain.setOn(on)) on else FirewallChain.isOn()

        AUTOSTART -> if (AutoStartGate.setOn(context, on)) on else isOn()

        HIDING -> {
            Signal.entries.forEach { Hiding.setSignalEnabled(it, on) }
            HidingWatchService.refresh(context)
            on
        }

        FORCE_DARK -> {
            ForceDark.setEnabled(on)
            // The watch starts and stops with the mode, the same way it does from the screen.
            HidingWatchService.refresh(context)
            on
        }

        WATCHDOG -> {
            // The setting starts and stops the service and arms the alarm that brings it back.
            ShizukuSettings.setWatchdog(context, on)
            on
        }

        else -> {
            val hiding = signal
            when {
                hiding != null -> {
                    Hiding.setSignalEnabled(hiding, on)
                    HidingWatchService.refresh(context)
                    on
                }

                else -> settingKey?.let { key ->
                    val result = writeGlobal(context, key, on)
                    if (EnvironmentUtils.isDebugSwitchReadRedacted(key)) {
                        redactedWrite = result
                    }
                    result
                }
            }
        }
    }

    /** Uses this process's last redacted write, if there is one, instead of the assumed read. */
    private fun readPlatformSwitch(read: Boolean): Boolean {
        val key = settingKey
        return if (key != null && EnvironmentUtils.isDebugSwitchReadRedacted(key)) {
            redactedWrite ?: read
        } else {
            read
        }
    }

    /** The platform setting [key], or null when it cannot be read. */
    private fun readGlobal(key: String): Boolean? = runCatching {
        Settings.Global.getInt(ShizukuApplication.application.contentResolver, key, 0) != 0
    }.getOrNull()

    /**
     * Writes the platform setting [key], answering the state it ended up in, or null when it could
     * not be written.
     *
     * The app holds WRITE_SECURE_SETTINGS once it has attached to a running server, and that is the
     * copy of the permission these settings need: with it, a tile works with the server stopped,
     * which is the point of a switch that turns debugging off. A phone that has never run the
     * server has no such grant, so the shell is tried as the fallback - and that one does need a
     * server.
     *
     * Android 17 redacts the debug switches to 0, so no read on that platform can confirm an on
     * write. There a direct write that did not throw is the answer; the shell fallback cannot be
     * checked, so it is not used for those settings.
     */
    private fun writeGlobal(context: Context, key: String, on: Boolean): Boolean? {
        val value = if (on) 1 else 0
        val direct = runCatching {
            Settings.Global.putInt(context.contentResolver, key, value)
        }.isSuccess

        if (EnvironmentUtils.isDebugSwitchReadRedacted(key)) return if (direct) on else null

        if (readGlobal(key) == on) return on

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Diag.warn(
                AppConstants.TAG,
                "Could not write the $key setting, and the shell fallback cannot run on the main thread"
            )
            return null
        }

        // The same fallback the Labs switches use: the server is shell, and shell writes these.
        runShellCommand("settings put global $key $value")
        return if (readGlobal(key) == on) on else null
    }

    /** Switches the feature to its other state, answering the new one, or null when it could not. */
    fun toggle(context: Context): Boolean? {
        val current = isOn() ?: return null
        return apply(context, !current)
    }

    /**
     * Says what the switch became, after a tap outside the app.
     *
     * A tile and a widget both answer on the control itself, which is fine when the control is in
     * front of you and useless the moment the shade closes or the card is under a thumb. The toast
     * is the same sentence for both: "Firewall is on". Null means the feature could not be moved
     * at all - most often because the server is not running - and says that instead of naming a
     * state that did not happen.
     */
    fun announce(context: Context, on: Boolean?) {
        val text = context.getString(
            when (on) {
                true -> R.string.lab_switch_is_on
                false -> R.string.lab_switch_is_off
                null -> R.string.lab_switch_could_not_change
            },
            context.getString(label)
        )

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * What the two surfaces outside the app show for a state.
     *
     * One mapping rather than one per surface: the tile and the widget sit on the same screen a
     * few inches apart, and the quickest way to make them contradict each other is to spell the
     * three answers out twice. Unknown is the third answer, and it has a label of its own, because
     * a state that could not be read is not the same thing as a feature that is off.
     */
    enum class Look(@StringRes val label: Int) {
        ON(R.string.lab_state_on),
        OFF(R.string.lab_state_off),
        UNKNOWN(R.string.lab_state_unavailable);

        companion object {
            fun of(on: Boolean?): Look = when (on) {
                true -> ON
                false -> OFF
                null -> UNKNOWN
            }
        }
    }

    companion object {
        /** Sent when a switch changed, so the widget for it shows what the tile just did. */
        const val ACTION_CHANGED = "moe.shizuku.manager.action.LAB_SWITCH_CHANGED"

        /** Which switch changed, as [LabSwitch.name]. */
        const val EXTRA_SWITCH = "moe.shizuku.manager.extra.LAB_SWITCH"

        /** Tells the widget for [switch] that the tile changed it. */
        fun notifyChanged(context: Context, switch: LabSwitch) {
            context.sendBroadcast(
                android.content.Intent(ACTION_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_SWITCH, switch.name)
            )
        }
    }
}
