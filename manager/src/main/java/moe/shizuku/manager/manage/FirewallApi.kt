package moe.shizuku.manager.manage

import android.os.IBinder
import moe.shizuku.manager.utils.Diag
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The platform's firewall by its system API, for the ROMs whose connectivity command cannot do it.
 *
 * `cmd connectivity set-chain3-enabled` and `set-package-networking-enabled` are what this app uses,
 * and they are not on every ROM: an older or trimmed connectivity service answers that the verb does
 * not exist, and the switch then does nothing at all. Those commands are a front end for the calls
 * below - the same `IConnectivityManager`, reached as Shizuku instead of as `cmd` - so where the
 * front end is missing the calls behind it still work.
 *
 * This is ShizuWall's technique, from the release that added the fallback
 * (AhmetCanArslan, commit `f9a6ffa`, "Add firewall chain management methods to user service and
 * daemon"): take the connectivity binder with [SystemServiceHelper], wrap it in
 * [ShizukuBinderWrapper] so the transactions are made as the shell, and reach the hidden interface
 * by reflection because a hidden one cannot be compiled against. Thanks to them for working it out.
 *
 * What each call takes was read from `IConnectivityManager.aidl` rather than guessed, including the
 * two ways to ask the state back - which is what lets a switch verify that it did what it said.
 */
object FirewallApi {

    private const val TAG = "FirewallApi"

    /**
     * The chain this switch uses, which is the one `set-chain3-enabled` names:
     * `ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3`.
     *
     * A number rather than a name because there is nothing to reference: the constants live in a
     * hidden class, so the value is pinned here and held to it by a test.
     */
    const val CHAIN = 9

    /** `ConnectivityManager.FIREWALL_RULE_ALLOW` and `FIREWALL_RULE_DENY`. */
    const val RULE_ALLOW = 1
    const val RULE_DENY = 2

    /** The rule a switch means: blocking denies, and unblocking allows. */
    fun ruleFor(blocked: Boolean): Int = if (blocked) RULE_DENY else RULE_ALLOW

    /**
     * What a rule from the platform means, or null for one this app does not recognise.
     *
     * The default rule is an answer rather than a gap: a uid with none set is not blocked by this
     * chain, which is what the switch should show.
     */
    fun isBlocked(rule: Int?): Boolean? = when (rule) {
        RULE_DENY -> true
        RULE_ALLOW, RULE_DEFAULT -> false
        else -> null
    }

    /** `ConnectivityManager.FIREWALL_RULE_DEFAULT`. */
    private const val RULE_DEFAULT = 0

    /**
     * Whether a write that reached the platform should be reported as applied.
     *
     * A successful write is not always readable: Android 13 added the setters and the OEM deny
     * chain, but not the getters, so a rule can be set there with no way to ask it back. Unknown
     * is not a refusal, and reporting it as one is what made the switch spring back after a rule
     * that had in fact been applied. Only a definite opposite answer is a failure.
     */
    internal fun writeApplied(wrote: Boolean, requested: Boolean, observed: Boolean?): Boolean =
        wrote && (observed == null || observed == requested)

    /**
     * Whether the chain is on, or null when the platform cannot be asked.
     *
     * Null is a real answer and is passed on as itself: a ROM with neither the command nor an
     * interface that answers leaves the app unable to say, and saying so is better than guessing.
     */
    fun chainEnabled(): Boolean? = withService { service ->
        service.javaClass
            .getMethod("getFirewallChainEnabled", Int::class.javaPrimitiveType)
            .invoke(service, CHAIN) as Boolean
    }

    /**
     * Switches the chain on or off, answering whether the platform took the call.
     *
     * The call is void, and `invoke` answers null for a void method: reading that null as the
     * answer made this report a refusal after having made the call. On the ROMs the fallback
     * exists for - the ones whose connectivity command is missing - the chain was switched and
     * the caller was told it was not, so the block set afterwards was thrown away as a failure
     * and the switch sprang back. Reaching the end of the block is the answer.
     */
    fun setChainEnabled(enabled: Boolean): Boolean = withService { service ->
        service.javaClass
            .getMethod(
                "setFirewallChainEnabled",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            .invoke(service, CHAIN, enabled)
        true
    } == true

    /** The rule set for [uid] on this chain, or null when the platform cannot be asked. */
    fun blockedRule(uid: Int): Int? = withService { service ->
        service.javaClass
            .getMethod(
                "getUidFirewallRule",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .invoke(service, CHAIN, uid) as Int
    }

    /**
     * Blocks or allows [uid] on this chain, answering whether the platform took the call.
     *
     * The call is void, so the null `invoke` answers for it is not a refusal - see
     * [setChainEnabled] for what reading it as one did.
     */
    fun setBlocked(uid: Int, blocked: Boolean): Boolean = withService { service ->
        service.javaClass
            .getMethod(
                "setUidFirewallRule",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .invoke(service, CHAIN, uid, ruleFor(blocked))
        true
    } == true

    /**
     * Calls [block] with the connectivity interface, or answers null when there is nothing to call.
     *
     * The interface is fetched and made over on every call rather than kept. It is two binder
     * lookups, which is nothing beside a shell command, and a cached proxy would be a cached set of
     * assumptions about a service that can be restarted underneath it.
     */
    private fun <T> withService(block: (Any) -> T): T? {
        val service = runCatching {
            val binder: IBinder = SystemServiceHelper.getSystemService("connectivity")
                ?: return null

            Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(binder))
        }
            .onFailure { Diag.warn(TAG, "the connectivity interface could not be reached", it) }
            .getOrNull() ?: return null

        return runCatching { block(service) }
            .onFailure { Diag.warn(TAG, "the connectivity interface refused the call", it) }
            .getOrNull()
    }
}
