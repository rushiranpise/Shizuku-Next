package moe.shizuku.manager.utils

import java.io.File

/** The kernel's SELinux mode, as far as this app can tell. */
enum class SelinuxMode { ENFORCING, PERMISSIVE, DISABLED }

/**
 * The kernel's SELinux state, worked out the way KernelSU's manager works it out:
 * ask `getenforce` as an ordinary app, and treat a refusal as the answer selinuxfs is
 * world-readable on disk but denied to app domains, and a permissive policy would have
 * allowed the read. That is what makes this work with no root, no server and no KSU.
 *
 * Null when nothing could be read. Runs commands, so never on the main thread.
 */
fun readSelinuxMode(): SelinuxMode? {
    // With a server running, read the switch itself rather than infer it.
    val raw = runShellCommand("cat /sys/fs/selinux/enforce")
        ?: runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()

    when (raw) {
        "1" -> return SelinuxMode.ENFORCING
        "0" -> return SelinuxMode.PERMISSIVE
    }

    val (stdout, stderr) = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", "getenforce").start()
        val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val err = process.errorStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        out to err
    }.getOrNull() ?: return null

    return when {
        stdout.equals("Enforcing", true) -> SelinuxMode.ENFORCING
        stdout.equals("Permissive", true) -> SelinuxMode.PERMISSIVE
        stdout.equals("Disabled", true) -> SelinuxMode.DISABLED
        // Refused: only an enforcing policy says no here.
        stderr.contains("Permission denied") -> SelinuxMode.ENFORCING
        else -> null
    }
}
