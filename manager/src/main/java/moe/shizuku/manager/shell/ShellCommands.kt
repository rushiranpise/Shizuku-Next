package moe.shizuku.manager.shell

/** One command the shell offers, with what it does and what it is about. */
data class LibraryCommand(
    /** May contain `<placeholders>`, which have to be filled in before it can run. */
    val command: String,
    val description: String,
    val tags: List<String>
)

/**
 * The commands worth having to hand.
 *
 * Written for this fork rather than taken from anywhere: the shell app this is modelled on is
 * GPL-3.0 and this project is not, so its list could not come across even if the wording had
 * been rewritten wholesale. The coverage is our own judgement the package manager, the
 * activity manager, `cmd`, `settings`, `app ops`, input, display, network, battery, files and
 * the facts that are only readable through the shell.
 *
 * What makes a list like this usable is the placeholders: `pm grant <package> <permission>` is
 * describable but not runnable, so the shell asks for the two things it needs and hands back a
 * command that can be read before it is run.
 */
object ShellCommands {

    /** `<package>`, `<permission>`, `<x1>`… anything in angle brackets. */
    private val PLACEHOLDER = Regex("<([A-Za-z][A-Za-z0-9_-]*)>")

    /** The names whose value is an app, so the picker can offer one instead of the keyboard. */
    private val PACKAGE_NAMES = setOf("package", "packages", "pkg", "app")

    fun variablesOf(command: String): List<String> =
        PLACEHOLDER.findAll(command).map { it.groupValues[1] }.distinct().toList()

    fun isPackageVariable(name: String): Boolean = name.lowercase() in PACKAGE_NAMES

    /**
     * The command with its placeholders replaced. Anything left unfilled keeps its placeholder
     * rather than becoming an empty gap, so a half-filled command still says what it is missing.
     */
    fun filled(command: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(command) { match ->
            values[match.groupValues[1]]?.takeIf { it.isNotBlank() } ?: match.value
        }

    /** Everything matching [query] in the command, the description or the tags. */
    fun search(query: String): List<LibraryCommand> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return LIBRARY
        return LIBRARY.filter { entry ->
            entry.command.contains(trimmed, ignoreCase = true) ||
                entry.description.contains(trimmed, ignoreCase = true) ||
                entry.tags.any { it.contains(trimmed, ignoreCase = true) }
        }
    }

    val LIBRARY: List<LibraryCommand> = listOf(
        // ---- packages -----------------------------------------------------------------
        LibraryCommand("pm list packages", "Every package on the device.", listOf("package", "list")),
        LibraryCommand("pm list packages -3", "Only the apps you installed.", listOf("package", "list")),
        LibraryCommand("pm list packages -s", "Only the apps that came with the system.", listOf("package", "list")),
        LibraryCommand("pm list packages -d", "Packages that are disabled.", listOf("package", "list")),
        LibraryCommand("pm path <package>", "Where an app's APK lives.", listOf("package", "info")),
        LibraryCommand("pm grant <package> <permission>", "Grant a permission to an app.", listOf("package", "permission")),
        LibraryCommand("pm revoke <package> <permission>", "Take a permission away again.", listOf("package", "permission")),
        LibraryCommand("pm clear <package>", "Delete an app's data and stop it.", listOf("package", "storage")),
        LibraryCommand("pm disable-user --user 0 <package>", "Disable an app for this user.", listOf("package")),
        LibraryCommand("pm enable <package>", "Enable a disabled app again.", listOf("package")),
        LibraryCommand("pm suspend --user 0 <package>", "Suspend an app, keeping its data.", listOf("package")),
        LibraryCommand("pm unsuspend --user 0 <package>", "Unsuspend an app.", listOf("package")),
        LibraryCommand("pm uninstall --user 0 <package>", "Remove an app for this user, keeping its data.", listOf("package", "uninstall")),
        LibraryCommand("pm install-existing --user 0 <package>", "Put a removed system app back.", listOf("package")),
        LibraryCommand("pm dump <package>", "Everything the package manager knows about an app.", listOf("package", "info")),
        LibraryCommand("pm list permissions -d -g", "The dangerous permissions, by group.", listOf("permission", "list")),
        LibraryCommand("pm install <apk_path>", "Install an APK from a file on the device.", listOf("package", "install")),
        LibraryCommand("pm reset-permissions -p <package>", "Put an app's permissions back to their defaults.", listOf("package", "permission")),
        LibraryCommand("pm uninstall-system-updates <package>", "Roll a system app back to the version it shipped with.", listOf("package", "uninstall")),
        LibraryCommand("pm list features", "The features this device's hardware reports.", listOf("device", "list")),
        LibraryCommand(
            "cmd package compile -m speed -f <package>",
            "Compile an app ahead of time, so it starts faster.",
            listOf("package", "performance")
        ),

        // ---- activities, intents and app ops ------------------------------------------
        LibraryCommand("am start -n <package>/<activity>", "Launch one activity of an app.", listOf("activity", "intent")),
        LibraryCommand("am start -a <action>", "Launch whatever handles an intent action.", listOf("activity", "intent")),
        LibraryCommand("am force-stop <package>", "Stop an app now.", listOf("package", "process")),
        LibraryCommand("am kill <package>", "Kill an app's background processes.", listOf("package", "process")),
        LibraryCommand("am kill-all", "Kill every background process.", listOf("process")),
        LibraryCommand("am broadcast -a <action>", "Send a broadcast.", listOf("intent")),
        LibraryCommand("am get-current-user", "Which user the device is on.", listOf("user", "info")),
        LibraryCommand("dumpsys activity activities", "What is on screen right now.", listOf("activity", "info")),
        LibraryCommand("dumpsys activity top", "The top activity in detail.", listOf("activity", "info")),
        LibraryCommand("cmd appops get <package>", "An app's app op modes.", listOf("appops", "package")),
        LibraryCommand("cmd appops set <package> <op> deny", "Deny one of an app's ops.", listOf("appops", "package")),
        LibraryCommand("cmd appops set <package> <op> allow", "Allow one of an app's ops.", listOf("appops", "package")),
        LibraryCommand("am set-standby-bucket <package> restricted", "Restrict an app in the background.", listOf("battery", "package")),
        LibraryCommand("am set-standby-bucket <package> active", "Stop restricting it.", listOf("battery", "package")),

        // ---- settings -----------------------------------------------------------------
        LibraryCommand("settings list global", "Every global setting and its value.", listOf("settings", "list")),
        LibraryCommand("settings list secure", "Every secure setting.", listOf("settings", "list")),
        LibraryCommand("settings list system", "Every system setting.", listOf("settings", "list")),
        LibraryCommand("settings get global <key>", "Read one global setting.", listOf("settings")),
        LibraryCommand("settings put global <key> <value>", "Write one global setting.", listOf("settings")),
        LibraryCommand("settings delete global <key>", "Remove an override.", listOf("settings")),
        LibraryCommand(
            "settings put global animator_duration_scale 0.5",
            "Halve the animation speed.",
            listOf("settings", "display")
        ),

        // ---- display and input ---------------------------------------------------------
        LibraryCommand("wm size", "The screen's resolution.", listOf("display", "info")),
        LibraryCommand("wm density", "The screen's density.", listOf("display", "info")),
        LibraryCommand("wm size <width>x<height>", "Change the resolution.", listOf("display")),
        LibraryCommand("wm density <dpi>", "Change the density.", listOf("display")),
        LibraryCommand("cmd uimode night yes", "Turn the dark theme on.", listOf("display", "ui")),
        LibraryCommand("cmd uimode night no", "Turn the dark theme off.", listOf("display", "ui")),
        LibraryCommand("cmd statusbar expand-notifications", "Pull the notification shade down.", listOf("ui")),
        LibraryCommand("cmd statusbar collapse", "Put the shade back.", listOf("ui")),
        LibraryCommand("cmd notification list", "Everything currently posted.", listOf("notification", "list")),
        LibraryCommand("dumpsys window displays", "Display and window state.", listOf("display", "info")),
        LibraryCommand("input keyevent KEYCODE_HOME", "Press home.", listOf("input")),
        LibraryCommand("input keyevent KEYCODE_BACK", "Press back.", listOf("input")),
        LibraryCommand("input keyevent KEYCODE_APP_SWITCH", "Open recents.", listOf("input")),
        LibraryCommand("input keyevent KEYCODE_POWER", "Press the power button.", listOf("input", "power")),
        LibraryCommand("input text <text>", "Type into whatever has focus.", listOf("input")),
        LibraryCommand("input tap <x> <y>", "Tap a point on the screen.", listOf("input")),
        LibraryCommand("input swipe <x1> <y1> <x2> <y2>", "Drag from one point to another.", listOf("input")),
        LibraryCommand("screencap -p /sdcard/screen.png", "Take a screenshot.", listOf("display", "file")),
        LibraryCommand("screenrecord /sdcard/record.mp4", "Record the screen until interrupted.", listOf("display", "file")),

        // ---- network -------------------------------------------------------------------
        LibraryCommand("ip addr", "Every interface and its addresses.", listOf("network", "info")),
        LibraryCommand("ip route", "The routing table.", listOf("network", "info")),
        LibraryCommand("cmd wifi status", "Whether Wi-Fi is on and what it is joined to.", listOf("network", "wifi")),
        LibraryCommand("svc wifi enable", "Turn Wi-Fi on.", listOf("network", "wifi")),
        LibraryCommand("svc wifi disable", "Turn Wi-Fi off.", listOf("network", "wifi")),
        LibraryCommand("svc data enable", "Turn mobile data on.", listOf("network")),
        LibraryCommand("svc data disable", "Turn mobile data off.", listOf("network")),
        LibraryCommand("cmd connectivity airplane-mode enable", "Turn airplane mode on.", listOf("network")),
        LibraryCommand("cmd connectivity airplane-mode disable", "Turn airplane mode off.", listOf("network")),
        LibraryCommand("ping -c 4 <host>", "Check whether a host answers.", listOf("network")),
        LibraryCommand("netstat", "Open sockets, and who owns them.", listOf("network", "info")),

        // ---- power and battery ----------------------------------------------------------
        LibraryCommand("dumpsys battery", "Charge, temperature and health.", listOf("battery", "info")),
        LibraryCommand("dumpsys deviceidle", "Whether the device is dozing.", listOf("battery", "info")),
        LibraryCommand("dumpsys deviceidle whitelist", "The apps exempt from Doze.", listOf("battery", "list")),
        LibraryCommand("dumpsys deviceidle whitelist +<package>", "Exempt an app from Doze.", listOf("battery", "package")),
        LibraryCommand("dumpsys deviceidle whitelist -<package>", "Take the exemption away.", listOf("battery", "package")),
        LibraryCommand("dumpsys power | grep -m1 mWakefulness", "Whether the screen is awake.", listOf("power", "info")),

        // ---- storage and files -----------------------------------------------------------
        LibraryCommand("df -h", "Free space on every filesystem.", listOf("storage", "info")),
        LibraryCommand("du -sh <path>", "How much space a directory takes.", listOf("storage", "directory")),
        LibraryCommand("ls -l <path>", "List a directory in detail.", listOf("file", "directory")),
        LibraryCommand("pwd", "Where the session is, which is the directory the next command runs in.", listOf("file", "directory")),
        LibraryCommand("mkdir -p <path>", "Create a directory and its parents.", listOf("file", "directory")),
        LibraryCommand("cp <source> <destination>", "Copy a file or directory.", listOf("file")),
        LibraryCommand("mv <source> <destination>", "Move or rename it.", listOf("file")),
        LibraryCommand("rm -rf <path>", "Delete a file or directory.", listOf("file", "directory")),
        LibraryCommand("cat <path>", "Read a file.", listOf("file")),
        LibraryCommand("chmod <mode> <path>", "Change a file's permissions.", listOf("file")),
        LibraryCommand("find <path> -name <pattern>", "Find files by name.", listOf("file", "directory")),

        // ---- the system itself ------------------------------------------------------------
        LibraryCommand("getprop", "Every system property.", listOf("info", "list")),
        LibraryCommand("getprop <property>", "Read one property.", listOf("info")),
        LibraryCommand("setprop <property> <value>", "Set one, until the next reboot.", listOf("info")),
        LibraryCommand("id", "Which uid the shell is running as.", listOf("info")),
        LibraryCommand("uptime", "How long the device has been up.", listOf("info")),
        LibraryCommand("ps -A", "Every process.", listOf("process", "list")),
        LibraryCommand("kill <pid>", "Stop one process, by the pid ps -A shows.", listOf("process")),
        LibraryCommand("top -n 1", "One sample of what is using the CPU.", listOf("process", "info")),
        LibraryCommand("dumpsys meminfo", "Memory use, by app.", listOf("memory", "info")),
        LibraryCommand("dumpsys cpuinfo", "CPU use, by app.", listOf("info")),
        LibraryCommand("service list", "Every system service, ready for the ones with a shell interface.", listOf("info", "list")),
        LibraryCommand("logcat -d -t 200", "The last two hundred log lines.", listOf("log", "info")),
        LibraryCommand("logcat -d -s <tag>", "Log lines from one tag.", listOf("log")),
        LibraryCommand("logcat -c", "Clear the log buffers.", listOf("log")),
        LibraryCommand("pm list users", "Every user on the device.", listOf("user", "list")),
        LibraryCommand("reboot", "Restart the device.", listOf("power")),
        LibraryCommand("reboot recovery", "Restart into recovery.", listOf("power")),
        LibraryCommand("reboot bootloader", "Restart into the bootloader.", listOf("power")),
        LibraryCommand("sync", "Flush pending writes to disk.", listOf("file"))
    )
}
