package moe.shizuku.manager.manage

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.runShellCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import moe.shizuku.manager.utils.Diag

/**
 * The two permissions hiding needs beyond the one this app already holds, given once by Shizuku.
 *
 * Both are AppOps-and-pm-grant territory rather than runtime permissions, so neither can be asked
 * for with a dialog: there is no prompt to show, only a command to run. Both are declared in the
 * manifest, which is not a formality - a permission an app has not declared cannot be granted to
 * it, and commands run by hand against an undeclared permission appear to do nothing at all.
 *
 * They are asked for here, silently, because there is no decision in them worth a screen: the
 * user turned on a list on a hiding screen, and without these two the list cannot work. What the
 * screen does instead is say afterwards whether they are in place - see the permissions screen.
 *
 *     cmd appops set --user current <package> GET_USAGE_STATS allow   which app is in front
 *     pm grant <package> android.permission.DUMP                      why another app was closed
 *
 * The first is the one the watch cannot do without: with it the foreground comes from the
 * platform, which is what makes a list that hides a debugging transport survivable. Without it
 * the watch falls back to reading the foreground through the shell - and the shell is the first
 * thing to die when the setting being hidden is the one carrying it.
 *
 * Both survive until the app is reinstalled, so this is a setup step and not a per-run one.
 */
object HidingGrants {

    private const val TAG = AppConstants.TAG

    /** Whether the app may ask the platform which app is in front. */
    fun usageAccess(): Boolean = Hiding.hasUsageAccess()

    /** Whether the app may ask the platform why another app was closed. */
    fun dump(): Boolean = granted(Manifest.permission.DUMP)

    fun all(): Boolean = usageAccess() && dump()

    private fun granted(permission: String): Boolean = runCatching {
        ShizukuApplication.application.checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Whether there is a shell to ask at all: both grants are shell-only. */
    fun canGrant(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Asks for whichever of the two is missing, and says whether they are both in place after.
     *
     * Runs shell commands, so it does not belong on the main thread.
     */
    fun ensure(): Boolean {
        if (all()) return true
        if (!canGrant()) return false

        val packageName = ShizukuApplication.application.packageName

        if (!usageAccess()) {
            runShellCommand("cmd appops set --user current $packageName GET_USAGE_STATS allow")
        }
        if (!dump()) {
            runShellCommand("pm grant $packageName android.permission.DUMP")
        }

        val done = all()
        if (!done) {
            Diag.warn(TAG, "the hiding grants are not in place: usage=${usageAccess()} dump=${dump()}")
        }
        return done
    }

    /**
     * The same, for callers that are not asking a question.
     *
     * Called when a hiding list is opened, when the watch starts, and whenever the state machine
     * resolves the server to RUNNING - the last of which is the one that makes this behave like
     * the permission this app grants itself for wireless debugging. Runs off the caller's thread
     * on its own scope, because two of those three callers are not on a thread that may block:
     * the state machine is read from wherever a state is needed, including composition.
     */
    fun ensureQuietly() {
        scope.launch { runCatching { ensure() } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
