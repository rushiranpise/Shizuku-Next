package moe.shizuku.manager.receiver

/** What [ShizukuReceiverStarter.start] does with a request, decided before anything is touched. */
enum class StartDecision {
    /** Go ahead and start. */
    START,

    /**
     * Go ahead and start, before the unlock: never through WorkManager. The wireless and USB
     * starts run in the foreground service, a root start runs directly.
     */
    START_LOCKED,

    /** The start cannot be made before the unlock; the unlock asks for it again. */
    DEFER_UNTIL_UNLOCK,

    /** The server is up and nothing asked to replace it. */
    IGNORE_RUNNING,

    /** A start is already under way; this request adds nothing to it. */
    JOIN_IN_FLIGHT
}

/**
 * The order of the checks matters: a running server answers a plain request first, and only then
 * is a start in flight asked about.
 *
 * A start somebody tapped, or the automatic start after a completed pairing, may replace an
 * attempt that is already under way. The watchdog's forced starts and a boot's start arrive in the
 * same few seconds after a boot, and each queued work that cancelled the last - but cancelling a
 * start does not stop the command it has already sent, so the two ran side by side and the second
 * one's starter killed the first one's server.
 *
 * Before the first unlock nothing here may reach WorkManager, whose database is in storage that is
 * not readable yet. A start that can be made without it ([canStartLocked]) is made directly or in
 * the foreground service ([StartDecision.START_LOCKED]); one that cannot waits for the unlock.
 * [userUnlocked] and [canStartLocked] default to the answers that leave every earlier decision as
 * it was.
 */
internal fun decideStart(
    binderAlive: Boolean,
    forceStart: Boolean,
    userInitiated: Boolean,
    startInFlight: Boolean,
    userUnlocked: Boolean = true,
    canStartLocked: Boolean = false,
    replaceInFlight: Boolean = false
): StartDecision = when {
    binderAlive && !forceStart -> StartDecision.IGNORE_RUNNING
    startInFlight && !userInitiated && !replaceInFlight -> StartDecision.JOIN_IN_FLIGHT
    !userUnlocked && canStartLocked -> StartDecision.START_LOCKED
    !userUnlocked -> StartDecision.DEFER_UNTIL_UNLOCK
    else -> StartDecision.START
}
