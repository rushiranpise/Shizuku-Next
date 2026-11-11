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
import moe.shizuku.manager.utils.Diag
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
    /** Already resolved: an op the app does not describe is named after the platform's name. */
    val label: String,
    /** What it does, for the ops worth a sentence; null for the rest. */
    val note: String?,
    val mode: OpMode
)

/** One curated app op that the batch editor can apply to selected apps. */
data class AppOpChoice(
    val name: String,
    @StringRes val label: Int,
    @StringRes val note: Int
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
    val networkBlocked: Boolean?,

    /**
     * The app's rotation mode. Null on a platform without the Android 16 rotation feature.
     */
    val rotationMode: RotationMode?
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
internal val APP_OPS = listOf(
    AppOpChoice("RUN_IN_BACKGROUND", R.string.app_op_background, R.string.app_op_background_note),
    // The blunter half of the same question, and the one autostart managers reach for: it
    // denies background execution whether or not the app is on screen, which takes its boot
    // receivers with it. They are separate ops because the platform treats them that way, and
    // the pairing is worth keeping visible: denying one is a decision about how an app may run,
    // not a setting that quietly does the same thing twice.
    AppOpChoice("RUN_ANY_IN_BACKGROUND", R.string.app_op_any_background, R.string.app_op_any_background_note),
    AppOpChoice("START_FOREGROUND", R.string.app_op_foreground, R.string.app_op_foreground_note),
    AppOpChoice("WAKE_LOCK", R.string.app_op_wake_lock, R.string.app_op_wake_lock_note),
    AppOpChoice("POST_NOTIFICATION", R.string.app_op_notifications, R.string.app_op_notifications_note),
    AppOpChoice("READ_CLIPBOARD", R.string.app_op_clipboard, R.string.app_op_clipboard_note),
    AppOpChoice("SYSTEM_ALERT_WINDOW", R.string.app_op_overlay, R.string.app_op_overlay_note),
    AppOpChoice("REQUEST_INSTALL_PACKAGES", R.string.app_op_install, R.string.app_op_install_note),
    AppOpChoice("TOAST_WINDOW", R.string.app_op_toast, R.string.app_op_toast_note),
    AppOpChoice("PROJECT_MEDIA", R.string.app_op_project_media, R.string.app_op_project_media_note),
    AppOpChoice("RECORD_AUDIO", R.string.app_op_record_audio, R.string.app_op_record_audio_note),
    AppOpChoice("CAMERA", R.string.app_op_camera, R.string.app_op_camera_note),
    AppOpChoice("VIBRATE", R.string.app_op_vibrate, R.string.app_op_vibrate_note),
    AppOpChoice("MOCK_LOCATION", R.string.app_op_mock_location, R.string.app_op_mock_location_note),
    AppOpChoice("PICTURE_IN_PICTURE", R.string.app_op_pip, R.string.app_op_pip_note)
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

    /** Where the apps that lose only mobile data are kept. */
    private const val PREF_FIREWALL_MOBILE_ONLY = "firewall_mobile_only_packages"

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
            networkBlocked = if (running) readNetworkBlockedForDisplay(context, packageName) else null,
            rotationMode = if (RotationOverride.isSupported()) {
                RotationOverride.modeFor(packageName)
            } else {
                null
            }
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

    /**
     * Every op the platform reports for this app, with the ones this app can describe first.
     *
     * The list used to be the fourteen in [APP_OPS], which meant an op that was not curated could not
     * be seen or changed here at all - the complaint in issue #147, from somebody who kept a
     * second app open for the rest. What the platform reports is the honest list: the shell prints
     * every op the app has a mode for, which is the same set `appops set` will accept, so it is
     * read and shown whole. The curated ones keep their sentences; the rest are named after the
     * platform's own name for them.
     */
    private fun readOps(context: Context, packageName: String): List<AppOp> {
        // `cmd appops` is the modern entry point; the old `appops` binary still answers on
        // a few releases where the cmd is unavailable.
        val modes = parseOpModes(
            runShellCommand("cmd appops get $packageName")
                ?: runShellCommand("appops get $packageName")
        )

        // The curated ones are always drawn, in the order they are written: an op the platform
        // did not print is shown in its default state rather than dropped, which is how this list
        // behaved before it followed the platform.
        val described = APP_OPS.map { choice ->
            AppOp(
                choice.name,
                context.getString(choice.label),
                context.getString(choice.note),
                modes[choice.name] ?: OpMode.DEFAULT
            )
        }

        val describedNames = described.mapTo(HashSet()) { it.name }
        val rest = modes.entries
            .filterNot { it.key in describedNames }
            .sortedBy { it.key }
            .map { (name, mode) -> AppOp(name, prettyOpName(name), null, mode) }

        return described + rest
    }

    /**
     * The ops and modes in `appops get` output.
     *
     * Read from the shell's own answer rather than from a list kept here, because the set of ops
     * belongs to the platform and its vendor: MIUI and OnePlus add their own, and a list compiled
     * into this app would be wrong on both while looking complete. Lines are `OP: mode`, or
     * `OP: mode; telemetry=...`, sometimes under a `Uid mode:` heading that only marks the first
     * line of that block.
     */
    internal fun parseOpModes(output: String?): LinkedHashMap<String, OpMode> {
        val modes = LinkedHashMap<String, OpMode>()
        output?.lineSequence()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // The op is the second-to-last field and the mode is the last, whichever shape the
            // line has; a mode carries a `;` tail when the platform adds timings to it.
            val fields = trimmed.split(':')
            if (fields.size < 2) return@forEach
            val op = fields[fields.size - 2].trim()
            if (op.isEmpty() || !op.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                return@forEach
            }

            modes[op] = OpMode.from(
                fields.last().trim().substringBefore(';').trim().substringBefore(' ')
            )
        }
        return modes
    }

    /**
     * A name for an op this app does not describe, from the platform's own name for it.
     *
     * READ_HEART_RATE is a name, not a sentence: underscores become spaces and only the first
     * word is capitalised, so it reads as something in a list beside the described ones.
     */
    internal fun prettyOpName(name: String): String {
        if (name.isEmpty()) return name
        if (!name.all { it.isUpperCase() || it == '_' || it.isDigit() }) return name

        val words = name.split('_').filter { it.isNotEmpty() }
        if (words.isEmpty()) return name
        return words.joinToString(" ") { it.lowercase() }
            .replaceFirstChar { it.titlecase() }
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

    /** Whether the metered deny chain exists on this platform. */
    fun supportsMobileOnly(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Apps this list should block only on metered networks. */
    fun readFirewallMobileOnly(context: Context): Set<String> =
        ShizukuSettings.getPreferences()
            .getStringSet(PREF_FIREWALL_MOBILE_ONLY, emptySet())
            .orEmpty()
            .toSet()

    /**
     * Moves a blocked app between the all-network chain and the metered chain.
     *
     * The rule is cleared on the old chain before it is set on the new one, because a uid can be
     * denied by both. If the new rule cannot be set, the old one is put back so the switch does
     * not leave an app unblocked. An app that is not currently blocked only changes its preference.
     */
    fun setFirewallMobileOnly(context: Context, key: String, mobileOnly: Boolean): Boolean {
        if (!supportsMobileOnly()) return false

        val known = readFirewallMobileOnly(context)
        if ((key in known) == mobileOnly) return true

        val blocked = key in readFirewallBlocked(context) ||
            readNetworkBlocked(context, key) == true
        if (blocked) {
            val packageName = FirewallKey.packageOf(key)
            val userId = FirewallKey.userIdOf(key)
            val uid = uidOf(context, packageName, userId) ?: return false
            val oldChain = if (mobileOnly) FirewallApi.CHAIN else FirewallApi.CHAIN_METERED
            val newChain = if (mobileOnly) FirewallApi.CHAIN_METERED else FirewallApi.CHAIN

            if (!FirewallApi.setBlocked(uid, false, oldChain)) return false
            if (!FirewallApi.setBlocked(uid, true, newChain)) {
                FirewallApi.setBlocked(uid, true, oldChain)
                return false
            }
        }

        ShizukuSettings.getPreferences().edit()
            .putStringSet(
                PREF_FIREWALL_MOBILE_ONLY,
                if (mobileOnly) known + key else known - key
            )
            .apply()
        return true
    }

    /**
     * Whether the platform's firewall is dropping this app's traffic.
     *
     * Asked rather than remembered: the bit belongs to the platform, another app can set it
     * (ShizuWall does exactly this for a living), and a switch has to show what is true now
     * rather than what this app last asked for. The command answers `package:deny` or
     * `package:allow` on stdout.
     */
    fun readNetworkBlocked(context: Context, key: String): Boolean? {
        if (!FirewallChain.supportsFirewall()) return null

        val packageName = FirewallKey.packageOf(key)
        val userId = FirewallKey.userIdOf(key)
        val chain = firewallChain(context, key)

        // The command only knows this user's packages, so another profile is asked through the
        // uid the firewall actually holds. The metered chain is not the command's either.
        if (chain == FirewallApi.CHAIN && userId == UserHandleCompat.myUserId()) {
            val answer = runShellCommand(
                "cmd connectivity get-package-networking-enabled $packageName"
            )
            when {
                answer?.endsWith(":deny") == true -> return true
                answer?.endsWith(":allow") == true -> return false
            }
        }

        // The command could not say - it is missing on some ROMs, which is the whole reason for the
        // fallback - so the platform is asked through the interface the command is a front end for.
        val uid = uidOf(context, packageName, userId) ?: return null
        return FirewallApi.isBlocked(FirewallApi.blockedRule(uid, chain))
    }

    /**
     * The state the app's own page shows.
     *
     * Android 13 can set a rule but not read it back, so the platform's answer is null there.
     * This app's record is then the only state available; it is not the platform's, but it is
     * the state this app wrote and the switch can still change it.
     */
    fun readNetworkBlockedForDisplay(context: Context, key: String): Boolean? {
        readNetworkBlocked(context, key)?.let { return it }
        if (!FirewallChain.supportsFirewall()) return null
        return key in readFirewallBlocked(context)
    }

    /**
     * The uid the platform's firewall knows a package by.
     *
     * Asked of the local package manager rather than the shell: for an installed app it is the same
     * number, and it costs nothing.
     */
    private fun uidOf(
        context: Context,
        packageName: String,
        userId: Int = UserHandleCompat.myUserId()
    ): Int? {
        if (userId == UserHandleCompat.myUserId()) {
            return runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).uid
            }.getOrNull()
        }
        return ShizukuSystemApis.getPackageInfoAsShell(packageName, 0L, userId)
            ?.applicationInfo?.uid
    }

    /** The chain this app belongs to: the metered one when it is marked mobile-only. */
    private fun firewallChain(context: Context, key: String): Int =
        if (supportsMobileOnly() && key in readFirewallMobileOnly(context)) {
            FirewallApi.CHAIN_METERED
        } else {
            FirewallApi.CHAIN
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
    fun setNetworkBlocked(context: Context, key: String, blocked: Boolean): Boolean {
        if (!FirewallChain.supportsFirewall()) return false

        if (FirewallChain.isOn() != true) {
            FirewallChain.setOn(true)
        }

        val packageName = FirewallKey.packageOf(key)
        val userId = FirewallKey.userIdOf(key)
        val chain = firewallChain(context, key)
        val uid = uidOf(context, packageName, userId)
        if (uid == null) {
            Diag.warn(TAG, "Firewall $key: no uid for the rule")
            return false
        }

        if (chain == FirewallApi.CHAIN && userId == UserHandleCompat.myUserId()) {
            val value = if (blocked) "false" else "true"
            // The command is tried first, but its exit code is not the answer: a ROM can accept
            // the verb and leave the rule where it was. What it did is read, and the same rule is
            // set through the interface behind the command when the answer is not the one asked
            // for.
            val commandWrote =
                shellOk("cmd connectivity set-package-networking-enabled $value $packageName")
            val commandRead = readNetworkBlocked(context, key)
            val applied = FirewallApi.writeApplied(commandWrote, blocked, commandRead)
            Diag.info(
                TAG,
                "Firewall $key: block=$blocked command=$commandWrote read=$commandRead"
            )
            if (applied) {
                rememberFirewallBlocked(context, key, blocked)
                return true
            }
        }

        // A ROM whose connectivity command has no such verb, or has one that does not take, gets
        // the same rule through the interface it is a front end for. Another user and the metered
        // chain go straight there because the command has no way to name either.
        if (!FirewallApi.setBlocked(uid, blocked, chain)) {
            Diag.warn(TAG, "Firewall $key: the interface refused the rule")
            return false
        }
        val observed = FirewallApi.isBlocked(FirewallApi.blockedRule(uid, chain))
        val applied = FirewallApi.writeApplied(
            wrote = true,
            requested = blocked,
            observed = observed
        )
        Diag.info(TAG, "Firewall $key via the interface: read=$observed")

        // Recorded only once the platform agreed, because this is what the Firewall list shows
        // and a list that says a package is blocked when it is not is worse than no list.
        if (applied) rememberFirewallBlocked(context, key, blocked)
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
        parseOpDenied(runShellCommand("cmd appops query-op $op deny"))

    /**
     * The names `cmd appops query-op` printed, and only the ones that are names.
     *
     * A line that is not a package name is not a package. `query-op` is asked for an op by the
     * name the framework gives it, and a name this platform does not have makes the command
     * print something else entirely - which the screen then counted as one blocked app, with no
     * row to show for it. A package name never contains a space and never starts with a dash, so
     * anything that does is the command talking rather than an app.
     */
    internal fun parseOpDenied(output: String?): Set<String> =
        output
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.none { c -> c.isWhitespace() } && !it.startsWith("-") }
            ?.toSet()
            ?: emptySet()

    /**
     * The installed packages, by name, in one command.
     *
     * For the one thing a package name read out of an app op is not allowed to be: an app that is
     * not on the device. The op service keeps its records by uid and answers with names, and a
     * record can outlive what it was made for - an app that has been uninstalled, a name this
     * platform has never had. Counting those is how a screen says "1 blocked" over an empty list,
     * and how a tile grows a dot for a feature that is doing nothing at all.
     */
    fun installedPackageNames(): Set<String> =
        runShellCommand("pm list packages")
            ?.lineSequence()
            ?.map { it.trim().removePrefix("package:") }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /**
     * The packages with [op] denied, among those the device actually has.
     *
     * What every count of "blocked" in the UI is built from, so that the number and the list under
     * it are the same statement: an app that is not installed cannot be shown, cannot be unblocked
     * from here, and is not something to claim anything about.
     */
    fun readOpDeniedInstalled(op: String): Set<String> =
        readOpDenied(op) intersect installedPackageNames()

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

    /**
     * The blocked packages the platform no longer has blocked.
     *
     * Recorded by this app, still installed, and not blocked where the platform was asked - the
     * platform is the authority, so a block that is still in place needs nothing done to it. Split
     * out from the shell work so the decision can be reasoned about without a device: the answering
     * lambda is the only part of it that needs one.
     */
    internal fun toReapply(
        recorded: Set<String>,
        installed: Set<String>,
        blockedNow: (String) -> Boolean
    ): Set<String> = (recorded intersect installed).filterNot(blockedNow).toSet()

    /**
     * Puts back the blocks that are no longer in place, and returns how many took.
     *
     * A block is `cmd connectivity` state, which belongs to the framework and goes with it: a full
     * reboot clears it, and so does a soft one, where the kernel keeps running and only the
     * framework restarts. That is why this asks the platform what is still blocked rather than
     * trying to work out whether a reboot happened - what matters is the state, not the cause. It
     * also covers a change made by something else in the same session.
     *
     * Only packages the device still has are asked about: an app uninstalled since is not one to
     * reach for behind the user's back, and blocking a package name nobody has installed is a
     * command with nothing to do.
     */
    fun reapplyNetworkBlocked(context: Context): Int {
        val recorded = readFirewallBlocked(context)
        if (recorded.isEmpty()) return 0

        // Android 13 has no reader, so its record is the only evidence there is: the list is put
        // back rather than assumed to have survived. Everywhere else a failed read still counts
        // as blocked, because blocking it again would fail for the same reason.
        val canRead = FirewallChain.canReadRule()
        val gone = toReapply(recorded, installedFirewallKeys(context)) { key ->
            !canRead || readNetworkBlocked(context, key) != false
        }
        return gone.count { setNetworkBlocked(context, it, true) }
    }

    /** The firewall keys the device still has, including work profiles and clones. */
    private fun installedFirewallKeys(context: Context): Set<String> =
        InstalledPackages.allUsers(context, 0)
            .mapNotNull { pi ->
                pi.applicationInfo?.uid?.let { FirewallKey.fromUid(it, pi.packageName) }
            }
            .toSet()

    private fun rememberFirewallBlocked(
        context: Context,
        key: String,
        blocked: Boolean
    ) {
        val known = readFirewallBlocked(context).toMutableSet()
        if (blocked) known.add(key) else known.remove(key)
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
