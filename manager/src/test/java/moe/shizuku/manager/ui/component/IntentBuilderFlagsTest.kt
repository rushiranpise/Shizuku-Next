package moe.shizuku.manager.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flags are a table of numbers written by hand, which is exactly the kind of table that ends up
 * with the same number twice - and two chips sharing one value is a chip that appears to do nothing
 * because the other one is doing it.
 */
class IntentBuilderFlagsTest {

    @Test
    fun `no two flags share a value`() {
        val values = Flags.map { it.value }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `every flag is a single bit`() {
        // A flag that is not one bit set would be several flags at once, and a table entry that
        // toggles more than it says is worse than a missing one.
        Flags.forEach { flag ->
            assertTrue(
                "0x${flag.value.toString(16)} is not a single bit",
                flag.value != 0 && (flag.value and (flag.value - 1)) == 0
            )
        }
    }

    @Test
    fun `there are enough of them to be worth a row`() {
        assertTrue(Flags.size >= 4)
    }
}
