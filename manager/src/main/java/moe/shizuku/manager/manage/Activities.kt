package moe.shizuku.manager.manage

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Every activity an app declares, and how to start the ones it keeps to itself.
 *
 * Two thirds of what is interesting in an installed app is not on its launcher icon: settings
 * screens, debug screens, internal editors, the activity a deep link lands on. They are declared
 * in the manifest and this app can list them, because the manifest is public - and most of them
 * carry `android:exported="false"`, which is a statement that only the app itself, the system, or
 * the shell may start them.
 *
 * So there are two ways in, and which one applies is the export flag:
 *
 *     exported or launcher    the platform starts it, the same way one app starts another. No
 *                             privilege, no assistant, nothing to put back afterwards.
 *     everything else         the system starts it on our behalf, by way of the assistant.
 *
 * The second is the trick, and it is worth writing down plainly because every line of it looks
 * wrong: the `assistant` secure setting is pointed at the component, `voice_interaction_service`
 * is blanked so nothing else answers, and then a real `KEYCODE_ASSIST` is pressed. The system
 * reads the setting it owns and starts the activity, as the system, with no export check to fail.
 * The original values are written down before the swap and put back the moment the activity has
 * been given its two seconds - and because they are written to disk rather than held in memory,
 * a process that died in the middle is fixed by [restorePending] on the next start. Leaving
 * somebody with no assistant would be a much worse bug than not having the feature.
 *
 * `KEYCODE_ASSIST` has no app-facing API to trigger - firing `ACTION_ASSIST` only opens a chooser
 * - so it is pressed through the shell, which needs nothing beyond the `WRITE_SECURE_SETTINGS`
 * this app already holds for hiding. `SearchManager` has hidden entry points for the same thing,
 * and they are the fallback for a phone with no Shizuku running at all.
 */
object Activities {

    private const val TAG = "Activities"

    /** `KeyEvent.KEYCODE_ASSIST`. Spelled out, because the constant lives in a framework class. */
    private const val ASSIST_KEYCODE = 219

    /** How long the system is given to read the swapped setting and start the activity. */
    private const val ASSIST_WINDOW_MS = 2000L

    private const val KEY_ASSISTANT = "assistant"
    private const val KEY_VOICE_INTERACTION = "voice_interaction_service"

    private const val PREF_BACKUP = "activities_assistant_backup"

    /** Between the two saved values, and standing in for "there was nothing here". */
    private const val FIELD = "\u001F"
    private const val NOTHING = "\u0000"

    /** One activity of an app, as its manifest declares it. */
    data class Activity(
        val packageName: String,
        val name: String,
        /** What the app calls it in its own manifest, or null when it just uses the app's name. */
        val label: String?,
        val exported: Boolean,
        val launcher: Boolean
    ) {
        val component: ComponentName get() = ComponentName(packageName, name)

        /** The last part of the class name, which is what a person recognises. */
        val shortName: String get() = name.substringAfterLast('.')

        val title: String get() = label ?: shortName
    }

    /** How a launch went, so the screen can say the true thing rather than "done". */
    enum class Outcome { STARTED, ELEVATED, NO_SHELL, REFUSED }

    // ---- the list ----------------------------------------------------------------

    /**
     * The activities of a package that has already been read with `GET_ACTIVITIES`.
     *
     * Passed in rather than re-read so that the app list, which needs every package anyway, does
     * not pay for a second round of package manager calls.
     */
    fun of(packageManager: PackageManager, info: PackageInfo): List<Activity> {
        val launcher = runCatching {
            packageManager.getLaunchIntentForPackage(info.packageName)?.component?.className
        }.getOrNull()

        // What the app calls itself. An activity with no label of its own is given the app's, and
        // inside one app's list a row headed by the app's own name says nothing at all - which is
        // what every row said before this. Dropped, so the row is headed by the class name instead.
        val appLabel = runCatching {
            info.applicationInfo?.let { packageManager.getApplicationLabel(it)?.toString() }
        }.getOrNull()

        return order(
            info.activities.orEmpty().map { declared ->
                activity(packageManager, declared, launcher, appLabel)
            }
        )
    }

    /**
     * Most reachable first: the launcher entry, then anything another app may start, then the ones
     * only this trick can reach - which are the reason somebody opened the list at all.
     */
    internal fun order(activities: List<Activity>): List<Activity> = activities.sortedWith(
        compareByDescending<Activity> { it.launcher }
            .thenByDescending { it.exported }
            .thenBy { it.name.lowercase() }
    )

    /**
     * The activities of one app, read as the shell where it can be.
     *
     * The manifest of a package this app cannot see is not readable either, so the list would come
     * back empty for the same apps the package list used to leave out - and an app that is in the
     * list but opens onto no activities reads as an app that has none.
     */
    fun of(context: Context, packageName: String): List<Activity> {
        val packageManager = context.packageManager
        val info = InstalledPackages.one(context, packageName, PackageManager.GET_ACTIVITIES)
            ?: return emptyList()
        return of(packageManager, info)
    }

    private fun activity(
        packageManager: PackageManager,
        declared: ActivityInfo,
        launcher: String?,
        appLabel: String?
    ): Activity = Activity(
        packageName = declared.packageName ?: "",
        name = declared.name ?: "",
        // Only a label the activity declares for itself: one that repeats the class name or the
        // app name is not a name, it is the absence of one.
        label = runCatching { declared.loadLabel(packageManager)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != declared.name && it != appLabel },
        exported = declared.exported,
        launcher = declared.name == launcher
    )

    // ---- starting one ------------------------------------------------------------

    fun launch(context: Context, activity: Activity): Outcome = launch(context, activity, activity.exported, activity.launcher)

    private fun launch(context: Context, activity: Activity, exported: Boolean, launcher: Boolean): Outcome {
        if (exported || launcher) {
            val intent = Intent()
                .setComponent(activity.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                Diag.info(TAG, "started ${activity.component}")
                return Outcome.STARTED
            }
            // An exported activity can still refuse - a signature permission in the manifest does
            // not show in the export flag - so this falls through to the elevated route rather
            // than reporting a failure the user has no way to act on.
        }

        if (!holdsWriteSecureSettings()) return Outcome.REFUSED
        if (elevate(activity.component)) return Outcome.ELEVATED

        // Neither route worked. Say which half was missing rather than a flat refusal, because
        // "start Shizuku" is something the user can act on.
        return if (ShizukuStateMachine.isRunning()) Outcome.REFUSED else Outcome.NO_SHELL
    }

    /**
     * Whether this app can do the swap at all.
     *
     * Only the grant is required, not Shizuku: asking for the assist in-process needs nothing else
     * on any device where that route answers, and the shell is the fallback rather than the price
     * of entry.
     */
    fun canElevate(): Boolean = holdsWriteSecureSettings()

    /**
     * Starts an intent, with the same two routes an activity row gets.
     *
     * The builder can name a component that is not exported as easily as one that is - a class
     * name is a class name - and a builder that could describe an activity it could not open
     * would be a strange thing to hand somebody. So a refused start falls through to the same
     * swap rather than reporting the refusal the platform gave.
     */
    fun launch(context: Context, intent: Intent): Outcome {
        // Nothing here is an activity, so there is no task to join: without this the platform
        // refuses the start outright with "Calling startActivity() from outside of an Activity
        // context requires the FLAG_ACTIVITY_NEW_TASK flag".
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (runCatching { context.startActivity(intent) }.isSuccess) {
            Diag.info(TAG, "started an intent for ${intent.component ?: intent.action}")
            return Outcome.STARTED
        }

        val component = intent.component ?: return Outcome.REFUSED
        if (!holdsWriteSecureSettings()) return Outcome.REFUSED
        if (elevate(component)) return Outcome.ELEVATED
        return if (ShizukuStateMachine.isRunning()) Outcome.REFUSED else Outcome.NO_SHELL
    }

    /**
     * Sends [intent] as a broadcast.
     *
     * The shell first, and for a different reason than an activity gets: a broadcast worth sending
     * by hand is usually addressed at a receiver that is *not* exported, because those are the
     * ones an app cannot reach and the ones a tool like this is for. An app may not send to them at
     * all, so `am broadcast` is the route that works and the in-process call is only what covers a
     * phone with no Shizuku running - where the exported receivers are still reachable, which is
     * more than nothing.
     *
     * There is no elevated route here the way an activity has one. The assistant swap points the
     * system at a component to *start*; nothing about it sends a broadcast.
     */
    fun broadcast(context: Context, intent: Intent): Outcome {
        if (ShizukuStateMachine.isRunning() && broadcastAsShell(intent)) {
            Diag.info(TAG, "broadcast as the shell: ${intent.action ?: intent.component}")
            return Outcome.STARTED
        }

        return if (runCatching { context.sendBroadcast(intent) }.isSuccess) {
            Diag.info(TAG, "broadcast from the app: ${intent.action ?: intent.component}")
            Outcome.STARTED
        } else {
            Outcome.REFUSED
        }
    }

    /**
     * `am broadcast`, with the whole intent written out as its command line.
     *
     * The marker after it, because the command answers with nothing and an empty answer is how
     * this app spells "the shell did not run" - the same trade the assist key press makes.
     */
    private fun broadcastAsShell(intent: Intent): Boolean {
        val arguments = intentArguments(intent).joinToString(" ") { it }
        // Written down before it runs: a broadcast that does not arrive leaves nothing else
        // behind, and the command is the only record of what was actually sent.
        Diag.info(TAG, "am broadcast $arguments")
        return runShellCommand("am broadcast $arguments; echo broadcast") != null
    }

    /**
     * The `am` command line for an intent.
     *
     * `am` is the one place the whole of an intent can be written down: every part of it is an
     * argument, including each extra with its type - `--es` for a string and `--ed` for a double,
     * which is why the six types the builder offers can all be sent this way and none of them have
     * to be dropped.
     *
     * Every value is quoted, because a value with a space in it is otherwise two arguments.
     */
    internal fun intentArguments(intent: Intent): List<String> = buildList {
        fun pair(flag: String, value: String) {
            add(flag)
            add(quoted(value))
        }

        fun triple(flag: String, key: String, value: String) {
            add(flag)
            add(quoted(key))
            add(quoted(value))
        }

        intent.action?.takeIf { it.isNotEmpty() }?.let { pair("-a", it) }
        intent.dataString?.let { pair("-d", it) }
        intent.type?.let { pair("-t", it) }
        intent.component?.let { pair("-n", it.flattenToString()) }
        intent.categories?.forEach { pair("-c", it) }
        if (intent.flags != 0) pair("-f", intent.flags.toString())

        intent.extras?.let { extras ->
            extras.keySet().forEach { key ->
                when (val value = extras.get(key)) {
                    is String -> triple("--es", key, value)
                    is Boolean -> triple("--ez", key, value.toString())
                    is Int -> triple("--ei", key, value.toString())
                    is Long -> triple("--el", key, value.toString())
                    is Float -> triple("--ef", key, value.toString())
                    is Double -> triple("--ed", key, value.toString())
                }
            }
        }
    }

    /** Single-quoted, with a quote inside closed and reopened, which is what a shell needs. */
    private fun quoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** One of the things an intent would reach, for naming it before anything is sent. */
    data class Target(val label: String, val packageName: String)

    /**
     * What [intent] reaches on this device.
     *
     * An empty answer is the interesting one: it is the difference between an intent that will do
     * something and one that will disappear, which an app is never told when it sends a broadcast
     * and is only told after the fact when it starts an activity. So the builder asks this before
     * sending rather than reporting the silence afterwards.
     */
    fun targets(context: Context, intent: Intent, broadcast: Boolean): List<Target> = runCatching {
        val pm = context.packageManager
        val infos = if (broadcast) {
            pm.queryBroadcastReceivers(intent, 0).mapNotNull { it.activityInfo }
        } else {
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo }
        }

        infos.map { info ->
            Target(
                label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.name),
                packageName = info.packageName
            )
        }.distinctBy { it.packageName + "/" + it.label }
    }.getOrDefault(emptyList())

    private fun holdsWriteSecureSettings(): Boolean = runCatching {
        ShizukuApplication.application
            .checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Starts [component] through the assistant, and puts the assistant back before returning.
     *
     * Write down, then swap, then press, then wait, then restore - in that order, and the restore
     * is in a `finally`. The only way this leaves the phone as it found it is if every step is
     * unconditional.
     */
    private fun elevate(component: ComponentName): Boolean {
        val resolver = ShizukuApplication.application.contentResolver
        val assistant = Settings.Secure.getString(resolver, KEY_ASSISTANT)
        val voice = Settings.Secure.getString(resolver, KEY_VOICE_INTERACTION)

        // Committed rather than applied: this has to be on disk before the setting it describes
        // is changed, because the process dying between the two is exactly the case it is for.
        val written = ShizukuSettings.getPreferences().edit()
            .putString(PREF_BACKUP, backupLine(assistant, voice))
            .commit()
        if (!written) {
            Diag.warn(TAG, "the assistant could not be written down, so it will not be swapped")
            return false
        }

        return try {
            Settings.Secure.putString(resolver, KEY_ASSISTANT, component.flattenToString())
            Settings.Secure.putString(resolver, KEY_VOICE_INTERACTION, "")

            // The shell first, and deliberately: a real injected key event either fires or the
            // command does not run, while the in-process call can return having done nothing at
            // all - a hidden method that answers is not the same as an assist that happened. So
            // the route whose outcome is legible is the one used whenever it is available, and
            // the in-process call is what covers a phone with no Shizuku running.
            val asked = pressAssistKey() || askInProcess()
            if (asked) Thread.sleep(ASSIST_WINDOW_MS)

            Diag.info(
                TAG,
                if (asked) "asked the system to start $component"
                else "the assist request could not be made"
            )
            asked
        } catch (e: Throwable) {
            Diag.warn(TAG, "the elevated launch failed", e)
            false
        } finally {
            restoreAssistant()
        }
    }

    /**
     * Asks the system for an assist without leaving the process.
     *
     * `SearchManager` has the two hidden entry points for it - `launchAssist` and `startAssist` -
     * which is how the Shizuku plugin for Activity Launcher asks for the same assist. Reached only
     * when the shell is not there to press the key, so a phone without Shizuku running can still
     * open a hidden screen.
     */
    private fun askInProcess(): Boolean {
        // HiddenApiBypass needs Android 9 (API 28); below it there is no bypass to reach the
        // hidden assist entry points through, so the answer is simply that this way did not work.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false

        val search = runCatching {
            ShizukuApplication.application.getSystemService(Context.SEARCH_SERVICE) as? SearchManager
        }.getOrNull() ?: return false

        val launched = runCatching {
            HiddenApiBypass.invoke(SearchManager::class.java, search, "launchAssist", Bundle())
        }.isSuccess
        if (launched) {
            Diag.info(TAG, "asked the system for an assist in-process")
            return true
        }

        val started = runCatching {
            HiddenApiBypass.invoke(SearchManager::class.java, search, "startAssist", Bundle())
        }.isSuccess
        if (started) Diag.info(TAG, "started an assist in-process")
        return started
    }

    /** A real assist key press, which only the shell may make. */
    private fun pressAssistKey(): Boolean {
        if (!ShizukuStateMachine.isRunning()) return false
        // A marker after the key event, because the command answers with nothing and an empty
        // answer is how this app spells "the shell did not run".
        val pressed = runShellCommand("input keyevent $ASSIST_KEYCODE; echo pressed") != null
        if (pressed) Diag.info(TAG, "asked the system for an assist through the shell")
        return pressed
    }

    internal fun backupLine(assistant: String?, voice: String?): String =
        (assistant ?: NOTHING) + FIELD + (voice ?: NOTHING)

    /**
     * The saved pair, or null when the line is not one.
     *
     * A value that was not set has to come back as null rather than as an empty string: writing
     * "" back over an assistant that was never set is a different setting than the one that was
     * there, and this line is the only record of which it was.
     */
    internal fun parseBackup(line: String?): Pair<String?, String?>? {
        if (line == null) return null
        val parts = line.split(FIELD)
        if (parts.size != 2) return null
        fun value(part: String) = part.takeIf { it != NOTHING }
        return value(parts[0]) to value(parts[1])
    }

    /** The saved pair, or null when nothing was ever swapped. */
    private fun backup(): Pair<String?, String?>? =
        parseBackup(ShizukuSettings.getPreferences().getString(PREF_BACKUP, null))

    /** Puts the assistant back, and forgets the backup only once it is really back. */
    private fun restoreAssistant() {
        val backup = backup() ?: return
        if (!holdsWriteSecureSettings()) return

        val resolver = ShizukuApplication.application.contentResolver
        val restored = runCatching {
            Settings.Secure.putString(resolver, KEY_ASSISTANT, backup.first)
            Settings.Secure.putString(resolver, KEY_VOICE_INTERACTION, backup.second)
        }.onFailure { Diag.warn(TAG, "putting the assistant back failed", it) }.isSuccess

        if (restored) {
            ShizukuSettings.getPreferences().edit().remove(PREF_BACKUP).apply()
            Diag.info(TAG, "the assistant is back where it was")
        }
    }

    /**
     * Puts the assistant back after a launch that did not finish.
     *
     * Called when the app starts, and the reason the backup is a preference: the one failure this
     * feature must not have is a phone left with no assistant because a process died holding the
     * name of the one it replaced.
     */
    fun restorePending() {
        if (backup() == null) return
        Diag.warn(TAG, "an activity launch did not finish: putting the assistant back")
        restoreAssistant()
    }
}
