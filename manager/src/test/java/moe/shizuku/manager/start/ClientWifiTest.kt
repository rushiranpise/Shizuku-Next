package moe.shizuku.manager.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a forced start leaves the hotspot out and waits for Wi-Fi, and when a hotspot is seen to
 * take Wi-Fi down.
 *
 * Both exist for a radio that cannot be a client and a hotspot at once (the FYT 7870 head unit),
 * and both must stay out of the way of a phone whose radio can: that is the phone the forced start
 * was written on, and the one where a hotspot is the whole answer.
 */
class ClientWifiTest {

    @Test
    fun `the hotspot is left out only when wifi is wanted and the hotspot would take it down`() {
        assertTrue(shouldAvoidHotspot(wantsWifi = true, hotspotDisplacesClient = true))
    }

    @Test
    fun `a radio that does both is left to the hotspot`() {
        assertFalse(shouldAvoidHotspot(wantsWifi = true, hotspotDisplacesClient = false))
    }

    @Test
    fun `a platform that cannot say whether its radio does both is left as it was`() {
        assertFalse(shouldAvoidHotspot(wantsWifi = true, hotspotDisplacesClient = null))
    }

    @Test
    fun `wifi the user has off is nothing a hotspot takes away`() {
        assertFalse(shouldAvoidHotspot(wantsWifi = false, hotspotDisplacesClient = true))
        assertFalse(shouldAvoidHotspot(wantsWifi = null, hotspotDisplacesClient = true))
    }

    @Test
    fun `a radio coming up after a boot counts as wifi wanted`() {
        assertEquals(true, wifiWanted(radioOnOrComingOn = true, wifiOnSetting = null))
    }

    @Test
    fun `the toggle counts while a hotspot holds the radio`() {
        // The client reads as off under a hotspot, the toggle stays on.
        assertEquals(true, wifiWanted(radioOnOrComingOn = false, wifiOnSetting = 1))
        // On in airplane mode.
        assertEquals(true, wifiWanted(radioOnOrComingOn = false, wifiOnSetting = 2))
    }

    @Test
    fun `wifi switched off is not wanted`() {
        assertEquals(false, wifiWanted(radioOnOrComingOn = false, wifiOnSetting = 0))
        // Off because airplane mode is on.
        assertEquals(false, wifiWanted(radioOnOrComingOn = false, wifiOnSetting = 3))
        assertEquals(false, wifiWanted(radioOnOrComingOn = false, wifiOnSetting = null))
        assertEquals(false, wifiWanted(radioOnOrComingOn = null, wifiOnSetting = 0))
    }

    @Test
    fun `nothing readable is no answer`() {
        assertNull(wifiWanted(radioOnOrComingOn = null, wifiOnSetting = null))
    }

    @Test
    fun `a client that was on and is off after the hotspot was taken down by it`() {
        assertTrue(displacementObserved(wifiEnabledBefore = true, wifiEnabledNow = false))
    }

    @Test
    fun `a client that stays on was not taken down`() {
        assertFalse(displacementObserved(wifiEnabledBefore = true, wifiEnabledNow = true))
    }

    @Test
    fun `a client that was only coming up never marks the device`() {
        // Still enabling reads as off by itself; that is not the hotspot's doing.
        assertFalse(displacementObserved(wifiEnabledBefore = false, wifiEnabledNow = false))
    }

    @Test
    fun `an unknown reading never marks the device`() {
        assertFalse(displacementObserved(wifiEnabledBefore = null, wifiEnabledNow = false))
        assertFalse(displacementObserved(wifiEnabledBefore = true, wifiEnabledNow = null))
    }
}
