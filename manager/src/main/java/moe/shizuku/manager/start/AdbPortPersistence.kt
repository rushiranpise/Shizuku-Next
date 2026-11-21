package moe.shizuku.manager.start

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    enum class Outcome {
        /** Written and read back: the port will be there after a reboot. */

        APPLIED,

        /** The platform refused, which on a shell-only server is the expected answer. */
        REFUSED,

        /**
         * Nothing to write through (Shizuku is not running and there is no root shell), or the
         * outcome is not known: the command did not run to the end, or the property could not be
         * read back. Either way nothing is known to have been refused, so a later try is welcome.
         */
        UNAVAILABLE
    }

    /**
     * One writer at a time: the switch the user flips and the fallback's [reconcile] both decide
     * from the preferences and then write the property, and a reconcile that had read "not
     * declined" just before the user switched the port off would write it straight back.
     */
    private val writeMutex = Mutex()

    /** The port the setting asks for, or 0 to leave adbd not listening. */
    private fun wantedPort(enable: Boolean): Int = if (enable) ShizukuSettings.getTcpPort() else 0

    suspend fun apply(enable: Boolean): Outcome = withContext(Dispatchers.IO) {
        val port = wantedPort(enable)
        val rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
        if (!rooted && !ShizukuStateMachine.isRunning()) return@withContext Outcome.UNAVAILABLE

        val backend = if (rooted) ShellBackend.ROOT else ShellBackend.SHIZUKU
        val output = StringBuilder()
        val session = ShellSession()
        val code = runCatching {
            session.run(backend, "setprop $KEY $port") { line -> output.append(line.text) }
        }.getOrElse { throwable ->
            // The command did not get to answer: not a refusal, just nothing known.
            Log.w(AppConstants.TAG, "Could not write $KEY", throwable)
            return@withContext Outcome.UNAVAILABLE
        }

        val readBack = read()
        when {
            // -1 is ShellSession's "could not be run at all, or was stopped": nothing known.
            code < 0 -> {
                Log.w(AppConstants.TAG, "The write of $KEY did not run to the end")
                Outcome.UNAVAILABLE
            }

            // The command ran and said it was refused ("Failed to set property", exit 1 on an
            // enforcing phone): that is an answer, whatever the read-back says.
            code != 0 -> {
                Log.w(
                    AppConstants.TAG,
                    "$KEY was refused (exit $code, reads back $readBack): " + output.toString().trim()
                )
                Outcome.REFUSED
            }

            // It said yes but there is nothing to compare against: whether it took is not known.
            readBack == null -> {
                Log.w(AppConstants.TAG, "$KEY could not be read back after the write (exit $code)")
                Outcome.UNAVAILABLE
            }

            code == 0 && readBack == port -> {
                Log.i(AppConstants.TAG, "$KEY is $port; the port will outlive a reboot")
                Outcome.APPLIED
            }

            else -> {
                Log.w(
                    AppConstants.TAG,
                    "$KEY was refused (exit $code, reads back $readBack): " +
                        output.toString().trim()
                )
                Outcome.REFUSED
            }
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

    /**
     * The user's own switch: writes the property and records the choice as theirs, so the
     * fallback neither takes back a port they switched on nor brings back one they switched off.
     * Serialized with [reconcile].
     */
    suspend fun applyByUser(enable: Boolean): Outcome = writeMutex.withLock {
        val outcome = apply(enable)
        if (outcome == Outcome.APPLIED) {
            ShizukuSettings.setPersistAdbPort(enable)
            ShizukuSettings.setPersistAdbPortAuto(false)
            ShizukuSettings.setPersistAdbPortDeclined(!enable)
        } else {
            // Off means "not on" only when the write went through.
            ShizukuSettings.setPersistAdbPort(!enable)
        }
        outcome
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
    suspend fun reconcile(context: Context, @ShizukuSettings.StartMethod method: Int) =
        writeMutex.withLock { reconcileLocked(context, method) }

    private suspend fun reconcileLocked(context: Context, @ShizukuSettings.StartMethod method: Int) {
        val withoutNetwork = method == ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK
        val tcpMode = ShizukuSettings.getTcpMode()

        if (shouldClearAutoPort(ShizukuSettings.getPersistAdbPortAuto(), withoutNetwork, tcpMode)) {
            when (apply(false)) {
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

        // A port the user switched on stays theirs when only the port number changed under it.
        val userOwned = ShizukuSettings.getPersistAdbPort() && !ShizukuSettings.getPersistAdbPortAuto()
        when (apply(true)) {
            Outcome.APPLIED -> {
                ShizukuSettings.setPersistAdbPort(true)
                ShizukuSettings.setPersistAdbPortAuto(!userOwned)
                Diag.info(
                    AppConstants.TAG,
                    "Start without Wi-Fi: the classic ADB port $wanted stays open after a reboot, " +
                        "so this device starts without a network"
                )
            }

            // Only a refusal that ran and read back another value: anything unknown is
            // UNAVAILABLE and gets tried again on a later start.
            Outcome.REFUSED -> {
                ShizukuSettings.setPersistAdbPortRefusedBuild(Build.FINGERPRINT)
                Diag.info(
                    AppConstants.TAG,
                    "Start without Wi-Fi: the platform refused $KEY; this build will not be asked " +
                        "again, and a start here needs Wi-Fi"
                )
            }

            Outcome.UNAVAILABLE -> Unit
        }
    }
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
