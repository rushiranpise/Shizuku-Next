package moe.shizuku.manager.manage

import moe.shizuku.manager.ShizukuSettings

/**
 * The activities somebody saved, newest first.
 *
 * What a saved activity is, is a component name: that is all that is needed to open it again. The
 * title is kept beside it so the row reads the same on the screen it was saved from, without
 * reading the manifest of every app again to draw a list of a few rows - and an app that has since
 * been uninstalled leaves a record that can still be shown and removed rather than a silent gap.
 *
 * One string rather than a string set, because a set has no order: these are listed newest first,
 * which is the order somebody saves them in, and a saved list that reshuffled itself is a list
 * nobody can learn.
 */
object ActivityFavorites {

    private const val PREF = "activities_saved"

    /** Between the three fields of one saved activity. */
    private const val FIELD = "\u001F"

    /** Between one saved activity and the next. */
    private const val RECORD = "\u001E"

    /** One saved activity, as it is stored and shown. */
    data class Saved(val packageName: String, val name: String, val title: String) {
        /** What a saved activity is matched by, the same key a row carries. */
        val key: String get() = packageName + "/" + name
    }

    fun all(): List<Saved> = parse(ShizukuSettings.getPreferences().getString(PREF, null))

    /**
     * Saves [activity], or forgets it when it is already saved.
     *
     * One action rather than two, because one star both adds and removes. The list it now stands
     * at is returned, so a screen that has just toggled a row does not have to read the
     * preference again to draw the result.
     */
    fun toggle(activity: Activities.Activity): List<Saved> {
        val next = toggled(all(), activity)
        write(next)
        return next
    }

    fun remove(packageName: String, name: String): List<Saved> {
        val key = packageName + "/" + name
        val next = all().filterNot { it.key == key }
        write(next)
        return next
    }

    /** The saved list with [activity] either added in front of it or taken out of it. */
    internal fun toggled(now: List<Saved>, activity: Activities.Activity): List<Saved> {
        val saved = Saved(activity.packageName, activity.name, activity.title)
        return if (now.any { it.key == saved.key }) {
            now.filterNot { it.key == saved.key }
        } else {
            listOf(saved) + now
        }
    }

    private fun write(saved: List<Saved>) {
        ShizukuSettings.getPreferences().edit().putString(PREF, format(saved)).apply()
    }

    internal fun format(saved: List<Saved>): String =
        saved.joinToString(RECORD) { it.packageName + FIELD + it.name + FIELD + it.title }

    /**
     * The saved activities a stored line holds.
     *
     * A record that is not three fields is dropped rather than read as one: a list is not the
     * place to find out that the line holding it has been damaged, and a row built out of half a
     * record names nothing that can be opened.
     */
    internal fun parse(text: String?): List<Saved> {
        if (text.isNullOrEmpty()) return emptyList()

        return text.split(RECORD).mapNotNull { record ->
            val parts = record.split(FIELD)
            if (parts.size != 3) return@mapNotNull null

            val packageName = parts[0]
            val name = parts[1]
            if (packageName.isEmpty() || name.isEmpty()) null else Saved(packageName, name, parts[2])
        }
    }
}
