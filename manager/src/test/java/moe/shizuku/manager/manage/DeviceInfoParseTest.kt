package moe.shizuku.manager.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two new readings on the device info screen are text dumps, so the parsing is where they go
 * wrong. The first version of the cpuinfo one asked for a capture group that was never there and
 * took the whole screen down with it; these are the cases that broke it.
 */
class DeviceInfoParseTest {

    @Test
    fun `cpuinfo gives up the load averages`() {
        val cpu = DeviceInfo.cpu(
            """
            Load: 11.51 / 10.51 / 10.1
            CPU usage from 300087ms to 133423ms ago (2026-09-30 20:11:00.111 to 2026-09-30 20:13:46.775):
            """.trimIndent()
        )
        assertEquals(listOf(11.51, 10.51, 10.1), cpu.load)
    }

    @Test
    fun `cpuinfo names the processes without their pid`() {
        val cpu = DeviceInfo.cpu(
            """
            Load: 1.0 / 1.0 / 1.0
              19% 4762/com.android.systemui: 15% user + 3.5% kernel / faults: 189116 minor 659 major
              4.9% 1317/crtc_commit:203: 0% user + 4.9% kernel
              0.3% 9846/moe.shizuku.privileged.api: 0.3% user + 0% kernel
            """.trimIndent()
        )
        assertEquals(
            listOf("com.android.systemui", "crtc_commit:203", "moe.shizuku.privileged.api"),
            cpu.processes.map { it.name }
        )
        assertEquals(19.0, cpu.processes.first().percent, 0.001)
    }

    @Test
    fun `cpuinfo drops the idle and stops at the total`() {
        val cpu = DeviceInfo.cpu(
            """
            Load: 1.0 / 1.0 / 1.0
              19% 4762/com.android.systemui: 15% user + 3.5% kernel
              0% 19558/com.android.samsung.utilityapp: 0% user + 0% kernel
             +0% 10728/kworker/0:3-events: 0% user + 0% kernel
            12% TOTAL: 5.8% user + 5.8% kernel + 0% iowait + 1% irq + 0% softirq
              99% 1/after-the-total: 50% user
            """.trimIndent()
        )
        assertEquals(listOf("com.android.systemui"), cpu.processes.map { it.name })
    }

    @Test
    fun `thermal pairs each name with its own reading`() {
        val thermal = DeviceInfo.thermal(
            """
            aoss-0
            cpu-0-0-0
            battery
            ---
            33000
            37300
            30000
            """.trimIndent()
        )
        assertEquals(3, thermal.total)
        assertEquals(
            listOf("cpu-0-0-0" to 37.3, "aoss-0" to 33.0, "battery" to 30.0),
            thermal.zones.map { it.name to it.temperatureC }
        )
    }

    @Test
    fun `thermal leaves out a sensor that is not a temperature`() {
        // The -273 sentinel is what a radio with nothing attached reports, and the power-supply
        // trip levels read zero: neither is a reading, and a screen showing -273 is lying.
        val thermal = DeviceInfo.thermal(
            """
            cpu-0-0-0
            mmw0
            pm8550-bcl-lvl0
            ---
            37300
            -273000
            0
            """.trimIndent()
        )
        assertEquals(listOf("cpu-0-0-0"), thermal.zones.map { it.name })
        assertEquals(3, thermal.total)
    }

    @Test
    fun `a dump with no separator yields nothing rather than a crash`() {
        val thermal = DeviceInfo.thermal("aoss-0\ncpu-0-0-0\n33000\n")
        assertTrue(thermal.zones.isEmpty())
        assertTrue(!thermal.present)
    }
}
