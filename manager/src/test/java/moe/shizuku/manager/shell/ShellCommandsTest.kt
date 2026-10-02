package moe.shizuku.manager.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's placeholders, which are what makes a list of commands usable rather than
 * readable: `pm grant <package> <permission>` has to become a command before it can run.
 */
class ShellCommandsTest {

    @Test
    fun `placeholders are found in order and once each`() {
        assertEquals(
            listOf("width", "height"),
            ShellCommands.variablesOf("wm size <width>x<height>")
        )
        assertEquals(
            listOf("x1", "y1", "x2", "y2"),
            ShellCommands.variablesOf("input swipe <x1> <y1> <x2> <y2>")
        )
        assertEquals(
            listOf("package"),
            ShellCommands.variablesOf("pm grant <package> <package>")
        )
    }

    @Test
    fun `a command without placeholders has none`() {
        assertTrue(ShellCommands.variablesOf("dumpsys battery").isEmpty())
    }

    @Test
    fun `filling puts the values where the placeholders were`() {
        assertEquals(
            "pm grant com.foo android.permission.CAMERA",
            ShellCommands.filled(
                "pm grant <package> <permission>",
                mapOf("package" to "com.foo", "permission" to "android.permission.CAMERA")
            )
        )
    }

    @Test
    fun `what is not filled in keeps its placeholder`() {
        // Better a command that says what it is still missing than one with a silent gap in it.
        assertEquals(
            "pm grant com.foo <permission>",
            ShellCommands.filled("pm grant <package> <permission>", mapOf("package" to "com.foo"))
        )
        assertEquals(
            "pm grant <package> <permission>",
            ShellCommands.filled(
                "pm grant <package> <permission>",
                mapOf("package" to "  ")
            )
        )
    }

    @Test
    fun `the app variables are the ones worth offering a picker for`() {
        assertTrue(ShellCommands.isPackageVariable("package"))
        assertTrue(ShellCommands.isPackageVariable("pkg"))
        assertTrue(ShellCommands.isPackageVariable("app"))
        assertFalse(ShellCommands.isPackageVariable("permission"))
        assertFalse(ShellCommands.isPackageVariable("width"))
    }

    @Test
    fun `search matches the command, the description and the tags`() {
        assertTrue(ShellCommands.search("force-stop").any { it.command.startsWith("am force-stop") })
        assertTrue(ShellCommands.search("battery").isNotEmpty())
        assertTrue(ShellCommands.search("nothingmatches this").isEmpty())
        assertEquals(ShellCommands.LIBRARY.size, ShellCommands.search("").size)
    }

    @Test
    fun `the library itself is sound`() {
        val commands = ShellCommands.LIBRARY.map { it.command }
        assertEquals("no command is listed twice", commands.size, commands.distinct().size)
        assertTrue(
            "every entry is described",
            ShellCommands.LIBRARY.all { it.description.isNotBlank() && it.tags.isNotEmpty() }
        )
        // Every placeholder has to be one the filling can find again, which is what the regex
        // and the library have to agree on: filling them all in leaves no angle brackets.
        ShellCommands.LIBRARY.forEach { entry ->
            val variables = ShellCommands.variablesOf(entry.command)
            if (variables.isEmpty()) return@forEach
            val filled = ShellCommands.filled(entry.command, variables.associateWith { "VALUE" })
            assertFalse("${entry.command} still has a placeholder: $filled", filled.contains('<'))
            assertFalse(entry.command == filled)
        }
    }
}
