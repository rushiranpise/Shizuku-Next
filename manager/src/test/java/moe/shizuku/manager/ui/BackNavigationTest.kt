package moe.shizuku.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a back press does, which is the whole of the navigation now.
 *
 * The window is inclusive: a press exactly two seconds later is still the second press. A tab
 * always goes Home first, and that move is not the exit press; the hint belongs to Home.
 */
class BackNavigationTest {

    @Test
    fun `back from a tab goes home`() {
        assertEquals(BackAction.GO_HOME, backAction(2, 0L, 10_000L))
    }

    @Test
    fun `a tab goes home even after a recent press`() {
        assertEquals(BackAction.GO_HOME, backAction(4, 1_000L, 1_500L))
    }

    @Test
    fun `back on home after a first press exits`() {
        assertEquals(BackAction.EXIT, backAction(0, 1_000L, 2_500L))
    }

    @Test
    fun `back on home after the window shows the hint`() {
        assertEquals(BackAction.SHOW_HINT, backAction(0, 1_000L, 3_001L))
    }

    @Test
    fun `back on home without a previous press shows the hint`() {
        assertEquals(BackAction.SHOW_HINT, backAction(0, 0L, 10_000L))
    }

    @Test
    fun `the window is inclusive`() {
        assertEquals(BackAction.EXIT, backAction(0, 1_000L, 3_000L))
    }
}
