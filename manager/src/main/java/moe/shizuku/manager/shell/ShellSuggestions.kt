package moe.shizuku.manager.shell

import android.content.pm.PackageManager

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
    val kind: SuggestionKind
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
                ShellSuggestion(it.first, it.second, it.first, SuggestionKind.COMMAND)
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
                .map { ShellSuggestion(it.first, it.second, it.first, SuggestionKind.COMMAND) } +
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
    val COMMANDS: List<Pair<String, String>> = listOf(
        "pm" to "packages: list, grant, revoke, suspend",
        "am" to "activities: start, force-stop, kill",
        "cmd" to "the platform's own services",
        "appops" to "app op switches",
        "settings" to "global and secure settings",
        "dumpsys" to "everything the system knows",
        "logcat" to "the log",
        "getprop" to "read a system property",
        "setprop" to "write a system property",
        "svc" to "power, wifi, data",
        "wm" to "screen size and density",
        "input" to "send taps, text and keys",
        "content" to "query and change providers",
        "screencap" to "screenshot to a file",
        "screenrecord" to "record the screen",
        "monkey" to "throw events at an app",
        "ip" to "interfaces and addresses",
        "ping" to "reach another host",
        "netstat" to "open sockets",
        "iptables" to "firewall rules",
        "df" to "free space",
        "du" to "what is using it",
        "mount" to "what is mounted",
        "ls" to "list a directory",
        "cd" to "change directory",
        "pwd" to "where you are",
        "cat" to "read a file",
        "cp" to "copy",
        "mv" to "move or rename",
        "rm" to "delete",
        "mkdir" to "make a directory",
        "chmod" to "change permissions",
        "chown" to "change owner",
        "ln" to "link",
        "find" to "find files",
        "grep" to "search text",
        "sed" to "edit text",
        "awk" to "process columns",
        "sort" to "sort lines",
        "uniq" to "collapse repeats",
        "wc" to "count lines and words",
        "head" to "first lines",
        "tail" to "last lines",
        "cut" to "pick columns",
        "tr" to "translate characters",
        "ps" to "running processes",
        "top" to "what is using the cpu",
        "kill" to "stop a process",
        "pidof" to "a process's id",
        "id" to "who you are",
        "date" to "the clock",
        "uptime" to "how long it has been up",
        "env" to "the environment",
        "which" to "where a command is",
        "tar" to "archives",
        "unzip" to "extract a zip",
        "base64" to "encode or decode",
        "md5sum" to "a checksum",
        "seq" to "a range of numbers",
        "xargs" to "run something per line",
        "sync" to "flush writes",
        "reboot" to "restart the device",
        "sh" to "another shell"
    )
}
