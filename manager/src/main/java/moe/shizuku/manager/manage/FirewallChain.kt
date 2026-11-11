package moe.shizuku.manager.manage

import android.os.Build
import moe.shizuku.manager.utils.runShellCommand
import rikka.shizuku.Shizuku

/**
 * The whole firewall as one switch: chain 3, which every per-app block this app sets lands in.
 *
 * The per-app switches in Labs decide what is blocked and this decides whether any of it is in
 * force, which is the difference between a block list and a firewall. It is also the switch a
 * person wants on the way out of the house and off in the evening, which is why it is offered
 * outside the app: a tile in the shade, and a widget on the home screen.
 *
 * The chain is shared, on purpose. ShizuWall and anything else that blocks with this platform
 * mechanism writes the same chain, and a switch that claims to turn the firewall off has to turn
 * all of it off rather than only the part that this app wrote. Nothing is unblocked while it is
 * off: the rules stay in the framework, and switching it back on returns exactly the apps that
 * were blocked before, with no list to keep and nothing to rebuild.
 *
 * Whether the chain is on belongs to the platform and is asked every time, never remembered. A
 * reboot, another app, or the platform itself can change it, and a tile that showed the last
 * thing this app did would be wrong the moment anything else touched it.
 */
object FirewallChain {

    /**
     * Whether this platform has the OEM deny chain at all.
     *
     * Android 13 added the chain and the calls that write it. Before that there is no such
     * firewall to offer, so the list is left out rather than opening onto switches that cannot
     * do anything.
     */
    fun supportsFirewall(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * Whether a rule can be read back once it is set.
     *
     * Android 14 added the getters. Android 13 can write a rule but has no way to ask what it
     * holds, so its writes have to be trusted rather than checked.
     */
    fun canReadRule(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * What `cmd connectivity get-chain3-enabled` answered, or null when it said neither thing.
     *
     * Answered as itself rather than as "off": a ROM that does not have the verb, and a server
     * that is not running, both leave this unable to say, and a switch that guessed would be
     * telling the user something it does not know. The answer on a platform that has it is
     * `chain:enabled` or `chain:disabled`.
     */
    internal fun parse(answer: String?): Boolean? = when {
        answer?.trim()?.endsWith(":enabled") == true -> true
        answer?.trim()?.endsWith(":disabled") == true -> false
        else -> null
    }

    /**
     * Whether the chain is on, or null when neither the command nor the interface behind it
     * could say.
     */
    fun isOn(): Boolean? {
        // The binder, not the state machine: a tile or a widget runs in a process of its own, and
        // one that has just started has not been told anything yet. The binder is the truth about
        // whether a command can be run right now, and the state machine catches up to it.
        if (!supportsFirewall() || !Shizuku.pingBinder()) return null

        parse(runShellCommand("cmd connectivity get-chain3-enabled"))?.let { return it }

        // The command is missing on some ROMs - the same ones the per-app switch already has a
        // fallback for - so the platform is asked through the interface `cmd` is a front end for.
        return FirewallApi.chainEnabled()
    }

    /**
     * Switches the chain on or off, answering whether it is in the state that was asked for.
     *
     * The command's exit code is not the answer: a ROM can accept the verb and leave the chain
     * where it was, which is a switch that appears to work and does nothing. What it did is read
     * back, and the interface is tried when the command's answer is not the one that was asked
     * for, since a ROM with the verb can still be one where the verb does nothing.
     */
    fun setOn(on: Boolean): Boolean {
        if (!supportsFirewall() || !Shizuku.pingBinder()) return false

        runShellCommand("cmd connectivity set-chain3-enabled ${if (on) "true" else "false"}")
        if (isOn() == on) return true

        if (!FirewallApi.setChainEnabled(on)) return false
        // Android 13 can write the chain but cannot read it back; the write is the answer there.
        return FirewallApi.writeApplied(wrote = true, requested = on, observed = isOn())
    }

    /** Switches the chain to the other state, answering the new one, or null when it could not. */
    fun toggle(): Boolean? {
        val current = isOn() ?: return null
        return if (setOn(!current)) !current else null
    }
}
