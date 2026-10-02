package moe.shizuku.manager.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two parts of the activity browser that are logic rather than plumbing: what order the list
 * comes out in, and the line that remembers somebody's assistant while it is swapped away. The
 * second is the one that must never be wrong - getting it wrong is how a phone ends up with no
 * assistant and nobody able to say which one it had.
 */
class ActivitiesTest {

    private fun activity(
        name: String,
        exported: Boolean = false,
        launcher: Boolean = false
    ) = Activities.Activity(
        packageName = "com.example",
        name = name,
        label = null,
        exported = exported,
        launcher = launcher
    )

    @Test
    fun `the launcher entry comes first, then the exported ones, then the rest`() {
        val ordered = Activities.order(
            listOf(
                activity("com.example.Internal"),
                activity("com.example.Exported", exported = true),
                activity("com.example.MainActivity", exported = true, launcher = true),
                activity("com.example.Another", exported = true)
            )
        )
        assertEquals(
            listOf(
                "com.example.MainActivity",
                "com.example.Another",
                "com.example.Exported",
                "com.example.Internal"
            ),
            ordered.map { it.name }
        )
    }

    @Test
    fun `names are ordered case-insensitively, so a lowercase class is not sorted last`() {
        val ordered = Activities.order(
            listOf(
                activity("com.example.zebra"),
                activity("com.example.Alpha")
            )
        )
        assertEquals(listOf("com.example.Alpha", "com.example.zebra"), ordered.map { it.name })
    }

    @Test
    fun `an activity is known by its last name, and by its label when it has one`() {
        val withoutLabel = activity("com.example.Settings\$Advanced")
        assertEquals("Settings\$Advanced", withoutLabel.shortName)
        assertEquals("Settings\$Advanced", withoutLabel.title)

        val withLabel = withoutLabel.copy(label = "Advanced")
        assertEquals("Advanced", withLabel.title)
    }

    @Test
    fun `the assistant that was there comes back as it was, empty or absent`() {
        val both = "com.google.android.googlequicksearchbox/.GsaVoiceInteractionService"
        assertEquals(both to both, Activities.parseBackup(Activities.backupLine(both, both)))

        // Nothing set is not the same as set to nothing, and the line has to keep them apart.
        assertEquals(null to null, Activities.parseBackup(Activities.backupLine(null, null)))
        assertEquals(both to null, Activities.parseBackup(Activities.backupLine(both, null)))
        assertEquals(null to both, Activities.parseBackup(Activities.backupLine(null, both)))

        val empty = ""
        assertEquals(empty to empty, Activities.parseBackup(Activities.backupLine(empty, empty)))
    }

    @Test
    fun `a line that is not a saved assistant is not read as one`() {
        assertNull(Activities.parseBackup(null))
        assertNull(Activities.parseBackup(""))
        assertNull(Activities.parseBackup("only-one-value"))
        assertNull(Activities.parseBackup("a\u001Fb\u001Fc"))
    }
}
