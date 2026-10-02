package moe.shizuku.manager.utils

import android.util.Log
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuShell"

/**
 * Runs [cmd] as shell through Shizuku and returns its output, or null when it couldn't
 * run at all (server down, permission denied, no output).
 *
 * For the few facts an app is not allowed to read itself the SELinux status lives in
 * selinuxfs, which is world-readable on disk but denied to app domains by policy.
 */
fun runShellCommand(cmd: String): String? {
    val process = newShellProcess(cmd) ?: return null

    return try {
        process.inputStream.bufferedReader().use { it.readText() }.trim().ifEmpty { null }
    } catch (e: Throwable) {
        Log.w(TAG, "Shell command via Shizuku failed: $cmd", e)
        null
    } finally {
        runCatching { process.destroy() }
    }
}

/**
 * Starts [cmd] as shell through Shizuku and hands the process back without waiting for it.
 *
 * The other one reads the output to the end, which is what almost every command here wants and
 * is exactly wrong for `logcat`: it never ends. The caller reads the stream itself and kills the
 * process when it has seen enough.
 *
 * Null when there is nothing to start it with - no server, or the call refused - so a caller can
 * say so rather than sitting in front of a stream that will never produce anything.
 */
fun newShellProcess(cmd: String): Process? {
    if (!Shizuku.pingBinder()) return null

    return try {
        // Shizuku#newProcess is private, reach it through reflection.
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }

        newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? Process
    } catch (e: Throwable) {
        Log.w(TAG, "Could not start a shell process via Shizuku: $cmd", e)
        null
    }
}
