package moe.shizuku.manager.manage

import android.os.Build
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand

/**
 * The per-app compat change that lets an app which asks for landscape follow the user's rotation.
 *
 * The platform owns the override and does not offer a read command for it, so the state is read
 * back from `dumpsys platform_compat`, the same place `am compat` wrote it. Keeping a copy here
 * would be a second answer that drifts as soon as the change is reset or made from adb.
 */
object RotationOverride {

    private const val CHANGE = "OVERRIDE_ANY_ORIENTATION_TO_USER"

    /** The change was added with Android 16's orientation behaviour. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 36

    /** Every package with the override set, as the platform reports it. */
    fun enabledPackages(): Set<String> =
        parse(runShellCommand("dumpsys platform_compat"))

    /**
     * Applies the override and reports whether the platform holds the requested state.
     *
     * The app is deliberately not protected with `--no-kill`: a running process keeps the
     * orientation it started with, so closing it is what makes the switch take effect.
     */
    fun setEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!ShizukuStateMachine.isRunning()) return false

        val verb = if (enabled) "enable" else "disable"
        runShellCommand("am compat $verb $CHANGE $packageName")
        return readEnabled(packageName) == enabled
    }

    private fun readEnabled(packageName: String): Boolean? {
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
}
