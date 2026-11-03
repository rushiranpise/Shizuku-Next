package moe.shizuku.manager.module

import java.io.File
import moe.shizuku.manager.utils.AppStrings
import moe.shizuku.manager.R

/**
 * A module as it sits on disk: what it says about itself, where it lives, and what it turned out to
 * contain.
 *
 * Nothing here is persisted: a module is its directory, and the manager reads it back rather than
 * keeping a copy that can drift from what is actually there. The same goes for whether it is
 * enabled - that is one marker file, so a module disabled by hand in the app's own data is disabled.
 */
data class AdbModule(
    val prop: ModuleProp,
    val directory: File,
) {
    val id: String get() = prop.id
    val name: String get() = prop.name

    /** Magisk's convention, which the ADB module format inherits: a `disable` file means off. */
    val enabled: Boolean get() = !File(directory, DISABLE_MARKER).exists()

    val logsDir: File get() = File(directory, LOGS_DIR)

    val lastActionLog: File get() = File(logsDir, "action-last.log")
    val lastServiceLog: File get() = File(logsDir, "service-last.log")

    /** The banner to show, or null when the module has none under any of the usual names. */
    val banner: File?
        get() = prop.banner?.let { named(it) } ?: ModuleProp.BANNER_NAMES.firstNotNullOfOrNull { named(it) }

    /** The WebUI directory, or null when the module has none under any of the usual names. */
    val webRoot: File?
        get() = prop.webUi?.let { named(it) } ?: ModuleProp.WEB_UI_NAMES.firstNotNullOfOrNull { named(it) }

    val hasWebUi: Boolean
        get() = webRoot?.resolve(ModuleProp.WEB_UI_ENTRY)?.isFile == true

    /** The script an action runs, when the module shipped one. */
    val actionScript: File?
        get() = named(prop.action)?.takeIf { it.isFile }

    val hasAction: Boolean get() = actionScript != null

    val serviceScript: File?
        get() = named(ModuleProp.SERVICE_SCRIPT)?.takeIf { it.isFile }

    val hasService: Boolean get() = serviceScript != null

    /** How much space the module takes, counted from what is on disk. */
    val sizeBytes: Long
        get() = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * A file the module named, or null when the name is not one a module may name.
     *
     * The names come out of `module.prop`, so they get the same treatment the zip's entries got: a
     * name that climbs out of the module directory is not a file in it.
     */
    private fun named(name: String): File? {
        if (!ModulePackage.isSafeEntry(name)) return null

        val file = File(directory, name)
        return if (file.exists()) file else null
    }

    companion object {
        const val DISABLE_MARKER = "disable"
        const val LOGS_DIR = "logs"
    }
}

/**
 * What running a module's script produced.
 *
 * Both streams are kept, and the exit code with them: the output is what the user is shown and what
 * is written to the module's log, and a module that failed and said nothing is still a module that
 * failed. "No output" is written out rather than left empty so that an empty log is a fact about the
 * script rather than a log that was never written.
 */
data class ModuleActionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(stderr.trim())
            }
            if (isBlank()) append(AppStrings.get(R.string.module_no_output))
        }
}

/**
 * Every module installed under [root], in name order.
 *
 * A directory without a readable `module.prop` is not a module and is skipped: that is what the
 * staging directory of an install in progress looks like, and what is left of a module whose zip
 * was refused.
 */
fun installedModules(root: File): List<AdbModule> =
    root.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { directory ->
            val propFile = File(directory, ModuleProp.FILE_NAME)
            if (!propFile.isFile) return@mapNotNull null

            runCatching { ModuleProp.parse(propFile.readText()) }
                .getOrNull()
                ?.let { prop -> AdbModule(prop, directory) }
        }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()
