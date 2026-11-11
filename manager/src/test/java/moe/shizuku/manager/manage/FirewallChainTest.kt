package moe.shizuku.manager.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the chain's own answer is read.
 *
 * The tile shows exactly what this says, and it is the half of the switch that runs without a
 * device: a ROM is free to answer something else entirely, and "something else" has to come out as
 * unknown rather than as off, or a firewall that cannot be asked would tell the user it is off
 * while every block is still in force.
 */
class FirewallChainTest {

    @Test
    fun `the platform's two answers are read as the two states`() {
        assertEquals(true, FirewallChain.parse("chain:enabled"))
        assertEquals(false, FirewallChain.parse("chain:disabled"))
    }

    @Test
    fun `a trailing newline is not a different answer`() {
        assertEquals(true, FirewallChain.parse("chain:enabled\n"))
        assertEquals(false, FirewallChain.parse("  chain:disabled  "))
    }

    @Test
    fun `the firewall needs Android 13`() {
        assertFalse(FirewallChain.supportsFirewall(32))
        assertTrue(FirewallChain.supportsFirewall(33))
        assertTrue(FirewallChain.supportsFirewall(36))
    }

    @Test
    fun `a rule can only be read back from Android 14`() {
        assertFalse(FirewallChain.canReadRule(33))
        assertTrue(FirewallChain.canReadRule(34))
        assertTrue(FirewallChain.canReadRule(36))
    }

    @Test
    fun `anything else is unknown, not off`() {
        // No server, and so no output at all.
        assertNull(FirewallChain.parse(null))
        assertNull(FirewallChain.parse(""))
        assertNull(FirewallChain.parse("   "))

        // A ROM that does not have the verb answers with the command's own complaint.
        assertNull(FirewallChain.parse("Unknown command: get-chain3-enabled"))
        assertNull(FirewallChain.parse("Error: chain not found"))
    }
}
