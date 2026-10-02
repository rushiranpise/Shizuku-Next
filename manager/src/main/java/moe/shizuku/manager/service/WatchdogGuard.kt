package moe.shizuku.manager.service

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * The deaths the watchdog should not react to.
 *
 * Replacing a running server is our own doing, but the old binder dying on the way out looks
 * from the watchdog's side exactly like a server that fell over, so a deliberate restart used
 * to be answered with a crash notification and a second start racing the one the user asked
 * for. Anything about to bring a running server down marks the death as expected here, and the
 * watchdog consumes that mark instead of reacting to it.
 *
 * The mark carries a deadline because the event it is waiting for is not guaranteed to arrive:
 * a start that never gets far enough to kill the old binder would otherwise leave the watchdog
 * deaf until the next reboot. A mark read after its window is dropped rather than honoured.
 */
object WatchdogGuard {

    private const val WINDOW_MS = 30_000L

    private val deadline = AtomicLong(0L)

    /** Marks the server's death, or its replacement, as expected. */
    fun expectDeath() {
        deadline.set(SystemClock.elapsedRealtime() + WINDOW_MS)
    }

    /** Whether a death is expected right now. */
    fun isExpectingDeath(): Boolean {
        val at = deadline.get()
        return at != 0L && SystemClock.elapsedRealtime() <= at
    }

    /**
     * Answers once for the death that was expected, and drops the mark either way: a death
     * that arrives after the window is a real one, not the one this was set for.
     */
    fun consumeExpectedDeath(): Boolean {
        val at = deadline.getAndSet(0L)
        return at != 0L && SystemClock.elapsedRealtime() <= at
    }

    /** Forgets the mark, used when the server is confirmed running again. */
    fun clear() {
        deadline.set(0L)
    }
}
