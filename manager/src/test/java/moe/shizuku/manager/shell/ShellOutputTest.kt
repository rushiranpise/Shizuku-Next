package moe.shizuku.manager.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding a line in a few thousand, which is the one thing a `dumpsys` makes necessary. */
class ShellOutputTest {

    @Test
    fun `matches are found left to right and do not overlap`() {
        assertEquals(listOf(0..1, 2..3), ShellOutput.matchRangesOf("aaaa", "aa"))
    }

    @Test
    fun `matching ignores case, which it has to`() {
        assertEquals(listOf(0..2), ShellOutput.matchRangesOf("Hello", "HEL"))
        assertEquals(listOf(6..8), ShellOutput.matchRangesOf("hello World", "wor"))
    }

    @Test
    fun `nothing matches a query that is not there`() {
        assertTrue(ShellOutput.matchRangesOf("hello", "zzz").isEmpty())
    }

    @Test
    fun `a blank query matches nothing rather than everything`() {
        assertTrue(ShellOutput.matchRangesOf("hello", "").isEmpty())
        assertTrue(ShellOutput.matchingLines(listOf("a", "b"), "").isEmpty())
        assertEquals(0, ShellOutput.matchCount(listOf("a", "b"), ""))
    }

    @Test
    fun `lines that match are listed by index`() {
        val lines = listOf("one", "two", "one again")
        assertEquals(listOf(0, 2), ShellOutput.matchingLines(lines, "one"))
        assertEquals(listOf(0, 2), ShellOutput.matchingLines(lines, "ONE"))
        assertTrue(ShellOutput.matchingLines(lines, "three").isEmpty())
    }

    @Test
    fun `the count is occurrences, not lines`() {
        // The line above the answer is usually part of the answer, so a line holding two
        // matches is two steps of the search rather than one.
        assertEquals(3, ShellOutput.matchCount(listOf("one one", "two", "one"), "one"))
    }
}
