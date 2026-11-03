package moe.shizuku.manager.install

import android.os.Build
import moe.shizuku.manager.utils.AppStrings
import moe.shizuku.manager.R
import android.os.ParcelFileDescriptor
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.files.FileOperations
import moe.shizuku.manager.files.PrivilegedFiles
import moe.shizuku.manager.utils.Diag

/**
 * Installing an app, as a command line.
 *
 * The whole feature is this: `pm` is already a program that installs packages, the shell is
 * already allowed to run it, and this app already has a shell. So nothing here is a package
 * installer - there is no session to babysit, no intent the system asks the user about, and no
 * claim to be the thing Android routes installs through. A screen collects the flags and a button
 * runs the command.
 *
 * What that buys is the part `pm` is ordinarily used for and a person on a phone cannot reach: the
 * flags. Replacing an older build, allowing a downgrade, granting every permission on the way in,
 * naming a different installer so an app believes it came from somewhere it did not, installing
 * for every user at once. Without the shell those are a cable and a terminal.
 *
 * Split packages are where the one command becomes several, and they are still shell commands:
 * `pm install-create`, one `install-write` per split, `install-commit`. The four are run as one
 * script so the session id never has to travel through this process.
 */
object AppInstall {

    private const val TAG = "AppInstall"

    /** Where the shell unpacks an APKS, and where a picked file is put for it to read. */
    private const val ShellWorkspace = "/data/local/tmp"

    /** The marker the archive probe answers with, so its exit code survives being printed. */
    private const val CODE = "code="

    /**
     * The flags `pm install` takes, as the screen's switches.
     *
     * The defaults are the ones a person wants every time: replacing what is there, because
     * updating an app is the ordinary case, and nothing else, because everything else is a
     * decision about a package somebody specifically means to make.
     */
    data class Options(
        val replace: Boolean = true,
        val downgrade: Boolean = false,
        val grantAll: Boolean = false,
        val allowTest: Boolean = false,
        val keepRunning: Boolean = false,
        val skipVerification: Boolean = false,
        val bypassLowTargetSdk: Boolean = false,
        /** `current`, `all`, or a user id. */
        val user: String = "current",
        /** 0 auto, 1 internal, 2 the shared storage. */
        val location: Int = 0,
        /** What the installed app is told installed it, or null for the truth. */
        val installerPackage: String? = null,
        /** An ephemeral install: no icon in the launcher, gone with the session. */
        val instant: Boolean = false,
        /** Keep the previous version rollable-back to. */
        val rollback: Boolean = false,
        /** Compile without the app's own dexopt profile, in- or out-of-band. */
        val ignoreDexoptProfile: Boolean = false,
        /** A compiler filter for the install's own dexopt, or null for the platform's choice. */
        val dexoptFilter: String? = null,
        /** The URI the app is told it was referred from, or null for nothing. */
        val referrer: String? = null
    )

    sealed interface Outcome {
        /**
         * The words of the install's own command line, and the flags it actually ran with.
         *
         * The flags rather than the options as they were ticked: a switch can be on and still
         * leave nothing behind - the target SDK bypass does not exist before Android 14, and a
         * switch that changes nothing is worth being told about rather than silently ignored.
         */
        val flags: List<String>

        data class Done(val output: String, override val flags: List<String>) : Outcome

        data class Failed(
            val message: String,
            val output: String = "",
            override val flags: List<String> = emptyList()
        ) : Outcome
    }

    /**
     * Installs [target], which is an apk, an apks/apkm/xapk holding one, or an apk already
     * unpacked next to them.
     *
     * Runs on IO: every line of this goes through a binder call to the shell, and a package is
     * tens of megabytes of it.
     */
    suspend fun install(
        target: String,
        options: Options,
        onOutput: (String) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            // One working directory per install, under the shell's own, because the package has to
            // be installed from there and from nowhere else. See [single].
            val work = "$ShellWorkspace/install-${System.currentTimeMillis()}"
            val apks = if (isContainer(target)) {
                unpackInto(target, work, onOutput)
            } else {
                listOf(target)
            }
            if (apks.isEmpty()) return@withContext Outcome.Failed(AppStrings.get(R.string.install_err_no_apk, name(target)))

            // Before anything is handed to the installer: a package whose own archive cannot be
            // read is refused by the platform too, and the reason it gives - "failed to extract
            // native libraries" - describes the symptom rather than the cause. Saying which
            // belongs to this app, because it is the one holding the file.
            apks.forEach { apk ->
                damage(apk)?.let { reason ->
                    val message = "${name(apk)} cannot be read: $reason"
                    Diag.warn(TAG, message)
                    // Nothing was run, so there is nothing to add to the message: it is the whole
                    // of what happened, and it is shown once.
                    return@withContext Outcome.Failed(message)
                }
            }

            // Every install is written down, not only the ones that go wrong: a log that shows
            // failures and says nothing about the rest cannot answer "did that work", which is
            // the first thing anybody asks it. The flags are in here because they are the whole
            // reason this screen exists, and "which options were on" is the next question.
            Diag.info(TAG, "installing ${name(target)}: ${flags(options).joinToString(" ")}")

            val script = if (apks.size == 1) {
                single(apks.first(), options, work)
            } else {
                session(apks, options, work, onOutput)
            }

            val output = PrivilegedFiles.exec(script)
            onOutput(output)
            // `pm` answers a refused install with a line starting "Failure" and still exits zero in
            // some releases, so the output is read as well as the exit code.
            val refusal = output.lineSequence().firstOrNull { it.trimStart().startsWith("Failure") }
            if (refusal != null) {
                Diag.warn(TAG, "${name(target)} was refused: ${refusal.trim()}")
                Outcome.Failed(
                    refusal.trim().removePrefix("Failure").trim().trim('[', ']'),
                    output,
                    flags(options)
                )
            } else {
                Diag.info(TAG, "installed ${name(target)}: ${headline(output)}")
                Outcome.Done(output, flags(options))
            }
        } catch (t: Throwable) {
            Diag.warn(TAG, "installing ${name(target)} failed", t)
            Outcome.Failed(t.message ?: AppStrings.get(R.string.install_err_failed))
        }
    }

    /** The one line worth logging out of `pm`'s answer, which can run to a stack trace. */
    private fun headline(output: String): String = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.contains("Success") || it.startsWith("Failure") || it.isNotEmpty() }
        ?.take(200)
        ?: AppStrings.get(R.string.install_err_no_output)

    /**
     * Why a package cannot be installed, when its own archive is the reason.
     *
     * Nothing more than a listing, which is what makes it affordable: `unzip -v` prints the central
     * directory and checks that each entry's header is where that directory says it is, without
     * decompressing anything, so it costs the same on a two hundred megabyte package as on a small
     * one - fifty milliseconds, measured. A file whose entry headers were written over - which is
     * what a broken repack or a damaged download looks like - fails that walk, and the failure is
     * the answer to why the install would not go.
     *
     * Null when the package looks readable, and also when there is no `unzip` to ask: a check that
     * cannot be made is not a reason to refuse.
     */
    private fun damage(path: String): String? {
        val probe = runCatching {
            PrivilegedFiles.exec(
                "if command -v unzip >/dev/null 2>&1; then " +
                    "unzip -v ${quoted(path)} >/dev/null 2>&1; echo code=\$?; else echo code=none; fi"
            )
        }.getOrNull()
            ?.lineSequence()
            ?.lastOrNull { it.startsWith(CODE) }
            ?.removePrefix(CODE)
            ?.trim()

        // Nothing asked, nothing found, or a clean listing.
        if (probe == null || probe == "none" || probe == "0") return null

        // The listing again, this time keeping the complaint: its own output goes to /dev/null, so
        // what comes back is the line unzip wrote to stderr.
        val detail = runCatching {
            PrivilegedFiles.exec("unzip -v ${quoted(path)} >/dev/null; exit 0")
        }.getOrNull()
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("unzip:") || it.contains("Invalid", ignoreCase = true) }
            ?.trim()

        return detail ?: AppStrings.get(R.string.install_err_unreadable_archive, probe)
    }

    /**
     * The command for a single apk, which is a copy and then the install.
     *
     * The copy is not tidiness, it is the whole thing working: an install whose source is on the
     * shared storage is refused on this platform, and when it is not refused outright the native
     * libraries fail to extract, which is the error this screen used to answer with. The shell's
     * own directory is the one the installer is written for - `pm` says so itself, in the error it
     * gives for the other one - and the copy is removed whatever the install does.
     *
     * `-S` is deliberately not passed: with a path, `pm` reads the file itself, and a size would
     * be a second thing to be wrong.
     */
    private fun single(apk: String, options: Options, work: String): String {
        // A package already in the shell's directory - an unpacked bundle, or a file the screen
        // staged - is not copied onto itself; its own caller is the one that removes it.
        val staged = apk.startsWith("$ShellWorkspace/")
        val source = if (staged) apk else "$work/${name(apk)}"

        return buildString {
            appendLine("W=${quoted(work)}")
            appendLine("rm -rf \"\$W\"")
            appendLine("mkdir -p \"\$W\" || { echo 'Failure: no working directory under $ShellWorkspace'; exit 1; }")
            if (!staged) {
                appendLine("cp ${quoted(apk)} \"\$W\"/ || { echo 'Failure: could not copy the package'; rm -rf \"\$W\"; exit 1; }")
            }
            appendLine("pm install ${flags(options).joinToString(" ")} ${quoted(source)}")
            appendLine("rc=\$?")
            appendLine("rm -rf \"\$W\"")
            appendLine("exit \$rc")
        }
    }

    /**
     * The session, for a package that arrives as several apks.
     *
     * One script rather than three round trips, and `sed` rather than a parse here: the session id
     * exists only inside this command, so nothing has to be remembered between calls - and a
     * session that was created and never committed is a leak the platform cleans up on its own.
     */
    private fun session(
        apks: List<String>,
        options: Options,
        work: String,
        onOutput: (String) -> Unit
    ): String {
        val sizes = apks.map { it to sizeOf(it) }
        val total = sizes.sumOf { it.second }
        val flagLine = flags(options).joinToString(" ")

        return buildString {
            appendLine("W=${quoted(work)}")
            appendLine("S=\$(pm install-create $flagLine -S $total | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p')")
            appendLine("if [ -z \"\$S\" ]; then echo 'Failure: could not open an install session'; rm -rf \"\$W\"; exit 1; fi")
            sizes.forEach { (path, size) ->
                appendLine("pm install-write -S $size \$S ${quoted(name(path))} ${quoted(path)}")
            }
            appendLine("pm install-commit \$S")
            appendLine("rc=\$?")
            appendLine("rm -rf \"\$W\"")
            appendLine("exit \$rc")
        }.also { onOutput(AppStrings.get(R.string.install_log_installing, apks.size) + "\n") }
    }

    private fun flags(options: Options): List<String> = buildList {
        if (options.replace) add("-r")
        if (options.downgrade) add("-d")
        if (options.grantAll) add("-g")
        if (options.allowTest) add("-t")
        if (options.keepRunning) add("--dont-kill")
        if (options.skipVerification) add("--skip-verification")
        // Only where the platform has the switch; passing an unknown flag makes `pm` refuse the
        // whole install, which would be a worse outcome than not bypassing anything.
        if (options.bypassLowTargetSdk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add("--bypass-low-target-sdk-block")
        }
        add("--user")
        add(options.user)
        if (options.location in 1..2) {
            add("--install-location")
            add(options.location.toString())
        }
        options.installerPackage?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add("-i")
            add(it)
        }
        // The rest of what this `pm` documents, in the order its own help lists them. Every one
        // of these is a thing a terminal could do and a phone could not, which is the whole
        // reason the screen exists.
        if (options.instant) add("--instant")
        if (options.rollback) add("--enable-rollback")
        if (options.ignoreDexoptProfile) add("--ignore-dexopt-profile")
        options.dexoptFilter?.takeIf { it.isNotBlank() }?.let {
            add("--dexopt-compiler-filter")
            add(it)
        }
        options.referrer?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add("--referrer")
            add(it)
        }
    }

    /**
     * Puts [source] where the shell can read it, and answers that path.
     *
     * A package this app holds - a download, or a file somebody picked - is in a directory the
     * shell cannot open, and `pm` is run by the shell: the package has to reach the one place both
     * of them can, which is the shell's own directory under [ShellWorkspace]. The write goes
     * through [PrivilegedFiles], so the file arrives with the shell's own permissions rather than
     * this app's, and it is the caller's to remove afterwards.
     */
    suspend fun stage(source: InputStream, name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val target = "$ShellWorkspace/${name.ifBlank { "package.apk" }}"

            ParcelFileDescriptor.AutoCloseOutputStream(PrivilegedFiles.openWrite(target)).use { sink ->
                source.copyTo(sink)
            }

            target
        }.getOrNull()
    }

    /**
     * The apks inside a packaged bundle, unpacked where the shell can read them.
     *
     * An APKS, an APKM and an XAPK are all a zip of apks, and `pm` cannot be pointed at a zip. The
     * unpacking is this app's own, through the same descriptors everything else uses, and the
     * result is left in the one directory both this app and the shell can be sure of.
     */
    private fun isContainer(target: String): Boolean =
        listOf(".apks", ".apkm", ".xapk").any { target.endsWith(it, ignoreCase = true) }

    private suspend fun unpackInto(target: String, into: String, onOutput: (String) -> Unit): List<String> {
        onOutput(AppStrings.get(R.string.install_log_unpacking, name(target)) + "\n")
        val unpacked = FileOperations.extract(target, into, onProgress = {}, cancelled = { false })
        if (unpacked is FileOperations.Outcome.Failed) {
            throw IllegalStateException(unpacked.message)
        }

        val found = ArrayList<String>()
        collectApks(into, found)
        onOutput(AppStrings.get(R.string.install_log_found, found.size) + "\n")
        return found
    }

    /** Every apk under a path, however the bundle nested them. */
    private fun collectApks(path: String, into: MutableList<String>) {
        val entries = runCatching { PrivilegedFiles.list(path) }.getOrNull() ?: return
        for (entry in entries) {
            val child = "$path/${entry.name}"
            if (entry.directory) collectApks(child, into)
            else if (entry.name.endsWith(".apk", ignoreCase = true)) into.add(child)
        }
    }

    /** The size of a file, as its directory reports it. Zero when it cannot be looked at. */
    private fun sizeOf(path: String): Long {
        val parent = path.substringBeforeLast('/', "/")
        return runCatching {
            PrivilegedFiles.list(parent).firstOrNull { it.name == name(path) }?.size ?: 0L
        }.getOrDefault(0L)
    }

    /** Single-quoted, with a quote inside closed and reopened, which is what a shell needs. */
    private fun quoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun name(path: String): String = path.substringAfterLast('/')
}
