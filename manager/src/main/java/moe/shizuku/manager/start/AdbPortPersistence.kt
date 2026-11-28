package moe.shizuku.manager.start

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.shell.ShellBackend
import moe.shizuku.manager.shell.ShellSession
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import java.io.File

/**
 * Keeping the classic ADB port open across reboots.
 *
 * adbd listens on the port in `persist.adb.tcp.port` every time it starts, which is the
 * only way to have a port waiting after a reboot: `service.adb.tcp.port`, the one `adb
 * tcpip` sets, is cleared on every boot, and wireless debugging needs a network, a hotspot
 * and a race against the framework to exist at all. With the persistent port there, a start
 * after a reboot is a plain connection to 127.0.0.1 and needs none of that.
 *
 * The catch is ownership. `persist.adb.tcp.port` is in adbd's own SELinux property context,
 * so the shell uid is refused the write - verified on a stock Samsung: `setprop
 * persist.adb.tcp.port 5555` exits 1 with "Failed to set property", where a property the
 * shell does own exits 0. Root and the system uid are allowed, which on a device that can
 * start the server as either is enough, and so is a policy that is not enforced at all: the
 * FYT 7870 head unit runs SELinux permissive, and there the shell's write goes through. So
 * this reports what actually happened rather than assuming.
 *
 * The setting is off until someone turns it on, with one exception, [reconcile]: on a device
 * where a hotspot cannot start the server, because raising one takes down the Wi-Fi the
 * framework insists on, the classic port is the only way "Start without Wi-Fi" can mean what it
 * says - and so on a device whose SELinux is not enforced, where the write is known to go
 * through and the boot start then needs no hotspot at all (on Android 13 the hotspot never gets
 * adbd's wireless server started without a Wi-Fi network anyway). There the first start that
 * gets through writes it, so every cold boot after it starts over 127.0.0.1 without any
 * network. TCP mode keeps the same port open after every start anyway; what this adds is the
 * stretch between the boot and the start.
 *
 * The port is also reachable from any network the device joins while it is open, which is
 * worth saying out loud in the setting's own words: that is the trade for never needing a
 * network to start.
 */
object AdbPortPersistence {

    private const val KEY = "persist.adb.tcp.port"

    /** One boundary for the whole user toggle and for reconcile, not just for their commands. */
    private val operations = Mutex()

    enum class Outcome {
        /** Written and read back: the port will be there after a reboot. */

        APPLIED,

        /** The platform refused, which on a shell-only server is the expected answer. */
        REFUSED,

        /**
         * The command did not run or its result could not be read back, so whether the platform
         * refused is unknown. Nothing is remembered about the build: a later start may succeed.
         */
        UNKNOWN,

        /** Nothing to write through: Shizuku is not running and there is no root shell. */
        UNAVAILABLE
    }

    /** The port the setting asks for, or 0 to leave adbd not listening. */
    private fun wantedPort(enable: Boolean): Int = if (enable) ShizukuSettings.getTcpPort() else 0

    suspend fun apply(enable: Boolean): Outcome = operations.withLock { applyLocked(enable) }

    private suspend fun applyLocked(enable: Boolean): Outcome = withContext(Dispatchers.IO) {
        val port = wantedPort(enable)
        val rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
        if (!rooted && !ShizukuStateMachine.isRunning()) return@withContext Outcome.UNAVAILABLE

        val backend = if (rooted) ShellBackend.ROOT else ShellBackend.SHIZUKU
        val output = StringBuilder()
        val session = ShellSession()
        val code = runCatching {
            session.run(backend, "setprop $KEY $port") { line -> output.append(line.text) }
        }.getOrElse { throwable ->
            Log.w(AppConstants.TAG, "Could not run the write to $KEY", throwable)
            return@withContext Outcome.UNKNOWN
        }

        val readBack = read()
        val outcome = writeOutcome(code, readBack, port)
        when (outcome) {
            Outcome.APPLIED ->
                Log.i(AppConstants.TAG, "$KEY is $port; the port will outlive a reboot")

            Outcome.REFUSED ->
                Log.w(
                    AppConstants.TAG,
                    "$KEY was refused (exit $code, reads back ${readBack ?: "nothing"}): " +
                        output.toString().trim()
                )

            else ->
                Log.w(AppConstants.TAG, "Could not confirm $KEY (exit $code): the property is unreadable")
        }
        outcome
    }

    /**
     * The user's toggle as one transaction: the write, then the preferences that describe whose
     * port it is. Holding the same mutex as [reconcile] keeps it from reading the old choice
     * between the write and the preference updates.
     */
    suspend fun setByUser(enable: Boolean): Outcome = operations.withLock {
        // Waiting for the lock stays cancellable; once it is held, the write and the preferences
        // that describe it have to finish together. A cancellation after the property write but
        // before the preferences would leave the port and the switch disagreeing.
        withContext(NonCancellable) {
            val outcome = applyLocked(enable)
            val applied = outcome == Outcome.APPLIED
            ShizukuSettings.setPersistAdbPort(if (enable) applied else !applied)
            if (applied) {
                // The user's choice from here on, either way: the no-network fallback neither
                // takes back a port they switched on nor brings back one they switched off.
                ShizukuSettings.setPersistAdbPortAuto(false)
                ShizukuSettings.setPersistAdbPortDeclined(!enable)
            }
            outcome
        }
    }

    /** What the property currently says, or null when nothing can read it. */
    suspend fun read(): Int? = withContext(Dispatchers.IO) {
        val rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
        if (!rooted && !ShizukuStateMachine.isRunning()) return@withContext null

        val backend = if (rooted) ShellBackend.ROOT else ShellBackend.SHIZUKU
        val printed = StringBuilder()
        runCatching {
            ShellSession().run(backend, "getprop $KEY") { line ->
                if (line.text.isNotBlank()) printed.append(line.text.trim())
            }
        }.getOrNull()

        printed.toString().trim().toIntOrNull()
    }

    /**
     * Whether the setting and the property agree, for the switch's own state: a switch that
     * says on while the platform refused the write would be a lie.
     */
    suspend fun isApplied(): Boolean {
        if (!ShizukuSettings.getPersistAdbPort()) return false
        return read() == ShizukuSettings.getTcpPort()
    }

    /** Read once per process: the policy does not change while the system runs. */
    @Volatile
    private var selinuxNotEnforcedCache: Boolean? = null

    /**
     * Whether SELinux is not enforced here, from selinuxfs alone: an app domain may read it only
     * where the policy is not enforced (the FYT 7870 head unit runs permissive), so an enforcing
     * phone answers false from the refused read itself - no shell, no `getenforce` process.
     */
    fun selinuxNotEnforced(): Boolean = selinuxNotEnforcedCache ?: runCatching {
        File("/sys/fs/selinux/enforce").readText().trim() == "0"
    }.getOrDefault(false).also { selinuxNotEnforcedCache = it }

    /**
     * Whether the start without Wi-Fi rests on the classic port on this device rather than on a
     * hotspot: one that takes Wi-Fi down, where the hotspot cannot get adbd's wireless server
     * started, or one whose policy lets the shell keep the port across reboots, where the port
     * makes the boot start need no network at all. Known without raising a hotspot first on the
     * latter, which is what makes the head unit start from its first boot after a start that got
     * through. False on a phone whose radio does both and whose SELinux is enforced.
     */
    fun needsClassicPortFallback(context: Context): Boolean =
        classicPortFallback(ClientWifi.hotspotDisplacesClient(context), selinuxNotEnforced())

    /**
     * After a start that got through: brings the persistent port in line with what the no-network
     * fallback needs (see the class comment), and takes it back once the start no longer needs it.
     * Only ever touches a port the fallback wrote itself; one the user switched on or off stays as
     * they left it. Runs through the server, so it is for after a start, never on the main thread.
     */
    suspend fun reconcile(context: Context, @ShizukuSettings.StartMethod method: Int) {
        // As with the user's toggle: the wait is cancellable, the transaction is not. A write
        // that took and a preference update that did not would be the state this exists to avoid.
        operations.withLock {
            withContext(NonCancellable) { reconcileLocked(context, method) }
        }
    }

    private suspend fun reconcileLocked(context: Context, method: Int) {
        val withoutNetwork = method == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK
        val tcpMode = ShizukuSettings.getTcpMode()

        if (shouldClearAutoPort(ShizukuSettings.getPersistAdbPortAuto(), withoutNetwork, tcpMode)) {
            when (applyLocked(false)) {
                Outcome.APPLIED -> {
                    ShizukuSettings.setPersistAdbPort(false)
                    ShizukuSettings.setPersistAdbPortAuto(false)
                    Diag.info(
                        AppConstants.TAG,
                        "The start no longer needs the ADB port after a reboot; $KEY is cleared"
                    )
                }

                else -> Diag.warn(AppConstants.TAG, "Could not clear $KEY the fallback wrote")
            }
            return
        }

        val wanted = ShizukuSettings.getTcpPort()
        val fallbackDevice = needsClassicPortFallback(context)
        // A port the user switched on is theirs even when this write brings it to a new port:
        // only the fallback's own port may be taken back when the start no longer needs it.
        val userOwned =
            ShizukuSettings.getPersistAdbPort() && !ShizukuSettings.getPersistAdbPortAuto()
        val declined = ShizukuSettings.getPersistAdbPortDeclined()
        val refusedHere = ShizukuSettings.getPersistAdbPortRefusedBuild() == Build.FINGERPRINT
        // Everything that needs no shell first: on a device the fallback is not for, this is
        // where it ends, without a single command sent.
        if (!shouldAutoPersistPort(withoutNetwork, tcpMode, fallbackDevice, declined, refusedHere, null, wanted)) {
            return
        }

        val current = read()
        if (current == wanted) {
            // Already there - written by an earlier start, or by the user. Make the switch say so.
            if (!ShizukuSettings.getPersistAdbPort()) {
                ShizukuSettings.setPersistAdbPort(true)
                ShizukuSettings.setPersistAdbPortAuto(true)
            }
            return
        }
        if (!shouldAutoPersistPort(withoutNetwork, tcpMode, fallbackDevice, declined, refusedHere, current, wanted)) {
            return
        }

        when (applyLocked(true)) {
            Outcome.APPLIED -> {
                ShizukuSettings.setPersistAdbPort(true)
                if (!userOwned) ShizukuSettings.setPersistAdbPortAuto(true)
                Diag.info(
                    AppConstants.TAG,
                    "Start without Wi-Fi: the classic ADB port $wanted stays open after a reboot, " +
                        "so this device starts without a network"
                )
            }

            Outcome.REFUSED -> {
                ShizukuSettings.setPersistAdbPortRefusedBuild(Build.FINGERPRINT)
                Diag.info(
                    AppConstants.TAG,
                    "Start without Wi-Fi: the platform refused $KEY; this build will not be asked " +
                        "again, and a start here needs Wi-Fi"
                )
            }

            // A command that did not run, or a read-back that failed, says nothing about the
            // platform: not remembering it here lets a later start try again.
            Outcome.UNKNOWN -> Diag.warn(
                AppConstants.TAG,
                "Start without Wi-Fi: could not confirm $KEY; a later start will try again"
            )

            Outcome.UNAVAILABLE -> Unit
        }
    }
}

/**
 * What one write to the persistent port says: written, refused, or still unknown.
 *
 * The read-back alone cannot tell a refusal from a property that cannot be read. A refused
 * `setprop` leaves the property unset, and the `getprop` after it then prints nothing, which is
 * also what an unreadable property gives - so a read-back-only check calls every refusal on a phone
 * whose shell may not write adbd's property "unknown", asks again on every start and warns each
 * time (issue #285). The exit code is what separates the two: it is the one `setprop` returned, or
 * -1 when the command did not run at all.
 */
internal fun writeOutcome(exitCode: Int, readBack: Int?, port: Int): AdbPortPersistence.Outcome =
    when {
        readBack == port -> AdbPortPersistence.Outcome.APPLIED
        // The command ran and failed, or a value it should have written did not take.
        exitCode > 0 || readBack != null -> AdbPortPersistence.Outcome.REFUSED
        // Not run, or run and unreadable after it: nothing is known about the platform.
        else -> AdbPortPersistence.Outcome.UNKNOWN
    }

/**
 * Whether a device needs the classic port for its start without Wi-Fi (see
 * [AdbPortPersistence.needsClassicPortFallback]): a hotspot takes Wi-Fi down, or SELinux is not
 * enforced. An unknown answer about the hotspot counts as "no".
 */
internal fun classicPortFallback(hotspotDisplacesClient: Boolean?, selinuxNotEnforced: Boolean): Boolean =
    hotspotDisplacesClient == true || selinuxNotEnforced

/**
 * Whether the no-network fallback should write the persistent ADB port: the start is the one
 * without a network, TCP mode keeps the classic port (without it every start closes the port
 * again), the device needs the classic port ([classicPortFallback]), the user has not switched the port off,
 * this build has not refused it, and the property does not already say [wanted]. [current] is
 * null when it is not known yet, which is the cheap first pass before anything asks the shell.
 * On a phone whose radio holds a hotspot next to Wi-Fi and whose SELinux is enforced this is always false.
 */
internal fun shouldAutoPersistPort(
    withoutNetwork: Boolean,
    tcpMode: Boolean,
    fallbackDevice: Boolean,
    declined: Boolean,
    refusedOnThisBuild: Boolean,
    current: Int?,
    wanted: Int
): Boolean = withoutNetwork && tcpMode && fallbackDevice && !declined &&
    !refusedOnThisBuild && wanted > 0 && current != wanted

/**
 * Whether a persistent port the fallback wrote should go: the start is no longer the one without
 * a network, or TCP mode is off. A port the user switched on is never the fallback's to take.
 */
internal fun shouldClearAutoPort(autoApplied: Boolean, withoutNetwork: Boolean, tcpMode: Boolean): Boolean =
    autoApplied && (!withoutNetwork || !tcpMode)
