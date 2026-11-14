package moe.shizuku.manager.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

/** Who may start while another start is under way; see [decideStart]. */
class StartRequestDecisionTest {

    @Test
    fun `a plain start during another one joins it`() {
        assertEquals(
            StartDecision.JOIN_IN_FLIGHT,
            decideStart(binderAlive = false, forceStart = false, userInitiated = false, startInFlight = true)
        )
    }

    @Test
    fun `a forced start nobody tapped joins it too`() {
        assertEquals(
            StartDecision.JOIN_IN_FLIGHT,
            decideStart(binderAlive = false, forceStart = true, userInitiated = false, startInFlight = true)
        )
    }

    @Test
    fun `a restart somebody tapped replaces it`() {
        assertEquals(
            StartDecision.START,
            decideStart(binderAlive = false, forceStart = true, userInitiated = true, startInFlight = true)
        )
    }

    @Test
    fun `a start somebody tapped replaces it without a restart`() {
        assertEquals(
            StartDecision.START,
            decideStart(binderAlive = false, forceStart = false, userInitiated = true, startInFlight = true)
        )
    }

    @Test
    fun `a completed pairing replaces it`() {
        assertEquals(
            StartDecision.START,
            decideStart(
                binderAlive = false, forceStart = false, userInitiated = false,
                startInFlight = true, replaceInFlight = true
            )
        )
    }

    @Test
    fun `a running server ignores a plain start`() {
        assertEquals(
            StartDecision.IGNORE_RUNNING,
            decideStart(binderAlive = true, forceStart = false, userInitiated = true, startInFlight = true)
        )
    }

    @Test
    fun `a start with nothing in flight goes ahead`() {
        assertEquals(
            StartDecision.START,
            decideStart(binderAlive = false, forceStart = false, userInitiated = false, startInFlight = false)
        )
        assertEquals(
            StartDecision.START,
            decideStart(binderAlive = true, forceStart = true, userInitiated = false, startInFlight = false)
        )
    }

    @Test
    fun `a boot request during a start from the unlock joins it`() {
        // Unlock comes first and BOOT_COMPLETED a few seconds later, both unattended and unforced.
        // The second must find the first in flight; so must the binder request and the watchdog.
        for (forced in listOf(false, true)) {
            assertEquals(
                StartDecision.JOIN_IN_FLIGHT,
                decideStart(binderAlive = false, forceStart = forced, userInitiated = false, startInFlight = true)
            )
        }
    }

    @Test
    fun `a start before the unlock that can be made without WorkManager is made in the service`() {
        assertEquals(
            StartDecision.START_LOCKED,
            decideStart(
                binderAlive = false, forceStart = false, userInitiated = false, startInFlight = false,
                userUnlocked = false, canStartLocked = true
            )
        )
    }

    @Test
    fun `a start before the unlock that needs WorkManager waits for the unlock`() {
        assertEquals(
            StartDecision.DEFER_UNTIL_UNLOCK,
            decideStart(
                binderAlive = false, forceStart = false, userInitiated = false, startInFlight = false,
                userUnlocked = false, canStartLocked = false
            )
        )
    }

    @Test
    fun `a binder request before the unlock joins the start the service has under way`() {
        // The request that used to reach WorkManager and take the process down.
        assertEquals(
            StartDecision.JOIN_IN_FLIGHT,
            decideStart(
                binderAlive = false, forceStart = false, userInitiated = false, startInFlight = true,
                userUnlocked = false, canStartLocked = true
            )
        )
    }

    @Test
    fun `a running server is not started again whether or not the phone is unlocked`() {
        assertEquals(
            StartDecision.IGNORE_RUNNING,
            decideStart(
                binderAlive = true, forceStart = false, userInitiated = false, startInFlight = false,
                userUnlocked = false, canStartLocked = true
            )
        )
    }

    @Test
    fun `once unlocked the service's route is not used`() {
        assertEquals(
            StartDecision.START,
            decideStart(
                binderAlive = false, forceStart = false, userInitiated = false, startInFlight = false,
                userUnlocked = true, canStartLocked = true
            )
        )
    }
}
