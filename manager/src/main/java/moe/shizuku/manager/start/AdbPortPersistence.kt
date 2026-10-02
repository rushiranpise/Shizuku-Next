package moe.shizuku.manager.start

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.shell.ShellBackend
import moe.shizuku.manager.shell.ShellSession
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * Keeping the classic ADB port open across reboots.
 *
 * adbd listens on the port in `persist.adb.tcp.port` every time it starts, which is the
 * only way to have a port waiting after a reboot: `service.adb.tcp.port`, the one `adb
 * tcpip` sets, is cleared on every boot, and wireless debugging needs a network, a hotspot
 * and a race against the framework to exist at all. With the persistent port there, a start
 * after a reboot is a plain connection to 127.0.0.1 and needs none of that.
 *
 * The catch is ownership. `persist.adb.tcp.port` is in adbd's own SELinux property context,
 * so the shell uid is refused the write - verified on a stock Samsung: `setprop
 * persist.adb.tcp.port 5555` exits 1 with "Failed to set property", where a property the
 * shell does own exits 0. Root and the system uid are allowed, which on a device that can
 * start the server as either is enough, so this reports what actually happened rather than
 * assuming, and the setting it belongs to is off until someone turns it on.
 *
 * The port is also reachable from any network the device joins while it is open, which is
 * worth saying out loud in the setting's own words: that is the trade for never needing a
 * network to start.
 */
object AdbPortPersistence {

    private const val KEY = "persist.adb.tcp.port"

    enum class Outcome {
        /** Written and read back: the port will be there after a reboot. */

        APPLIED,

        /** The platform refused, which on a shell-only server is the expected answer. */
        REFUSED,

        /** Nothing to write through: Shizuku is not running and there is no root shell. */
        UNAVAILABLE
    }

    /** The port the setting asks for, or 0 to leave adbd not listening. */
    private fun wantedPort(enable: Boolean): Int = if (enable) ShizukuSettings.getTcpPort() else 0

    suspend fun apply(enable: Boolean): Outcome = withContext(Dispatchers.IO) {
        val port = wantedPort(enable)
        val rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
        if (!rooted && !ShizukuStateMachine.isRunning()) return@withContext Outcome.UNAVAILABLE

        val backend = if (rooted) ShellBackend.ROOT else ShellBackend.SHIZUKU
        val output = StringBuilder()
        val session = ShellSession()
        val code = runCatching {
            session.run(backend, "setprop $KEY $port") { line -> output.append(line.text) }
        }.getOrElse { throwable ->
            Log.w(AppConstants.TAG, "Could not write $KEY", throwable)
            return@withContext Outcome.REFUSED
        }

        val readBack = read()
        when {
            code == 0 && readBack == port -> {
                Log.i(AppConstants.TAG, "$KEY is $port; the port will outlive a reboot")
                Outcome.APPLIED
            }

            else -> {
                Log.w(
                    AppConstants.TAG,
                    "$KEY was refused (exit $code, reads back $readBack): " +
                        output.toString().trim()
                )
                Outcome.REFUSED
            }
        }
    }

    /** What the property currently says, or null when nothing can read it. */
    suspend fun read(): Int? = withContext(Dispatchers.IO) {
        val rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
        if (!rooted && !ShizukuStateMachine.isRunning()) return@withContext null

        val backend = if (rooted) ShellBackend.ROOT else ShellBackend.SHIZUKU
        val printed = StringBuilder()
        runCatching {
            ShellSession().run(backend, "getprop $KEY") { line ->
                if (line.text.isNotBlank()) printed.append(line.text.trim())
            }
        }.getOrNull()

        printed.toString().trim().toIntOrNull()
    }

    /**
     * Whether the setting and the property agree, for the switch's own state: a switch that
     * says on while the platform refused the write would be a lie.
     */
    suspend fun isApplied(): Boolean {
        if (!ShizukuSettings.getPersistAdbPort()) return false
        return read() == ShizukuSettings.getTcpPort()
    }
}
