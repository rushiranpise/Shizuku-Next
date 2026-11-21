package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.start.isDeveloperOptionsEnabled
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsPage

/**
 * Starts the pairing flow. The ways want different things from the user: automated pairing
 * reads the code out of the system dialog and so needs the accessibility service running,
 * while manual pairing needs nothing but typing it. Switching the service on is the
 * automated branch's business, and it is only ever raised there: asking for it up front
 * demanded something of a user who may be about to choose the way that doesn't need it.
 *
 * With legacy pairing on, the choice has already been made, so asking again would only
 * stand between the user and the dialog they asked for.
 */
fun Context.showAccessibilityDialog() {
    if (ShizukuSettings.getLegacyPairing()) {
        startManualPairing()
        return
    }
    showNavigateDialog()
}

/**
 * Makes sure the accessibility service is on for automated pairing, and reports whether it
 * is. When it can't be switched on, the dialog that explains why is shown instead.
 *
 * Shizuku can normally do this by itself: writing the enabled-services setting only needs
 * WRITE_SECURE_SETTINGS, which is why the prompt never appeared on installs that had it. A
 * fresh install doesn't have it yet, so there the user is asked or told the one command
 * that would save them the asking.
 */
private fun Context.ensureAccessibilityService(): Boolean {
    if (isAccessibilityEnabled()) return true

    if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED &&
        enableAccessibilityService()
    ) {
        return true
    }

    showEnableDialog()
    return false
}

/**
 * The one dialog between the automated way and nothing happening, and it names both reasons the
 * switch can refuse to move.
 *
 * A fresh install has no WRITE_SECURE_SETTINGS, so it cannot turn the service on by itself; and
 * Android's restricted settings keep the accessibility switch of an app that did not come from
 * an app store greyed out and inert, which is what the list shows as "Controlled by Restricted
 * Setting". The second one cannot be read back from the platform - the app-op behind it reports
 * the same state before and after Android decides to grey the row - so it is described rather
 * than detected, and the same dialog has to serve both: sending somebody to a dead switch with
 * no explanation is exactly how they end up hunting through App info by themselves.
 */
private fun Context.showEnableDialog() {
    // The reason this dialog is here, then the whole of what the greyed-out row means and both
    // ways to open it: the App info menu on the phone, the command with a computer attached.
    // A TV (the Google TV Streamer among them) has no "Allow restricted settings" entry in App
    // info: TV Settings has no corner menu to put it in. Sending somebody there is a dead end, so
    // on a TV the button is left out and the message names the computer-side ways instead.
    val television = EnvironmentUtils.isTelevision()

    // On a watch the long computer-side explanation is not what the user is holding, and an
    // AlertDialog lays it out in a strip too narrow to read. Say what to do in the fewest
    // lines that fit, and let the watch panel scroll if it still needs to.
    if (EnvironmentUtils.isWatch()) {
        showWatchPairingDialog(
            title = getString(R.string.dialog_adb_pairing_title),
            message = getString(R.string.dialog_adb_pairing_accessibility_enable_watch),
            positive = WatchAction(R.string.enable) { SettingsPage.Accessibility.launch(this) },
            neutral = WatchAction(R.string.dialog_adb_pairing_open_app_info) {
                SettingsPage.ApplicationDetails.launch(this)
            },
            negative = WatchAction(R.string.auto_pair_manual) { startManualPairing() },
        )
        return
    }

    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(pairingEnableMessage(television))
        .setPositiveButton(R.string.enable) { _, _ ->
            SettingsPage.Accessibility.launch(this)
        }
        // The way out that needs none of this: type the code yourself.
        .setNegativeButton(R.string.auto_pair_manual) { _, _ -> startManualPairing() }
    if (!television) {
        builder.setNeutralButton(R.string.dialog_adb_pairing_open_app_info) { _, _ ->
            SettingsPage.ApplicationDetails.launch(this)
        }
    }
    builder.show()
}

/**
 * The two paragraphs of the dialog, with the one ADB command in them set in monospace.
 *
 * The placeholders are filled in here rather than with TextUtils.expandTemplate: that one parses
 * the whole message as one template and throws for a placeholder a translation carries over from
 * a string it no longer belongs to, and a dialog is a poor place to crash.
 */
private fun Context.pairingEnableMessage(television: Boolean): CharSequence {
    val command = "adb shell cmd appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"
    val text = buildString {
        // The package as it is installed now, not the one it was built as: a hidden copy is
        // told to grant a name that is not installed otherwise, and the command fails.
        append(getString(R.string.dialog_adb_pairing_accessibility_enable, packageName))
        append("\n\n")
        if (television) {
            append(getString(R.string.dialog_adb_pairing_accessibility_permission_tv).replace("^1", command))
        } else {
            append(
                getString(R.string.dialog_adb_pairing_accessibility_permission)
                    .replace("^1", "ACCESS_RESTRICTED_SETTINGS")
                    .replace("^2", command),
            )
        }
    }

    return SpannableString(text).apply {
        val start = indexOf(command)
        if (start >= 0) {
            setSpan(
                TypefaceSpan("monospace"),
                start,
                start + command.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}

/**
 * Explains the automated flow and opens Developer options once the accessibility service is
 * ready. The manual path stays one tap away.
 */
private fun Context.showNavigateDialog() {
    val isTelevision = EnvironmentUtils.isTelevision() && EnvironmentUtils.isTlsSupported()

    // A watch has neither the width nor the taps for the full instructions, and the Material
    // dialog there is laid out for a phone: its title took most of the panel and left the
    // message a line-tall strip. The watch panel scrolls in a square that fits the circle.
    if (EnvironmentUtils.isWatch()) {
        showWatchPairingDialog(
            title = getString(R.string.dialog_adb_pairing_title),
            message = getString(R.string.auto_pair_instructions_watch),
            positive = WatchAction(R.string.development_settings) { startAutomaticPairing() },
            negative = WatchAction(R.string.auto_pair_manual) { startManualPairing() },
        )
        return
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(
            if (isTelevision) R.string.dialog_adb_pairing_accessibility_navigate
            else R.string.auto_pair_instructions
        )
        .setPositiveButton(R.string.development_settings) { _, _ -> startAutomaticPairing() }
        .setNegativeButton(R.string.auto_pair_manual) { _, _ -> startManualPairing() }
        .show()
}

/** The automated branch: make sure the reader is on, then open the screen it drives. */
private fun Context.startAutomaticPairing() {
    // Only now, having chosen the automated way, is the accessibility service
    // needed and only if Shizuku can't switch it on itself.
    // The screen this opens is under Developer options, which this app's own "ADB
    // without Developer options" setting hides: launching then would open nothing.
    if (!isDeveloperOptionsEnabled()) {
        Toast.makeText(
            this,
            getString(R.string.toast_developer_options_required),
            Toast.LENGTH_LONG
        ).show()
        return
    }

    if (ensureAccessibilityService()) {
        SettingsPage.Developer.HighlightWirelessDebugging.launch(this)
        // A watch may already have the service connected from an earlier attempt, in which
        // case it is not going to be told to drive by another connection: ask it directly.
        // If it is not connected yet, it schedules the same drive when the system binds it.
        // A phone keeps its old flow: the service drives itself once Android binds it.
        if (EnvironmentUtils.isWatch()) {
            AdbPairingAccessibilityService.drive()
        }
    }
}

/** The manual branch: type the code in the app, or in the notification. */
private fun Context.startManualPairing() {
    // The setting is about this choice: with it on, the code is entered in a dialog in
    // the app instead of the notification flow. The tutorial that hosts the dialog is an
    // Android 11 activity (wireless pairing arrived with it), so below that the call is not
    // made at all and the notification flow below is the way in, as it always was.
    if (ShizukuSettings.getLegacyPairing() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // The tutorial activity is still the way in: it asks for the permissions discovery
        // needs, then shows the code dialog instead of starting the notification flow.
        runCatching {
            startActivity(AdbPairingTutorialActivity.inAppPairingIntent(this))
        }
        return
    }

    // Reading the code can still fail (OEM dialog, service killed) keep the
    // notification flow one tap away as the fallback.
    runCatching {
        startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
    }
}

/** One row of a [showWatchPairingDialog]. */
private class WatchAction(val labelRes: Int, val onClick: () -> Unit)

/**
 * The pairing dialogs as they should look on a watch.
 *
 * The Material dialog was laid out for a phone. On a 480x480 round watch it fills the screen,
 * spends most of it on the title, and stacks the buttons into the bottom of the circle; the
 * message ends up in a strip one line tall and "Manual" is half off the screen. This uses a
 * square smaller than the screen so text stays inside the round edge, and scrolls the whole
 * content, so every line and every button can be reached.
 */
private fun Context.showWatchPairingDialog(
    title: String,
    message: String,
    positive: WatchAction,
    negative: WatchAction,
    neutral: WatchAction? = null,
) {
    val view = LayoutInflater.from(this).inflate(R.layout.dialog_watch_pairing, null)
    view.findViewById<TextView>(R.id.title).text = title
    view.findViewById<TextView>(R.id.message).text = message

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(view)
    dialog.setCanceledOnTouchOutside(false)

    fun bind(id: Int, action: WatchAction?) {
        val button = view.findViewById<MaterialButton>(id)
        if (action == null) {
            button.isVisible = false
        } else {
            button.text = getString(action.labelRes)
            button.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
        }
    }
    bind(R.id.positive, positive)
    bind(R.id.neutral, neutral)
    bind(R.id.negative, negative)

    dialog.show()

    // A square that leaves the corners of the circle empty, so the title and the last button
    // are not cut by the bezel. In pixels because the window takes no dp.
    val metrics = resources.displayMetrics
    val side = (minOf(metrics.widthPixels, metrics.heightPixels) - 48 * metrics.density).toInt()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        setGravity(Gravity.CENTER)
        setLayout(side, side)
    }
}

/**
 * True when the pairing accessibility service is switched on, i.e. codes can be read
 * out of the pairing dialog without the user typing them.
 */
fun Context.isAccessibilityEnabled(): Boolean {
    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    return getEnabledAccessibilityServices()?.any { it.equals(accessibilityServiceName) } ?: false
}

private fun Context.getEnabledAccessibilityServices(): List<String>? {
    val enabledServices =
        Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    return enabledServices?.split(":")
}

/**
 * Writes the pairing service into the enabled-services setting.
 *
 * The app marks the connection before the write, so the service leaves Developer options in
 * front, and clears the mark if the write did not take.
 */
private fun Context.enableAccessibilityService(): Boolean {
    if (isAccessibilityEnabled()) return true

    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    val enabledServices = getEnabledAccessibilityServices()
    val newServices =
        if (enabledServices.isNullOrEmpty()) {
            accessibilityServiceName
        } else {
            enabledServices.joinToString(":") + ":$accessibilityServiceName"
        }

    // This app is enabling the service, so the connection that follows belongs to this
    // flow. Leave Developer options in front instead of pulling Shizuku back over it.
    AdbPairingAccessibilityService.noteAppEnabledService()
    Settings.Secure.putString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        newServices,
    )

    return isAccessibilityEnabled().also { enabled ->
        if (!enabled) AdbPairingAccessibilityService.clearAppEnabledService()
    }
}
