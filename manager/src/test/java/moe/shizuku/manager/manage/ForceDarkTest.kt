package moe.shizuku.manager.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The force-dark engine is shell strings, and a shell string is where a feature like this goes
 * quietly wrong: a switch named wrongly is a switch that is never read, and nothing fails.
 */
class ForceDarkTest {

    @Test
    fun `every known switch is written, in both directions`() {
        val on = ForceDark.switches(true)
        assertTrue(on.contains("setprop debug.hwui.force_dark true"))
        assertTrue(on.contains("settings put global force_dark_mode_on 1"))
        assertTrue(on.contains("settings put secure hwui_force_dark 1"))
        assertTrue(on.contains("settings put secure aosp_force_dark_mode 1"))
        assertTrue(on.contains("settings put secure op_force_dark_entire_world 1"))

        val off = ForceDark.switches(false)
        assertTrue(off.contains("setprop debug.hwui.force_dark false"))
        assertTrue(off.contains("settings put global force_dark_mode_on 0"))
        assertTrue(off.contains("settings put secure hwui_force_dark 0"))
        assertTrue(off.contains("settings put secure aosp_force_dark_mode 0"))
        assertTrue(off.contains("settings put secure op_force_dark_entire_world 0"))
    }

    @Test
    fun `the switches go out as one line, because they are one round trip`() {
        assertTrue(!ForceDark.switches(true).contains("\n"))
    }

    /**
     * `cmd uimode night` takes words and the setting stores numbers, and the two numberings are the
     * part that is easy to get backwards: 2 is dark, 1 is light, and 0 is the schedule.
     */
    @Test
    fun `the stored night mode becomes the word the command wants`() {
        assertEquals("yes", ForceDark.nightModeArgument("2"))
        assertEquals("no", ForceDark.nightModeArgument("1"))
        assertEquals("auto", ForceDark.nightModeArgument("0"))
        assertEquals("yes", ForceDark.nightModeArgument(" 2 "))
    }

    @Test
    fun `a night mode that was never set is left on the schedule rather than guessed`() {
        assertEquals("auto", ForceDark.nightModeArgument("null"))
        assertEquals("auto", ForceDark.nightModeArgument(""))
    }
}
