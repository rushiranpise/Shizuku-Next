package moe.shizuku.manager.manage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import java.security.MessageDigest
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.utils.runShellCommand

private const val TAG = "PackageTools"

/**
 * The base protection level lives in the low nibble of `protectionLevel`; the rest of the
 * int is flags. Spelled out rather than using `PermissionInfo.PROTECTION_MASK_BASE`, which
 * only exists from API 28 while the manager runs from API 24.
 */
private const val PROTECTION_MASK_BASE = 0x0f

/** Protected enough that granting it hands the app real power over the device. */
private val DANGEROUS_GRANTS = setOf(
    "android.permission.INSTALL_PACKAGES",
    "android.permission.DELETE_PACKAGES",
    "android.permission.READ_LOGS",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
    "android.permission.FORCE_STOP_PACKAGES",
    "android.permission.MANAGE_USB",
    "android.permission.DUMP",
    "android.permission.PACKAGE_USAGE_STATS",
)

/** Which half of the detail screen a permission belongs to. */
enum class PermissionKind { PRIVILEGED, RUNTIME }

/**
 * One permission an app declares.
 *
 * [changeable] is the honest part: adb (and so Shizuku) can only change a permission the
 * platform marks as *development* or *dangerous*. Everything else a plain `signature`
 * permission, for instance is shown because the app asked for it, but the switch is
 * disabled rather than left to fail.
 */
data class AppPermission(
    val name: String,
    val short: String,
    val kind: PermissionKind,
    val granted: Boolean,
    val changeable: Boolean,
    val protection: String,
    val dangerous: Boolean
)

enum class OpMode(val id: String) {
    DEFAULT("default"),
    ALLOW("allow"),
    IGNORE("ignore"),
    DENY("deny");

    companion object {
        fun from(value: String?): OpMode =
            entries.firstOrNull { it.id == value?.lowercase() } ?: DEFAULT
    }
}

data class AppOp(
    val name: String,
    @StringRes val label: Int,
    @StringRes val note: Int,
    val mode: OpMode
)

/**
 * The app standby buckets, least to most restricted.
 *
 * [settable] marks the one bucket adb cannot assign: an app lands in "exempted" because the
 * system put it there (it is on the battery optimisation exemption list), so it is reported
 * but never offered as a choice and `am set-standby-bucket <pkg> exempted` silently does
 * nothing, which is why it is worth saying so rather than letting a switch lie.
 */
enum class StandbyBucket(
    val id: String,
    @StringRes val label: Int,
    @StringRes val note: Int,
    val settable: Boolean = true
) {
    EXEMPTED(
        "exempted", R.string.app_bucket_exempted, R.string.app_bucket_exempted_note,
        settable = false
    ),
    ACTIVE("active", R.string.app_bucket_active, R.string.app_bucket_active_note),
    WORKING_SET("working_set", R.string.app_bucket_default, R.string.app_bucket_default_note),
    FREQUENT("frequent", R.string.app_bucket_frequent, R.string.app_bucket_frequent_note),
    RARE("rare", R.string.app_bucket_rare, R.string.app_bucket_rare_note),
    RESTRICTED("restricted", R.string.app_bucket_restricted, R.string.app_bucket_restricted_note),
    NEVER("never", R.string.app_bucket_never, R.string.app_bucket_never_note);

    companion object {
        /**
         * `am get-standby-bucket` answers with the bucket number, and the names are
         * accepted too both are read, because the answer has changed shape across
         * releases.
         */
        fun from(raw: String?): StandbyBucket? = when (raw?.trim()?.lowercase()) {
            "5", "exempted" -> EXEMPTED
            "10", "active" -> ACTIVE
            "20", "working_set", "working", "default" -> WORKING_SET
            "30", "frequent" -> FREQUENT
            "40", "rare" -> RARE
            "45", "restricted" -> RESTRICTED
            "50", "never" -> NEVER
            else -> null
        }
    }
}

/** Everything the detail screen shows, read in one pass. */
data class AppDetail(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val uid: Int,
    val targetSdk: Int,
    val minSdk: Int,
    val abi: String?,
    val dataDir: String?,
    val signature: String?,
    val installer: String?,
    val debuggable: Boolean,
    val backupAllowed: Boolean,
    val systemApp: Boolean,
    val uninstalled: Boolean,
    val suspended: Boolean,
    val enabled: Boolean,
    val firstInstall: Long,
    val lastUpdate: Long,
    val hasLauncher: Boolean,
    val permissions: List<AppPermission>,
    val ops: List<AppOp>,
    val bucket: StandbyBucket?,
    val batteryUnrestricted: Boolean,

    /**
     * Whether the platform is dropping this app's traffic. Null when nothing could be asked:
     * the chain is Android 11 and up, and the answer needs a running server.
     */
    val networkBlocked: Boolean?
)

/**
 * The app ops worth exposing, in the order they are shown.
 *
 * Chosen rather than complete: the platform has hundreds and most of them are noise, or are
 * the same switch as a runtime permission that the permissions section above already offers.
 * These are the ones with an effect somebody can describe in a sentence and expect, and every
 * one of them was measured changing through adb before it was listed.
 *
 * The camera and the microphone are here for a reason worth stating, since the permission
 * section already covers both: revoking a permission is visible to the app and makes it ask
 * again, while denying the *op* underneath leaves the permission granted and makes the data
 * come back empty. That is the switch for an app that will not take no for an answer.
 */
private val OPS = listOf(
    Triple("RUN_IN_BACKGROUND", R.string.app_op_background, R.string.app_op_background_note),
    // The blunter half of the same question, and the one autostart managers reach for: it
    // denies background execution whether or not the app is on screen, which takes its boot
    // receivers with it. They are separate ops because the platform treats them that way, and
    // the pairing is worth keeping visible: denying one is a decision about how an app may run,
    // not a setting that quietly does the same thing twice.
    Triple("RUN_ANY_IN_BACKGROUND", R.string.app_op_any_background, R.string.app_op_any_background_note),
    Triple("START_FOREGROUND", R.string.app_op_foreground, R.string.app_op_foreground_note),
    Triple("WAKE_LOCK", R.string.app_op_wake_lock, R.string.app_op_wake_lock_note),
    Triple("POST_NOTIFICATION", R.string.app_op_notifications, R.string.app_op_notifications_note),
    Triple("READ_CLIPBOARD", R.string.app_op_clipboard, R.string.app_op_clipboard_note),
    Triple("SYSTEM_ALERT_WINDOW", R.string.app_op_overlay, R.string.app_op_overlay_note),
    Triple("REQUEST_INSTALL_PACKAGES", R.string.app_op_install, R.string.app_op_install_note),
    Triple("TOAST_WINDOW", R.string.app_op_toast, R.string.app_op_toast_note),
    Triple("PROJECT_MEDIA", R.string.app_op_project_media, R.string.app_op_project_media_note),
    Triple("RECORD_AUDIO", R.string.app_op_record_audio, R.string.app_op_record_audio_note),
    Triple("CAMERA", R.string.app_op_camera, R.string.app_op_camera_note),
    Triple("VIBRATE", R.string.app_op_vibrate, R.string.app_op_vibrate_note),
    Triple("MOCK_LOCATION", R.string.app_op_mock_location, R.string.app_op_mock_location_note),
    Triple("PICTURE_IN_PICTURE", R.string.app_op_pip, R.string.app_op_pip_note)
)

/*
 * Not in that list, because the platform refuses to let adb change them measured, not
 * assumed: `cmd appops set ... allow` exits 0 and the op reads back unchanged for
 * SCHEDULE_EXACT_ALARM, WRITE_SETTINGS, MANAGE_EXTERNAL_STORAGE and GET_USAGE_STATS. They
 * are special access in system settings now, which is also where the user has to go for
 * them. Offering a switch that silently does nothing would be worse than not offering it,
 * and the App ops section says so instead.
 */

object PackageTools {

    /** Where the firewall list's own record of what it blocked is kept. */
    private const val PREF_FIREWALL_BLOCKED = "firewall_blocked_packages"

    private val pm get() = moe.shizuku.manager.ShizukuApplication.application.packageManager

    // ---- reading -----------------------------------------------------------------

    /**
     * Reads one app. Local package-manager calls where they exist, because they do not
     * need the server and cannot fail because it is down; the three facts that only shell
     * can read (ops, bucket, battery exemption) come back empty when it is.
     */
    fun loadDetail(context: Context, packageName: String): AppDetail? {
        val pm = context.packageManager
        // The signing and signature flags have to be asked for explicitly: without them
        // `signingInfo` comes back null and the signature fact reads as missing rather
        // than as unread.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES

        // Through [InstalledPackages], not this app's package manager: a package the shell listed
        // may be one this app cannot look up, and a row that opens onto "not found" is worse than
        // a row that was never there.
        val info = InstalledPackages.one(context, packageName, flags) ?: return null

        val ai = info.applicationInfo
        val uninstalled = ai == null
        val label = runCatching { ai?.loadLabel(pm)?.toString() ?: packageName }
            .getOrDefault(packageName)

        val running = ShizukuStateMachine.isRunning()

        return AppDetail(
            packageName = packageName,
            label = label,
            versionName = info.versionName,
            // `longVersionCode` arrived with Android 9 (API 28); below it the int is all the
            // platform keeps, so widening that is the same number, not a fallback.
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            uid = ai?.uid ?: 0,
            targetSdk = ai?.targetSdkVersion ?: 0,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ai?.minSdkVersion ?: 0 else 0,
            abi = abiOf(ai),
            dataDir = ai?.dataDir,
            signature = signatureOf(info),
            installer = installerOf(context, packageName),
            debuggable = ai != null && (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            backupAllowed = ai != null && (ai.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0,
            systemApp = ai != null && (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
            uninstalled = uninstalled,
            suspended = ai != null && (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
            enabled = ai?.enabled ?: false,
            firstInstall = info.firstInstallTime,
            lastUpdate = info.lastUpdateTime,
            hasLauncher = runCatching { pm.getLaunchIntentForPackage(packageName) != null }
                .getOrDefault(false),
            permissions = permissionsOf(pm, info),
            ops = if (running) readOps(context, packageName) else emptyList(),
            bucket = if (running) readBucket(context, packageName) else null,
            batteryUnrestricted = running && isBatteryUnrestricted(context, packageName),
            networkBlocked = if (running) readNetworkBlocked(packageName) else null
        )
    }

    /**
     * Splits the declared permissions in two and works out what can be changed.
     *
     * The classification comes from the platform's own [PermissionInfo] rather than a list
     * we maintain: a release that adds a permission should not need a release of ours. The
     * curated names are only a fallback for permissions the package manager will not
     * describe to us (some signature permissions of other packages are not readable from
     * an app), so a known-important one is still shown instead of silently dropped.
     */
    private fun permissionsOf(pm: PackageManager, info: PackageInfo): List<AppPermission> {
        val declared = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(0)

        val result = ArrayList<AppPermission>(declared.size)
        for (i in declared.indices) {
            val name = declared[i] ?: continue
            val granted = flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0

            val pi = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
            val raw = pi?.protectionLevel ?: 0
            val base = raw and PROTECTION_MASK_BASE
            val development = raw and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0
            val privileged = raw and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0
            val dangerous = base == PermissionInfo.PROTECTION_DANGEROUS
            val signature = base == PermissionInfo.PROTECTION_SIGNATURE
            // PROTECTION_INTERNAL is 4, and is not a constant we can rely on being visible.
            val internal = base == 4

            val known = name in DANGEROUS_GRANTS
            val kind = when {
                dangerous -> PermissionKind.RUNTIME
                signature || privileged || internal || development -> PermissionKind.PRIVILEGED
                // Nothing to go on, but we know this one matters.
                known -> PermissionKind.PRIVILEGED
                else -> continue
            }

            result.add(
                AppPermission(
                    name = name,
                    short = name.substringAfterLast('.'),
                    kind = kind,
                    granted = granted,

                    // What adb is actually allowed to change. Everything else is shown
                    // read-only instead of pretending a toggle would work.
                    changeable = development || (dangerous && pi != null),
                    protection = protectionLabel(base, development, privileged, dangerous, pi),
                    dangerous = dangerous || known
                )
            )
        }

        return result.sortedWith(compareBy({ it.kind != PermissionKind.PRIVILEGED }, { it.short }))
    }

    private fun protectionLabel(
        base: Int,
        development: Boolean,
        privileged: Boolean,
        dangerous: Boolean,
        pi: PermissionInfo?
    ): String {
        if (pi == null) return "unknown"
        val parts = ArrayList<String>(3)
        parts.add(
            when {
                dangerous -> "dangerous"
                base == PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                base == 4 -> "internal"
                else -> "normal"
            }
        )
        if (privileged) parts.add("privileged")
        if (development) parts.add("development")
        return parts.joinToString("|")
    }

    private fun readOps(context: Context, packageName: String): List<AppOp> {
        // `cmd appops` is the modern entry point; the old `appops` binary still answers on
        // a few releases where the cmd is unavailable.
        val output = runShellCommand("cmd appops get $packageName")
            ?: runShellCommand("appops get $packageName")
            ?: return OPS.map { AppOp(it.first, it.second, it.third, OpMode.DEFAULT) }

        val modes = HashMap<String, OpMode>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Either "OP: mode" or a prefixed "something: OP: mode"; the op name is the
            // second-to-last field and the mode is the last, whichever shape it is.
            val fields = trimmed.split(':')
            if (fields.size < 2) continue
            val op = fields[fields.size - 2].trim()
            if (op.isEmpty() || !op.all { it.isUpperCase() || it == '_' || it.isDigit() }) continue
            val mode = OpMode.from(fields.last().trim().substringBefore(';').trim().substringBefore(' '))
            if (mode != OpMode.DEFAULT) modes[op] = mode
        }

        return OPS.map { (name, label, note) ->
            AppOp(name, label, note, modes[name] ?: OpMode.DEFAULT)
        }
    }

    private fun readBucket(context: Context, packageName: String): StandbyBucket? =
        StandbyBucket.from(runShellCommand("am get-standby-bucket $packageName"))

    /** The mode one op is in, or the default when it has never been set. */
    private fun readOp(packageName: String, op: String): OpMode {
        val output = runShellCommand("cmd appops get $packageName $op") ?: return OpMode.DEFAULT
        val line = output.lineSequence().lastOrNull { it.isNotBlank() } ?: return OpMode.DEFAULT
        val value = line.substringAfterLast(':')
            .trim()
            .substringBefore(';')
            .trim()
            .substringBefore(' ')
        return OpMode.from(value)
    }

    private fun isBatteryUnrestricted(context: Context, packageName: String): Boolean {
        val output = runShellCommand("dumpsys deviceidle whitelist") ?: return false
        // Lines are "<list>,<package>", where the list is "system" or "user".
        return output.lineSequence().any {
            it.trim().substringAfter(',', "").trim() == packageName
        }
    }

    /**
     * The ABI the app was installed for, taken from the directory its native libraries were
     * unpacked into (`.../lib/arm64`). `ApplicationInfo.primaryCpuAbi` holds the same answer
     * but is not part of the public SDK, and the APK path does not mention it on Android
     * 11+ the libraries sit beside the APK rather than inside its path.
     *
     * Null means the app ships no native code, which the row shows as nothing to report.
     */
    private fun abiOf(ai: ApplicationInfo?): String? =
        ai?.nativeLibraryDir?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun signatureOf(info: PackageInfo): String? = runCatching {
        @Suppress("DEPRECATION")
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null

        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(32) + "…"
    }.getOrNull()

    private fun installerOf(context: Context, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    /** The permission state as the platform reports it the only answer worth trusting. */
    private fun granted(context: Context, packageName: String, permission: String): Boolean? =
        runCatching {
            // Read as the shell, like the list that named the app: a package this app cannot see
            // is one whose declared permissions it cannot read either, and the answer would be a
            // null that reads as "not granted" rather than as "could not tell".
            val info = InstalledPackages.one(context, packageName, PackageManager.GET_PERMISSIONS)
                ?: return@runCatching null
            val declared = info.requestedPermissions ?: return@runCatching null
            val flags = info.requestedPermissionsFlags ?: return@runCatching null
            val index = declared.indexOf(permission)
            if (index < 0) return@runCatching null
            flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
        }.getOrNull()

    // ---- writing -----------------------------------------------------------------

    /**
     * Grants or revokes one permission, and reports what actually happened.
     *
     * The IPC call is tried first because it is the same call `pm` makes, without the
     * process. But it can land on a permission the platform refuses to change, and some
     * releases answer a refused change with silence so the permission's own state is
     * what decides the result, not the call's return, and the shell is tried as a second
     * opinion before giving up.
     */
    fun setPermission(context: Context, packageName: String, permission: String, grant: Boolean): Boolean {
        if (!ShizukuStateMachine.isRunning()) return false
        val userId = UserHandleCompat.myUserId()

        runCatching {
            if (grant) ShizukuSystemApis.grantRuntimePermission(packageName, permission, userId)
            else ShizukuSystemApis.revokeRuntimePermission(packageName, permission, userId)
        }
        if (granted(context, packageName, permission) == grant) return true

        val verb = if (grant) "grant" else "revoke"
        shellOk("pm $verb --user $userId $packageName $permission")
        return granted(context, packageName, permission) == grant
    }

    /**
     * Sets one app op, and only reports success if the op actually holds that value.
     *
     * The platform exits 0 for an op it does not let anyone change, so the exit code alone
     * would report a change that never happened. Reading the op back is the difference
     * between a switch that works and one that lies.
     */
    fun setOp(context: Context, packageName: String, op: String, mode: OpMode): Boolean {
        shellOk("cmd appops set $packageName $op ${mode.id}")
        return readOp(packageName, op) == mode
    }

    /** Sets [op] for one app to allowed or denied, and reports whether it took. */
    fun setOpBlocked(
        context: Context,
        packageName: String,
        op: String,
        blocked: Boolean
    ): Boolean = setOp(context, packageName, op, if (blocked) OpMode.DENY else OpMode.ALLOW)

    fun setBucket(context: Context, packageName: String, bucket: StandbyBucket): Boolean {
        shellOk("am set-standby-bucket $packageName ${bucket.id}")
        return readBucket(context, packageName) == bucket
    }

    fun setBatteryUnrestricted(context: Context, packageName: String, unrestricted: Boolean): Boolean {
        val sign = if (unrestricted) "+" else "-"
        // The two entry points that exist across the range we support; the second is the
        // newer one and is where the first goes missing.
        val ran = shellOk("dumpsys deviceidle whitelist $sign$packageName") ||
            shellOk("cmd deviceidle whitelist $sign$packageName")
        return ran && isBatteryUnrestricted(context, packageName) == unrestricted
    }

    /**
     * Whether the platform's firewall is dropping this app's traffic.
     *
     * Asked rather than remembered: the bit belongs to the platform, another app can set it
     * (ShizuWall does exactly this for a living), and a switch has to show what is true now
     * rather than what this app last asked for. The command answers `package:deny` or
     * `package:allow` on stdout.
     */
    fun readNetworkBlocked(packageName: String): Boolean? {
        val answer = runShellCommand("cmd connectivity get-package-networking-enabled $packageName")
            ?: return null
        return when {
            answer.endsWith(":deny") -> true
            answer.endsWith(":allow") -> false
            else -> null
        }
    }

    /**
     * Blocks or allows this app's traffic with the platform's own firewall, no VPN involved.
     *
     * The bit lives in a chain that is off by default and does nothing until it is on, so the
     * chain is switched on first. It is left on afterwards on purpose: switching it off would
     * allow every app anything else has blocked through it, which is not this switch's
     * business. Android's own description of the chain calls it one for debugging, which is
     * why the row says what it is rather than pretending it is a documented feature.
     */
    fun setNetworkBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        val chain = runShellCommand("cmd connectivity get-chain3-enabled")
        if (chain?.endsWith(":enabled") != true) {
            shellOk("cmd connectivity set-chain3-enabled true")
        }

        val value = if (blocked) "false" else "true"
        val ran = shellOk("cmd connectivity set-package-networking-enabled $value $packageName")
        val applied = ran && readNetworkBlocked(packageName) == blocked
        // Recorded only once the platform agreed, because this is what the Firewall list shows
        // and a list that says a package is blocked when it is not is worse than no list.
        if (applied) rememberFirewallBlocked(context, packageName, blocked)
        return applied
    }

    // ---- one state, for every app --------------------------------------------------

    /**
     * The packages with [op] denied, in one command.
     *
     * Asked for the whole mode at once rather than one app at a time, because `cmd appops
     * query-op` prints a package name per line: one shell round trip answers for six hundred
     * apps instead of six hundred of them.
     */
    fun readOpDenied(op: String): Set<String> =
        runShellCommand("cmd appops query-op $op deny")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /**
     * The packages this app has blocked with the platform's firewall, as far as it knows.
     *
     * The platform can answer for one package at a time and there is no way to ask it for the
     * list - the command needs a package name, and six hundred shell round trips is not a
     * screen anybody would wait for - so the set is remembered here, written whenever this app
     * blocks or unblocks something. The app's own page asks the platform directly, because one
     * answer is cheap, and that page is where the truth is read: another app can block a
     * package through the same chain, and this list will not know it.
     */
    fun readFirewallBlocked(context: Context): Set<String> =
        ShizukuSettings.getPreferences()
            .getStringSet(PREF_FIREWALL_BLOCKED, emptySet())
            .orEmpty()
            .toSet()

    private fun rememberFirewallBlocked(
        context: Context,
        packageName: String,
        blocked: Boolean
    ) {
        val known = readFirewallBlocked(context).toMutableSet()
        if (blocked) known.add(packageName) else known.remove(packageName)
        ShizukuSettings.getPreferences().edit()
            .putStringSet(PREF_FIREWALL_BLOCKED, known)
            .apply()
    }

    fun forceStop(context: Context, packageName: String): Boolean =
        shellOk("am force-stop $packageName")

    fun clearData(context: Context, packageName: String): Boolean =
        shellOk("pm clear --user ${UserHandleCompat.myUserId()} $packageName")

    fun setSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        val verb = if (suspended) "suspend" else "unsuspend"
        return shellOk("pm $verb --user ${UserHandleCompat.myUserId()} $packageName")
    }

    /**
     * Disabling is a state change the package manager reports back, so [setDisabled] can
     * check it landed instead of trusting the command's exit code alone.
     */
    fun setDisabled(context: Context, packageName: String, disabled: Boolean): Boolean {
        val userId = UserHandleCompat.myUserId()
        val verb = if (disabled) "disable-user" else "enable"
        shellOk("pm $verb --user $userId $packageName")
        val now = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).enabled
        }.getOrNull() ?: return false
        return now != disabled
    }

    fun uninstallForUser(context: Context, packageName: String): Boolean {
        val userId = UserHandleCompat.myUserId()
        shellOk("pm uninstall --user $userId $packageName")
        return runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            false
        }.getOrDefault(true)
    }

    fun restoreSystemApp(context: Context, packageName: String): Boolean {
        val userId = UserHandleCompat.myUserId()
        shellOk("pm install-existing --user $userId $packageName")
        return runCatching {
            context.packageManager.getApplicationInfo(packageName, 0) != null
        }.getOrDefault(false)
    }

    // ---- shell plumbing ----------------------------------------------------------

    /**
     * Runs [cmd] and reports its exit code.
     *
     * [runShellCommand] returns the output, which is nothing at all for the commands that
     * matter here `am force-stop` prints nothing on success, so an empty answer cannot
     * be told from a failure. Asking the shell for the status instead makes success and
     * failure distinguishable, which is what lets a row say "it did not work" honestly.
     */
    private fun shellExit(cmd: String): Int? =
        runShellCommand("{ $cmd ; } > /dev/null 2>&1; echo \$?")
            ?.lineSequence()
            ?.lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.toIntOrNull()

    private fun shellOk(cmd: String): Boolean {
        val code = shellExit(cmd)
        if (code != 0) Log.w(TAG, "Shell command failed ($code): $cmd")
        return code == 0
    }
}
