package moe.shizuku.manager.manage

import moe.shizuku.manager.ShizukuSettings

/**
 * Apps that bulk changes in Labs leave alone.
 *
 * A pin is a property of the app rather than of one list: somebody who pins an app does not want
 * it silently changed by an "all" action, whichever list they are looking at. The set is stored
 * beside the other settings, so a backup carries it and a restore brings it back.
 */
object PinnedApps {

    private const val KEY = "labs_pinned_apps"

    fun all(): Set<String> = ShizukuSettings.getPreferences()
        .getStringSet(KEY, emptySet())
        .orEmpty()
        // Copied: a set handed back by SharedPreferences is not one to mutate.
        .toSet()

    /** Pins or unpins [packageName], returning whether it is pinned afterwards. */
    fun toggle(packageName: String): Boolean {
        val pinned = all()
        val shouldPin = packageName !in pinned
        write(if (shouldPin) pinned + packageName else pinned - packageName)
        return shouldPin
    }

    fun clear() {
        ShizukuSettings.getPreferences().edit().remove(KEY).apply()
    }

    private fun write(packages: Set<String>) {
        ShizukuSettings.getPreferences().edit()
            .putStringSet(KEY, packages)
            .apply()
    }
}
