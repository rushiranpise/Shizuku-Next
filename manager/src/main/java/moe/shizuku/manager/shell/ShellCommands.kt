package moe.shizuku.manager.shell

import androidx.annotation.StringRes
import moe.shizuku.manager.R

/** One command the shell offers, with what it does and what it is about. */
data class LibraryCommand(
    /** May contain `<placeholders>`, which have to be filled in before it can run. */
    val command: String,
    @StringRes val description: Int,
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
    fun search(
        query: String,
        /** The description as the user reads it, in their language; it is searched as well. */
        describe: (LibraryCommand) -> String = { "" }
    ): List<LibraryCommand> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return LIBRARY
        return LIBRARY.filter { entry ->
            entry.command.contains(trimmed, ignoreCase = true) ||
                describe(entry).contains(trimmed, ignoreCase = true) ||
                entry.tags.any { it.contains(trimmed, ignoreCase = true) }
        }
    }

    val LIBRARY: List<LibraryCommand> = listOf(
        // ---- packages -----------------------------------------------------------------
        LibraryCommand("pm list packages", R.string.shell_lib_pm_list_packages, listOf("package", "list")),
        LibraryCommand("pm list packages -3", R.string.shell_lib_pm_list_packages_3, listOf("package", "list")),
        LibraryCommand("pm list packages -s", R.string.shell_lib_pm_list_packages_s, listOf("package", "list")),
        LibraryCommand("pm list packages -d", R.string.shell_lib_pm_list_packages_d, listOf("package", "list")),
        LibraryCommand("pm path <package>", R.string.shell_lib_pm_path, listOf("package", "info")),
        LibraryCommand("pm grant <package> <permission>", R.string.shell_lib_pm_grant, listOf("package", "permission")),
        LibraryCommand("pm revoke <package> <permission>", R.string.shell_lib_pm_revoke, listOf("package", "permission")),
        LibraryCommand("pm clear <package>", R.string.shell_lib_pm_clear, listOf("package", "storage")),
        LibraryCommand("pm disable-user --user 0 <package>", R.string.shell_lib_pm_disable_user_user_0, listOf("package")),
        LibraryCommand("pm enable <package>", R.string.shell_lib_pm_enable, listOf("package")),
        LibraryCommand("pm suspend --user 0 <package>", R.string.shell_lib_pm_suspend_user_0, listOf("package")),
        LibraryCommand("pm unsuspend --user 0 <package>", R.string.shell_lib_pm_unsuspend_user_0, listOf("package")),
        LibraryCommand("pm uninstall --user 0 <package>", R.string.shell_lib_pm_uninstall_user_0, listOf("package", "uninstall")),
        LibraryCommand("pm install-existing --user 0 <package>", R.string.shell_lib_pm_install_existing_user_0, listOf("package")),
        LibraryCommand("pm dump <package>", R.string.shell_lib_pm_dump, listOf("package", "info")),
        LibraryCommand("pm list permissions -d -g", R.string.shell_lib_pm_list_permissions_d_g, listOf("permission", "list")),
        LibraryCommand("pm install <apk_path>", R.string.shell_lib_pm_install, listOf("package", "install")),
        LibraryCommand("pm reset-permissions -p <package>", R.string.shell_lib_pm_reset_permissions_p, listOf("package", "permission")),
        LibraryCommand("pm uninstall-system-updates <package>", R.string.shell_lib_pm_uninstall_system_updates, listOf("package", "uninstall")),
        LibraryCommand("pm list features", R.string.shell_lib_pm_list_features, listOf("device", "list")),
        LibraryCommand(
            "cmd package compile -m speed -f <package>",
            R.string.shell_lib_cmd_package_compile_m_speed_f,
            listOf("package", "performance")
        ),

        // ---- activities, intents and app ops ------------------------------------------
        LibraryCommand("am start -n <package>/<activity>", R.string.shell_lib_am_start_n, listOf("activity", "intent")),
        LibraryCommand("am start -a <action>", R.string.shell_lib_am_start_a, listOf("activity", "intent")),
        LibraryCommand("am force-stop <package>", R.string.shell_lib_am_force_stop, listOf("package", "process")),
        LibraryCommand("am kill <package>", R.string.shell_lib_am_kill, listOf("package", "process")),
        LibraryCommand("am kill-all", R.string.shell_lib_am_kill_all, listOf("process")),
        LibraryCommand("am broadcast -a <action>", R.string.shell_lib_am_broadcast_a, listOf("intent")),
        LibraryCommand("am get-current-user", R.string.shell_lib_am_get_current_user, listOf("user", "info")),
        LibraryCommand("dumpsys activity activities", R.string.shell_lib_dumpsys_activity_activities, listOf("activity", "info")),
        LibraryCommand("dumpsys activity top", R.string.shell_lib_dumpsys_activity_top, listOf("activity", "info")),
        LibraryCommand("cmd appops get <package>", R.string.shell_lib_cmd_appops_get, listOf("appops", "package")),
        LibraryCommand("cmd appops set <package> <op> deny", R.string.shell_lib_cmd_appops_set_deny, listOf("appops", "package")),
        LibraryCommand("cmd appops set <package> <op> allow", R.string.shell_lib_cmd_appops_set_allow, listOf("appops", "package")),
        LibraryCommand("am set-standby-bucket <package> restricted", R.string.shell_lib_am_set_standby_bucket_restricted, listOf("battery", "package")),
        LibraryCommand("am set-standby-bucket <package> active", R.string.shell_lib_am_set_standby_bucket_active, listOf("battery", "package")),

        // ---- settings -----------------------------------------------------------------
        LibraryCommand("settings list global", R.string.shell_lib_settings_list_global, listOf("settings", "list")),
        LibraryCommand("settings list secure", R.string.shell_lib_settings_list_secure, listOf("settings", "list")),
        LibraryCommand("settings list system", R.string.shell_lib_settings_list_system, listOf("settings", "list")),
        LibraryCommand("settings get global <key>", R.string.shell_lib_settings_get_global, listOf("settings")),
        LibraryCommand("settings put global <key> <value>", R.string.shell_lib_settings_put_global, listOf("settings")),
        LibraryCommand("settings delete global <key>", R.string.shell_lib_settings_delete_global, listOf("settings")),
        LibraryCommand(
            "settings put global animator_duration_scale 0.5",
            R.string.shell_lib_settings_put_global_animator_durat,
            listOf("settings", "display")
        ),

        // ---- display and input ---------------------------------------------------------
        LibraryCommand("wm size", R.string.shell_lib_wm_size, listOf("display", "info")),
        LibraryCommand("wm density", R.string.shell_lib_wm_density, listOf("display", "info")),
        LibraryCommand("wm size <width>x<height>", R.string.shell_lib_wm_size_x, listOf("display")),
        LibraryCommand("wm density <dpi>", R.string.shell_lib_wm_density_2, listOf("display")),
        LibraryCommand("cmd uimode night yes", R.string.shell_lib_cmd_uimode_night_yes, listOf("display", "ui")),
        LibraryCommand("cmd uimode night no", R.string.shell_lib_cmd_uimode_night_no, listOf("display", "ui")),
        LibraryCommand("cmd statusbar expand-notifications", R.string.shell_lib_cmd_statusbar_expand_notifications, listOf("ui")),
        LibraryCommand("cmd statusbar collapse", R.string.shell_lib_cmd_statusbar_collapse, listOf("ui")),
        LibraryCommand("cmd notification list", R.string.shell_lib_cmd_notification_list, listOf("notification", "list")),
        LibraryCommand("dumpsys window displays", R.string.shell_lib_dumpsys_window_displays, listOf("display", "info")),
        LibraryCommand("input keyevent KEYCODE_HOME", R.string.shell_lib_input_keyevent_keycode_home, listOf("input")),
        LibraryCommand("input keyevent KEYCODE_BACK", R.string.shell_lib_input_keyevent_keycode_back, listOf("input")),
        LibraryCommand("input keyevent KEYCODE_APP_SWITCH", R.string.shell_lib_input_keyevent_keycode_app_switch, listOf("input")),
        LibraryCommand("input keyevent KEYCODE_POWER", R.string.shell_lib_input_keyevent_keycode_power, listOf("input", "power")),
        LibraryCommand("input text <text>", R.string.shell_lib_input_text, listOf("input")),
        LibraryCommand("input tap <x> <y>", R.string.shell_lib_input_tap, listOf("input")),
        LibraryCommand("input swipe <x1> <y1> <x2> <y2>", R.string.shell_lib_input_swipe, listOf("input")),
        LibraryCommand("screencap -p /sdcard/screen.png", R.string.shell_lib_screencap_p_sdcard_screen_png, listOf("display", "file")),
        LibraryCommand("screenrecord /sdcard/record.mp4", R.string.shell_lib_screenrecord_sdcard_record_mp4, listOf("display", "file")),

        // ---- network -------------------------------------------------------------------
        LibraryCommand("ip addr", R.string.shell_lib_ip_addr, listOf("network", "info")),
        LibraryCommand("ip route", R.string.shell_lib_ip_route, listOf("network", "info")),
        LibraryCommand("cmd wifi status", R.string.shell_lib_cmd_wifi_status, listOf("network", "wifi")),
        LibraryCommand("svc wifi enable", R.string.shell_lib_svc_wifi_enable, listOf("network", "wifi")),
        LibraryCommand("svc wifi disable", R.string.shell_lib_svc_wifi_disable, listOf("network", "wifi")),
        LibraryCommand("svc data enable", R.string.shell_lib_svc_data_enable, listOf("network")),
        LibraryCommand("svc data disable", R.string.shell_lib_svc_data_disable, listOf("network")),
        LibraryCommand("cmd connectivity airplane-mode enable", R.string.shell_lib_cmd_connectivity_airplane_mode_ena, listOf("network")),
        LibraryCommand("cmd connectivity airplane-mode disable", R.string.shell_lib_cmd_connectivity_airplane_mode_dis, listOf("network")),
        LibraryCommand("ping -c 4 <host>", R.string.shell_lib_ping_c_4, listOf("network")),
        LibraryCommand("netstat", R.string.shell_lib_netstat, listOf("network", "info")),

        // ---- power and battery ----------------------------------------------------------
        LibraryCommand("dumpsys battery", R.string.shell_lib_dumpsys_battery, listOf("battery", "info")),
        LibraryCommand("dumpsys deviceidle", R.string.shell_lib_dumpsys_deviceidle, listOf("battery", "info")),
        LibraryCommand("dumpsys deviceidle whitelist", R.string.shell_lib_dumpsys_deviceidle_whitelist, listOf("battery", "list")),
        LibraryCommand("dumpsys deviceidle whitelist +<package>", R.string.shell_lib_dumpsys_deviceidle_whitelist_2, listOf("battery", "package")),
        LibraryCommand("dumpsys deviceidle whitelist -<package>", R.string.shell_lib_dumpsys_deviceidle_whitelist_3, listOf("battery", "package")),
        LibraryCommand("dumpsys power | grep -m1 mWakefulness", R.string.shell_lib_dumpsys_power_grep_m1_mwakefulness, listOf("power", "info")),

        // ---- storage and files -----------------------------------------------------------
        LibraryCommand("df -h", R.string.shell_lib_df_h, listOf("storage", "info")),
        LibraryCommand("du -sh <path>", R.string.shell_lib_du_sh, listOf("storage", "directory")),
        LibraryCommand("ls -l <path>", R.string.shell_lib_ls_l, listOf("file", "directory")),
        LibraryCommand("pwd", R.string.shell_lib_pwd, listOf("file", "directory")),
        LibraryCommand("mkdir -p <path>", R.string.shell_lib_mkdir_p, listOf("file", "directory")),
        LibraryCommand("cp <source> <destination>", R.string.shell_lib_cp, listOf("file")),
        LibraryCommand("mv <source> <destination>", R.string.shell_lib_mv, listOf("file")),
        LibraryCommand("rm -rf <path>", R.string.shell_lib_rm_rf, listOf("file", "directory")),
        LibraryCommand("cat <path>", R.string.shell_lib_cat, listOf("file")),
        LibraryCommand("chmod <mode> <path>", R.string.shell_lib_chmod, listOf("file")),
        LibraryCommand("find <path> -name <pattern>", R.string.shell_lib_find_name, listOf("file", "directory")),

        // ---- the system itself ------------------------------------------------------------
        LibraryCommand("getprop", R.string.shell_lib_getprop, listOf("info", "list")),
        LibraryCommand("getprop <property>", R.string.shell_lib_getprop_2, listOf("info")),
        LibraryCommand("setprop <property> <value>", R.string.shell_lib_setprop, listOf("info")),
        LibraryCommand("id", R.string.shell_lib_id, listOf("info")),
        LibraryCommand("uptime", R.string.shell_lib_uptime, listOf("info")),
        LibraryCommand("ps -A", R.string.shell_lib_ps_a, listOf("process", "list")),
        LibraryCommand("kill <pid>", R.string.shell_lib_kill, listOf("process")),
        LibraryCommand("top -n 1", R.string.shell_lib_top_n_1, listOf("process", "info")),
        LibraryCommand("dumpsys meminfo", R.string.shell_lib_dumpsys_meminfo, listOf("memory", "info")),
        LibraryCommand("dumpsys cpuinfo", R.string.shell_lib_dumpsys_cpuinfo, listOf("info")),
        LibraryCommand("service list", R.string.shell_lib_service_list, listOf("info", "list")),
        LibraryCommand("logcat -d -t 200", R.string.shell_lib_logcat_d_t_200, listOf("log", "info")),
        LibraryCommand("logcat -d -s <tag>", R.string.shell_lib_logcat_d_s, listOf("log")),
        LibraryCommand("logcat -c", R.string.shell_lib_logcat_c, listOf("log")),
        LibraryCommand("pm list users", R.string.shell_lib_pm_list_users, listOf("user", "list")),
        LibraryCommand("reboot", R.string.shell_lib_reboot, listOf("power")),
        LibraryCommand("reboot recovery", R.string.shell_lib_reboot_recovery, listOf("power")),
        LibraryCommand("reboot bootloader", R.string.shell_lib_reboot_bootloader, listOf("power")),
        LibraryCommand("sync", R.string.shell_lib_sync, listOf("file"))
    )
}
