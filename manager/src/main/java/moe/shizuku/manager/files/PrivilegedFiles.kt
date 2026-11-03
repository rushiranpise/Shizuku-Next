package moe.shizuku.manager.files

import android.content.ComponentName
import moe.shizuku.manager.ShizukuApplication
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import moe.shizuku.manager.utils.AppStrings
import moe.shizuku.manager.R

/**
 * Files, opened as the shell.
 *
 * The app cannot open `Android/data/com.whatsapp` and a rooted phone is not a requirement, but the
 * adb shell can - so this asks Shizuku for a process of its own running as that shell, and does
 * the work there. What comes back over the binder is what matters: a real [ParcelFileDescriptor],
 * seekable and readable by this process, rather than the bytes of a file piped through a second
 * shell command.
 *
 * The service is bound once and kept; the binder dying drops it, and the next call binds again.
 * Every call here blocks on a binder round trip and is meant to be made from a background
 * dispatcher - [list] and [open] are the same shape as any other IO.
 *
 * Nothing in here decides what may be reached. The uid decides, the platform refuses what it
 * refuses, and a refusal arrives as [IOException] carrying the platform's own words.
 */
object PrivilegedFiles {

    private const val TAG = "PrivilegedFiles"

    /**
     * The tag Shizuku files the service under, and the suffix its process is named with.
     *
     * Fixed strings rather than anything derived: the tag is what lets a later build find and
     * replace the service an earlier one started, and the process suffix is what makes the
     * process recognisable in a process list.
     */
    private const val SERVICE_TAG = "shizuku-next-files"
    private const val PROCESS_SUFFIX = "files"

    /** How long a bind is given before the caller is told there is no service. */
    private const val BIND_TIMEOUT_SECONDS = 10L

    /** One entry of a directory, as the filesystem holds it. */
    data class Entry(
        val name: String,
        val directory: Boolean,
        val size: Long,
        val modified: Long
    ) {
        val path: String get() = name
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(
            // The app's own package as it is *now*, not as it was built: Stealth mode renames the
            // package, and a component named by the build-time id then points at a package that no
            // longer exists - which is what made the file browser and the installer fail on a
            // hidden copy while everything else worked. The service class keeps its name, so only
            // the package half of this needed to change.
            ComponentName(
                ShizukuApplication.appContext.packageName,
                PrivilegedFilesService::class.java.name
            )
        )
            // Not a daemon: the service exists for this app's screens, and a process left running
            // after the app is gone would be a thing the user never asked to keep.
            .daemon(false)
            .processNameSuffix(PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag(SERVICE_TAG)
    }

    private val lock = Any()
    private var service: IPrivilegedFiles? = null
    private var binder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** Set while a bind is in flight, so callers racing each other wait on the same one. */
    private var binding: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, newBinder: IBinder) {
            val connected = IPrivilegedFiles.Stub.asInterface(newBinder)
            val recipient = IBinder.DeathRecipient { drop(newBinder) }
            if (runCatching { newBinder.linkToDeath(recipient, 0) }.isFailure) {
                // Without the death watch a stale interface would be handed out after Shizuku's
                // process is gone, so this connection is refused rather than kept.
                complete(null)
                return
            }

            val latch = synchronized(lock) {
                val previousBinder = binder
                val previousRecipient = deathRecipient
                binder = newBinder
                deathRecipient = recipient
                service = connected
                val pending = binding
                binding = null
                if (previousBinder != null && previousRecipient != null && previousBinder !== newBinder) {
                    runCatching { previousBinder.unlinkToDeath(previousRecipient, 0) }
                }
                pending
            }
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) = drop(null)

        override fun onBindingDied(name: ComponentName) {
            drop(null)
            complete(null)
        }

        override fun onNullBinding(name: ComponentName) {
            // A null binding can never answer a call; let whoever is waiting stop waiting.
            drop(null)
            complete(null)
        }
    }

    /**
     * True when there is a server to ask.
     *
     * Deliberately no permission check, which is what an ordinary client would need and this is
     * not one: the server identifies the manager by its package name and allows it whatever the
     * permission says, so asking for the permission here would report "not running" on a phone
     * where the server is running and this app is the only thing on it that could use it. The
     * state machine is the app's own answer to whether the binder is there.
     */
    fun isAvailable(): Boolean = ShizukuStateMachine.isRunning()

    @Throws(IOException::class)
    fun list(path: String): List<Entry> = call { it.list(path) }.let(::parse)

    @Throws(IOException::class)
    fun exec(command: String): String = call { it.exec(command) }

    /** Whether anything is at [path]. A path that cannot be looked at reads as absent. */
    fun exists(path: String): Boolean = runCatching { call { it.exists(path) } }.getOrDefault(false)

    /** Creates a directory, parents and all. Throws when it could not be made. */
    @Throws(IOException::class)
    fun mkdir(path: String) {
        if (!call { it.mkdir(path) }) throw IOException(AppStrings.get(R.string.files_err_cannot_create, path))
    }

    /** Renames within one filesystem. False means the caller has to copy instead. */
    @Throws(IOException::class)
    fun rename(from: String, to: String): Boolean = call { it.rename(from, to) }

    /** Removes a file, or a whole tree. Throws when it is still there afterwards. */
    @Throws(IOException::class)
    fun delete(path: String, recursive: Boolean = true) {
        if (!call { it.delete(path, recursive) }) throw IOException(AppStrings.get(R.string.files_err_cannot_remove, path))
    }

    /** Writes a file, making its parent directory if that is what is missing. */
    @Throws(IOException::class)
    fun openWrite(path: String): ParcelFileDescriptor {
        File(path).parent?.let { parent ->
            if (!exists(parent)) runCatching { mkdir(parent) }
        }
        return open(
            path,
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )
    }

    @Throws(IOException::class)
    fun open(path: String, mode: Int): ParcelFileDescriptor = try {
        call { it.open(path, mode) } ?: throw IOException(AppStrings.get(R.string.files_err_no_descriptor, path))
    } catch (e: IllegalStateException) {
        throw IOException(e.message ?: AppStrings.get(R.string.files_err_cannot_open, path), e)
    }

    /** Opens a file for reading, which is what every caller so far wants. */
    @Throws(IOException::class)
    fun openRead(path: String): ParcelFileDescriptor =
        open(path, ParcelFileDescriptor.MODE_READ_ONLY)

    /**
     * Unbinds the service and tells it to stop.
     *
     * Called when the app is done with it - the service is not a daemon, so leaving it behind
     * would leave a process running that nothing is going to use.
     */
    fun close() {
        val current = synchronized(lock) {
            val held = service
            service = null
            binder = null
            deathRecipient = null
            binding = null
            held
        }

        runCatching { current?.destroy() }
        runCatching { Shizuku.unbindUserService(args, connection, false) }
    }

    /** Runs [call] against the service, binding first if there is nothing to run it against. */
    private fun <T> call(call: (IPrivilegedFiles) -> T): T {
        val connected = connect() ?: throw IOException(AppStrings.get(R.string.files_err_service_not_running))
        return try {
            call(connected)
        } catch (e: RemoteException) {
            // The binder died mid-call. Forgotten so the next call binds a new one rather than
            // replaying against a corpse; the call itself is not retried, because a retry of a
            // command that already ran is how a write happens twice.
            drop(connected.asBinder())
            throw IOException(AppStrings.get(R.string.files_err_service_disconnected), e)
        } catch (e: IllegalStateException) {
            throw IOException(e.message ?: AppStrings.get(R.string.files_err_service_refused), e)
        }
    }

    private fun connect(): IPrivilegedFiles? {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (!isAvailable()) return null

        val latch: CountDownLatch
        var startBinding = false
        synchronized(lock) {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            var pending = binding
            if (pending == null) {
                pending = CountDownLatch(1)
                binding = pending
                startBinding = true
            }
            latch = pending
        }

        if (startBinding) {
            val asked = runCatching { Shizuku.bindUserService(args, connection) }
            if (asked.isFailure) {
                Diag.warn(TAG, "binding the privileged file service failed", asked.exceptionOrNull())
                complete(latch)
            }
        }

        // Shizuku delivers the connection callbacks on the main looper, so a caller on that
        // thread would be waiting for the thread that has to end the wait.
        if (Looper.myLooper() == Looper.getMainLooper()) return service

        val connected = runCatching { latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!connected) {
            Diag.warn(TAG, "the privileged file service did not bind in ${BIND_TIMEOUT_SECONDS}s")
            complete(latch)
        }

        return service?.takeIf { it.asBinder().isBinderAlive }
    }

    private fun complete(expected: CountDownLatch?) {
        val latch = synchronized(lock) {
            if (expected != null && binding !== expected) return
            val pending = binding
            binding = null
            pending
        }
        latch?.countDown()
    }

    private fun drop(expected: IBinder?) {
        val held: IBinder?
        val recipient: IBinder.DeathRecipient?
        val latch: CountDownLatch?
        synchronized(lock) {
            if (expected != null && binder !== expected) return
            held = binder
            recipient = deathRecipient
            service = null
            binder = null
            deathRecipient = null
            latch = binding
            binding = null
        }
        if (held != null && recipient != null) {
            runCatching { held.unlinkToDeath(recipient, 0) }
        }
        latch?.countDown()
    }

    /**
     * The records [IPrivilegedFiles.list] answers with, back into entries.
     *
     * A record that is not one is dropped rather than guessed at: the service writes these, so a
     * malformed one means something is very wrong and inventing an entry from it would hide that.
     */
    internal fun parse(listing: String): List<Entry> = listing
        .lineSequence()
        .mapNotNull { line ->
            val fields = line.split('\t', limit = 4)
            if (fields.size != 4) return@mapNotNull null
            val size = fields[2].toLongOrNull() ?: return@mapNotNull null
            val modified = fields[3].toLongOrNull() ?: return@mapNotNull null
            Entry(
                name = unescape(fields[0]),
                directory = fields[1] == "d",
                size = size,
                modified = modified
            )
        }
        .toList()

    /**
     * Backslashes first, then the escapes: done the other way round, the backslash added for a
     * tab would be taken as the start of another escape.
     */
    private fun unescape(name: String): String {
        val out = StringBuilder(name.length)
        var index = 0
        while (index < name.length) {
            val character = name[index]
            if (character != '\\' || index == name.length - 1) {
                out.append(character)
                index++
                continue
            }
            when (val escaped = name[index + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                else -> out.append(escaped)
            }
            index += 2
        }
        return out.toString()
    }
}
