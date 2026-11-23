package moe.shizuku.manager.manage

import android.app.usage.UsageEvents
import moe.shizuku.manager.manage.Hiding.TopActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide how long a hide is held around a restart. Each of these going wrong ends
 * the same way: the settings come back while the app is on screen, or the app is killed again.
 */
class HidingHoldTest {

    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED

    // ---- when the app came to the front ---------------------------------------------

    @Test
    fun `a resume records when the app came to the front`() {
        val top = Hiding.foldForegroundEvent(null, resumed, "target", ".Main", 1_000L)
        assertEquals(1_000L, top?.resumedAt)
    }

    @Test
    fun `another activity of the app pausing keeps when it came to the front`() {
        val start = TopActivity("target", ".Main", 1_000L)
        val top = Hiding.foldForegroundEvent(start, paused, "target", ".Splash", 2_000L)
        assertEquals(1_000L, top?.resumedAt)
    }

    // ---- releasing the opening hold -------------------------------------------------

    @Test
    fun `a foreground report from before the restart does not release the hold`() {
        assertFalse(Hiding.openingSeen("target", 1_000L, "target", 900L))
    }

    @Test
    fun `the app resumed after the restart releases the hold`() {
        assertTrue(Hiding.openingSeen("target", 1_000L, "target", 1_100L))
    }

    @Test
    fun `a live foreground read with no time releases the hold`() {
        assertTrue(Hiding.openingSeen("target", 1_000L, "target", null))
    }

    @Test
    fun `another app in front does not release the hold`() {
        assertFalse(Hiding.openingSeen("target", 1_000L, "manager", 1_100L))
    }

    // ---- the restart marker ---------------------------------------------------------

    @Test
    fun `the marker stays while the restarted app is in front`() {
        assertEquals("target", Hiding.restartMarkerAfter("target", "target", "manager"))
    }

    @Test
    fun `the marker stays while the manager's launch activity is in front`() {
        assertEquals("target", Hiding.restartMarkerAfter("target", "manager", "manager"))
    }

    @Test
    fun `the marker stays when nothing is named in front`() {
        assertEquals("target", Hiding.restartMarkerAfter("target", null, "manager"))
    }

    @Test
    fun `the marker goes when another app comes to the front`() {
        assertNull(Hiding.restartMarkerAfter("target", "launcher", "manager"))
    }

    // ---- acting for an app the user closed ------------------------------------------

    @Test
    fun `an app nobody closed may be restarted`() {
        assertTrue(Hiding.openedAgainSinceClose("target", null, 1_000L))
    }

    @Test
    fun `a close of another app does not hold this one back`() {
        assertTrue(Hiding.openedAgainSinceClose("target", "other" to 5_000L, 1_000L))
    }

    @Test
    fun `a closed app still named in front is not restarted`() {
        assertFalse(Hiding.openedAgainSinceClose("target", "target" to 5_000L, 1_000L))
    }

    @Test
    fun `a closed app still named in front after the hide was reset is not acted for`() {
        // The pass that restored the settings reset the hide's start, so the exit records no
        // longer say the app was closed; the close itself has to.
        assertFalse(Hiding.openedAgainSinceClose("target", "target" to 5_000L, 4_000L))
    }

    @Test
    fun `a closed app opened again is restarted`() {
        assertTrue(Hiding.openedAgainSinceClose("target", "target" to 5_000L, 6_000L))
    }

    @Test
    fun `a live foreground read after a close may restart`() {
        assertTrue(Hiding.openedAgainSinceClose("target", "target" to 5_000L, null))
    }

    // ---- stops the manager made -----------------------------------------------------

    @Test
    fun `a stop of one app is not forgotten when another app is stopped`() {
        val stops = Hiding.ManagerStops()
        stops.record("a", 1_000L)
        stops.record("b", 2_000L)
        assertTrue(stops.matches("a", 1_500L))
        assertTrue(stops.matches("b", 2_000L))
    }

    @Test
    fun `an exit far from any stop of ours is the user's`() {
        val stops = Hiding.ManagerStops()
        stops.record("a", 1_000L)
        assertFalse(stops.matches("a", 10_000L))
        assertFalse(stops.matches("c", 1_000L))
    }

    @Test
    fun `clearing forgets every stop`() {
        val stops = Hiding.ManagerStops()
        stops.record("a", 1_000L)
        stops.clear()
        assertFalse(stops.matches("a", 1_000L))
    }
}
