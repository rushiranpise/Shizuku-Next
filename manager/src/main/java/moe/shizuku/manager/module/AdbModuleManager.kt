package moe.shizuku.manager.module

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.newShellProcess
import moe.shizuku.manager.utils.AppStrings
import moe.shizuku.manager.R

/**
 * Everything the app does to a module: put one there, switch it on, run it, take it away.
 *
 * The running half goes through the shell this app already has for its terminal - the same Shizuku
 * process creation, the same server, the same uid - so a module runs exactly as a command typed into
 * that terminal does, with the environment the module format promises around it.
 */
object AdbModuleManager {

    private const val TAG = "AdbModuleManager"
    private const val ROOT_DIR = "adb_modules"

    /** The file in a module whose contents are the URL of its update manifest. */
    private const val UPDATE_MANIFEST = "update.json"

    /** How much of a script's stream is read at a time, in bytes. */
    private const val READ_BUFFER_BYTES = 8 * 1024

    /** The services started and not yet stopped, so a user cannot start the same one twice. */
    private val services = ConcurrentHashMap<String, Process>()

    /** The actions running right now, so a person can stop one instead of waiting it out. */
    private val actions = ConcurrentHashMap<String, Process>()

    /** Where a service's output is read to, for as long as it runs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Where modules live.
     *
     * Not the app's private storage, which is the obvious place and does not work: a module's
     * scripts are run through Shizuku, which runs them as shell, and shell cannot enter an app's own
     * directory - the first `cd` into a module would fail with a permission error and the module
     * would look broken for no visible reason. The external files directory is the one place both
     * halves can reach: this app writes it as its owner, and the shell has it as well.
     *
     * The fallback is the private directory for a device that has no external storage at all, where
     * a module can be installed and read but its scripts cannot run.
     */
    fun modulesRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT_DIR)

    suspend fun list(context: Context): List<AdbModule> =
        withContext(Dispatchers.IO) { installedModules(modulesRoot(context)) }

    /**
     * Installs the module in [uri].
     *
     * Throws [ModuleInstallException] for a zip that is not a module or is not one this app will
     * unpack - see [ModulePackage] - and the caller is expected to say which.
     */
    suspend fun install(context: Context, uri: Uri): AdbModule = withContext(Dispatchers.IO) {
        val stream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw ModuleInstallException(ModuleInstallProblem.NOT_A_MODULE)

        stream.use { ModulePackage.install(modulesRoot(context), it) }
    }

    /**
     * Installs a module that has already been downloaded.
     *
     * The catalog hands bytes rather than a file because what it has is an http response: there is
     * no reason to write a module to disk twice on the way in.
     */
    suspend fun install(context: Context, bytes: ByteArray, source: String? = null): AdbModule =
        withContext(Dispatchers.IO) {
            ByteArrayInputStream(bytes).use { ModulePackage.install(modulesRoot(context), it) }
                // Where it came from, when the caller knows: the catalogue is the caller that does,
                // and this is what lets it show a repository as already installed afterwards.
                .also { module -> source?.let { ModuleSettings.setSource(module.id, it) } }
        }

    /**
     * The URL a module points at for its own updates, or null when it has none.
     *
     * The format has a module ship an `update.json` whose contents are a URL, rather than a version
     * in the module itself: the manifest can then say anything it likes - a changelog, a zip on
     * another host - without the module being reinstalled to change it.
     */
    fun updateUrl(module: AdbModule): String? =
        File(module.directory, UPDATE_MANIFEST)
            .takeIf { it.isFile }
            .let { file -> file?.let { runCatching { it.readText().trim() }.getOrNull() } }
            ?.takeIf { it.startsWith("http") }

    /**
     * What a newer version of [module] would be, or null when there is none.
     *
     * Nothing is cached and nothing is assumed: a module with no `update.json`, one whose URL does
     * not answer, and one that already is the newest all come back as no update, and only a
     * manifest naming a higher version code with a zip to install comes back as one.
     */
    suspend fun checkUpdate(module: AdbModule): ModuleUpdate? =
        updateUrl(module)
            ?.let { ModuleCatalog.updateManifest(it) }
            ?.takeIf { it.isNewerThan(module) }

    /** Switches a module on or off, which is the one marker file the format uses. */
    fun setEnabled(module: AdbModule, enabled: Boolean): Boolean = runCatching {
        val marker = File(module.directory, AdbModule.DISABLE_MARKER)

        if (enabled) {
            !marker.exists() || marker.delete()
        } else {
            marker.parentFile?.mkdirs()
            marker.exists() || marker.createNewFile()
        }
    }.getOrDefault(false)

    fun delete(module: AdbModule): Boolean = module.directory.deleteRecursively()

    fun isServiceRunning(module: AdbModule): Boolean =
        services[module.id]?.let { process ->
            // A process that has exited throws here, and a service that has exited is not running.
            runCatching { process.exitValue() }.isFailure
        } ?: false

    /**
     * Runs the module's action, asking [approve] about the command first.
     *
     * An action is a person pressing a button, so it is always allowed to run - what may not run is
     * a command the user has not seen, which is what the approval is for.
     *
     * [onOutput] is handed each piece of output as it is read, which is what a console showing a
     * script while it runs needs - the finished run is still returned whole. It is called from
     * whichever stream the piece came off, so a caller that touches state of its own is answered
     * one piece at a time but not from one thread: the two streams are read at once.
     */
    suspend fun runAction(
        module: AdbModule,
        approve: suspend (ModuleCommandRequest) -> Boolean,
        onOutput: (String) -> Unit = {},
    ): ModuleActionResult = runScript(
        module = module,
        script = module.actionScript,
        missing = AppStrings.get(R.string.module_err_no_action_script),
        background = false,
        approve = approve,
        log = module.lastActionLog,
        onOutput = onOutput
    )

    /**
     * Starts the module's service, and leaves it running.
     *
     * What it prints goes to the module's service log rather than to a dialog: a service is the
     * part of a module that runs with nobody watching, and it is read afterwards.
     */
    suspend fun startService(
        module: AdbModule,
        approve: suspend (ModuleCommandRequest) -> Boolean,
    ): ModuleActionResult {
        if (isServiceRunning(module)) {
            return ModuleActionResult(0, AppStrings.get(R.string.module_already_running), "")
        }

        val mode = ModuleSettings.getAccessMode()
        if (!ModuleWebUi.canRunService(module, mode, ModuleSettings.allowsBackground())) {
            return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_service_needs_access))
        }
        val script = module.serviceScript
            ?: return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_no_service_script))
        if (!module.enabled) {
            return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_switched_off))
        }

        val command = moduleScriptCommand(module, script, mode, ModuleSettings.isTrusted(module.id), true)
        if (!approve(ModuleCommandRequest(module, ModuleCommandSource.SERVICE, command))) {
            return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_not_approved))
        }

        val process = newShellProcess(command)
            ?: return ModuleActionResult(-1, "", AppStrings.get(R.string.err_shizuku_not_running))

        services[module.id] = process
        Diag.info(TAG, "service started for ${module.id}")

        scope.launch {
            val output = StringBuilder()
            readCapped(process.inputStream, output)
            runCatching {
                module.lastServiceLog.parentFile?.mkdirs()
                module.lastServiceLog.writeText(output.toString())
            }
            services.remove(module.id)
            Diag.info(TAG, "service for ${module.id} exited")
        }

        return ModuleActionResult(0, "Started.", "")
    }

    fun stopService(module: AdbModule): Boolean {
        val process = services.remove(module.id) ?: return false

        return runCatching {
            process.destroy()
            true
        }.getOrDefault(false)
    }

    /**
     * Stops a module's running action, by killing the process the same way [stopService] does.
     *
     * The readers end when their streams do, so the run comes back with what the script had printed
     * by then and with the exit code the kill gave it - the console shows the output so far rather
     * than nothing, and the module's log keeps it. What the script started for itself is the
     * script's own business; this stops the process the app started.
     *
     * False when the module has no action running.
     */
    fun stopAction(module: AdbModule): Boolean {
        val process = actions.remove(module.id) ?: return false

        return runCatching {
            process.destroy()
            true
        }.getOrDefault(false)
    }

    /**
     * A command a module's own WebUI asked for.
     *
     * It is a separate path from an action because it is a separate trust decision: an action runs
     * when a person presses a button, while a page can ask as often as it likes, so every command it
     * asks for is one the user is shown - unless they have marked the module trusted, which is what
     * that mark is for. What the page may ask at all is [ModuleWebUi.canUseShellBridge]'s business.
     */
    suspend fun runWebUiCommand(
        module: AdbModule,
        command: String,
        approve: suspend (ModuleCommandRequest) -> Boolean,
        timeoutSeconds: Int = ModuleRunLimits.SCRIPT_TIMEOUT_SECONDS,
    ): ModuleActionResult {
        if (!ModuleWebUi.canUseShellBridge(
                module,
                ModuleSettings.getAccessMode(),
                ModuleSettings.isTrusted(module.id)
            )
        ) {
            return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_no_shell_bridge))
        }

        if (!ModuleSettings.isTrusted(module.id) &&
            !approve(ModuleCommandRequest(module, ModuleCommandSource.WEB_UI, command))
        ) {
            return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_not_approved))
        }

        return runCommand(command, timeoutSeconds = timeoutSeconds)
    }

    private suspend fun runScript(
        module: AdbModule,
        script: File?,
        missing: String,
        background: Boolean,
        approve: suspend (ModuleCommandRequest) -> Boolean,
        log: File,
        onOutput: (String) -> Unit = {},
    ): ModuleActionResult {
        if (script == null) return ModuleActionResult(-1, "", missing)
        if (!module.enabled) return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_switched_off))

        val mode = ModuleSettings.getAccessMode()
        val command = moduleScriptCommand(
            module = module,
            script = script,
            mode = mode,
            trusted = ModuleSettings.isTrusted(module.id),
            background = background
        )

        if (!approve(ModuleCommandRequest(module, ModuleCommandSource.ACTION, command))) {
            return ModuleActionResult(-1, "", AppStrings.get(R.string.module_err_not_approved))
        }

        val result = runCommand(command, onOutput, moduleId = module.id)

        runCatching {
            log.parentFile?.mkdirs()
            log.writeText(result.combinedOutput)
        }

        return result
    }

    /**
     * Runs [command] as shell, with the output capped and the clock running.
     *
     * [moduleId] is the module the run belongs to. It is filed under that id for as long as it
     * lives, which is how [stopAction] finds the process to kill - and why a run is only filed
     * when somebody could want it stopped. Null for a command nobody is offered a way to stop.
     */
    private suspend fun runCommand(
        command: String,
        onOutput: (String) -> Unit = {},
        moduleId: String? = null,
        timeoutSeconds: Int = ModuleRunLimits.SCRIPT_TIMEOUT_SECONDS,
    ): ModuleActionResult = withContext(Dispatchers.IO) {
        val process = newShellProcess(command)
            ?: return@withContext ModuleActionResult(-1, "", AppStrings.get(R.string.err_shizuku_not_running))

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        // The two streams are read at once and each reports its own pieces, so what the caller is
        // handed is serialised here: one piece at a time, and never two of them at once.
        val reporting = Any()
        val report: (String) -> Unit = { text -> synchronized(reporting) { onOutput(text) } }

        if (moduleId != null) actions[moduleId] = process

        try {
            coroutineScope {
                // Both streams are read on their own coroutine: a command that writes a lot would
                // otherwise block on a full pipe while the other stream is being read.
                val readers = listOf(
                    launch { readCapped(process.inputStream, stdout, report) },
                    launch { readCapped(process.errorStream, stderr, report) }
                )

                val exitCode = awaitExit(process, timeoutSeconds * 1000L)
                if (exitCode == null) {
                    // Out of time: the process is killed and the reader coroutines end with their
                    // streams. The exit code is the one the format uses for this, so a module can
                    // tell the difference between a failure and running too long.
                    runCatching { process.destroy() }
                    readers.joinAll()
                    ModuleActionResult(ModuleRunLimits.TIMEOUT_EXIT_CODE, stdout.toString(), stderr.toString())
                } else {
                    readers.joinAll()
                    ModuleActionResult(exitCode, stdout.toString(), stderr.toString())
                }
            }
        } finally {
            // A run that has ended is not something to stop, so the entry goes as soon as it does -
            // whether it ended on its own, on the clock, or on the stop button.
            if (moduleId != null) actions.remove(moduleId)
        }
    }

    /** The process's exit code, or null when it is still running when the time is up. */
    private suspend fun awaitExit(process: Process, timeoutMillis: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            val exit = runCatching { process.exitValue() }.getOrNull()
            if (exit != null) return exit

            delay(100)
        }

        return null
    }

    /**
     * Reads a process's stream into [sink], keeping only what fits, and hands each piece to
     * [onChunk].
     *
     * A module that prints in a loop would otherwise fill the manager's heap with its log: the
     * format gives a stream a cap, and this is where it is applied - counted in bytes, because
     * that is the unit the format gives it. What is reported is exactly what is kept, so a console
     * and the log cannot come to hold different things.
     *
     * Past the cap the stream is still read, and thrown away. That is not tidiness: a process
     * writing into a pipe nobody reads from blocks the moment the pipe fills, so a script that
     * printed more than the cap would sit there until the clock stopped it instead of finishing -
     * which is a script written for another implementation of this format, where the streams are
     * drained, breaking here. The first [ModuleRunLimits.MAX_OUTPUT_BYTES] are what is kept.
     *
     * The bytes are decoded through a [Charsets.UTF_8] decoder instead of `String(bytes, UTF_8)`,
     * and that is the whole reason for the shape of this: one read can end in the middle of a
     * character, and decoding each read on its own would turn both halves into replacement
     * characters and show a script's perfectly good text as mojibake. The decoder is what holds
     * the half over until the rest of it arrives. Bytes that never become a character - a script
     * printing junk - are replaced rather than refused, which is what a stream of somebody else's
     * output has always been read as here.
     */
    internal fun readCapped(
        stream: InputStream,
        sink: StringBuilder,
        onChunk: (String) -> Unit = {},
    ) {
        runCatching {
            val bytes = ByteBuffer.allocate(READ_BUFFER_BYTES)
            val chars = CharBuffer.allocate(READ_BUFFER_BYTES)
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)

            var total = 0
            val drained = ByteArray(READ_BUFFER_BYTES)

            while (true) {
                // At the cap the bytes are read and dropped, so the process never blocks on a full
                // pipe. Under it, only what is left of the cap is read: the cap is the cap, and no
                // byte that is kept is read past it.
                if (total >= ModuleRunLimits.MAX_OUTPUT_BYTES) {
                    if (stream.read(drained) <= 0) break
                    continue
                }

                val room = minOf(bytes.remaining(), ModuleRunLimits.MAX_OUTPUT_BYTES - total)
                val read = stream.read(bytes.array(), bytes.position(), room)
                if (read <= 0) break

                total += read
                bytes.position(bytes.position() + read)

                // Decode everything that is whole and keep the tail: the bytes the decoder did not
                // consume are a character that is not finished, and compact() moves them to the
                // front of the buffer for the next read to complete.
                bytes.flip()
                chars.clear()
                decoder.decode(bytes, chars, false)
                bytes.compact()

                chars.flip()
                if (chars.hasRemaining()) {
                    val text = chars.toString()
                    sink.append(text)
                    onChunk(text)
                }
            }

            // What the decoder is still holding is a character the stream stopped in the middle of.
            // It is reported as the replacement character it is rather than dropped silently.
            bytes.flip()
            chars.clear()
            decoder.decode(bytes, chars, true)
            decoder.flush(chars)
            chars.flip()
            if (chars.hasRemaining()) {
                val text = chars.toString()
                sink.append(text)
                onChunk(text)
            }
        }
    }
}
