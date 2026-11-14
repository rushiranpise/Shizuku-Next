package moe.shizuku.manager.start

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils

/** What the user can do about a failed start, if anything. */
enum class StartFailureKind {
    GENERIC,

    /** Wireless debugging can't be enabled without a Wi-Fi connection. */
    WIFI,

    /** The ADB TLS handshake was rejected: the device needs pairing. */
    PAIRING,

    /** No local ADB port is open it can be opened from the card. */
    PORT,

    /**
     * Wireless debugging has to be switched on by hand: without WRITE_SECURE_SETTINGS the
     * app can't do it, but a user can, in Developer options. The card offers that instead
     * of leaving the adb command as the only way out (plenty of people have no computer).
     */
    SETTINGS
}

/** Outcome of the most recent start attempt, surfaced in the manager UI. */
sealed interface StartStatus {
    data object Idle : StartStatus
    data object Starting : StartStatus
    data object Succeeded : StartStatus
    data class Failed(
        val message: String,
        val kind: StartFailureKind = StartFailureKind.GENERIC
    ) : StartStatus
}

/**
 * A start usually runs in the background (AdbStartWorker), so failures used to be
 * invisible in the app. Both the starter and the worker report here and the home
 * screen shows the reason (and a fix action where one exists).
 */
/** The message shown when a USB start finds no classic ADB port. */
fun Context.usbStartNoPortMessage(): String = getString(R.string.start_failed_usb_no_port)

/** Display name of a start method, shared by the home and settings screens. */
@StringRes
fun startMethodLabelRes(@ShizukuSettings.StartMethod method: Int): Int = when (method) {
    ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK ->
        R.string.start_method_wireless_no_network

    ShizukuSettings.StartMethod.USB -> R.string.start_method_usb
    ShizukuSettings.StartMethod.SYSTEM -> R.string.start_method_system
    ShizukuSettings.StartMethod.ROOT -> R.string.start_method_root
    else -> R.string.start_method_wireless
}

/**
 * The method the running server was started with, or null when no launch of ours was
 * recorded (e.g. something outside the app started it).
 */
@StringRes
fun runningStartMethodLabelRes(): Int? = when (ShizukuSettings.getRunningStartMethod()) {
    ShizukuSettings.StartMethod.WIRELESS -> R.string.start_method_wireless
    ShizukuSettings.StartMethod.USB -> R.string.start_method_usb
    ShizukuSettings.StartMethod.SYSTEM -> R.string.start_method_system
    ShizukuSettings.StartMethod.ROOT -> R.string.start_method_root
    else -> null
}

/** "Wireless debugging", or null when it isn't known. */
fun Context.runningMethodLabel(): String? = runningStartMethodLabelRes()?.let { getString(it) }

/**
 * The same, ready to append to a notification: " · Wireless debugging", or nothing at
 * all when the method isn't known so every status notification can say how Shizuku is
 * running without inventing a value.
 */
fun Context.runningMethodSuffix(): String = runningMethodLabel()?.let { " · $it" } ?: ""

/**
 * Whether what a failure was about is still in the way.
 *
 * A failure here is an instruction about one thing to do - switch wireless debugging on, join a
 * network, open the port - and once it has been done the instruction is worse than useless: it asks
 * for the step a second time, and the flow looks stuck until somebody presses Start and finds out
 * it was not. So the answer is asked about again whenever the screen behind it comes back into
 * view.
 *
 * Only the ones that can be asked about are asked about. Pairing is not one of them: the only thing
 * that knows a phone is paired is a real connection to it, which is the start itself, so that
 * message stays until a start replaces it.
 */
fun StartFailureKind.stillInTheWay(
    wifiConnected: Boolean,
    wirelessDebuggingOn: Boolean,
    adbPort: Int
): Boolean = when (this) {
    StartFailureKind.WIFI -> !wifiConnected
    StartFailureKind.SETTINGS -> !wirelessDebuggingOn
    StartFailureKind.PORT -> adbPort <= 0
    StartFailureKind.PAIRING, StartFailureKind.GENERIC -> true
}

object StartStatusReporter {
    private val _status = MutableStateFlow<StartStatus>(StartStatus.Idle)
    val status: StateFlow<StartStatus> = _status.asStateFlow()

    fun starting() {
        _status.value = StartStatus.Starting
    }

    fun succeeded() {
        _status.value = StartStatus.Succeeded
    }

    fun failed(message: String, kind: StartFailureKind = StartFailureKind.GENERIC) {
        _status.value = StartStatus.Failed(message, kind)
    }

    fun clear() {
        _status.value = StartStatus.Idle
    }
}
