package moe.shizuku.manager.manage

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.usage.UsageStatsManager
import android.content.ContentResolver
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.runShellCommand
import rikka.shizuku.Shizuku
import moe.shizuku.manager.utils.Diag

/**
 * One setting a restrictive app reads, and the value it is unhappy with.
 *
 * [namespace] and [name] are the arguments of `settings put`, and [hidden] is what the app
 * should see instead of the truth. Accessibility is two keys and not one - the switch and the
 * list of what it turns on - so a signal is a list of them and is only hidden when every one of
 * them says so.
 */
data class SettingKey(
    val namespace: String,
    val name: String,
    val hidden: String,
    /**
     * What to write when the setting is hidden and nothing was recorded to put back.
     *
     * Null for the keys where no value can stand in for what the user had: an accessibility
     * service list is theirs and cannot be invented, and a private DNS hostname is theirs too.
     * For those the recorded value is the only way back, which is why it is never thrown away.
     */
    val visible: String? = null
) {
    val id: String get() = "$namespace/$name"
}

/**
 * What a banking app, a payment app or a game looks at to decide this device is not one it will
 * run on.
 *
 * Every one of these is a real setting on the device, and hiding it means really changing it: the
 * shell writes the value and every app that reads it afterwards sees the lie. That is the whole
 * of what can be done without root, and it is worth being plain about the limit. An app that reads
 * its own process can be lied to alone, which is what the Xposed modules do; a setting read
 * through the settings provider is answered for whoever asks, and there is no way from outside
 * the system to answer one caller differently from the next.
 *
 * So a signal is hidden for the device while it is hidden, which is why the lists matter and why
 * the watch exists: hiding is for the length of the app that objects, and no longer.
 */
enum class Signal(
    @StringRes val labelRes: Int,
    val keys: List<SettingKey>
) {
    DEVELOPER_OPTIONS(
        R.string.hiding_signal_developer_options,
        listOf(SettingKey("global", "development_settings_enabled", "0", visible = "1"))
    ),

    USB_DEBUGGING(
        R.string.hiding_signal_usb_debugging,
        listOf(SettingKey("global", "adb_enabled", "0", visible = "1"))
    ),

    WIRELESS_DEBUGGING(
        R.string.hiding_signal_wireless_debugging,
        listOf(SettingKey("global", "adb_wifi_enabled", "0", visible = "1"))
    ),

    ACCESSIBILITY(
        R.string.hiding_signal_accessibility,
        listOf(
            SettingKey("secure", "accessibility_enabled", "0", visible = "1"),
            SettingKey("secure", "enabled_accessibility_services", "")
        )
    ),

    /**
     * Both keys, because the mode is not the whole answer: a device on a hostname has that
     * hostname written down beside it, and an app that reads settings directly would find it
     * still there while the mode said off. Restoring puts the mode back and the hostname with
     * it, which is the pair the settings screen writes and the pair to write back.
     */
    PRIVATE_DNS(
        R.string.hiding_signal_private_dns,
        listOf(
            SettingKey("global", "private_dns_mode", "off"),
            SettingKey("global", "private_dns_specifier", "")
        )
    ),

    /**
     * The tunnel, which is not a setting anywhere and cannot be written as one.
     *
     * There is no value in the settings provider that says a VPN is up: the state lives in a
     * service, as a live session, and an app reads it from the network it is routed over. So this
     * one is not hidden by writing anything - it is turned off by stopping the client that owns
     * the tunnel and has to be started again, which is why it carries no keys and answers
     * [isHidden] from the platform instead.
     *
     * A tunnel is the device's, so this is the device's VPN going down, not one app being lied to.
     */
    VPN(
        R.string.hiding_signal_vpn,
        emptyList()
    )
}

/**
 * The settings, what they were before, and the apps that say when to lie.
 *
 * Each signal keeps two things: what its keys said before this app touched them, and the apps it
 * is hidden for. The apps are a rule and not a state - a listed app means "hide this while that
 * app is in front" - and [reconcile] is the whole of the rule: read the app in the foreground,
 * hide the signals that name it, put back the rest.
 *
 * Nothing here trusts a write. Every change is read back and a signal counts as hidden only when
 * the device says it is, which is what lets a list say it did not take instead of showing a state
 * the platform never agreed to. What the values were is remembered per signal and restored
 * exactly, because the point is putting things back: an accessibility list is the user's own
 * services and not something to guess at, and `settings delete` is the only honest way to restore
 * a key that was never set at all.
 */
object Hiding {

    private const val TAG = AppConstants.TAG

    private const val PREF_PREFIX_ORIGINALS = "hiding_originals_"
    private const val PREF_PREFIX_APPS = "hiding_apps_"
    private const val PREF_PAUSED = "hiding_paused"
    private const val PREF_DISABLED = "hiding_disabled_signals"
    private const val PREF_HIDDEN_SINCE = "hiding_since"

    const val ACTION_RESTORE_HIDING = "moe.shizuku.manager.action.RESTORE_HIDING"
    const val ACTION_RESUME_HIDING = "moe.shizuku.manager.action.RESUME_HIDING"

    private fun originalsPref(signal: Signal) = "$PREF_PREFIX_ORIGINALS${signal.name}"

    private fun appsPref(signal: Signal) = "$PREF_PREFIX_APPS${signal.name}"

    // ---- reading -----------------------------------------------------------------

    /**
     * What a key says now, or null when it was never set.
     *
     * `settings get` prints `null` for a key that is not there and an empty line for one that is
     * there and empty, and [runShellCommand] reports both as nothing. The empty line is what an
     * emptied accessibility list looks like and the null is what a key nobody ever wrote looks
     * like, so they are told apart here rather than two screens up.
     */
    fun readKey(key: SettingKey): String? = runCatching { resolver.getString(key.namespace, key.name) }
        .getOrNull()

    /**
     * The settings provider, reached directly.
     *
     * This is the whole reason the feature survives hiding the connection: the app holds
     * WRITE_SECURE_SETTINGS, so a hide or a restore is one call from this process and does not
     * need the shell at all. Doing it through Shizuku was the first attempt and it failed in the
     * worst possible way - hiding wireless debugging kills the connection Shizuku is running on,
     * which killed the server that was supposed to put it back (measured, twice in four seconds,
     * in the log of 09-30 15:56). The shell is left with the one job an app cannot do itself:
     * saying which app is in the foreground.
     */
    private val resolver: ContentResolver
        get() = ShizukuApplication.application.contentResolver

    private fun ContentResolver.getString(namespace: String, name: String): String? = when (namespace) {
        "global" -> Settings.Global.getString(this, name)
        "secure" -> Settings.Secure.getString(this, name)
        else -> null
    }

    /**
     * Whether the shell can still be asked anything.
     *
     * The watch lives or dies by this, and it is checked before every pass: a server that is gone
     * cannot say which app is in front, and a device left hidden by a watch that stopped watching
     * is the one outcome this feature must never have.
     */
    fun shellAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Whether the foreground can be read at all, which is whether the watch can watch.
     *
     * Usage access answers it with nothing running, so the watch survives Shizuku being down and
     * survives hiding the transport Shizuku rides. Without the grant the shell is the only
     * answer, and a watch that cannot see must stand down rather than guess.
     */
    fun canWatch(): Boolean = hasUsageAccess() || shellAvailable()

    /** A signal is hidden only when every one of its keys says so, or when the tunnel is down. */
    fun isHidden(signal: Signal): Boolean = when (signal) {
        Signal.VPN -> vpnTakenDown()
        else -> signal.keys.all { readKey(it) == it.hidden }
    }

    /** The apps this signal is hidden for. */
    fun appsFor(signal: Signal): Set<String> =
        ShizukuSettings.getPreferences().getStringSet(appsPref(signal), emptySet()).orEmpty().toSet()

    /** When the settings were first hidden for the app in front, or 0 when nothing is hidden. */
    private fun hiddenSince(): Long =
        ShizukuSettings.getPreferences().getLong(PREF_HIDDEN_SINCE, 0L)

    private fun forgetHiddenSince() {
        ShizukuSettings.getPreferences().edit().remove(PREF_HIDDEN_SINCE).apply()
    }

    /**
     * Whether the user closed [packageName] since [sinceMillis].
     *
     * Needs DUMP, which is one of the two grants the hiding screens set up: without it the
     * platform answers only about this app, and the answer here is quietly empty rather than
     * wrong.
     */
    private fun closedByUserSince(packageName: String, sinceMillis: Long): Boolean {
        if (sinceMillis == 0L) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return runCatching {
            val manager = ShizukuApplication.application
                .getSystemService(ActivityManager::class.java) ?: return false
            manager.getHistoricalProcessExitReasons(packageName, 0, EXIT_RECORDS)
                .any { info ->
                    info.timestamp >= sinceMillis &&
                        info.reason == ApplicationExitInfo.REASON_USER_REQUESTED
                }
        }.getOrDefault(false)
    }

    /** Enough records to see past an app that was killed for memory and restarted. */
    private const val EXIT_RECORDS = 5

    /** Whether any signal has an app on its list. */
    fun hasAnyApp(): Boolean = Signal.entries.any { appsFor(it).isNotEmpty() }

    /**
     * Whether any list is both switched on and has an app on it - which is when there is
     * something for the watch to do at all.
     *
     * Separate from [hasAnyApp] because a list can be kept while its mode is off: the names of the
     * apps that object are worth keeping, and somebody may want them kept while the hiding itself
     * is turned off for a while. Nothing should be watching in that state, and the notification
     * that says hiding is on should not be there either.
     */
    fun hasWorkToDo(): Boolean =
        Signal.entries.any { isSignalEnabled(it) && appsFor(it).isNotEmpty() }

    /**
     * Whether hiding is doing anything: a mode is switched on, it names an app, and nobody has
     * suspended it.
     *
     * Asked of the rule rather than of the process, and the difference is another feature
     * entirely: the watch also runs for force dark, which hides nothing, and it runs for a list a
     * mode has been switched off around, which hides nothing either. The home screen's notice is
     * about this question and not that one.
     */
    fun isActive(): Boolean = hasWorkToDo() && !isPaused()

    /**
     * Whether a whole mode is switched on.
     *
     * Off is stored rather than on, so that a list that existed before this switch did is on by
     * default and nothing has to be migrated: only somebody who turns a mode off has a record.
     */
    fun isSignalEnabled(signal: Signal): Boolean = signal.name !in disabledSignals()

    fun setSignalEnabled(signal: Signal, enabled: Boolean) {
        val disabled = disabledSignals().toMutableSet()
        if (enabled) disabled.remove(signal.name) else disabled.add(signal.name)
        ShizukuSettings.getPreferences().edit()
            .putStringSet(PREF_DISABLED, disabled)
            .apply()
    }

    private fun disabledSignals(): Set<String> =
        ShizukuSettings.getPreferences().getStringSet(PREF_DISABLED, emptySet()).orEmpty().toSet()

    /**
     * Whether hiding is suspended.
     *
     * This is what the notification's Restore leaves behind, and it exists because the list is a
     * rule about an app and not about this moment: a user who asks for their settings back while
     * the app is still open means now, and a watch that put them back within the second would be
     * obeying the rule and ignoring the person.
     */
    fun isPaused(): Boolean = ShizukuSettings.getPreferences().getBoolean(PREF_PAUSED, false)

    fun setPaused(paused: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(PREF_PAUSED, paused).apply()
    }

    // ---- the rule ----------------------------------------------------------------

    /**
     * Whether the platform will say which app is in front.
     *
     * The grant is an AppOp, so it cannot be asked for with a dialog and is not something the
     * app can give itself: it comes from the usage-access page in Settings, or from Shizuku once.
     */
    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = ShizukuApplication.application.getSystemService(AppOpsManager::class.java)
            ?: return false
        // `unsafeCheckOpNoThrow` arrived with Android 10 (API 29); `checkOpNoThrow` is the same
        // question asked the way the older platforms understand it.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ShizukuApplication.application.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ShizukuApplication.application.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * The app in the foreground, or null.
     *
     * Asked of the platform rather than the shell, and that difference is the whole reason
     * hiding a debugging transport can work at all. A shell read is the first thing to die when
     * the setting being hidden is the one carrying the shell - measured: with Shizuku over
     * wireless debugging, hiding wireless debugging killed the server mid-write, so the write
     * that was supposed to put it back never ran. Usage events come from the platform, need
     * nothing running, and are answered while Shizuku is gone.
     *
     * Only the events since the last look are read, and the answer is remembered between calls,
     * for a reason worth stating: an app can be in front for longer than any window one could
     * query, so a query that asked "what came to the front in the last ten minutes" would decide
     * nothing is in front exactly when the user has been sitting in the banking app for eleven.
     *
     * [Foreground from the shell][foregroundFromShell] is kept for a device without the grant,
     * where a watch that cannot see is still better than no watch - and where the transport
     * lists are refused for exactly this reason.
     */
    fun foregroundPackage(): String? =
        if (hasUsageAccess()) foregroundFromUsage() else foregroundFromShell()

    private var lastEventQuery = 0L
    private var cachedForeground: String? = null

    private fun foregroundFromUsage(): String? {
        val manager = ShizukuApplication.application
            .getSystemService(UsageStatsManager::class.java) ?: return cachedForeground

        val now = System.currentTimeMillis()
        // Ten minutes back the first time, only the new events after that.
        val since = if (lastEventQuery == 0L) now - FIRST_LOOK_BACK_MILLIS else lastEventQuery
        val events = runCatching { manager.queryEvents(since, now) }.getOrNull()
            ?: return cachedForeground

        val event = UsageEvents.Event()
        var foreground = cachedForeground
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> foreground = event.packageName
                // Nothing is in front while an app is on its way out, which is the state a
                // recents swipe and a return to the home screen both pass through.
                UsageEvents.Event.ACTIVITY_PAUSED -> if (foreground == event.packageName) {
                    foreground = null
                }
            }
        }

        lastEventQuery = now
        cachedForeground = foreground
        return foreground
    }

    /**
     * The same question asked of the shell, for a device with no usage access.
     *
     * One dumpsys, filtered on the device so only the one line comes back over the binder:
     * measured at about twenty milliseconds, which is what makes a poll every second or so
     * affordable.
     */
    private fun foregroundFromShell(): String? {
        val line = runShellCommand(
            "dumpsys activity activities 2>/dev/null | " +
                "grep -m1 -E 'topResumedActivity|mResumedActivity'"
        ) ?: return null

        // topResumedActivity=ActivityRecord{91967062 u0 com.example/.MainActivity t429}
        return FOREGROUND.find(line)?.groupValues?.get(1)
    }

    private const val FIRST_LOOK_BACK_MILLIS = 10 * 60 * 1000L

    private val FOREGROUND = Regex("""\bu\d+\s+([A-Za-z0-9_.]+)/""")

    /**
     * One pass of the rule: the app in front decides.
     *
     * Everything is derived from the foreground each time rather than tracked as state, which is
     * what makes this recoverable: whatever is hidden and should not be is put back on the next
     * pass, including after the process was killed in the middle of a hide.
     */
    fun reconcile(): String? {
        val foreground = foregroundPackage()
        var hidingFor: String? = null

        // An app the user closed counts as gone even when the platform still names it the last
        // app to come to the front: a swipe out of recents does not always leave a pause behind
        // it, and the watch would go on hiding for an app that is not there. That is what DUMP
        // is for, and it is the only reason it is asked for.
        val inFront = foreground?.takeIf { !closedByUserSince(it, hiddenSince()) }

        Signal.entries.forEach { signal ->
            // A mode somebody switched off is put back and left alone, list and all: turning it
            // off is a statement about now, not about the apps named on it.
            if (!isSignalEnabled(signal)) {
                if (isHidden(signal)) restore(signal)
                return@forEach
            }

            val apps = appsFor(signal)
            if (apps.isEmpty()) return@forEach

            // The one thing the watch will not do, whatever a list says: cut the connection it
            // is watching through. A list that names it stays named and is simply not applied.
            if (carriesSession(signal)) {
                if (isHidden(signal)) restore(signal)
                return@forEach
            }

            val wanted = inFront != null && inFront in apps
            when {
                wanted -> {
                    if (!isHidden(signal)) hide(signal)
                    // Only a signal that is really hidden names the app, so the notification says
                    // what is true rather than what was asked for.
                    if (isHidden(signal)) hidingFor = inFront
                }

                // The values go back; the list stays, because the list is the rule and the
                // reason this happened, not a record of it.
                //
                // Without a record there is still no excuse for leaving it hidden, so the
                // signal's own visible value is written instead. This is not hypothetical:
                // hiding USB debugging kills adbd and with it the Shizuku server, this app
                // restarts Shizuku, and its start moves wireless debugging on the way - so the
                // setting ends up hidden by something that never recorded what it replaced.
                // Measured on the device, at 16:19, wireless debugging left off that way.
                isHidden(signal) -> if (hasRecord(signal)) restore(signal) else reveal(signal)
            }
        }

        if (hidingFor == null) forgetHiddenSince()
        return hidingFor
    }


    // ---- changing ----------------------------------------------------------------

    /**
     * Whether hiding this signal would cut the connection Shizuku itself is running on.
     *
     * The setting hides, and then the server that hid it is gone: the shell is a child of the adb
     * daemon that serves this very toggle, so turning it off kills the shell mid-write, and with
     * it the only thing that could have put the setting back. Measured rather than guessed - with
     * Shizuku started over wireless debugging, hiding it left three writes unwritten and the
     * server dead, the watchdog brought the server back, and the next pass killed it again.
     */
    fun carriesSession(signal: Signal): Boolean = !hasUsageAccess() && when (signal) {
        Signal.WIRELESS_DEBUGGING ->
            runningStartMethod() == ShizukuSettings.StartMethod.WIRELESS

        Signal.USB_DEBUGGING ->
            runningStartMethod() == ShizukuSettings.StartMethod.USB

        else -> false
    }

    /**
     * How the running server was started, falling back to the method configured for the next
     * start when no start of ours recorded one. The running one is what decides whether a
     * transport is ours to cut, and it is not always the configured one.
     */
    private fun runningStartMethod(): Int {
        val running = ShizukuSettings.getRunningStartMethod()
        return if (running >= 0) running else ShizukuSettings.getStartMethod()
    }

    /**
     * Puts one app on or off a signal's list.
     *
     * Membership is a rule, so it is written down whether or not the setting is hidden at this
     * moment: whether it should be hidden is a question only the foreground can answer.
     */
    fun setOnList(signal: Signal, packageName: String, listed: Boolean): Boolean {
        val apps = appsFor(signal).toMutableSet()
        if (listed) apps.add(packageName) else apps.remove(packageName)
        ShizukuSettings.getPreferences().edit().putStringSet(appsPref(signal), apps).apply()
        // Changing a list is asking for the rule to run, so it also ends a suspension.
        setPaused(false)
        return appsFor(signal).contains(packageName) == listed
    }

    /** Takes an app off every list, which is what the notification's Restore does. */
    fun unlist(packageName: String): Boolean {
        var changed = false
        Signal.entries.forEach { signal ->
            if (setOnList(signal, packageName, false)) changed = true
        }
        return changed
    }

    /**
     * Hides one signal, remembering what each of its keys was first.
     *
     * The originals are written down before the first write and never overwritten, so hiding the
     * same thing twice cannot record the lie as the truth and leave restore with nothing to put
     * back.
     */
    fun hide(signal: Signal): Boolean {
        if (signal == Signal.VPN) return takeVpnDown()
        if (isHidden(signal)) return true

        // When the hiding started, which is what the exit records are compared against: an app
        // closed before this was hidden is not news.
        if (hiddenSince() == 0L) {
            ShizukuSettings.getPreferences().edit()
                .putLong(PREF_HIDDEN_SINCE, System.currentTimeMillis())
                .apply()
        }

        val saved = originals(signal)
        signal.keys.forEach { key ->
            if (!saved.containsKey(key.id)) {
                val current = readKey(key)
                // What the setting says now is only an original if it is not already the lie.
                if (trustworthy(key, current)) saved[key.id] = current
            }
        }
        writeOriginals(signal, saved)

        var applied = true
        signal.keys.forEach { key ->
            if (!writeKey(key, key.hidden)) {
                Diag.warn(TAG, "hiding ${signal.name} would not take: ${key.id}")
                applied = false
            }
        }
        return applied
    }

    /**
     * Puts [signal] back the way it was found.
     *
     * A key that will not go back keeps its recorded value, so that a restore that did not take
     * is offered again rather than forgotten along with the value that would have put it back.
     */
    fun restore(signal: Signal): Boolean {
        if (signal == Signal.VPN) return bringVpnBack()

        val saved = originals(signal)
        var applied = true

        signal.keys.forEach { key ->
            if (!saved.containsKey(key.id)) return@forEach
            val value = saved.remove(key.id)

            if (!trustworthy(key, value)) {
                // Nothing is written: putting the lie back is not a restore, and the value the
                // setting really had was never recorded, so there is nothing to put back.
                Diag.warn(TAG, "not restoring ${key.id}: the remembered value is the hidden one")
                return@forEach
            }

            val put = if (value == null) deleteKey(key) else writeKey(key, value)
            if (!put) {
                Diag.warn(TAG, "restoring ${key.id} would not take")
                applied = false
                saved[key.id] = value
            }
        }

        writeOriginals(signal, saved)
        return applied
    }

    /** Puts everything back, which is what anything that stops hiding does. */
    fun restoreAll(): Boolean = Signal.entries.fold(true) { all, signal -> restore(signal) && all }

    /**
     * Whether a remembered value can be trusted as an original.
     *
     * A value equal to the hidden one is the lie itself rather than what the setting used to be:
     * the only way to remember it is to read the setting while it was already hidden, which is
     * what a read that failed part way through a hide used to look like. Writing that back is how
     * a device ends up with a setting that is neither what it had nor what was asked of it -
     * measured, once, on the private DNS mode, which came back empty and had to be put right by
     * hand.
     */
    private fun trustworthy(key: SettingKey, value: String?): Boolean = value != key.hidden

    /** Whether a record of what to put back is being held for this signal. */
    private fun hasRecord(signal: Signal): Boolean = when (signal) {
        Signal.VPN -> rememberedVpnClient() != null
        else -> originals(signal).isNotEmpty()
    }

    // ---- the tunnel --------------------------------------------------------------

    private const val PREF_VPN_CLIENT = "hiding_vpn_client"
    private const val PREF_VPN_CHOSEN = "hiding_vpn_chosen"

    /**
     * Whether a VPN is up, asked of the platform rather than the shell.
     *
     * In-process and free: the tunnel is a network, and a network is something any app holding
     * ACCESS_NETWORK_STATE can see. That is what makes "back to what it was" possible at all -
     * an app that only knows how to force-stop a client can turn a VPN on that was never on.
     */
    fun vpnUp(): Boolean = runCatching {
        val manager = connectivity() ?: return false
        manager.allNetworks
            .mapNotNull { manager.getNetworkCapabilities(it) }
            .any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }.getOrDefault(false)

    private fun connectivity(): ConnectivityManager? =
        ShizukuApplication.application.getSystemService(ConnectivityManager::class.java)

    /**
     * The apps that could be holding a tunnel, which is every app declaring a VpnService.
     *
     * Asked of the package manager rather than guessed from the network: a VPN network does not
     * name its owner in anything a normal app may read, and the field that does is hidden API.
     * VpnService answers to an intent action, so the clients are exactly the packages that
     * declare one - in-process, no permission, and needing the `<queries>` entry in the manifest
     * that package visibility demands.
     */
    fun vpnCandidates(): List<String> = runCatching {
        val manager = ShizukuApplication.application.packageManager
        manager.queryIntentServices(Intent("android.net.VpnService"), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * The client to act on: the one chosen, or the only one there is.
     *
     * More than one candidate and no choice made means this refuses rather than guesses, because
     * stopping the wrong client is a tunnel taken down that can never be put back - the client
     * that was running is the only thing that knows how to bring it back.
     */
    private fun vpnClientPackage(): String? {
        chosenVpnClient()?.let { return it }

        val candidates = vpnCandidates()
        if (candidates.size == 1) return candidates.first()

        // Deliberately not "the first known client": a known name is not the same as the client
        // behind the tunnel, and stopping one while starting another would leave the device
        // without the VPN it had and with one it did not ask for.

        Diag.warn(TAG, "a VPN is up and ${candidates.size} clients could hold it: $candidates")
        return null
    }

    fun chosenVpnClient(): String? =
        ShizukuSettings.getPreferences().getString(PREF_VPN_CHOSEN, null)

    /** Set once a choice has been made, so a second client cannot be acted on by accident. */
    fun chooseVpnClient(packageName: String) {
        ShizukuSettings.getPreferences().edit().putString(PREF_VPN_CHOSEN, packageName).apply()
    }

    private fun rememberedVpnClient(): String? =
        ShizukuSettings.getPreferences().getString(PREF_VPN_CLIENT, null)

    private fun rememberVpnClient(packageName: String) {
        ShizukuSettings.getPreferences().edit().putString(PREF_VPN_CLIENT, packageName).apply()
    }

    private fun forgetVpnClient() {
        ShizukuSettings.getPreferences().edit().remove(PREF_VPN_CLIENT).apply()
    }

    /** Hidden means: this app took a tunnel down, and it is still down. */
    private fun vpnTakenDown(): Boolean = rememberedVpnClient() != null && !vpnUp()

    /**
     * Takes the tunnel down by stopping the client that owns it.
     *
     * Nothing to do when no VPN is up: the device is not on a tunnel and this app did not take
     * one away, so there is nothing to remember and nothing to put back later.
     */
    fun takeVpnDown(): Boolean {
        if (!vpnUp()) {
            forgetVpnClient()
            return true
        }

        val client = vpnClientPackage()
        if (client == null) {
            Diag.warn(TAG, "a VPN is up and the client behind it was not identified")
            return false
        }

        rememberVpnClient(client)
        runShellCommand("am force-stop $client")
        // Asked, not assumed: the tunnel is gone when the platform says there is no VPN network.
        return !vpnUp()
    }

    /**
     * Asks the same client for the tunnel back, and only if this app took it away.
     *
     * Five clients are known by the commands that start their tunnels, taken from what VPNOnOff
     * sends: a client whose own start action is known is asked directly, and anything else is
     * relaunched and left to reconnect on its own - which some do and some do not, so the record
     * is dropped either way rather than claiming a tunnel that may not be there.
     */
    private fun bringVpnBack(): Boolean {
        val client = rememberedVpnClient() ?: return true

        // Somebody else already brought it back, or it never really went down.
        if (vpnUp()) {
            forgetVpnClient()
            return true
        }

        if (!shellAvailable()) return false

        val command = VPN_START[client]
            ?: "monkey -p $client -c android.intent.category.LAUNCHER 1"
        Diag.info(TAG, "asking $client for the tunnel back")
        runShellCommand(command)
        forgetVpnClient()
        return true
    }

    private val VPN_START = mapOf(
        "com.github.metacubex.clash.meta" to
            "am start -a com.github.metacubex.clash.meta.action.START_CLASH " +
            "-n com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity " +
            "--activity-multiple-task --activity-no-history --activity-no-animation " +
            "--activity-exclude-from-recents",
        "com.appshub.bettbox" to
            "am start -a com.appshub.bettbox.action.START " +
            "-n com.appshub.bettbox/com.appshub.bettbox.TempActivity " +
            "--activity-multiple-task --activity-no-history --activity-no-animation " +
            "--activity-exclude-from-recents",
        "com.follow.clash" to
            "am start -a com.follow.clash.action.START -n com.follow.clash/.TempActivity " +
            "--activity-multiple-task --activity-no-history --activity-no-animation " +
            "--activity-exclude-from-recents",
        "com.getsurfboard" to
            "am start -a android.intent.action.VIEW -d surfboard:///start -p com.getsurfboard",
        "com.nebula.clashmi" to
            "cmd deviceidle tempwhitelist -d 10000 com.nebula.clashmi >/dev/null 2>&1; " +
            "am broadcast --user current --receiver-foreground --include-stopped-packages " +
            "-a com.nebula.clashmi.action.CONNECT " +
            "-n com.nebula.clashmi/com.nebula.clashmi.AutomationCommandReceiver"
    )

    /**
     * Brings a signal back into view when there is nothing recorded to restore it to.
     *
     * This exists because a record was once thrown away for a signal that was visible at the
     * moment, on the reasoning that a spent record has no meaning. It has one: the setting can
     * be hidden again by something outside this app - the platform, or this app's own Shizuku
     * start - and without the record the watch knows the setting is hidden and has nothing to
     * write to un-hide it, so it stays hidden for good.
     */
    private fun reveal(signal: Signal): Boolean {
        if (signal == Signal.VPN) return bringVpnBack()

        var applied = true
        signal.keys.forEach { key ->
            val visible = key.visible ?: run {
                Diag.warn(TAG, "${key.id} is hidden and nothing was recorded for it")
                return@forEach
            }
            if (!writeKey(key, visible)) {
                Diag.warn(TAG, "bringing ${key.id} back would not take")
                applied = false
            }
        }
        return applied
    }

    private fun originals(signal: Signal): MutableMap<String, String?> =
        ShizukuSettings.getPreferences()
            .getStringSet(originalsPref(signal), emptySet())
            .orEmpty()
            .associate { entry ->
                val split = entry.indexOf('=')
                // No `=` at all is a key that was not set, which is not the same as one that was
                // set to nothing.
                if (split < 0) entry to null else entry.substring(0, split) to entry.substring(split + 1)
            }
            .toMutableMap()

    private fun writeOriginals(signal: Signal, saved: Map<String, String?>) {
        val entries = saved.entries.map { (id, value) -> if (value == null) id else "$id=$value" }
        ShizukuSettings.getPreferences().edit()
            .putStringSet(originalsPref(signal), entries.toSet())
            .apply()
    }

    private fun writeKey(key: SettingKey, value: String): Boolean = runCatching {
        val put = when (key.namespace) {
            "global" -> Settings.Global.putString(resolver, key.name, value)
            "secure" -> Settings.Secure.putString(resolver, key.name, value)
            else -> false
        }
        // Read back, always: a write the provider accepted is not the same as a setting the
        // platform is using, and the switches have to be able to say which happened.
        put && readKey(key) == value
    }.getOrDefault(false)

    /**
     * Removes a key rather than setting it to nothing, which is the only way back to a key that
     * was never there: an empty string is a value somebody chose.
     */
    private fun deleteKey(key: SettingKey): Boolean = runCatching {
        when (key.namespace) {
            "global" -> Settings.Global.putString(resolver, key.name, null)
            "secure" -> Settings.Secure.putString(resolver, key.name, null)
            else -> false
        }
        readKey(key) == null
    }.getOrDefault(false)
}
