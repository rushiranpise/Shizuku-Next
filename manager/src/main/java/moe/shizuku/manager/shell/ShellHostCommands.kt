package moe.shizuku.manager.shell

import androidx.annotation.StringRes
import moe.shizuku.manager.R

/**
 * The commands that belong to `adb` on a computer rather than to the device.
 *
 * Someone used to a computer types `adb devices` or `adb install app.apk` here, and a shell on
 * the device answers "not found" to both, which says nothing about why. Each of these is
 * answered with the device's own version of the job instead, in the same spirit as the three
 * commands this shell already answers itself (`clear`, `exit` and a bare `su`).
 *
 * Matched on the command with any `adb ` or `shell ` prefix already removed, because the habit
 * comes with the prefix attached and without it.
 */
object ShellHostCommands {

    /**
     * The resource that explains what to run here instead, or null when this is a device
     * command after all and should simply run.
     */
    @StringRes
    fun hintFor(command: String): Int? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null

        val words = trimmed.split(Regex("\\s+"))
        val first = words.first().lowercase()

        return when (first) {
            // Nothing is attached to a device: it is the device.
            "devices" -> R.string.shell_adb_host_devices

            "install" -> R.string.shell_adb_host_install

            "push", "pull", "sync" -> R.string.shell_adb_host_transfer

            // The rest of the computer's half of adb: opening ports, connecting to a device
            // by address, and managing the adb server are all things with no meaning from
            // inside the device.
            "tcpip", "connect", "disconnect", "forward", "reverse", "kill-server",
            "start-server", "wait-for-device", "pair", "reconnect", "sideload",
            "uninstall" -> R.string.shell_adb_host_generic

            "help", "-h", "--help" -> R.string.shell_adb_host_help

            else -> null
        }
    }
}
