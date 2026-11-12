package moe.shizuku.manager.adb

import android.os.SystemClock
import java.io.EOFException
import java.net.SocketException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils

private const val TAG = "AdbRoot"

/** adbd refused to switch, or did not come back in the requested state. */
class AdbRootUnavailableException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * adbd's own root switch.
 *
 * This is not the same thing as a root manager. On a debuggable (userdebug or eng) build,
 * adbd is allowed to restart itself as uid 0 after a `root:` service request. The switch
 * is temporary and only covers the ADB daemon and the processes it launches, so the caller
 * has to unroot after the server has started.
 *
 * A production `user` build refuses the request. The start path therefore only chooses
 * this fallback when [isSupported] says the build is debuggable.
 */
object AdbRoot {

    /** How long adbd is given to notice a root/unroot request before it is read back. */
    private const val SETTLE_DELAY_MS = 600L

    /** A daemon restart can also have to bring a wireless listener back up. */
    private const val RESTART_TIMEOUT_MS = 10_000L
    private const val RETRY_DELAY_MS = 300L

    /**
     * Whether this build is allowed to run adbd as root.
     *
     * `ro.debuggable` is the property adbd itself uses. Build.TYPE is a second answer for
     * images where the property is not readable by an app.
     */
    fun isSupported(): Boolean = EnvironmentUtils.isDebuggableBuild()

    /**
     * Asks adbd on [port] to run as root, or to drop back to the shell user.
     *
     * The request restarts adbd, so the result is read from a new connection rather than
     * assumed from the command's answer. A connection that closes during the restart is
     * expected; a refusal such as "cannot run as root in production builds" is not.
     */
    suspend fun setRoot(port: Int, root: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (port <= 0) return@withContext false

        val key = runCatching {
            AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        }.getOrElse {
            Diag.warn(TAG, "The ADB key is not available", it)
            return@withContext false
        }

        val current = readUid(port, key)
        if (root && current == 0) return@withContext true
        if (!root && current != null && current != 0) return@withContext true

        val service = if (root) "root:" else "unroot:"
        val output = StringBuilder()
        val sent = try {
            AdbClient("127.0.0.1", port, key).use { client ->
                client.connect()
                try {
                    client.command(service) { output.append(String(it)) }
                } catch (e: Exception) {
                    // The command restarts adbd, so the connection ending is expected.
                    if (!e.isAdbdRestart()) throw e
                }
            }
            true
        } catch (e: Exception) {
            Diag.warn(TAG, "Sending $service to port $port failed", e)
            false
        }
        if (!sent) return@withContext false

        val message = output.toString().trim()
        if (message.contains("cannot", ignoreCase = true)) {
            Diag.warn(TAG, "adbd refused $service: $message")
            return@withContext false
        }

        delay(SETTLE_DELAY_MS)
        val deadline = SystemClock.elapsedRealtime() + RESTART_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val uid = readUid(port, key)
            if (root && uid == 0) {
                Diag.info(TAG, "adbd is running as root on port $port")
                return@withContext true
            }
            if (!root && uid != null && uid != 0) {
                Diag.info(TAG, "adbd is back to uid $uid on port $port")
                return@withContext true
            }
            delay(RETRY_DELAY_MS)
        }

        Diag.warn(
            TAG,
            "Timed out waiting for adbd to ${if (root) "become root" else "drop root"} " +
                "on port $port"
        )
        false
    }

    /** The uid adbd's shells run as, or null when the port cannot answer. */
    private fun readUid(port: Int, key: AdbKey): Int? {
        val client = AdbClient("127.0.0.1", port, key)
        return try {
            client.connect()
            val output = StringBuilder()
            client.command("shell:id -u") { output.append(String(it)) }
            output.toString().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        } finally {
            client.close()
        }
    }

    private fun Exception.isAdbdRestart(): Boolean =
        this is EOFException || this is SocketException || this is SSLException
}
