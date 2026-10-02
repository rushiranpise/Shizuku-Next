package moe.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.manage.HidingGrants
import moe.shizuku.manager.start.grantWriteSecureSettingsIfNeeded
import rikka.shizuku.Shizuku

private val appContext = ShizukuApplication.appContext

/**
 * The same one-time handing over, for the two grants the hiding lists need.
 *
 * Only while a list is actually in use, which is where this deliberately differs from the
 * permission granted beside it: that one is needed by the start flow itself, while these two are
 * only ever read by the hiding watch - and usage access is a real special-access grant, not one
 * of the harmless ones. Somebody who has never made a hiding list should not be handing it out.
 *
 * It exists because the permission above it is granted here, on the server being seen running,
 * and hiding's two have no better trigger: this is the moment the shell becomes available, and
 * without a shell there is nothing to ask with.
 */
private fun grantHidingAccessIfListsAreInUse() {
    if (!Hiding.hasAnyApp()) return
    HidingGrants.ensureQuietly()
}

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private var state = AtomicReference<State>(State.STOPPED)
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener { set(State.RUNNING) }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener { setDead() }
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        val oldState = state.getAndUpdate(transform)
        val newState = transform(oldState)
        if(oldState != newState) {
            // Every transition, into the log the phone can read: "why did Shizuku stop" is
            // usually answered by the sequence of these rather than by any one line.
            Diag.info("ShizukuStateMachine", "${oldState.name} -> ${newState.name}")
            // A confirmed running server lifts manual-stop suppression, so a
            // later crash is auto-restarted by the watchdog.
            if (newState == State.RUNNING) {
                ShizukuSettings.setManuallyStopped(false)
                // The server is up, so it can hand us the ADB-only permission the wireless
                // flow needs the user shouldn't have to reach for a computer for it.
                grantWriteSecureSettingsIfNeeded()
                grantHidingAccessIfListsAreInUse()
                // Remember how the server was launched so later background starts
                // know whether to use root or wireless debugging (previously done by
                // the removed HomeViewModel).
                runCatching {
                    ShizukuSettings.setLastLaunchMode(
                        if (Shizuku.getUid() == 0) ShizukuSettings.LaunchMethod.ROOT
                        else ShizukuSettings.LaunchMethod.ADB
                    )
                }
            }
            // A deliberate stop (STOPPING -> STOPPED) is where the debugging toggles get
            // switched off, if the settings ask for it.
            if (oldState == State.STOPPING && newState == State.STOPPED) {
                disableDebuggingTogglesIfAsked()
            }

            // Deliberately NOT clearing the recorded transport when the server stops.
            // It describes how the server was launched, so it stays true after the
            // launch ends and clearing it here also wiped it on every transient
            // STOPPED while a start was still coming up (the binder is not up yet),
            // which left a running server being reported as "Unknown".
            listeners.forEach { it(newState) }
            Log.d("ShizukuStateMachine", newState.toString())
            when (newState) {
                State.RUNNING, State.STOPPED, State.CRASHED -> sendShizukuChangedBroadcast(newState)
                else -> Unit
            }
        }
    }

    // Broadcast so automation apps (e.g. MacroDroid/Tasker) can react to
    // Shizuku starting or stopping.
    private fun sendShizukuChangedBroadcast(newState: State) {
        val intent = Intent("${appContext.packageName}.SHIZUKU_CHANGED").apply {
            putExtra("status", if (newState == State.RUNNING) 1 else 0)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        appContext.sendBroadcast(intent)
    }

    fun set(newState: State) = transition { newState }

    fun setDead() = transition {
        when (it) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> State.STOPPED
            else -> it
        }
    }

    /**
     * Turns the debugging toggles off after a deliberate stop, when the user asked for
     * that in settings.
     *
     * Called from the transition rather than from [setDead] because the binder dying can
     * be noticed by [update] first which reaches STOPPED from STOPPING just the same,
     * and used to skip this entirely.
     */
    private fun disableDebuggingTogglesIfAsked() {
        try {
            val granted = appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return

            if (ShizukuSettings.getAutoDisableUsbDebugging()) {
                Settings.Global.putInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0)
            }
            // Wireless debugging is kept on by default that is what lets Shizuku restart
            // with no Wi-Fi so turning it off with Shizuku is opt-in. The opt-in is
            // ignored while the experiment that keeps it on without a network is in use:
            // stopping Shizuku would otherwise undo the state the whole trick exists to
            // hold, and the next start would have to win it all over again.
            if (ShizukuSettings.getAutoDisableWirelessDebugging() &&
                !ShizukuSettings.getForceWirelessDebugging()
            ) {
                Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 0)
            }
        } catch (e: Exception) {
            Log.w("ShizukuStateMachine", "Failed to disable the debugging toggles", e)
        }
    }

    fun update(): State {
        val state = if (Shizuku.pingBinder()) State.RUNNING else State.STOPPED
        set(state)
        // Also covers a server that was already running when this process started, or a
        // permission that was revoked behind our back: there is no transition to hook then.
        if (state == State.RUNNING) {
            grantWriteSecureSettingsIfNeeded()
            grantHidingAccessIfListsAreInUse()
        }
        return state
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
    }

    fun isDead(): Boolean {
        return (get() == State.STOPPED || get() == State.CRASHED) 
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

}