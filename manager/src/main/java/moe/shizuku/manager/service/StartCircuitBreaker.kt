package moe.shizuku.manager.service

/**
 * Stops the watchdog restarting a server that a restart cannot bring back.
 *
 * The failure it exists for: an outage the watchdog cannot fix leaves the state at STOPPED, and
 * STOPPED is also what a crash looks like. A stale ADB pairing is the one seen in the wild - the
 * start fails with a TLS rejection, the state goes back to STOPPED, and the recovery loop answers
 * it with another start thirty seconds later, for as long as the app is installed. Nothing told
 * the user; the app simply appeared to open and close itself forever, and the only way out was to
 * press Stop, which is the one control that sets the flag the loop checks.
 *
 * So attempts are counted, and a run of them inside one window means the start itself is broken
 * rather than the server. The window is what keeps this about a *failing* start and not a busy
 * phone: a server that dies and comes back is working, and its attempts fall outside any single
 * window. Only attempts that keep it down fill one.
 *
 * Deliberately just the arithmetic: the clock is passed in, nothing here notifies, and nothing
 * reads a setting, so the behaviour can be tested without a device.
 */
internal class StartCircuitBreaker(
    private val maxAttempts: Int,
    private val windowMs: Long
) {

    private val attempts = ArrayDeque<Long>()

    /** True once [maxAttempts] have landed inside one [windowMs], until [reset] says otherwise. */
    var open: Boolean = false
        private set

    /**
     * Counts one attempt at [now], dropping whatever has fallen out of the window first.
     *
     * @return true when this is the attempt that opened the breaker, so the caller can say so
     *   exactly once rather than on every attempt after it.
     */
    fun record(now: Long): Boolean {
        if (open) return false
        while (attempts.isNotEmpty() && now - attempts.first() > windowMs) {
            attempts.removeFirst()
        }
        attempts.addLast(now)
        if (attempts.size < maxAttempts) return false
        open = true
        return true
    }

    /** A server that is running again proves the failures were not permanent, so the count goes. */
    fun reset() {
        attempts.clear()
        open = false
    }
}
