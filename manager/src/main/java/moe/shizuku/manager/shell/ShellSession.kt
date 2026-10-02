package moe.shizuku.manager.shell

import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.InputStreamReader
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

/** Where a command runs. */
enum class ShellBackend {
    /** Through the Shizuku server: uid 2000 over adb, 0 for root, 1000 for the exploit. */
    SHIZUKU,

    /** Through `su`. Works with Shizuku stopped, and is the only way to reach uid 0 then. */
    ROOT
}

/** One line of output, and what it is. */
data class ShellLine(val text: String, val kind: Kind) {
    enum class Kind { COMMAND, OUTPUT, ERROR, INFO, EXIT }
}

/**
 * A shell that is not a terminal.
 *
 * There is no tty to be had from an app. rish allocates its pty on the server, and only when
 * the caller's own stdin already is one; an app has no terminal and cannot open `/dev/ptmx`,
 * so anything that insists on a tty an interactive `nano`, an ssh password prompt cannot
 * be run from here. That is what the rish export is for.
 *
 * What is possible is one command at a time, and that is enough for most of it as long as the
 * two things people notice are carried: every command is prefixed with the session's working
 * directory and the variables that have been exported, so `cd` and `export` behave the way a
 * session does even though each command is its own process.
 */
class ShellSession {

    /** Where the next command runs. Resolved by the shell, never by string arithmetic. */
    var cwd: String = DEFAULT_CWD
        private set

    private val exports = linkedMapOf<String, String>()

    /**
     * The process a Shizuku command is running in, so Stop has something to destroy. A root
     * command runs inside a `su` job, which offers no handle on it the screen says so
     * rather than pretending the button works.
     */
    @Volatile
    private var running: Process? = null

    fun stop() {
        runCatching { running?.destroy() }
    }

    fun exported(): Map<String, String> = exports

    /** The script handed to `sh`, which is the same shape for both backends. */
    private fun script(command: String): String = buildString {
        exports.forEach { (name, value) ->
            append("export ").append(name).append('=').append(quote(value)).append('\n')
        }
        // A directory that has gone missing (an unmounted card, a path deleted behind the
        // session) must not swallow the command that was just typed: say so and run it where
        // the shell already is, rather than exiting and leaving a prompt that did nothing.
        append("cd ").append(quote(cwd))
            .append(" 2>/dev/null || echo ")
            .append(quote("sh: cannot enter $cwd"))
            .append(" >&2\n")
        append(command)
    }

    /**
     * Runs [command] and streams its output to [sink].
     *
     * Blocking: callers run it off the main thread. Returns the exit code, or -1 when the
     * command could not be run at all (or was stopped).
     */
    fun run(backend: ShellBackend, command: String, sink: (ShellLine) -> Unit): Int {
        val body = script(command)
        return when (backend) {
            ShellBackend.SHIZUKU -> runThroughShizuku(body, sink)
            ShellBackend.ROOT -> runThroughRoot(body, sink)
        }
    }

    /**
     * The directory the session opens in.
     *
     * Shared storage when the device has it, because that is where files live that the rest
     * of the phone can also see, and `/data/local/tmp` otherwise which every device has and
     * the shell can always write. This is only where commands *start*: the shell runs as the
     * uid Shizuku runs as, so `cd /system`, `cd /sdcard` or (with root) `cd /data/data` are
     * all a `cd` away.
     *
     * Nothing of Shizuku's own is ever written there - not by this, not by the starter's log,
     * not by anything else - because a file named after this app in a directory every app can
     * list is one of the things other apps look for to decide a device runs Shizuku. What the
     * user's own commands leave there is the user's doing.
     */
    fun openInPreferredDirectory(sink: (ShellLine) -> Unit): String {
        if (!ShizukuStateMachine.isRunning()) return cwd
        if (cd("/sdcard", sink)) return cwd
        cd(DEFAULT_CWD, sink)
        return cwd
    }

    /**
     * `cd`, resolved and verified by the shell itself so `..`, `~` and symlinks stay its
     * business rather than ours.
     */
    fun cd(target: String, sink: (ShellLine) -> Unit): Boolean {
        val printed = StringBuilder()
        val code = run(ShellBackend.SHIZUKU, "cd $target && pwd") { line ->
            if (line.kind == ShellLine.Kind.OUTPUT) printed.append(line.text).append('\n')
        }
        val resolved = printed.toString().trim()
        if (code != 0 || resolved.isEmpty()) return false
        cwd = resolved
        return true
    }

    /**
     * True when [command] is a bare `cd` this side should handle.
     *
     * A compound command that merely contains one `cd /system && ls` is not: its `cd`
     * belongs to that command's own shell, and reading the whole line as a path is how the
     * session once ended up in a directory called "/system && ls".
     */
    fun isPlainCd(command: String): Boolean = CD.matches(command)

    fun cdTarget(command: String): String =
        CD.matchEntire(command)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "/"

    /**
     * `export NAME=VALUE`, kept on this side rather than in a shell's memory, because no
     * shell outlives the command that would have held it.
     */
    fun export(assignment: String): Boolean {
        val match = EXPORT.matchEntire(assignment) ?: return false
        exports[match.groupValues[1]] =
            match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
        return true
    }

    fun clearExports() = exports.clear()

    // ---- backends ------------------------------------------------------------------

    private fun runThroughShizuku(script: String, sink: (ShellLine) -> Unit): Int {
        if (!ShizukuStateMachine.isRunning()) {
            sink(ShellLine("Shizuku is not running.", ShellLine.Kind.ERROR))
            return -1
        }
        return try {
            // Shizuku#newProcess is private, and it is the only way in from the app side.
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("sh", "-c", script), null, null) as? Process
                ?: return -1

            running = process
            // Both streams are read on their own thread: a command that writes a lot would
            // otherwise block on a full pipe while the other stream is being read.
            val out = pump(process.inputStream.bufferedReader(), ShellLine.Kind.OUTPUT, sink)
            val err = pump(process.errorStream.bufferedReader(), ShellLine.Kind.ERROR, sink)

            val code = process.waitFor()
            out.join()
            err.join()
            // Stop removed the process underneath it, so there is no exit code to report.
            val stopped = runCatching { process.exitValue() }.isFailure
            runCatching { process.destroy() }
            running = null
            if (stopped) -1 else code
        } catch (e: Throwable) {
            Log.w(AppConstants.TAG, "Shell command failed", e)
            sink(ShellLine(e.message ?: e.javaClass.simpleName, ShellLine.Kind.ERROR))
            running = null
            -1
        }
    }

    private fun runThroughRoot(script: String, sink: (ShellLine) -> Unit): Int {
        return try {
            val out = object : CallbackList<String>() {
                override fun onAddElement(e: String) = sink(ShellLine(e, ShellLine.Kind.OUTPUT))
            }
            val err = object : CallbackList<String>() {
                override fun onAddElement(e: String) = sink(ShellLine(e, ShellLine.Kind.ERROR))
            }
            val result = Shell.cmd(script).to(out, err).exec()
            if (!result.isSuccess && err.isEmpty()) {
                // su refused, or the command died before it could say anything.
                sink(ShellLine("Root refused the command (exit ${result.code}).", ShellLine.Kind.ERROR))
            }
            result.code
        } catch (e: Throwable) {
            Log.w(AppConstants.TAG, "Root shell failed", e)
            sink(ShellLine(e.message ?: e.javaClass.simpleName, ShellLine.Kind.ERROR))
            -1
        }
    }

    private fun pump(
        reader: BufferedReader,
        kind: ShellLine.Kind,
        sink: (ShellLine) -> Unit
    ): Thread = Thread {
        try {
            reader.useLines { lines -> lines.forEach { sink(ShellLine(it, kind)) } }
        } catch (e: Throwable) {
            // The stream ends when the process does, and when Stop destroys it.
            Log.d(AppConstants.TAG, "Shell stream ended: ${e.message}")
        }
    }.apply { isDaemon = true; start() }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        /** A directory that exists on every device and needs no permission to enter. */
        private const val DEFAULT_CWD = "/data/local/tmp"

        private val EXPORT = Regex("""^\s*export\s+([A-Za-z_][A-Za-z0-9_]*)=(.*)$""")

        /** A bare `cd`, or `cd <one token with no shell syntax in it>`, and nothing else. */
        private val CD = Regex("""^\s*cd(?:\s+([^\s;&|<>()]+))?\s*$""")
    }
}
