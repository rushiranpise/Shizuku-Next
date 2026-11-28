package moe.shizuku.manager.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the no-network fallback writes the persistent ADB port, and when it takes it back.
 *
 * The port is the one way to start without any network on a device where a hotspot cannot start
 * the server (the FYT 7870 head unit: one radio, SELinux permissive). Everywhere else - above all a
 * phone whose radio holds a hotspot next to Wi-Fi and whose SELinux is enforced - the fallback must
 * never write it.
 */
class AdbPortPersistenceDecisionTest {

    private fun persist(
        withoutNetwork: Boolean = true,
        tcpMode: Boolean = true,
        fallbackDevice: Boolean = true,
        declined: Boolean = false,
        refusedOnThisBuild: Boolean = false,
        current: Int? = null,
        wanted: Int = 5555
    ) = shouldAutoPersistPort(withoutNetwork, tcpMode, fallbackDevice, declined, refusedOnThisBuild, current, wanted)

    @Test
    fun `a head unit starting without wifi gets the port`() {
        assertTrue(persist())
        assertTrue(persist(current = 0))
    }

    @Test
    fun `a device the fallback is not for never gets it`() {
        assertFalse(persist(fallbackDevice = false))
    }

    @Test
    fun `a radio that drops wifi for a hotspot needs the classic port`() {
        assertTrue(classicPortFallback(hotspotDisplacesClient = true, selinuxNotEnforced = false))
    }

    @Test
    fun `selinux not enforced needs the classic port without asking a hotspot first`() {
        // The head unit whose platform claims its radio does both, or will not say.
        assertTrue(classicPortFallback(hotspotDisplacesClient = false, selinuxNotEnforced = true))
        assertTrue(classicPortFallback(hotspotDisplacesClient = null, selinuxNotEnforced = true))
    }

    @Test
    fun `a phone whose radio does both and whose selinux is enforced is left alone`() {
        assertFalse(classicPortFallback(hotspotDisplacesClient = false, selinuxNotEnforced = false))
        assertFalse(classicPortFallback(hotspotDisplacesClient = null, selinuxNotEnforced = false))
    }

    @Test
    fun `only the start without a network asks for it`() {
        assertFalse(persist(withoutNetwork = false))
    }

    @Test
    fun `without tcp mode every start would close it again`() {
        assertFalse(persist(tcpMode = false))
    }

    @Test
    fun `a port the user switched off stays off`() {
        assertFalse(persist(declined = true))
    }

    @Test
    fun `a build that refused it is not asked again`() {
        assertFalse(persist(refusedOnThisBuild = true))
    }

    @Test
    fun `a port already there is not written again`() {
        assertFalse(persist(current = 5555))
    }

    @Test
    fun `no port to keep is nothing to write`() {
        assertFalse(persist(wanted = 0))
    }

    @Test
    fun `the fallback takes its port back when the method changes or tcp mode goes`() {
        assertTrue(shouldClearAutoPort(autoApplied = true, withoutNetwork = false, tcpMode = true))
        assertTrue(shouldClearAutoPort(autoApplied = true, withoutNetwork = true, tcpMode = false))
    }

    @Test
    fun `the fallback keeps its port while the start still needs it`() {
        assertFalse(shouldClearAutoPort(autoApplied = true, withoutNetwork = true, tcpMode = true))
    }

    @Test
    fun `a port the user switched on is never the fallback's to take`() {
        assertFalse(shouldClearAutoPort(autoApplied = false, withoutNetwork = false, tcpMode = false))
    }

    @Test
    fun `a refused write is a refusal even when the property was never set`() {
        // Android 11 (issue #285): setprop of adbd's own property is refused, so the property stays
        // unset and getprop prints nothing. That empty read is not "unknown", it is the refusal.
        assertEquals(
            AdbPortPersistence.Outcome.REFUSED,
            writeOutcome(exitCode = 1, readBack = null, port = 5555)
        )
    }

    @Test
    fun `a write that never ran is unknown`() {
        assertEquals(
            AdbPortPersistence.Outcome.UNKNOWN,
            writeOutcome(exitCode = -1, readBack = null, port = 5555)
        )
    }

    @Test
    fun `a write that ran but cannot be read back is unknown`() {
        assertEquals(
            AdbPortPersistence.Outcome.UNKNOWN,
            writeOutcome(exitCode = 0, readBack = null, port = 5555)
        )
    }

    @Test
    fun `a value that reads back is applied`() {
        assertEquals(
            AdbPortPersistence.Outcome.APPLIED,
            writeOutcome(exitCode = 0, readBack = 5555, port = 5555)
        )
    }

    @Test
    fun `a value that did not take is refused`() {
        assertEquals(
            AdbPortPersistence.Outcome.REFUSED,
            writeOutcome(exitCode = 0, readBack = 0, port = 5555)
        )
    }
}
