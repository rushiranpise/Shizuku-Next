package moe.shizuku.manager.start

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a start has to wait for a network, which is the difference between a start that
 * happens on a device with no Wi-Fi and one that sits there until a network appears.
 */
class StartTransportTest {

    @Test
    fun `a classic port with TCP mode on needs no network`() {
        assertFalse(StartTransport.wifiRequired(tcpPort = 5555, tcpMode = true))
    }

    @Test
    fun `TCP mode off closes the port, so the network is needed again`() {
        assertTrue(StartTransport.wifiRequired(tcpPort = 5555, tcpMode = false))
    }

    @Test
    fun `no port means discovery, which means the network`() {
        assertTrue(StartTransport.wifiRequired(tcpPort = -1, tcpMode = true))
        assertTrue(StartTransport.wifiRequired(tcpPort = 0, tcpMode = true))
    }

    @Test
    fun `the classic port is a fallback whenever there is one`() {
        assertEquals(5555, StartTransport.classicPortFallback(5555))
    }

    @Test
    fun `there is nothing to fall back to without a port`() {
        assertNull(StartTransport.classicPortFallback(-1))
        assertNull(StartTransport.classicPortFallback(0))
    }

    @Test
    fun `the experiment does not wait for a network either`() {
        // Waiting would gate the start the setting exists to make possible.
        assertFalse(StartTransport.wifiRequired(tcpPort = -1, tcpMode = false, forceWireless = true))
        assertFalse(StartTransport.wifiRequired(tcpPort = 5555, tcpMode = false, forceWireless = true))
    }
}
