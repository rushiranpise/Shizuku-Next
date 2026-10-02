package moe.shizuku.manager.manage

import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.runShellCommand

/**
 * Dark for the apps that never got around to it, one app at a time.
 *
 * An app that does not implement a dark theme cannot be given one from outside - the theme is the
 * app's own. What can be done is what the apps that do this all do: hold a *device-wide* "force
 * dark" switch on for exactly as long as the app that needs it is in front, and let go when it
 * leaves. That is the whole trick, and everything here is the bookkeeping around it.
 *
 * There is no single switch to hold. The mechanism was never an API, so over the years every
 * vendor and every one of these apps grew its own copy of it, and which one answers depends on
 * the device:
 *
 *     debug.hwui.force_dark            the platform's own, the property the developer option
 *                                      "override force-dark" sets. setprop needs the shell.
 *     global force_dark_mode_on        the setting DarkSwitch writes when the property is refused
 *     secure hwui_force_dark           Samsung's, the one One UI reads
 *     secure aosp_force_dark_mode      AOSP-shaped builds, and OxygenOS
 *     secure op_force_dark_entire_world  OxygenOS
 *
 * They are written together, all five, on every change: a device ignores the keys it does not
 * know, and which of them a given phone reads is not something this app can find out except by
 * trying. Writing them costs one shell round trip.
 *
 * None of it does anything on a modern phone, and the screen says so rather than pretending:
 * Force Dark is off by default for apps targeting API 29 and up, and every Material theme turns
 * it off explicitly, so on an Android 16 device the switches go on and the apps stay as they
 * were. Measured here rather than assumed - the property on, a fresh process, and the system's
 * own Settings app rendering pixel-identical. What still works anywhere is the last resort
 * below, which is why it is offered at all.
 *
 * The last resort is not force dark at all: it is the system's own light/dark theme, switched to
 * dark while the app is in front and put back to whatever it was when it leaves. That darkens
 * any app that implements a dark theme - which on a modern phone is nearly all of them - at the
 * cost of the whole phone going dark with it. It is off by default for that reason.
 */
object ForceDark {

    private const val TAG = "ForceDark"

    private const val PREF_ENABLED = "force_dark_enabled"
    private const val PREF_APPS = "force_dark_apps"
    private const val PREF_SYSTEM_THEME = "force_dark_system_theme"
    private const val PREF_SAVED_NIGHT_MODE = "force_dark_saved_night_mode"

    private fun prefs() = ShizukuSettings.getPreferences()

    /** Whether the whole feature is switched on. */
    fun isEnabled(): Boolean = prefs().getBoolean(PREF_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    /** The apps to force dark while they are open. */
    fun apps(): Set<String> = prefs().getStringSet(PREF_APPS, emptySet()).orEmpty().toSet()

    fun isForced(packageName: String): Boolean = packageName in apps()

    fun setForced(packageName: String, forced: Boolean) {
        if (packageName.isBlank()) return
        val apps = apps().toMutableSet()
        if (forced) apps.add(packageName) else apps.remove(packageName)
        prefs().edit().putStringSet(PREF_APPS, apps).apply()
    }

    /** Whether the feature is on and names an app - the only state a watch has anything to do in. */
    fun hasWorkToDo(): Boolean = isEnabled() && apps().isNotEmpty()

    /**
     * Whether the system's own theme is used as a last resort.
     *
     * Kept apart from the rest because it is not the same bargain: the switches above change only
     * how unthemed apps are drawn, and this changes the whole phone.
     */
    fun usesSystemTheme(): Boolean = prefs().getBoolean(PREF_SYSTEM_THEME, false)

    fun setUsesSystemTheme(on: Boolean) {
        prefs().edit().putBoolean(PREF_SYSTEM_THEME, on).apply()
    }

    // ---- the switches ------------------------------------------------------------

    /**
     * Every force-dark switch there is, as one shell line.
     *
     * The property is setprop and the rest are settings, and they are written in a single call:
     * one round trip through Shizuku instead of five, which matters because this runs on an app
     * change and a switch that lands half a second late is a switch that lands after the screen
     * it was for has already drawn.
     */
    internal fun switches(on: Boolean): String {
        val value = if (on) "1" else "0"
        return "setprop debug.hwui.force_dark " + (if (on) "true" else "false") + "; " +
            "settings put global force_dark_mode_on $value; " +
            "settings put secure hwui_force_dark $value; " +
            "settings put secure aosp_force_dark_mode $value; " +
            "settings put secure op_force_dark_entire_world $value"
    }

    /**
     * What the night mode was before this feature touched it, or null when it has not.
     *
     * It has to be remembered rather than assumed, because the value to go back to is the user's,
     * not "light": somebody who keeps their phone dark all day and names one app to force dark
     * should not have the phone come back light when they close it.
     */
    private fun savedNightMode(): String? =
        prefs().getString(PREF_SAVED_NIGHT_MODE, null)?.takeIf { it.isNotBlank() }

    fun hasSavedTheme(): Boolean = savedNightMode() != null

    /** `cmd uimode night` takes words where the setting stores numbers. */
    internal fun nightModeArgument(value: String): String = when (value.trim()) {
        "2" -> "yes"
        "1" -> "no"
        else -> "auto"
    }

    /**
     * Whether the switches are being held on, as far as this app is the one holding them.
     *
     * Written down rather than kept in memory, and that is the point: a watch process that was
     * killed while the switches were on comes back believing nothing is held, and the device
     * would stay forced dark with nothing left that thinks it has anything to put back. On disk,
     * the next pass - in whichever process that is - reads it and lets go.
     */
    private const val PREF_APPLIED = "force_dark_applied"

    fun isApplied(): Boolean = prefs().getBoolean(PREF_APPLIED, false)

    /**
     * One pass of the rule: the app in front decides.
     *
     * Derived from the foreground every time rather than tracked as state, so the device cannot be
     * left forced dark by a process that died in the middle of a switch - the next pass, in
     * whichever process that is, finds no app on the list in front and lets go.
     */
    fun applyFor(foreground: String?) {
        val wanted = hasWorkToDo() && foreground != null && foreground in apps()
        if (wanted == isApplied()) return
        apply(wanted)
    }

    private fun apply(on: Boolean) {
        val command = StringBuilder(switches(on))

        if (usesSystemTheme()) {
            if (on) {
                // Read before writing, and only the first time: taking the theme on again while
                // it is already held would save "dark" as the value to go back to.
                if (!hasSavedTheme()) {
                    val current = runShellCommand("settings get secure ui_night_mode")?.trim()
                    if (!current.isNullOrBlank() && current != "null") {
                        prefs().edit().putString(PREF_SAVED_NIGHT_MODE, current).apply()
                    }
                }
                command.append("; cmd uimode night yes")
            } else {
                val saved = savedNightMode()
                if (saved != null) {
                    command.append("; cmd uimode night ").append(nightModeArgument(saved))
                    prefs().edit().remove(PREF_SAVED_NIGHT_MODE).apply()
                }
            }
        }

        Diag.info(
            TAG,
            if (on) "forcing dark${if (usesSystemTheme()) " (and the system theme)" else ""}"
            else "letting go"
        )
        runShellCommand(command.toString())
        prefs().edit().putBoolean(PREF_APPLIED, on).apply()
    }

    /**
     * Puts everything back.
     *
     * Only when this app is the one holding it: a watch that stopped while nothing was ever
     * forced has no business writing force-dark switches, and five settings rows for a feature
     * nobody used is a mess somebody else has to explain later.
     */
    fun restore() {
        if (!isApplied() && !hasSavedTheme()) return
        apply(false)
    }

    /** Puts everything back whether or not this app set it - for the switch that turns the
     *  feature off, where the user has just asked for exactly that. */
    fun restoreAlways() = apply(false)

    /**
     * Restarts an app, so the switches it read at start-up are read again.
     *
     * This is what makes the feature usable rather than a puzzle: the switches are read when an
     * app starts, so an app that is already running keeps whatever it found, and the difference
     * between "force dark works" and "force dark does nothing" from the user's seat is whether
     * anything told them to reopen the app. They are on this screen toggling apps, and the app
     * being toggled is behind it rather than in front, so it can be closed out from under them
     * without anything visible happening.
     */
    fun restart(packageName: String) {
        if (packageName.isBlank()) return
        runShellCommand("am force-stop $packageName")
        Diag.info(TAG, "restarted $packageName so it picks the switches up")
    }
}
