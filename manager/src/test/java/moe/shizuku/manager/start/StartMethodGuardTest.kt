package moe.shizuku.manager.start

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wireless debugging is the one start method with an Android version floor. */
class StartMethodGuardTest {

    @Test
    fun `wireless debugging starts at Android 11`() {
        assertFalse(StartMethodGuard.supportsWirelessDebugging(29))
        assertTrue(StartMethodGuard.supportsWirelessDebugging(30))
    }

    @Test
    fun `starting without wifi stops at Android 17`() {
        assertTrue(StartMethodGuard.supportsStartingWithoutWifi(36))
        assertFalse(StartMethodGuard.supportsStartingWithoutWifi(37))
    }
}
