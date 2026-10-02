package moe.shizuku.manager.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading logcat's own output, which is the one part of the capture that can be checked without a
 * device: the lines below are what `logcat -v threadtime` actually writes, including the shapes
 * that are not a log line at all.
 *
 * It matters more than it looks. Everything downstream - the level filter, the tag chips, the
 * crash jump - reads what this produces, and a parser that quietly drops the lines it does not
 * understand would drop exactly the ones somebody opened a log to find: the separators between
 * buffers, and whatever a crash handler prints outside the usual shape.
 */
class LogcatParseTest {

    private fun parse(raw: String) = Logcat.parse(raw, at = 1_000L)

    @Test
    fun `an ordinary line becomes its parts`() {
        val line = parse("10-01 12:34:56.789  1234  5678 I ShizukuManager: the server is running")

        assertEquals('I', line.level)
        assertEquals(1234, line.pid)
        assertEquals("ShizukuManager", line.tag)
        assertEquals("the server is running", line.message)
        assertFalse(line.crash)
    }

    @Test
    fun `every level threadtime prints is carried through`() {
        val levels = mapOf('V' to "V", 'D' to "D", 'I' to "I", 'W' to "W", 'E' to "E", 'F' to "F")

        levels.forEach { (expected, letter) ->
            val line = parse("10-01 12:34:56.789  1  2 $letter Tag: message")
            assertEquals("level $letter", expected, line.level)
        }
    }

    /**
     * A short tag is padded with spaces before the colon, and a long one is not padded at all -
     * both come out as the tag, unadorned. A parser that split on whitespace would give the tag
     * of the first and the first word of the second.
     */
    @Test
    fun `a tag is taken up to the colon, padded or not`() {
        assertEquals("Tag", parse("10-01 12:34:56.789  1  2 I Tag     : short").tag)
        assertEquals(
            "VeryLongTagNameIndeed",
            parse("10-01 12:34:56.789  1  2 I VeryLongTagNameIndeed: message").tag
        )
    }

    /** A message with colons in it keeps all of them: only the first is the separator. */
    @Test
    fun `colons in the message are not separators`() {
        val line = parse("10-01 12:34:56.789  1  2 I Tag: https://example.com:8443/x")
        assertEquals("https://example.com:8443/x", line.message)
    }

    @Test
    fun `an empty message is still a line`() {
        val line = parse("10-01 12:34:56.789  1  2 I Tag: ")
        assertEquals("Tag", line.tag)
        assertEquals("", line.message)
    }

    /**
     * logcat writes separators between its buffers, and they are not log lines. They are kept as
     * themselves rather than dropped, because the boundary between buffers is a real thing to
     * see - it is where the log was rotated, and a reader that hides it makes a gap look like
     * silence.
     */
    @Test
    fun `a line that is not a log line is kept whole`() {
        val line = parse("--------- beginning of main")

        assertEquals("", line.tag)
        assertEquals("--------- beginning of main", line.message)
        assertEquals('I', line.level)
    }

    @Test
    fun `a separator is not called a crash`() {
        assertFalse(parse("--------- beginning of main").crash)
        assertFalse(parse("10-01 12:34:56.789  1  2 I Tag: everything is fine").crash)
    }

    @Test
    fun `the lines that announce a crash are flagged`() {
        val announcing = listOf(
            "FATAL EXCEPTION: main",
            "*** FATAL EXCEPTION IN SYSTEM PROCESS: main",
            "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0",
            "ANR in com.example (com.example/.Main)",
            "--------- beginning of crash",
            "signal 6 (SIGABRT)"
        )

        announcing.forEach { message ->
            assertTrue(message, Logcat.isCrash(message))
        }
    }

    /** `Process: ` is the line a crash report uses to name what died. */
    @Test
    fun `a crash report header is flagged too`() {
        assertTrue(Logcat.isCrash("Process: com.example, PID: 1234"))
    }

    /**
     * A log holds lines that are identical in every field.
     *
     * This is not a curiosity, it is what the list crashed on: an app logging an empty message
     * from several threads inside one millisecond gives lines that agree on the time, the pid, the
     * tag and the text, and a Compose list keyed on those refuses the second one outright.
     */
    @Test
    fun `two readings of the same line are indistinguishable`() {
        val raw = "10-01 12:34:56.789  1871  1871 I DMABUFHEAPS: "

        assertEquals(parse(raw), parse(raw))
    }

    @Test
    fun `every line is given its own identity`() {
        val raw = "10-01 12:34:56.789  1871  1871 I DMABUFHEAPS: "

        val lines = (1..50).map { parse(raw).copy(id = Logcat.nextId()) }

        assertEquals(50, lines.map { it.id }.distinct().size)
    }

    /** A stack frame is not itself the announcement, or every frame would count as a crash. */
    @Test
    fun `a stack frame is not a crash`() {
        assertFalse(Logcat.isCrash("\tat com.example.Main.onCreate(Main.kt:42)"))
        assertFalse(Logcat.isCrash("Caused by: java.lang.NullPointerException"))
    }
}
