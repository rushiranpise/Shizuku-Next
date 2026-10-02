package moe.shizuku.manager.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The commands worth keeping.
 *
 * A command that took a while to work out the exact `settings` key, the right `cmd` subcommand
 * is worth more than the typing it saves, so it is kept by name and run again later.
 *
 * Stored as JSON in the app's own preferences rather than a database: the list is tens of
 * entries long, read once when the shell opens, and a table, a DAO and a migration for it would
 * be more machinery than the thing it holds. The order is the order it was added in, newest
 * first, which is the order the sheet shows and the reason [Bookmark.addedAt] exists.
 */
object ShellBookmarks {

    private const val PREFS = "shell_bookmarks"
    private const val KEY = "bookmarks"

    data class Bookmark(
        val id: String,
        val name: String,
        val command: String,
        val addedAt: Long
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Bookmark> = runCatching {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Bookmark(
                id = item.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                name = item.optString("name"),
                command = item.optString("command"),
                addedAt = item.optLong("addedAt")
            )
        }.sortedByDescending { it.addedAt }
    }.getOrDefault(emptyList())

    private fun write(context: Context, bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            array.put(
                JSONObject()
                    .put("id", bookmark.id)
                    .put("name", bookmark.name)
                    .put("command", bookmark.command)
                    .put("addedAt", bookmark.addedAt)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    /**
     * Keeps [command] under [name]. Saving the same command twice under different names is
     * allowed the same line is often wanted with different arguments already filled in so
     * the identity is the entry, not the text.
     */
    fun add(context: Context, name: String, command: String): Bookmark {
        val bookmark = Bookmark(
            id = System.currentTimeMillis().toString(),
            name = name.trim().ifEmpty { command.trim().substringBefore(' ') },
            command = command.trim(),
            addedAt = System.currentTimeMillis()
        )
        write(context, load(context) + bookmark)
        return bookmark
    }

    fun remove(context: Context, id: String) {
        write(context, load(context).filterNot { it.id == id })
    }

    /** Puts back something that was just removed, which is what the undo offer needs. */
    fun restore(context: Context, bookmark: Bookmark) {
        write(context, load(context).filterNot { it.id == bookmark.id } + bookmark)
    }

    fun rename(context: Context, id: String, name: String) {
        write(context, load(context).map { if (it.id == id) it.copy(name = name.trim()) else it })
    }
}
