package moe.shizuku.manager.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The commands that have been run, newest first.
 *
 * Worth keeping for the same reason the bookmarks are, and more often: the long `pm`, `cmd`
 * and `settings` lines that took a minute to get right are typed once and wanted again an
 * hour later. It is also the ground a macro stands on, since a macro is a recorded sequence
 * of these saved under a name.
 *
 * JSON in the app's own preferences, like the bookmarks: tens of entries, read once when the
 * shell opens, and a table with a DAO and a migration would be more machinery than the thing
 * it holds. Capped, and a command that repeats the one already at the top is moved rather
 * than added, so holding the run button does not fill the list with itself.
 */
object ShellHistory {

    private const val PREFS = "shell_history"
    private const val KEY = "entries"
    private const val LIMIT = 100

    data class Entry(val command: String, val ranAt: Long)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Entry> = runCatching {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)

        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val command = item.optString("command").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Entry(command, item.optLong("ranAt"))
        }.sortedByDescending { it.ranAt }
    }.getOrDefault(emptyList())

    fun record(context: Context, command: String): List<Entry> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return load(context)

        val updated = (listOf(Entry(trimmed, System.currentTimeMillis())) +
            load(context).filterNot { it.command == trimmed }).take(LIMIT)

        save(context, updated)
        return updated
    }

    fun forget(context: Context, command: String): List<Entry> {
        val updated = load(context).filterNot { it.command == command }
        save(context, updated)
        return updated
    }

    fun clear(context: Context): List<Entry> {
        prefs(context).edit().remove(KEY).apply()
        return emptyList()
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("command", entry.command)
                    put("ranAt", entry.ranAt)
                }
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}
