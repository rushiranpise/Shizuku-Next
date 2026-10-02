package moe.shizuku.manager.ui.screen

import android.content.Context
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.start.StartMethodGuard
import moe.shizuku.manager.start.hasPermission
import moe.shizuku.manager.start.hasWriteSecureSettings
import moe.shizuku.manager.start.isWirelessDebuggingEnabled
import moe.shizuku.manager.start.localNetworkPermission
import moe.shizuku.manager.start.runningStartMethodLabelRes
import moe.shizuku.manager.start.startMethodLabelRes
import moe.shizuku.manager.ui.theme.LocalAmoledTheme
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

/**
 * The state of the app and the service as a block of `label: value` lines.
 *
 * Everything here is a question somebody asks after a start fails - is the service up, as what
 * uid, which API version, what context, is wireless debugging on, is there a port, is the app
 * allowed the local-network permission that discovery needs - and the reason they are in this
 * shape rather than a list of rows is the Copy button under them. A report that arrives with
 * this block in it does not need three follow-up questions first, and the alternative, a screen
 * somebody reads out over a chat, loses the exact strings that matter.
 *
 * The build, the Android version and the device model are here as well as in the rows below,
 * because this block is the thing that gets pasted somewhere: a report that arrives without them
 * needs three questions asked before it can be acted on.
 *
 * Everything is read here rather than through the state the home screen keeps, because the
 * two are not the same question: that state drives the cards, and this is a snapshot of what
 * the app can see right now, taken where it is cheap to take it, off the main thread.
 */
/**
 * The same block, read from live state rather than from a caller's snapshot.
 *
 * The home screen keeps what it shows and hands the values in, so the card cannot describe a
 * service that was there when the screen appeared and not now. Everywhere else - the report
 * dialog is opened from Settings, which keeps none of this - there is nobody to hand them in,
 * so they are asked for here. Must be called off the main thread: probing root spawns a shell
 * and the binder calls block.
 */
internal fun readDiagnostics(context: Context): String {
    val running = ShizukuStateMachine.isRunning()
    return buildDiagnostics(
        context = context,
        running = running,
        uid = if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1,
        method = StartMethodGuard.resolve(),
        hidingActive = HidingWatchService.isRunning() && Hiding.isActive(),
        batteryIgnored = SettingsHelper.isIgnoringBatteryOptimizations(context),
        rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
    )
}

internal fun buildDiagnostics(
    context: Context,
    running: Boolean,
    uid: Int,
    @ShizukuSettings.StartMethod method: Int,
    hidingActive: Boolean,
    batteryIgnored: Boolean,
    rooted: Boolean
): String {
    fun onOff(value: Boolean) = context.getString(if (value) R.string.state_on else R.string.state_off)
    fun yesNo(granted: Boolean) = context.getString(
        if (granted) R.string.diagnostics_granted else R.string.diagnostics_missing
    )

    // Null where the platform has no such permission at all, which is a different answer from
    // "not granted" and has to read as one: on those Androids discovery is not gated.
    val networkPermission = localNetworkPermission()
    val localNetwork = when {
        networkPermission == null -> context.getString(R.string.diagnostics_not_required)
        context.hasPermission(networkPermission) -> context.getString(R.string.diagnostics_granted)
        else -> context.getString(R.string.diagnostics_missing)
    }

    val adbPort = EnvironmentUtils.getAdbTcpPort()

    // Binder calls all of these, and a dead service throws rather than answering.
    val apiVersion = if (running) {
        runCatching { "${Shizuku.getVersion()}.${Shizuku.getServerPatchVersion()}" }.getOrDefault("-")
    } else {
        "-"
    }
    val seContext = if (running) {
        runCatching { Shizuku.getSELinuxContext() }.getOrDefault("-")
    } else {
        "-"
    }
    // How it is running now, what the Start button would use next, and what it is running as:
    // three different questions, which is why they are three lines. A server can be up over adb
    // while the method set for the next start is wireless and the user it runs as is none of them.
    val startedWith = if (running) {
        startedWithLabel(context, uid)
    } else {
        context.getString(R.string.status_value_none)
    }
    val transport = if (running) {
        transportLabel(context, uid)
    } else {
        context.getString(R.string.status_value_none)
    }
    val uidText = if (running && uid >= 0) {
        uid.toString() + (uidName(context, uid)?.let { " $it" } ?: "")
    } else {
        context.getString(R.string.status_value_none)
    }

    return buildString {
        appendLine("${context.getString(R.string.diagnostics_app)}: ${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("${context.getString(R.string.diagnostics_android)}: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("${context.getString(R.string.diagnostics_device)}: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine(
            "${context.getString(R.string.diagnostics_service)}: " +
                context.getString(if (running) R.string.status_running_short else R.string.status_stopped_short)
        )
        appendLine("${context.getString(R.string.diagnostics_server_uid)}: $uidText")
        appendLine("${context.getString(R.string.diagnostics_server_api)}: $apiVersion")
        appendLine("${context.getString(R.string.diagnostics_server_context)}: $seContext")
        appendLine("${context.getString(R.string.diagnostics_started_with)}: $startedWith")
        appendLine("${context.getString(R.string.home_status_transport_label)}: $transport")
        // Not the same line as "started with": this is what the Start button will use next,
        // and it is the one that answers a report that arrives from a stopped service.
        appendLine("${context.getString(R.string.diagnostics_default)}: ${context.getString(startMethodLabelRes(method))}")
        appendLine("${context.getString(R.string.diagnostics_write_secure)}: ${yesNo(context.hasWriteSecureSettings())}")
        appendLine(
            "${context.getString(R.string.diagnostics_wireless_debugging)}: " +
                onOff(context.isWirelessDebuggingEnabled())
        )
        appendLine(
            "${context.getString(R.string.diagnostics_adb_port)}: " +
                if (adbPort > 0) adbPort.toString() else context.getString(R.string.diagnostics_closed)
        )
        appendLine(
            "${context.getString(R.string.diagnostics_wifi)}: " +
                context.getString(
                    if (EnvironmentUtils.isWifiConnected()) R.string.diagnostics_connected
                    else R.string.diagnostics_not_connected
                )
        )
        appendLine("${context.getString(R.string.diagnostics_local_network)}: $localNetwork")
        appendLine(
            "${context.getString(R.string.diagnostics_battery)}: " +
                context.getString(
                    if (batteryIgnored) R.string.diagnostics_battery_unrestricted
                    else R.string.diagnostics_battery_optimized
                )
        )
        appendLine("${context.getString(R.string.diagnostics_hiding)}: ${onOff(hidingActive)}")
        appendLine(
            "${context.getString(R.string.diagnostics_root)}: " +
                context.getString(
                    if (rooted) R.string.diagnostics_available else R.string.diagnostics_unavailable
                )
        )
    }.trim()
}

/**
 * The method the running server was started with. Falls back to the adb transport when no
 * launch of ours was recorded - the server was started by another tool, or from a computer
 * with the command this app hands out - and shared with the notifications, so both name the
 * method the same way.
 */
internal fun startedWithLabel(context: Context, uid: Int): String =
    runningStartMethodLabelRes()?.let { context.getString(it) } ?: transportLabel(context, uid)

/**
 * The wire the running server is on.
 *
 * The transport is only known for a launch of ours that went over adb. A root or system launch
 * doesn't record one, a server started outside the app records nothing at all, and whatever an
 * earlier adb launch recorded would be a lie about this server. Those cases read "adb", which
 * is true: the server does run as adb, that is the fact worth showing, and which wire carried
 * the command isn't knowable from here anyway.
 *
 * "adb (...)" rather than the method names, so the transport can't be confused with the start
 * method beside it.
 */
internal fun transportLabel(context: Context, uid: Int): String = when {
    uid == 0 -> context.getString(R.string.start_method_root)
    uid == 1000 -> context.getString(R.string.start_method_system)
    launchedByUsOverAdb() -> when (ShizukuSettings.getLastAdbTransport()) {
        ShizukuSettings.ADB_TRANSPORT_TCP -> context.getString(R.string.home_status_adb_usb)
        ShizukuSettings.ADB_TRANSPORT_TLS -> context.getString(R.string.home_status_adb_wireless)
        else -> context.getString(R.string.transport_adb)
    }

    else -> context.getString(R.string.transport_adb)
}

/** True when the running server is one this app started over adb. */
private fun launchedByUsOverAdb(): Boolean = when (ShizukuSettings.getRunningStartMethod()) {
    ShizukuSettings.StartMethod.WIRELESS, ShizukuSettings.StartMethod.USB -> true
    else -> false
}

/**
 * What the uid is called, in brackets: 0, 1000 and 2000 are the ones worth being able to read
 * at a glance.
 *
 * Note that 2000 is shell, not adb: it is the shell user whichever wire the server came in on,
 * and naming it after the transport printed the same word twice on the status card that used
 * to show this.
 */
internal fun uidName(context: Context, uid: Int): String? {
    val name = when (uid) {
        0 -> R.string.uid_name_root
        1000 -> R.string.uid_name_system
        2000 -> R.string.uid_name_shell
        else -> return null
    }
    return context.getString(R.string.uid_name_format, context.getString(name))
}

/**
 * The block above, in the same card shape as everything else here, with the one action it is
 * for: putting the whole thing on the clipboard.
 *
 * Monospaced, because it is read as lines of `label: value` and pasted somewhere that keeps
 * them, and because that is what says "this is the machine's answer, not the app talking".
 */
@Composable
internal fun DiagnosticsCard(text: String, onCopy: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (LocalAmoledTheme.current) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.large
                    )
                } else {
                    Modifier
                }
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.MonitorHeart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // An outlined button rather than a text one: a control that puts something on the
            // clipboard has to look like a control, and this is the card's whole purpose.
            OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostics_copy))
            }
        }
    }
}
