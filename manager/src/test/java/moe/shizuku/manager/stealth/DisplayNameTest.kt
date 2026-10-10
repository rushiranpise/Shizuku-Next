package moe.shizuku.manager.stealth

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.renameHiddenCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a hidden copy is listed under.
 *
 * Two things have to hold. A copy with an empty field has to keep this app's own label, which the
 * manifest holds as a reference to a string resource - so "no name" means leaving that reference
 * alone, not writing an empty string over it. And a name that is given has to be written as itself:
 * not through that resource, which the app's own screens read, and not through the package rename
 * that runs just before it.
 */
class DisplayNameTest {

    /** The id a real string reference would have; the value never matters here. */
    private val appLabelReference = 0x7f0f0000

    private fun manifestWithReferenceLabel(): AndroidManifestBlock =
        AndroidManifestBlock().apply {
            setPackageName("moe.morphe.shizuku.privileged.api")
            getOrCreateApplicationElement()
            setApplicationLabel(appLabelReference)
        }

    @Test
    fun `an empty field leaves the label this app's own`() {
        val manifest = manifestWithReferenceLabel()

        assertFalse(manifest.renameHiddenCopy(null))
        assertFalse(manifest.renameHiddenCopy(""))
        assertFalse(manifest.renameHiddenCopy("   "))

        assertEquals(appLabelReference, manifest.getApplicationLabelReference())
        assertNull(manifest.getApplicationLabelString())
    }

    @Test
    fun `a name is written as a literal of its own`() {
        val manifest = manifestWithReferenceLabel()

        assertTrue(manifest.renameHiddenCopy("Widget"))

        assertEquals("Widget", manifest.getApplicationLabelString())
    }

    @Test
    fun `the name keeps the characters it was given`() {
        // Spaces inside, a case a launcher cares about, and something that is not a package name at
        // all: a name is a name, and the only edit made to it is the trimming of the ends.
        val manifest = manifestWithReferenceLabel()

        assertTrue(manifest.renameHiddenCopy("  My Shizuku  "))

        assertEquals("My Shizuku", manifest.getApplicationLabelString())
    }

    @Test
    fun `the length limit is on the name, not the spaces around it`() {
        assertNull("a".repeat(MAX_DISPLAY_NAME_LENGTH).validateDisplayName())
        assertNull("  ${"a".repeat(MAX_DISPLAY_NAME_LENGTH)}  ".validateDisplayName())

        assertEquals(
            R.string.stealth_error_display_name_too_long,
            "a".repeat(MAX_DISPLAY_NAME_LENGTH + 1).validateDisplayName()
        )
    }

    @Test
    fun `an empty name is not a mistake`() {
        // Blank means the copy keeps the name it has, which is what the field being optional is.
        assertNull("".validateDisplayName())
        assertNull("   ".validateDisplayName())
    }
}
