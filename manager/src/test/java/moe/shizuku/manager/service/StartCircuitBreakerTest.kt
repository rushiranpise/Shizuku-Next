package moe.shizuku.manager.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the watchdog giving up.
 *
 * This is the one part of the recovery loop that can be checked without a device, and it is worth
 * checking precisely because the cost of getting it wrong is invisible: too eager and a phone that
 * has not got its Wi-Fi back yet is cut off, too patient and the loop the breaker exists to stop
 * simply runs a little longer.
 *
 * The intervals below are the real ones the service passes in - five attempts inside two minutes -
 * and the poll that feeds them is thirty seconds, so the timing used here is the timing that
 * happens rather than a convenient round number.
 */
class StartCircuitBreakerTest {

    private fun breaker() = StartCircuitBreaker(maxAttempts = 5, windowMs = 120_000L)

    @Test
    fun `it stays closed while the attempts are fewer than the limit`() {
        val breaker = breaker()

        assertFalse(breaker.record(0))
        assertFalse(breaker.record(30_000))
        assertFalse(breaker.record(60_000))
        assertFalse(breaker.record(90_000))
        assertFalse(breaker.open)
    }

    /** The fifth attempt is the one that opens it, and only that one reports it. */
    @Test
    fun `the attempt that reaches the limit opens it and says so once`() {
        val breaker = breaker()
        repeat(4) { breaker.record(it * 30_000L) }

        assertTrue(breaker.record(120_000))
        assertTrue(breaker.open)
        // Nothing after it claims to be the opening attempt, so the user is told once.
        assertFalse(breaker.record(150_000))
        assertFalse(breaker.record(180_000))
    }

    /**
     * The case the window exists for: a server that keeps dying and coming back is working, and
     * must not be mistaken for a start that cannot succeed. Its attempts are real, they are just
     * spread out, and every one of them falls outside the window by the time the next arrives.
     */
    @Test
    fun `attempts spread wider than the window never open it`() {
        val breaker = breaker()

        var now = 0L
        repeat(20) {
            assertFalse(breaker.record(now))
            now += 130_000L // just past the window, every time
        }
        assertFalse(breaker.open)
    }

    /** An attempt exactly on the window's edge is still inside it. */
    @Test
    fun `an attempt exactly one window old still counts`() {
        val breaker = breaker()
        repeat(4) { breaker.record(it * 30_000L) }

        // 120_000 is exactly windowMs after 0, so the first attempt is not yet dropped and this
        // one opens the breaker.
        assertTrue(breaker.record(120_000))
    }

    @Test
    fun `the oldest attempt falling out of the window makes room for another`() {
        val breaker = breaker()
        repeat(4) { breaker.record(it * 30_000L) }

        // 120_001 drops the attempt at 0, leaving three, so this is the fourth and not the fifth.
        assertFalse(breaker.record(120_001))
        assertFalse(breaker.open)
    }

    /** A server that is running again is proof the failures were not permanent. */
    @Test
    fun `a reset closes it and clears the count`() {
        val breaker = breaker()
        repeat(5) { breaker.record(it * 30_000L) }
        assertTrue(breaker.open)

        breaker.reset()

        assertFalse(breaker.open)
        // And the count really is gone, not merely hidden: four more attempts are not enough.
        repeat(4) { breaker.record(1_000_000 + it * 30_000L) }
        assertFalse(breaker.open)
    }

    /** Resetting a breaker that never opened is harmless, which is what a healthy start does. */
    @Test
    fun `a reset on a closed breaker changes nothing`() {
        val breaker = breaker()
        breaker.record(0)

        breaker.reset()

        assertFalse(breaker.open)
        assertFalse(breaker.record(30_000))
    }
}
