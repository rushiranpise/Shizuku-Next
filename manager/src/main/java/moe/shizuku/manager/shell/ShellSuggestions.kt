package moe.shizuku.manager.shell

import android.content.pm.PackageManager
import androidx.annotation.StringRes
import moe.shizuku.manager.R

/** What the shell offers for the token being typed. */
enum class SuggestionKind {
    /** A command name: `du` offers `dumpsys`, `dumpstate`. */
    COMMAND,

    /** A package name: `com.fo` offers the apps that start with it. */
    PACKAGE,

    /** A permission: `android.permission.C` offers CAMERA and the rest. */
    PERMISSION
}

/** One thing the shell is offering, and what tapping it writes. */
data class ShellSuggestion(
    val label: String,
    val detail: String,
    val insert: String,
    val kind: SuggestionKind,

    /** Set instead of [detail] when the second line is a description the app ships. */
    @StringRes val detailRes: Int = 0
)

/** Everything suggestions are drawn from, read once. */
data class SuggestionSource(
    /** Label to package name, for every installed app. */
    val packages: List<Pair<String, String>> = emptyList(),

    /** The permissions apps on this device actually ask for. */
    val permissions: List<String> = emptyList()
)

/**
 * What the shell suggests while a command is being typed.
 *
 * The kind is decided by the token itself rather than by the command before it: a dotted token
 * starting with `android` is a permission, any other dotted token is a package name, anything
 * else is a command. Crude, and right nearly always `pm grant com.foo
 * android.permission.CAMERA` picks a package and then a permission, `dumpsys battery` picks
 * none without a table saying what each of the hundred-odd commands takes. A table would be
 * wrong on the next release; this cannot be.
 *
 * The package names are the user's own installed apps and the permissions are the ones their
 * apps declare, both read from the device rather than shipped in the APK.
 */
object ShellSuggestions {

    private const val ANDROID_NAMESPACE = "android"
    private const val SUBCOMMANDS = "shell exec-out"
    private const val LIMIT = 12

    /**
     * `adb shell ls` is what you type on a computer, and it lands here out of habit. On the
     * device the shell is already the shell, so the prefix is dropped rather than reported as
     * a command that does not exist.
     */
    fun withoutAdbPrefix(input: String): String? {
        val trimmed = input.trim()

        // `shell ps` is the other half of the same habit: the whole thing is `adb shell ps` on
        // a computer, and either half gets typed on its own often enough to be worth dropping
        // rather than failing with "shell: not found".
        if (trimmed == "shell" || trimmed.startsWith("shell ")) {
            return trimmed.removePrefix("shell").trimStart()
        }

        if (!trimmed.startsWith("adb ")) return null
        val rest = trimmed.removePrefix("adb ").trimStart()
        val sub = rest.substringBefore(' ')
        if (sub !in SUBCOMMANDS) {
            // Not one of the subcommands that name the device's shell, so this is a device
            // command after all, just typed with the computer's prefix: `adb reboot` is
            // `reboot` here. The computer-only ones (install, push, devices) are answered
            // before this by ShellHostCommands, so they never reach this line.
            return rest
        }
        return rest.removePrefix(sub).trimStart()
    }

    /** The token being typed, and what should be offered for it. */
    fun kindOfToken(token: String): SuggestionKind = when {
        token.startsWith("$ANDROID_NAMESPACE.") -> SuggestionKind.PERMISSION
        token.contains('.') -> SuggestionKind.PACKAGE
        else -> SuggestionKind.COMMAND
    }

    /**
     * The token at the end of the input what the next suggestion would replace and whether
     * there is one at all. After a space there is not: the next word is a new token.
     */
    fun tokenAt(input: String): String {
        if (input.isEmpty() || input.endsWith(' ')) return ""
        return input.substringAfterLast(' ')
    }

    /** What to offer for the end of [input]. */
    fun forInput(input: String, source: SuggestionSource, limit: Int = LIMIT): List<ShellSuggestion> {
        val token = tokenAt(input)
        if (token.isEmpty()) {
            // Nothing typed yet: the commands this shell knows, so a first word can be picked
            // instead of remembered.
            return COMMANDS.take(limit).map {
                ShellSuggestion(it.first, "", it.first, SuggestionKind.COMMAND, it.second)
            }
        }

        return when (kindOfToken(token)) {
            // A token with no dot in it is a command first but it can just as well be the
            // beginning of an app's name, and `green` meaning Greenify is not something the
            // shape of the word can tell us. So the apps whose names start with it come after
            // the commands that do, and the card's second line says which is which.
            SuggestionKind.COMMAND -> COMMANDS
                .filter { it.first.startsWith(token, ignoreCase = true) }
                // Shortest first, so the command being typed leads: `du` before `dumpsys`,
                // `ls` before `logcat`. The list order is by subject, which is no use here.
                .sortedWith(compareBy({ it.first.length }, { it.first }))
                .map { ShellSuggestion(it.first, "", it.first, SuggestionKind.COMMAND, it.second) } +
                source.packages
                    .filter { (label, _) -> label.startsWith(token, ignoreCase = true) }
                    .map { (label, packageName) ->
                        ShellSuggestion(label, packageName, packageName, SuggestionKind.PACKAGE)
                    }

            SuggestionKind.PACKAGE -> source.packages
                .filter { (label, packageName) ->
                    packageName.startsWith(token, ignoreCase = true) ||
                        label.contains(token, ignoreCase = true)
                }
                // The ones whose package name starts with what was typed come first: those are
                // the ones being typed out by hand. Name order within each group, so the row is
                // the same every time the same thing is typed.
                .sortedWith(
                    compareBy(
                        { (_, packageName) -> if (packageName.startsWith(token, true)) 0 else 1 },
                        { (_, packageName) -> packageName }
                    )
                )
                .map { (label, packageName) ->
                    ShellSuggestion(label, packageName, packageName, SuggestionKind.PACKAGE)
                }

            SuggestionKind.PERMISSION -> source.permissions
                .filter { it.contains(token, ignoreCase = true) }
                .map {
                    ShellSuggestion(it.substringAfterLast('.'), it, it, SuggestionKind.PERMISSION)
                }
        }.take(limit)
    }

    /**
     * Puts [insert] where the token was and leaves the space the next one goes in, so a command
     * can be built one tap per token: `pm grant ` then a package then a permission.
     */
    fun insertInto(input: String, insert: String): String {
        val token = tokenAt(input)
        val head = if (token.isEmpty()) input else input.dropLast(token.length)
        return head + insert + " "
    }

    /**
     * Reads what the device has to offer from a list the caller already has: its apps, and the
     * permissions they declare. Taking the list rather than fetching it means the same one
     * serves the app picker, instead of every screen reading the package manager again.
     */
    fun from(pm: PackageManager, installed: List<android.content.pm.PackageInfo>): SuggestionSource {
        val packages = installed
            .mapNotNull { info ->
                val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                label to info.packageName
            }
            .sortedBy { it.first.lowercase() }

        // Every permission any app on this device declares, which is a shorter and far more
        // useful list than every permission the platform defines.
        val permissions = installed
            .flatMap { it.requestedPermissions?.asList() ?: emptyList() }
            .filter { it.startsWith("$ANDROID_NAMESPACE.") }
            .distinct()
            .sorted()

        return SuggestionSource(packages, permissions)
    }

    /**
     * The commands worth offering, which are the ones this shell is actually used for: the
     * package manager, the activity manager, `cmd`, `settings`, `dumpsys` and the file tools
     * that are on every device. Names only a suggestion is for the first word, and the rest
     * of the line is the user's.
     */
    val COMMANDS: List<Pair<String, Int>> = listOf(
        "pm" to R.string.shell_cmd_pm,
        "am" to R.string.shell_cmd_am,
        "cmd" to R.string.shell_cmd_cmd,
        "appops" to R.string.shell_cmd_appops,
        "settings" to R.string.shell_cmd_settings,
        "dumpsys" to R.string.shell_cmd_dumpsys,
        "logcat" to R.string.shell_cmd_logcat,
        "getprop" to R.string.shell_cmd_getprop,
        "setprop" to R.string.shell_cmd_setprop,
        "svc" to R.string.shell_cmd_svc,
        "wm" to R.string.shell_cmd_wm,
        "input" to R.string.shell_cmd_input,
        "content" to R.string.shell_cmd_content,
        "screencap" to R.string.shell_cmd_screencap,
        "screenrecord" to R.string.shell_cmd_screenrecord,
        "monkey" to R.string.shell_cmd_monkey,
        "ip" to R.string.shell_cmd_ip,
        "ping" to R.string.shell_cmd_ping,
        "netstat" to R.string.shell_cmd_netstat,
        "iptables" to R.string.shell_cmd_iptables,
        "df" to R.string.shell_cmd_df,
        "du" to R.string.shell_cmd_du,
        "mount" to R.string.shell_cmd_mount,
        "ls" to R.string.shell_cmd_ls,
        "cd" to R.string.shell_cmd_cd,
        "pwd" to R.string.shell_cmd_pwd,
        "cat" to R.string.shell_cmd_cat,
        "cp" to R.string.shell_cmd_cp,
        "mv" to R.string.shell_cmd_mv,
        "rm" to R.string.shell_cmd_rm,
        "mkdir" to R.string.shell_cmd_mkdir,
        "chmod" to R.string.shell_cmd_chmod,
        "chown" to R.string.shell_cmd_chown,
        "ln" to R.string.shell_cmd_ln,
        "find" to R.string.shell_cmd_find,
        "grep" to R.string.shell_cmd_grep,
        "sed" to R.string.shell_cmd_sed,
        "awk" to R.string.shell_cmd_awk,
        "sort" to R.string.shell_cmd_sort,
        "uniq" to R.string.shell_cmd_uniq,
        "wc" to R.string.shell_cmd_wc,
        "head" to R.string.shell_cmd_head,
        "tail" to R.string.shell_cmd_tail,
        "cut" to R.string.shell_cmd_cut,
        "tr" to R.string.shell_cmd_tr,
        "ps" to R.string.shell_cmd_ps,
        "top" to R.string.shell_cmd_top,
        "kill" to R.string.shell_cmd_kill,
        "pidof" to R.string.shell_cmd_pidof,
        "id" to R.string.shell_cmd_id,
        "date" to R.string.shell_cmd_date,
        "uptime" to R.string.shell_cmd_uptime,
        "env" to R.string.shell_cmd_env,
        "which" to R.string.shell_cmd_which,
        "tar" to R.string.shell_cmd_tar,
        "unzip" to R.string.shell_cmd_unzip,
        "base64" to R.string.shell_cmd_base64,
        "md5sum" to R.string.shell_cmd_md5sum,
        "seq" to R.string.shell_cmd_seq,
        "xargs" to R.string.shell_cmd_xargs,
        "sync" to R.string.shell_cmd_sync,
        "reboot" to R.string.shell_cmd_reboot,
        "sh" to R.string.shell_cmd_sh
    )
}
