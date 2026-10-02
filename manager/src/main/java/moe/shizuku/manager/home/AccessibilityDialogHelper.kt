package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TypefaceSpan
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.start.isDeveloperOptionsEnabled
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsPage

/**
 * Starts the pairing flow by asking how to pair, always.
 *
 * The two ways want different things from the user: automated pairing reads the code out
 * of the system dialog and so needs the accessibility service running, while manual
 * pairing needs nothing but typing it. So switching the service on is the automated
 * branch's business, and it is only ever raised there: asking for it up front demanded
 * something of a user who may be about to choose the way that doesn't need it at all.
 */
fun Context.showAccessibilityDialog() {
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
    val message = pairingEnableMessage()

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(message)
        .setPositiveButton(R.string.enable) { _, _ ->
            SettingsPage.Accessibility.launch(this)
        }
        .setNeutralButton(R.string.dialog_adb_pairing_open_app_info) { _, _ ->
            SettingsPage.ApplicationDetails.launch(this)
        }
        // The way out that needs none of this: type the code yourself.
        .setNegativeButton(R.string.auto_pair_manual) { _, _ ->
            runCatching {
                startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
            }
        }
        .show()
}

/**
 * The two paragraphs of the dialog, with the one ADB command in them set in monospace.
 *
 * The placeholders are filled in here rather than with TextUtils.expandTemplate: that one parses
 * the whole message as one template and throws for a placeholder a translation carries over from
 * a string it no longer belongs to, and a dialog is a poor place to crash.
 */
private fun Context.pairingEnableMessage(): CharSequence {
    val command = "adb shell cmd appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"
    val text = buildString {
        append(getString(R.string.dialog_adb_pairing_accessibility_enable))
        append("\n\n")
        append(
            getString(R.string.dialog_adb_pairing_accessibility_permission)
                .replace("^1", "ACCESS_RESTRICTED_SETTINGS")
                .replace("^2", command),
        )
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

private fun Context.showNavigateDialog() {
    val isTelevision = EnvironmentUtils.isTelevision() && EnvironmentUtils.isTlsSupported()

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(
            if (isTelevision) R.string.dialog_adb_pairing_accessibility_navigate
            else R.string.auto_pair_instructions
        )
        .setPositiveButton(R.string.development_settings) { _, _ ->
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
                return@setPositiveButton
            }
            if (ensureAccessibilityService()) {
                SettingsPage.Developer.HighlightWirelessDebugging.launch(this)
            }
        }
        .setNegativeButton(R.string.auto_pair_manual) { _, _ ->
            // Reading the code can still fail (OEM dialog, service killed) keep the
            // notification flow one tap away as the fallback.
            runCatching {
                startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
            }
        }
        .show()
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

    Settings.Secure.putString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        newServices,
    )

    return isAccessibilityEnabled()
}
