package moe.shizuku.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the tabs were walked in, which is the order the back button walks back out.
 *
 * Getting this wrong is not a cosmetic bug: a list that grew while somebody pressed back would
 * walk between two tabs for ever and never reach Home, which is the one place the app leaves.
 */
class TabHistoryTest {

    @Test
    fun `a tab opened from another is added to the end`() {
        assertEquals(listOf(0, 1), afterSettling(listOf(0), 1))
        assertEquals(listOf(0, 1, 4), afterSettling(listOf(0, 1), 4))
    }

    @Test
    fun `the tab already open is not a second visit`() {
        assertEquals(listOf(0, 1), afterSettling(listOf(0, 1), 1))
    }

    @Test
    fun `landing on the tab before this one is the way back, not a visit`() {
        assertEquals(listOf(0, 1), afterSettling(listOf(0, 1, 4), 1))
        assertEquals(listOf(0), afterSettling(listOf(0, 1), 0))
    }

    @Test
    fun `walking back reaches Home and leaves nothing to pop`() {
        var visited = listOf(0)
        for (page in listOf(1, 2, 3)) visited = afterSettling(visited, page)
        assertEquals(listOf(0, 1, 2, 3), visited)

        for (page in listOf(2, 1, 0)) visited = afterSettling(visited, page)
        assertEquals(listOf(0), visited)
    }

    @Test
    fun `a tab reached again from a different one is a visit of its own`() {
        assertEquals(listOf(0, 1, 2, 0), afterSettling(listOf(0, 1, 2), 0))
    }
}
