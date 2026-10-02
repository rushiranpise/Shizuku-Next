package moe.shizuku.manager.utils

import android.util.Log
import moe.shizuku.manager.ShizukuApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What this app did, kept where it can be read back without a computer.
 *
 * Everything the manager decides - the watch hiding and putting back, the watchdog starting a
 * server that fell over, the state machine changing its mind - happens on a device with no
 * screen for it, and the way to see it was `adb logcat`. That window is about ninety-five seconds
 * on a busy phone, so by the time anybody asks "did it work" the answer has usually scrolled
 * away: twice while this was being built, the lines that mattered had been evicted before they
 * could be read.
 *
 * So the same messages go here as well: an in-memory ring, appended to a file, pruned to the last
 * day. The ring is what the screen reads, the file is what survives the process and the phone
 * being locked, and [log] still writes to logcat so nothing that watched it before has to change.
 *
 * Deliberately not a general logger for the whole app: a line belongs here when somebody would
 * want it while holding the phone, which is the watch, the watchdog, the state machine and the
 * permissions they need. Chatty code should keep using [Log] alone.
 */
object Diag {

    /** One line: when, what kind, who said it, and what it said. */
    data class Entry(val at: Long, val level: Char, val tag: String, val message: String)

    /** How long a line is kept. A day is long enough to read yesterday's failure and short
     * enough that the file cannot grow without bound. */
    private const val KEEP_MILLIS = 24 * 60 * 60 * 1000L

    /** The most lines held in memory, whatever the file says. */
    private const val MAX_LINES = 4000

    private const val FILE_NAME = "shizuku-log.txt"

    /**
     * Field separator. A unit separator rather than a comma or a tab, because the message is
     * prose that may contain anything at all and only this character cannot appear in it.
     */
    private const val SEP = '\u001F'

    private val lock = Any()
    private var loaded = false
    private val entries = ArrayDeque<Entry>()

    private fun file(): File = File(ShizukuApplication.application.filesDir, FILE_NAME)

    /** Reads the file once per process, dropping whatever is older than the window. */
    private fun loadLocked() {
        if (loaded) return
        loaded = true

        val f = file()
        if (!f.exists()) return

        val cutoff = System.currentTimeMillis() - KEEP_MILLIS
        var pruned = false
        runCatching {
            f.forEachLine { line ->
                val parts = line.split(SEP)
                if (parts.size < 4) return@forEachLine
                val at = parts[0].toLongOrNull() ?: return@forEachLine
                if (at < cutoff) {
                    pruned = true
                    return@forEachLine
                }
                entries.addLast(
                    Entry(at, parts[1].firstOrNull() ?: 'I', parts[2], parts.drop(3).joinToString(""))
                )
            }
        }
        while (entries.size > MAX_LINES) {
            entries.removeFirst()
            pruned = true
        }
        // Rewritten rather than left, so a file holding nothing but yesterday is emptied when it
        // is next opened instead of at some arbitrary point in its growth.
        if (pruned) runCatching { rewriteLocked() }
    }

    private fun rewriteLocked() {
        val f = file()
        if (entries.isEmpty()) {
            f.delete()
            return
        }
        f.writeText(entries.joinToString("\n") { it.serialise() } + "\n")
    }

    private fun Entry.serialise(): String = "$at$SEP$level$SEP$tag$SEP$message"

    /**
     * Writes one line, to logcat as well as here.
     *
     * Both from one call so that a call site cannot end up in one and not the other: the logcat
     * line is what a developer watching `adb logcat` sees, and the stored line is what the phone
     * can show hours later.
     */
    fun log(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val at = System.currentTimeMillis()
        // The exception's own name and message rather than its stack: a stack drawn onto one line
        // is unreadable on a phone, and the class and message are what say what went wrong. The
        // stack is still in logcat, which the throwable overload passes on.
        val said = if (throwable == null) {
            message
        } else {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        // One line each, because the file is one line each.
        val clean = said.replace(SEP, ' ').replace('\n', ' ').trim()

        Log.println(
            when (level) {
                'E' -> Log.ERROR
                'W' -> Log.WARN
                'D' -> Log.DEBUG
                'V' -> Log.VERBOSE
                else -> Log.INFO
            },
            tag,
            // The stack goes to logcat and not into the stored line, which is one line by
            // definition: whoever is reading the file on a phone wants the class and the message,
            // and whoever is watching logcat wants the frames.
            clean + (throwable?.let { "\n" + Log.getStackTraceString(it) } ?: "")
        )

        synchronized(lock) {
            loadLocked()
            entries.addLast(Entry(at, level, tag, clean))
            while (entries.size > MAX_LINES) entries.removeFirst()
            runCatching { file().appendText("$at$SEP$level$SEP$tag$SEP$clean\n") }
        }
    }

    fun info(tag: String, message: String, throwable: Throwable? = null) =
        log('I', tag, message, throwable)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log('W', tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log('E', tag, message, throwable)

    fun debug(tag: String, message: String, throwable: Throwable? = null) =
        log('D', tag, message, throwable)

    /**
     * The window, oldest first.
     *
     * Pruned on the way out as well as on the way in: a screen left open across midnight should
     * stop showing yesterday without waiting for the process to restart.
     */
    fun entries(): List<Entry> = synchronized(lock) {
        loadLocked()
        val cutoff = System.currentTimeMillis() - KEEP_MILLIS
        var dropped = false
        while (entries.isNotEmpty() && entries.first().at < cutoff) {
            entries.removeFirst()
            dropped = true
        }
        if (dropped) runCatching { rewriteLocked() }
        entries.toList()
    }

    /** The tags that have said something, most talkative first, for the screen's filters. */
    fun tags(): List<String> = entries()
        .groupingBy { it.tag }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

    fun clear() {
        synchronized(lock) {
            loaded = true
            entries.clear()
            runCatching { file().delete() }
        }
        Log.i("ShizukuManager", "the log was cleared")
    }

    /**
     * Writes the window out as a file to share, and returns where it went.
     *
     * Somewhere the user can reach without root: the app's own directory on shared storage, which
     * a file manager and the share sheet can both read.
     */
    fun export(): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = ShizukuApplication.application.getExternalFilesDir(null)
            ?: ShizukuApplication.application.filesDir
        val out = File(dir, "shizuku-log-$stamp.txt")
        return runCatching {
            out.writeText(
                entries().joinToString("\n") { entry ->
                    "${stamp(entry.at)} ${entry.level} ${entry.tag}: ${entry.message}"
                } + "\n"
            )
            out
        }.getOrNull()
    }

    /** `HH:mm:ss.SSS`, the shape a log line wants. */
    fun stamp(at: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(at))
}
