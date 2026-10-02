package moe.shizuku.manager.shell

import moe.shizuku.manager.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shell's suggestion rules, which are decisions about the shape of the word being typed
 * rather than about the device: a dotted token starting with `android` is a permission, another
 * dotted token is a package, anything else is a command.
 *
 * Worth testing rather than eyeballing, because the interesting ones cannot be reached by typing
 * on a phone: every permission name contains dots, and a keyboard inserts a space after each of
 * them, so the shell is never actually given `android.permission.C` to react to.
 */
class ShellSuggestionsTest {

    private val source = SuggestionSource(
        packages = listOf(
            "Greenify" to "com.oasisfeng.greenify",
            "Settings" to "com.android.settings"
        ),
        permissions = listOf(
            "android.permission.CAMERA",
            "android.permission.READ_LOGS"
        )
    )

    @Test
    fun `a dotted token under the android namespace is a permission`() {
        assertEquals(
            SuggestionKind.PERMISSION,
            ShellSuggestions.kindOfToken("android.permission.C")
        )
    }

    @Test
    fun `any other dotted token is a package`() {
        assertEquals(SuggestionKind.PACKAGE, ShellSuggestions.kindOfToken("com.oasis"))
    }

    @Test
    fun `a bare word is a command`() {
        assertEquals(SuggestionKind.COMMAND, ShellSuggestions.kindOfToken("dump"))
    }

    @Test
    fun `the token is the word at the end`() {
        assertEquals("com.oasis", ShellSuggestions.tokenAt("pm grant com.oasis"))
    }

    @Test
    fun `after a space there is no token yet`() {
        assertEquals("", ShellSuggestions.tokenAt("pm grant "))
    }

    @Test
    fun `inserting replaces the token and leaves the space for the next one`() {
        assertEquals(
            "pm grant com.oasisfeng.greenify ",
            ShellSuggestions.insertInto("pm grant com.oasis", "com.oasisfeng.greenify")
        )
    }

    @Test
    fun `inserting into an empty line just writes it`() {
        assertEquals("pm ", ShellSuggestions.insertInto("", "pm"))
    }

    @Test
    fun `an adb prefix is recognised and dropped`() {
        assertEquals("ls -l", ShellSuggestions.withoutAdbPrefix("adb shell ls -l"))
        assertEquals("id", ShellSuggestions.withoutAdbPrefix("adb exec-out id"))
    }

    @Test
    fun `a bare shell prefix is dropped too`() {
        // The other half of the same habit: on a computer it is `adb shell ps`, and either
        // half gets typed alone. Left alone, `shell ps` would be answered with
        // "shell: not found" by a shell that has no such command.
        assertEquals("ps -A", ShellSuggestions.withoutAdbPrefix("shell ps -A"))
        assertEquals("", ShellSuggestions.withoutAdbPrefix("shell"))

        // Not a prefix when it is part of the command's own name.
        assertNull(ShellSuggestions.withoutAdbPrefix("shellcheck script.sh"))
    }

    @Test
    fun `a device command typed with the computer prefix is the command itself`() {
        // The prefix is a habit, not a choice about where it runs: `adb reboot` on a computer
        // is `reboot` on the device, one command with a word in front of it.
        assertEquals("reboot", ShellSuggestions.withoutAdbPrefix("adb reboot"))
        assertEquals("tcpip 5555", ShellSuggestions.withoutAdbPrefix("adb tcpip 5555"))

        // Nothing prefixed, nothing dropped.
        assertNull(ShellSuggestions.withoutAdbPrefix("ls"))
    }

    @Test
    fun `host-side adb commands are answered with the device equivalent`() {
        assertEquals(R.string.shell_adb_host_devices, ShellHostCommands.hintFor("devices"))
        assertEquals(R.string.shell_adb_host_install, ShellHostCommands.hintFor("install app.apk"))
        assertEquals(R.string.shell_adb_host_transfer, ShellHostCommands.hintFor("push file.apk"))
        assertEquals(R.string.shell_adb_host_generic, ShellHostCommands.hintFor("tcpip 5555"))

        // Everything else is a device command and runs: answering these would be worse than
        // running them.
        assertNull(ShellHostCommands.hintFor("pm list packages"))
        assertNull(ShellHostCommands.hintFor("ps -A"))
        assertNull(ShellHostCommands.hintFor("dumpsys battery"))
    }

    @Test
    fun `a permission is offered by its short name and inserted in full`() {
        val offered = ShellSuggestions.forInput("pm grant com.foo android.permission.CAM", source)
        assertEquals(listOf("CAMERA"), offered.map { it.label })
        assertEquals("android.permission.CAMERA", offered.first().insert)
    }

    @Test
    fun `an app is offered by name and inserted as its package`() {
        val offered = ShellSuggestions.forInput("green", source)
        assertEquals(listOf("Greenify"), offered.map { it.label })
        assertEquals("com.oasisfeng.greenify", offered.first().insert)
    }

    @Test
    fun `a package prefix is answered with packages, in name order`() {
        val offered = ShellSuggestions.forInput("com.", source)
        assertEquals(
            listOf("com.android.settings", "com.oasisfeng.greenify"),
            offered.map { it.insert }
        )
    }

    @Test
    fun `a command prefix is answered with commands`() {
        val offered = ShellSuggestions.forInput("du", source)
        assertEquals(listOf("du", "dumpsys"), offered.map { it.insert })
    }

    @Test
    fun `an empty line offers commands to start from`() {
        val offered = ShellSuggestions.forInput("", source)
        assertEquals("pm", offered.first().insert)
        assertEquals(SuggestionKind.COMMAND, offered.first().kind)
    }
}
