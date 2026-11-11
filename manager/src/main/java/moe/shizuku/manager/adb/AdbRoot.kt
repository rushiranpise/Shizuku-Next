package moe.shizuku.manager.adb

import android.content.Context
import android.os.SystemClock
import java.io.EOFException
import java.net.SocketException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.start.StartTransport
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils

private const val TAG = "AdbRoot"

/**
 * adbd's own root switch, which is what `adb root` and `adb unroot` ask for.
 *
 * The host command is a single ADB service request, `root:` or `unroot:`, so no `adb` binary is
 * involved: this app already speaks the protocol to the local daemon, and that connection is a
 * loopback socket. The request makes adbd restart itself, which is why the state cannot be the
 * answer to the request - it is read back from a new connection, by checking the uid the daemon's
 * shells run as.
 *
 * Reading that uid is also how the switch is "peeked" at: a shell over adbd runs with adbd's
 * uid, so `id -u` is 0 exactly when `adb root` has taken effect. Regrettably it costs one local
 * connection and one command, but there is no in-protocol way of finding out.
 */
object AdbRoot {

    /** The uid adbd's shells run as. */
    enum class State { ROOT, SHELL }

    /** What came of asking adbd to change. */
    sealed interface Result {
        /** adbd was already in the requested state. */
        data class Already(val state: State) : Result

        /** adbd restarted and answered as the requested state. */
        data class Changed(val state: State) : Result

        /** No local ADB port answered. */
        data object NoAdb : Result

        /** adbd answered, but not in the requested state. */
        data class Failed(val message: String?) : Result
    }

    /**
     * The last answer, kept briefly so the screen can refresh without opening a connection for
     * every state change it observes. Null is never cached: "nothing answered" is the answer
     * that is most likely to have changed by the next look.
     */
    @Volatile
    private var cachedState: State? = null
    @Volatile
    private var cachedAt = 0L
    private const val CACHE_MS = 1_500L

    /**
     * How long a restart is waited for before it is reported as failure.
     *
     * adbd restarts in well under a second; the wait is generous because a wireless daemon also
     * has to bring its TLS listener back up before anything can be asked.
     */
    private const val RESTART_TIMEOUT_MS = 10_000L
    private const val RETRY_DELAY_MS = 300L

    /** How long adbd is given to notice the request before the state is read back. */
    private const val SETTLE_DELAY_MS = 600L

    /**
     * The state adbd is in, or null when no local port could be reached.
     *
     * A [force] read skips the short cache, which is what the screen does on resume and after a
     * tap: those are the moments when somebody may have run `adb root` from a computer.
     */
    suspend fun peek(force: Boolean = false): State? = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            cachedState?.let { if (now - cachedAt < CACHE_MS) return@withContext it }
        }

        val key = keyOrNull() ?: return@withContext null
        val state = probe(key)?.second
        cache(state)
        state
    }

    /**
     * Asks adbd to run as root or as shell.
     *
     * Called off the main thread: this opens an ADB connection and, when the request takes,
     * waits for the daemon to come back before answering what it came back as.
     */
    suspend fun setRoot(context: Context, root: Boolean): Result = withContext(Dispatchers.IO) {
        val want = if (root) State.ROOT else State.SHELL
        val key = keyOrNull() ?: return@withContext Result.NoAdb

        val current = probe(key) ?: return@withContext Result.NoAdb
        if (current.second == want) {
            cache(want)
            return@withContext Result.Already(want)
        }

        val service = if (root) "root:" else "unroot:"
        val output = StringBuilder()
        val sent = try {
            AdbClient("127.0.0.1", current.first, key).use { client ->
                client.connect()
                try {
                    client.command(service) { output.append(String(it)) }
                } catch (e: Exception) {
                    // The request restarts adbd, so the connection ending here is expected and
                    // is not a failed request. Anything else still is.
                    if (!e.isAdbdRestart()) throw e
                }
            }
            true
        } catch (e: Exception) {
            Diag.warn(TAG, "Sending $service to port ${current.first} failed", e)
            false
        }

        val message = output.toString().trim().ifEmpty { null }
        if (!sent) return@withContext Result.Failed(message)

        // The one failure adbd names without restarting: a production build. Waiting the full
        // timeout for a restart that was refused would just make the toast late.
        if (message?.contains("cannot", ignoreCase = true) == true) {
            return@withContext Result.Failed(message)
        }

        cache(null)
        val state = waitForState(context, key, current.first, want)
        if (state == want) cache(want)
        return@withContext if (state == want) {
            Result.Changed(want)
        } else {
            Result.Failed(message)
        }
    }

    /**
     * Waits for adbd to answer as [want] after a restart.
     *
     * The old daemon can answer for a moment while the new one is starting, so a state other
     * than [want] is not taken as final; only the deadline is. A wireless daemon may also come
     * back on a different port, which the search covers.
     */
    private suspend fun waitForState(
        context: Context,
        key: AdbKey,
        oldPort: Int,
        want: State
    ): State? {
        delay(SETTLE_DELAY_MS)

        val deadline = SystemClock.elapsedRealtime() + RESTART_TIMEOUT_MS
        var searched = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (probe(key)?.second == want) return want

            if (!searched && EnvironmentUtils.isWirelessDebuggingEnabled()) {
                // A wireless port is drawn at random, so a restart can move it. The platform's
                // property carries the new one on most devices; the advertisement covers the
                // ones where an app may not read that property.
                searched = true
                val fresh = AdbStarter.freshWirelessPort(
                    context,
                    exclude = setOf(oldPort),
                    timeoutMs = 5_000L
                )
                if (fresh != null && readState(fresh, key) == want) return want
            }
            delay(RETRY_DELAY_MS)
        }
        return null
    }

    /** The first candidate port that answers with a uid, and that uid's state. */
    private fun probe(key: AdbKey): Pair<Int, State>? {
        for (port in candidatePorts()) {
            readState(port, key)?.let { return port to it }
        }
        return null
    }

    /**
     * The local ports worth asking, in the same order a start asks them.
     *
     * A remembered wireless port from an earlier boot is harmless here: the cost of a stale
     * guess is one refused loopback connection.
     */
    private fun candidatePorts(): List<Int> = StartTransport.candidatePorts(
        configured = EnvironmentUtils.getAdbTcpPort(),
        tlsProperty = EnvironmentUtils.getAdbTlsPort(),
        remembered = ShizukuSettings.getLastAdbPort().takeIf { it > 0 },
        appTcpPort = ShizukuSettings.getTcpPort()
    ).map { it.port }

    /** The uid behind [port] as a state, or null when that port is not an adbd we can ask. */
    private fun readState(port: Int, key: AdbKey): State? {
        if (port <= 0) return null

        val client = AdbClient("127.0.0.1", port, key)
        return try {
            client.connect()
            val output = StringBuilder()
            client.command("shell:id -u") { output.append(String(it)) }
            when (output.toString().trim().toIntOrNull()) {
                0 -> State.ROOT
                null -> null
                else -> State.SHELL
            }
        } catch (e: Exception) {
            null
        } finally {
            client.close()
        }
    }

    private fun keyOrNull(): AdbKey? = runCatching {
        AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
    }.onFailure {
        Diag.warn(TAG, "The ADB key is not available", it)
    }.getOrNull()

    private fun cache(state: State?) {
        cachedState = state
        cachedAt = SystemClock.elapsedRealtime()
    }

    private fun Exception.isAdbdRestart(): Boolean =
        this is EOFException || this is SocketException || this is SSLException
}
