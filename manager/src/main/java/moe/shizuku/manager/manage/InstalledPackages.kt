package moe.shizuku.manager.manage

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.Intent
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.utils.runShellCommand

/**
 * Every installed package, asked of whichever side can answer.
 *
 * The app's own package manager is not a complete answer, and it fails quietly: from Android 11 a
 * package this app has not named in advance is invisible to it, so `getInstalledPackages` returns
 * a list that simply does not contain some of what is installed. On the device this was written
 * for that was 39 user-installed apps missing while every system package was present - a list
 * that looks fine and is wrong, on a screen whose whole job is to enumerate what is installed.
 *
 * The shell has no such filter, and this app is already holding one, so the list is asked for
 * there first. That is the same route the Apps tab takes for its own listing, and the same one
 * [ShizukuSystemApis] uses to reach `IPackageManager` at all.
 *
 * The local package manager is the fallback rather than the starting point: on a phone with no
 * Shizuku running there is nothing else, and a short list beats no list.
 */
object InstalledPackages {

    private const val TAG = "InstalledPackages"

    /**
     * The installed packages matching [flags], from the shell when it is up.
     *
     * A shell that answers with nothing is treated as a shell that could not answer, not as a
     * device with nothing installed, which is why the fallback looks at both - the two are the
     * same answer from this side and only one of them is true.
     */
    fun all(context: Context, flags: Int): List<PackageInfo> {
        val fromShell = ShizukuSystemApis.getInstalledPackagesAsShell(
            flags.toLong(), UserHandleCompat.myUserId()
        )
        if (fromShell.isNotEmpty()) return fromShell

        Diag.info(TAG, "no shell to list the packages, asking this app's own")

        return runCatching { context.packageManager.getInstalledPackages(flags) }
            .onFailure { Diag.warn(TAG, "this app could not list the packages either") }
            .getOrDefault(emptyList())
    }

    /**
     * One package, or null when neither side knows it.
     *
     * For the callers that already have a package name - the detail screens - and need its record
     * rather than the whole list.
     */
    fun one(context: Context, packageName: String, flags: Int): PackageInfo? {
        val fromShell = ShizukuSystemApis.getPackageInfoAsShell(
            packageName, flags.toLong(), UserHandleCompat.myUserId()
        )
        if (fromShell != null) return fromShell

        return runCatching { context.packageManager.getPackageInfo(packageName, flags) }.getOrNull()
    }

    /**
     * The categories that count as something in the launcher.
     *
     * Leanback as well as the phone's own, because a television's list is empty without it and
     * this app runs there too.
     */
    private val LauncherCategories = listOf(
        "android.intent.category.LAUNCHER",
        "android.intent.category.LEANBACK_LAUNCHER"
    )

    /**
     * The packages with something in the launcher, or null when there is no shell to ask.
     *
     * The same visibility filter applies to this question as to the list itself, and it fails the
     * same way: `getLaunchIntentForPackage` answers null for a package this app cannot see, so
     * every app the shell handed over would be filed as one with no launcher icon - which is what
     * "hidden" means in these lists. Forty apps wearing a label that is not true is worse than the
     * list being short.
     *
     * One command for the whole device rather than a question per app, which is also the cheaper
     * way round: the per-app version was two calls for each of eight hundred packages.
     */
    fun launchable(): Set<String>? {
        if (!ShizukuStateMachine.isRunning()) return null

        val found = mutableSetOf<String>()
        LauncherCategories.forEach { category ->
            val command = "cmd package query-activities --brief" +
                " -a android.intent.action.MAIN -c $category"
            val output = runShellCommand(command) ?: return null

            output.lineSequence()
                .map { it.trim() }
                // The component lines are the ones with a slash in them; everything else is the
                // command's own framing - the count on the first line, the matching on the rest.
                .filter { it.contains('/') }
                .map { it.substringBefore('/') }
                .filter { it.isNotEmpty() }
                .forEach { found += it }
        }

        return found
    }

    /**
     * The packages of [apps] with nothing in the launcher.
     *
     * Asked of the shell where it can be, and one package at a time only when it cannot - the
     * fallback is what this did everywhere before, and it is wrong in the same way for the same
     * reason.
     */
    fun launcherless(context: Context, apps: List<PackageInfo>): Set<String> {
        val launchable = launchable()
        if (launchable != null) {
            return apps.filterNot { it.packageName in launchable }
                .map { it.packageName }
                .toSet()
        }

        val pm = context.packageManager
        return apps.filterNot { pi ->
            runCatching {
                pm.getLaunchIntentForPackage(pi.packageName) != null ||
                    pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                            .setPackage(pi.packageName),
                        0
                    ).isNotEmpty()
            }.getOrDefault(true)
        }.map { it.packageName }.toSet()
    }
}
