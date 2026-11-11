package moe.shizuku.manager.manage

import android.os.Build
import androidx.annotation.StringRes
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand

/** What an app's rotation should do while it is in front. */
enum class RotationMode(
    @StringRes val labelRes: Int,
    @StringRes val noteRes: Int,
    /** Portrait and Landscape take over the display; Auto only sets the compat override. */
    val forced: Boolean = false,
    /** Surface rotation used for the two forced modes. */
    val rotation: Int = -1
) {
    OFF(
        R.string.rotation_mode_off,
        R.string.rotation_mode_off_note
    ),
    AUTO(
        R.string.rotation_mode_auto,
        R.string.rotation_mode_auto_note
    ),
    PORTRAIT(
        R.string.rotation_mode_portrait,
        R.string.rotation_mode_portrait_note,
        forced = true,
        rotation = 0
    ),
    LANDSCAPE(
        R.string.rotation_mode_landscape,
        R.string.rotation_mode_landscape_note,
        forced = true,
        rotation = 1
    );

    companion object {
        fun from(value: String?): RotationMode =
            entries.firstOrNull { it.name == value } ?: OFF
    }
}

/**
 * Per-app rotation, with a compat change for Auto and a temporary display lock for the two
 * forced modes.
 *
 * Auto is the change from issue #210: `OVERRIDE_ANY_ORIENTATION_TO_USER`. It only works where
 * the platform applies that override, and an app targeting Android 16 can keep requesting its
 * own orientation, which is exactly what the 1.1.1.1 app does. Portrait and Landscape therefore
 * do the thing AppRotate does: `wm fixed-to-user-rotation enabled` plus a locked
 * `user_rotation`, restored when the app leaves. The display is shared, so the lock only lasts
 * while the named app is in front; the watcher applies and restores it.
 *
 * Everything written here is read back. The compat state comes from `dumpsys platform_compat`,
 * and the display mode comes from `wm` and `settings`, so a command that was accepted but not
 * applied does not leave a row claiming it worked.
 */
object RotationOverride {

    private const val CHANGE = "OVERRIDE_ANY_ORIENTATION_TO_USER"
    private const val PREF_MODES = "rotation_modes"
    private const val PREF_BASE = "rotation_base"
    private const val PREF_APPLIED = "rotation_applied"
    private const val FIELD = "\u001f"

    private data class DisplayState(
        val autoRotate: String?,
        val userRotation: String?,
        val fixedToUser: String?,
        val userRotationMode: String?
    )

    /** The forced mode currently holding the display, if any. */
    data class Applied(val packageName: String, val mode: RotationMode)

    private fun prefs() = ShizukuSettings.getPreferences()

    /** The forced display lock arrived with Android 16's rotation behaviour. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 36

    /** Every app with a mode other than Off. */
    fun modes(): Map<String, RotationMode> =
        prefs().getStringSet(PREF_MODES, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val packageName = entry.substringBefore('=')
                val mode = RotationMode.from(entry.substringAfter('=', ""))
                packageName.takeIf { it.isNotEmpty() && mode != RotationMode.OFF }?.let { it to mode }
            }
            .toMap()

    fun modeFor(packageName: String): RotationMode = modes()[packageName] ?: RotationMode.OFF

    fun hasWorkToDo(): Boolean = modes().isNotEmpty()

    fun hasForceWork(): Boolean = modes().values.any { it.forced }

    /**
     * Saves one app's mode and applies the compat half.
     *
     * Forced modes deliberately leave the compat change alone: it does not affect an app that
     * targets Android 16, and the display lock is what makes those modes work. Auto is the one
     * mode that depends on the compat change, so it is the one that fails if the change cannot
     * be enabled.
     */
    fun setMode(packageName: String, mode: RotationMode): Boolean {
        if (!isSupported()) return false

        if (mode == RotationMode.AUTO && !setCompat(packageName, true)) return false
        if (mode == RotationMode.OFF && !setCompat(packageName, false)) return false

        val entries = prefs().getStringSet(PREF_MODES, emptySet()).orEmpty().toMutableSet()
        entries.removeAll { it.substringBefore('=') == packageName }
        if (mode != RotationMode.OFF) entries.add("$packageName=${mode.name}")
        prefs().edit().putStringSet(PREF_MODES, entries).apply()
        return modeFor(packageName) == mode
    }

    /**
     * Applies the lock for [foreground], or puts the display back when that app has no forced
     * mode. Called from the watch every pass, so it has to be cheap when nothing changed.
     */
    fun applyFor(foreground: String?) {
        val packageName = foreground ?: run {
            restore()
            return
        }
        val wanted = modeFor(packageName).takeIf { it.forced } ?: run {
            restore()
            return
        }

        val current = applied()
        if (current?.packageName == packageName && current.mode == wanted) return
        if (!ShizukuStateMachine.isRunning()) return

        // The first lock records the display as the user had it. A base left in settings is
        // reused after a process restart: capturing the forced state as the base would make
        // restore put the phone back into the lock it was trying to remove.
        if (readBase() == null) {
            val base = readDisplayState() ?: return
            saveBase(base)
        }

        if (applyLock(wanted)) {
            saveApplied(Applied(packageName, wanted))
        }
    }

    /** The forced mode currently applied, for the notification and for the next pass. */
    fun applied(): Applied? {
        val raw = prefs().getString(PREF_APPLIED, null) ?: return null
        val packageName = raw.substringBefore('=')
        val mode = RotationMode.from(raw.substringAfter('=', ""))
        if (packageName.isEmpty() || !mode.forced) return null
        return Applied(packageName, mode)
    }

    /**
     * Puts the display back exactly as it was before the first lock.
     *
     * Safe to call every pass: with no saved base there is nothing to restore. The order is the
     * one measured on the device: fixed-to-user first, then the two settings, then the user
     * rotation mode that was in force.
     */
    fun restore() {
        val base = readBase()
        if (base == null) {
            clearApplied()
            return
        }

        val commands = ArrayList<String>(4)
        base.fixedToUser?.let { commands.add("wm fixed-to-user-rotation $it") }
        base.autoRotate?.let { commands.add("settings put system accelerometer_rotation $it") }
        base.userRotation?.let { commands.add("settings put system user_rotation $it") }
        base.userRotationMode?.let { commands.add("wm user-rotation $it") }
        if (commands.isNotEmpty()) {
            // Without a shell the lock cannot be undone. Keeping the saved base is what lets
            // the next watch pass put the display back instead of forgetting what it replaced.
            if (!ShizukuStateMachine.isRunning()) return
            runShellCommand(commands.joinToString("; "))
        }

        clearBase()
        clearApplied()
    }

    // ---- compat ------------------------------------------------------------------

    private fun setCompat(packageName: String, enabled: Boolean): Boolean {
        if (!ShizukuStateMachine.isRunning()) return false

        val current = isEnabled(packageName) ?: return false
        if (current == enabled) return true

        val verb = if (enabled) "enable" else "disable"
        runShellCommand("am compat $verb $CHANGE $packageName")
        return isEnabled(packageName) == enabled
    }

    /** Whether [packageName] holds the override, or null when there is no shell to ask. */
    fun isEnabled(packageName: String): Boolean? {
        val dump = runShellCommand("dumpsys platform_compat") ?: return null
        return packageName in parse(dump)
    }

    /**
     * The packages marked true in the change's `packageOverrides` map.
     *
     * One line of the dump carries the whole map, for example
     * `... packageOverrides={com.example=true}; rawOverrides=...`. A package explicitly set to
     * false is present but not enabled, and an absent package is at the platform default.
     */
    internal fun parse(dump: String?): Set<String> {
        val line = dump
            ?.lineSequence()
            ?.firstOrNull { it.contains("name=$CHANGE") }
            ?: return emptySet()

        val overrides = line
            .substringAfter("packageOverrides={", "")
            .substringBefore("}", "")

        if (overrides.isBlank()) return emptySet()

        return overrides
            .split(',')
            .mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name.takeIf { it.isNotEmpty() && value == "true" }
            }
            .toSet()
    }

    // ---- display lock ------------------------------------------------------------

    private fun readDisplayState(): DisplayState? {
        val output = runShellCommand(
            "settings get system accelerometer_rotation; " +
                "settings get system user_rotation; " +
                "wm fixed-to-user-rotation; " +
                "wm user-rotation"
        ) ?: return null

        val lines = output.lineSequence().map { it.trim() }.toList()
        if (lines.size < 4) return null

        return DisplayState(
            autoRotate = lines[0].usable(),
            userRotation = lines[1].usable(),
            fixedToUser = lines[2].usable(),
            userRotationMode = lines[3].usable()
        )
    }

    private fun applyLock(mode: RotationMode): Boolean {
        val rotation = mode.rotation
        runShellCommand(
            "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation $rotation; " +
                "wm fixed-to-user-rotation enabled; " +
                "wm user-rotation lock $rotation"
        )

        val state = readDisplayState() ?: return false
        return state.autoRotate == "0" &&
            state.userRotation == rotation.toString() &&
            state.fixedToUser == "enabled" &&
            state.userRotationMode == "lock $rotation"
    }

    private fun saveBase(state: DisplayState) {
        prefs().edit().putString(
            PREF_BASE,
            listOf(
                state.autoRotate,
                state.userRotation,
                state.fixedToUser,
                state.userRotationMode
            ).joinToString(FIELD) { it ?: "" }
        ).apply()
    }

    private fun readBase(): DisplayState? {
        val raw = prefs().getString(PREF_BASE, null) ?: return null
        val fields = raw.split(FIELD)
        if (fields.size != 4) return null
        return DisplayState(
            fields[0].usable(),
            fields[1].usable(),
            fields[2].usable(),
            fields[3].usable()
        )
    }

    private fun clearBase() {
        prefs().edit().remove(PREF_BASE).apply()
    }

    private fun saveApplied(applied: Applied) {
        prefs().edit().putString(PREF_APPLIED, "${applied.packageName}=${applied.mode.name}").apply()
    }

    private fun clearApplied() {
        prefs().edit().remove(PREF_APPLIED).apply()
    }

    /** `settings get` prints "null" for an unset key; that is not a value to write back. */
    private fun String?.usable(): String? =
        this?.takeIf { it.isNotBlank() && it != "null" }
}
