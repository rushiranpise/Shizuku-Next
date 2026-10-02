package moe.shizuku.manager.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rikka.shizuku.server.UidPackages

/**
 * The reading that decides whether a UID's record - and with it the grants of its apps - is kept or
 * thrown away.
 *
 * The case that matters is the one a phone cannot be asked to produce: the packages-for-UID call
 * failing, which is what used to empty a UID's record because an empty answer and a call that never
 * happened were the same thing to the code that read it.
 */
class UidPackagesTest {

    @Test
    fun `a lookup that threw and no installed list says nothing, so the record stays`() {
        assertNull(UidPackages.of(null, null))
    }

    @Test
    fun `a lookup that answered nothing and no installed list means the UID is gone`() {
        assertEquals(emptySet<String>(), UidPackages.of(emptyArray(), null))
    }

    @Test
    fun `an installed list that was read and names the UID stands in for a lookup that threw`() {
        assertEquals(setOf("com.example.app"), UidPackages.of(null, listOf("com.example.app")))
    }

    @Test
    fun `an installed list that was read and names nothing for the UID means it is gone`() {
        assertEquals(emptySet<String>(), UidPackages.of(null, emptyList()))
    }

    @Test
    fun `what the lookup answered stands on its own`() {
        assertEquals(setOf("com.example.app"), UidPackages.of(arrayOf("com.example.app"), emptyList()))
    }

    @Test
    fun `both readings are believed, since either may have been filtered`() {
        assertEquals(
            setOf("com.example.app", "com.example.other"),
            UidPackages.of(arrayOf("com.example.app"), listOf("com.example.other"))
        )
    }

    @Test
    fun `a lookup that answered a filtered nothing is corrected by the installed list`() {
        assertEquals(setOf("com.example.work"), UidPackages.of(emptyArray(), listOf("com.example.work")))
    }
}
