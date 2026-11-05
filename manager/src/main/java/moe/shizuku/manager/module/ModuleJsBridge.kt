package moe.shizuku.manager.module

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking

/** Where a command a module's page asked for came from. */
enum class ModuleCommandSource {
    WEB_UI,
    ACTION,
    SERVICE
}

/** A command about to run, for the user to look at first. */
data class ModuleCommandRequest(
    val module: AdbModule,
    val source: ModuleCommandSource,
    val command: String
)

/**
 * The `window.Shizuku` object a module's WebUI talks to.
 *
 * A module's page runs inside this app, so this object is the whole of what it can reach: the
 * information the format says it gets, and - only when the module asked for a shell bridge, access
 * is Full, and the module is switched on - a way to run a command. Every answer is JSON, in the
 * shape the format documents, so a page written for any implementation of it works here.
 *
 * The WebView that owns this object is created with internet access switched off: a page with a
 * bridge into a shell does not also get to talk to a network this app cannot see.
 */
class ModuleJsBridge(
    private val module: AdbModule,
    private val openSettings: () -> Unit,
    private val runCommand: suspend (String, Int) -> ModuleActionResult,
) {

    @JavascriptInterface
    fun getModuleInfo(): String =
        ModuleWebUi.moduleInfoJson(
            module = module,
            mode = ModuleSettings.getAccessMode(),
            trusted = ModuleSettings.isTrusted(module.id),
            backgroundAllowed = ModuleSettings.allowsBackground()
        )

    @JavascriptInterface
    fun exec(command: String): String = runCommandJson(command, ModuleRunLimits.SCRIPT_TIMEOUT_SECONDS)

    /**
     * The same, with the options object the format defines.
     *
     * The timeout is honoured, clamped to the range the format documents; the others - stdin, a
     * working directory and extra environment variables - would each be a way to make the command
     * something other than what the user approved, and a bridge that quietly ignores an option is
     * worse than one that says it cannot: a page asking for those gets a refusal naming the option,
     * and so does one whose timeout is not a number.
     */
    @JavascriptInterface
    fun execWithOptions(command: String, optionsJson: String?): String =
        when (val options = ModuleWebUi.optionsFrom(optionsJson)) {
            is ModuleWebUi.Options.Refused -> ModuleWebUi.refusedJson(options.reason)
            is ModuleWebUi.Options.Supported -> runCommandJson(command, options.timeoutSeconds)
        }

    /**
     * Opens this app's module settings, over the module list.
     *
     * This is navigation, not a command: there is nothing to run, so there is nothing to review,
     * and no gate to be behind - the page that most needs the person at these settings is the one
     * whose module cannot run anything yet, which is why Safe mode gets this too. The page cannot
     * flip the switches itself; the most it can do is bring the person to the room where they can.
     */
    @JavascriptInterface
    fun openSettings(): String {
        // A JavascriptInterface method runs on the WebView's bridge thread; the screens do not.
        Handler(Looper.getMainLooper()).post(openSettings)
        return ModuleWebUi.ackJson()
    }

    private fun runCommandJson(command: String, timeoutSeconds: Int): String {
        val mode = ModuleSettings.getAccessMode()
        if (!ModuleWebUi.canUseShellBridge(module, mode, ModuleSettings.isTrusted(module.id))) {
            return ModuleWebUi.refusedJson("This module has no shell bridge.")
        }

        val result = runBlocking { runCommand(command, timeoutSeconds) }

        return ModuleWebUi.execResultJson(result, result.exitCode == ModuleRunLimits.TIMEOUT_EXIT_CODE)
    }
}

/**
 * The first option in [optionsJson] this bridge does not implement, or null when there is none.
 *
 * The check is a search rather than a parse on purpose: the answer to a missing option is the same
 * whatever else the object holds, and a malformed one is the page's to fix.
 */
internal fun unsupportedOption(optionsJson: String?): String? {
    val json = optionsJson?.takeIf { it.isNotBlank() } ?: return null

    return listOf("stdin", "cwd", "env", "envVars", "workingDirectory")
        .firstOrNull { name -> Regex("\"\\s*$name\\s*\"").containsMatchIn(json) }
}
