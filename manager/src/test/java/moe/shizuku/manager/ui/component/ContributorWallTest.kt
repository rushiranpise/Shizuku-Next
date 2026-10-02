package moe.shizuku.manager.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The arithmetic behind the contributor wall.
 *
 * This is the part of the wall that can be checked without a screen, and every mistake in it is a
 * silent one: a bad pack makes faces overlap, a bad limit lets the wall be dragged off its own
 * edge, a bad curve shrinks the wrong faces. None of that throws. It has to be caught by looking,
 * or here.
 *
 * The sizes below are the real ones the canvas passes in - 56dp faces with a 6dp gap - so the
 * layout under test is the layout that ships.
 */
class ContributorWallTest {

    private val avatar = 56f
    private val gap = 6f
    private val spacing = avatar + gap

    // -- packing ---------------------------------------------------------------------------------

    @Test
    fun `a single contributor sits alone at the origin`() {
        val geometry = contributorWallGeometry(1, avatar, gap)

        assertEquals(1, geometry.centers.size)
        assertEquals(Offset.Zero, geometry.centers.single())
        assertEquals(Size(avatar, avatar), geometry.contentSize)
    }

    @Test
    fun `the wall holds as many faces as there are contributors`() {
        for (count in 1..19) {
            assertEquals(count, contributorWallGeometry(count, avatar, gap).centers.size)
        }
    }

    @Test
    fun `faces never overlap`() {
        val centers = contributorWallGeometry(19, avatar, gap).centers

        centers.forEachIndexed { i, a ->
            centers.drop(i + 1).forEach { b ->
                assertTrue(
                    "faces at $a and $b are closer than one face apart",
                    (a - b).getDistance() >= spacing - 0.01f
                )
            }
        }
    }

    @Test
    fun `the first ring is packed before the second is started`() {
        // Six and seven both lay out on one ring - at eight the wall switches to two - so the only
        // difference between them is the last face of that ring, which has to be an outer one.
        val six = contributorWallGeometry(6, avatar, gap).centers
        val seven = contributorWallGeometry(7, avatar, gap).centers

        val added = seven.filterNot { it in six }
        assertEquals(1, added.size)
        assertTrue(
            "the seventh face belongs to the ring, not the middle",
            added.single().getDistance() > spacing - 0.01f
        )
        // And nothing already placed moved, so the wall does not reshuffle as faces are added.
        assertTrue(seven.containsAll(six))
    }

    @Test
    fun `a wall halfway through a ring stays balanced`() {
        // Two rings hold nineteen faces; thirteen stops partway through the second. The rings are
        // generated in mirrored pairs precisely so this still pulls equally on every side, instead
        // of filling one half of the wall and leaving the other sparse.
        val centers = contributorWallGeometry(13, avatar, gap).centers

        assertEquals(centers.count { it.x > 0f }, centers.count { it.x < 0f })
        assertEquals(centers.count { it.y > 0f }, centers.count { it.y < 0f })
        assertEquals(1, centers.count { it == Offset.Zero })
    }

    @Test
    fun `content size grows with the number of faces`() {
        val small = contributorWallGeometry(7, avatar, gap).contentSize
        val large = contributorWallGeometry(19, avatar, gap).contentSize

        assertTrue(large.width > small.width)
        assertTrue(large.height > small.height)
    }

    // -- fitting and panning ---------------------------------------------------------------------

    @Test
    fun `scale never magnifies content past its own fit`() {
        val geometry = contributorWallGeometry(19, avatar, gap)

        assertEquals(1f, geometry.fitScale(Size(10_000f, 10_000f)), 0.0001f)
        // A viewport exactly the size of the content is a scale of one, not one and a bit.
        assertEquals(1f, geometry.fitScale(geometry.contentSize), 0.0001f)
        // Half the room means half the scale.
        assertEquals(0.5f, geometry.fitScale(geometry.contentSize / 2f), 0.0001f)
    }

    @Test
    fun `a wall that fits has nowhere to be dragged`() {
        val geometry = contributorWallGeometry(7, avatar, gap)

        assertEquals(Offset.Zero, geometry.panLimit(geometry.contentSize * 2f, 1f))
    }

    @Test
    fun `a wall wider than its viewport moves by the difference, not the total`() {
        val geometry = contributorWallGeometry(7, avatar, gap)
        val scale = 2f

        val limit = geometry.panLimit(geometry.contentSize, scale)

        assertEquals(
            (geometry.contentSize.width * scale - geometry.contentSize.width) / 2f,
            limit.x,
            0.01f
        )
    }

    @Test
    fun `pan stays inside the wall's own limit`() {
        val limit = Offset(50f, 20f)

        assertEquals(Offset(50f, 20f), constrainWallOffset(Offset(900f, 900f), limit))
        assertEquals(Offset(-50f, -20f), constrainWallOffset(Offset(-900f, -900f), limit))
        assertEquals(Offset(10f, 5f), constrainWallOffset(Offset(10f, 5f), limit))
        // No room on an axis means no movement on that axis, not a drift off the edge.
        assertEquals(Offset.Zero, constrainWallOffset(Offset(30f, 30f), Offset.Zero))
    }

    // -- the edge ---------------------------------------------------------------------------------

    @Test
    fun `a face in the middle of the viewport is left alone`() {
        val viewport = Size(400f, 300f)

        val bubble = wallBubbleTransform(Offset(20f, -10f), viewport)

        assertEquals(Offset(20f, -10f), bubble.position)
        assertEquals(1f, bubble.scale, 0.0001f)
    }

    @Test
    fun `a face at the border is pulled in and shrunk`() {
        val viewport = Size(400f, 300f)
        val edge = Offset(viewport.width / 2f, 0f)

        val bubble = wallBubbleTransform(edge, viewport)

        assertTrue("the face should be pulled towards the middle", bubble.position.x < edge.x)
        assertTrue("the face should be smaller at the border", bubble.scale < 1f)
        assertTrue("the face should not vanish", bubble.scale >= 0.35f)
    }

    @Test
    fun `the pull is monotonic, so no face is dragged back outwards`() {
        val viewport = Size(400f, 300f)
        var previous = 0f

        for (x in 0..300 step 10) {
            val bubble = wallBubbleTransform(Offset(x.toFloat(), 0f), viewport)
            assertTrue("the mapping must not reverse at x=$x", bubble.position.x >= previous - 0.0001f)
            previous = bubble.position.x
        }
    }

    @Test
    fun `the border shrink never reaches zero, however far out a face is`() {
        val viewport = Size(400f, 300f)

        val bubble = wallBubbleTransform(Offset(4_000f, 3_000f), viewport)

        assertTrue(bubble.scale > 0f)
        assertTrue(bubble.position.getDistance() < Offset(4_000f, 3_000f).getDistance())
    }

    // -- drag resistance --------------------------------------------------------------------------

    @Test
    fun `a drag inside the limit is left exactly alone`() {
        val viewport = Size(400f, 600f)
        val limit = Offset(50f, 80f)

        val inside = Offset(20f, -30f)

        assertEquals(inside, resistWallOffset(inside, limit, viewport))
        assertEquals(inside, unresistWallOffset(inside, limit, viewport))
    }

    @Test
    fun `dragging past the edge resists and can never run away`() {
        val viewport = Size(400f, 600f)
        val limit = Offset(50f, 80f)

        val near = resistWallAxis(60f, limit.x, viewport.width)
        val far = resistWallAxis(10_000f, limit.x, viewport.width)

        assertTrue("it should move a little past the limit", near > limit.x)
        assertTrue("but a further drag must add less", near < far)
        // The travel is bounded by a fifth of the viewport, so the wall cannot be pulled off screen.
        assertTrue(far < limit.x + viewport.width * 0.18f + 0.001f)
    }

    @Test
    fun `the stretch is undone exactly when the fingers come back down`() {
        val viewport = Size(400f, 600f)
        val limit = Offset(50f, 80f)

        // Without this, touching a wall that was released mid-stretch would make it jump.
        for (overflow in listOf(1f, 10f, 100f, 1_000f)) {
            val stretched = resistWallAxis(limit.x + overflow, limit.x, viewport.width)
            val restored = unresistWallOffset(Offset(stretched, 0f), limit, viewport)

            assertEquals("overflow $overflow", limit.x + overflow, restored.x, 0.01f)
        }
    }

    @Test
    fun `zooming keeps the point under the fingers where it was`() {
        val centroid = Offset(120f, -40f)

        // Zooming about a point must leave that point alone, or a pinch drags the wall sideways.
        assertEquals(centroid, zoomAround(centroid, centroid, Offset.Zero, 2f))
        assertEquals(centroid, zoomAround(centroid, centroid, Offset.Zero, 0.5f))

        val moved = zoomAround(Offset.Zero, centroid, Offset.Zero, 2f)
        assertEquals(-centroid.x, moved.x, 0.0001f)
    }

    // -- momentum ---------------------------------------------------------------------------------

    @Test
    fun `momentum is only carried over when the fingers really did leave together`() {
        val handoff = WallVelocityHandoff(Offset(1_000f, 0f), uptimeMillis = 5_000L)

        // Released immediately: keep the speed.
        assertEquals(Offset(1_000f, 0f), handoff.velocityAtRelease(5_010L))
        // The last finger paused before lifting: throwing that speed on would fling the wall after
        // a finger was held still.
        assertNull(handoff.velocityAtRelease(5_041L))
        assertNull(handoff.velocityAtRelease(9_000L))
        // A release stamped before the handoff is nonsense and must not be trusted either.
        assertNull(handoff.velocityAtRelease(4_000L))
    }

    // -- the credits themselves -------------------------------------------------------------------

    private fun snapshot(): List<Contributor> = parseContributors(creditsFile().readText())

    private fun creditsFile(): File {
        val candidates = listOf(
            File("src/main/assets/contributors.json"),
            File("manager/src/main/assets/contributors.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("no contributors snapshot (looked in ${candidates.joinToString()})")
    }

    @Test
    fun `the shipped snapshot parses and credits people`() {
        val contributors = snapshot()

        assertTrue("the snapshot is empty", contributors.isNotEmpty())
        contributors.forEach { contributor ->
            assertTrue("a contributor needs a name", contributor.name.isNotBlank())
            assertTrue("a contributor needs commits", contributor.commits > 0)
            // Nothing may come out of the snapshot carrying the word "null": Android's JSON
            // reader answers a JSON null with it, and it then behaves like a name.
            assertNotEquals("a name is not the word null", "null", contributor.name)
            assertNotEquals("a login is not the word null", "null", contributor.login)
            assertNotEquals("an avatar URL is not the word null", "null", contributor.avatarUrl)
        }
    }

    @Test
    fun `the credits are ordered by how much was contributed`() {
        val contributors = snapshot()
        val commits = contributors.map { it.commits }

        assertEquals(commits.sortedDescending(), commits)
        // No name appears twice, which is the whole point of folding commit identities together.
        assertEquals(contributors.size, contributors.map { it.name }.distinct().size)
    }

    @Test
    fun `the wall can hold every credited contributor`() {
        // The wall is a honeycomb, so it has whole rings of places and no others. Anyone past the
        // last ring would silently not be drawn at all.
        val contributors = snapshot()
        val geometry = contributorWallGeometry(contributors.size, avatar, gap)

        assertEquals(contributors.size, geometry.centers.size)
    }

    @Test
    fun `a picture is the account's own URL, or there is no picture`() {
        // Nothing is carried in the app any more, so every picture has to be a URL that can be
        // fetched - and someone with no account has to have none, rather than a broken link.
        snapshot().forEach { contributor ->
            if (contributor.login == null) {
                assertNull("a picture cannot come from an account we do not have", contributor.avatarUrl)
                assertNull(contributor.profileUrl)
            } else {
                assertEquals("https://github.com/${contributor.login}", contributor.profileUrl)
                assertTrue(
                    "${contributor.name} has no picture to fetch",
                    contributor.avatarUrl?.startsWith("https://") == true
                )
            }
        }
    }

    // -- what the generator is allowed to claim ---------------------------------------------------

    @Test
    fun `a language is only claimed where translations were the work`() {
        // Everyone who writes code here also touches the Chinese string files, and bulk updates
        // span every locale at once. Read off paths alone, that made a maintainer look like a
        // translator of two hundred languages, so the generator may only claim a language for
        // someone whose main area is translation.
        snapshot().filter { it.languages.isNotEmpty() }.forEach { contributor ->
            assertTrue(
                "${contributor.name} claims a language without working on translations",
                contributor.areas.contains(ContributionArea.I18N)
            )
        }
    }

    @Test
    fun `language claims are well formed tags and stay few`() {
        val tag = Regex("^[a-z]{2,3}(-[A-Z]{2})?$")

        snapshot().forEach { contributor ->
            assertTrue(
                "${contributor.name} claims ${contributor.languages.size} languages",
                contributor.languages.size <= 3
            )
            contributor.languages.forEach { language ->
                assertTrue("$language is not a language tag", tag.matches(language))
                // English itself is the source language and so is not a translation; Middle English
                // is a separate locale this project does carry, and is.
                val english = language == "en" || language.startsWith("en-")
                assertTrue("$language is the source language", !english)
            }
            assertEquals(
                "${contributor.name} lists a language twice",
                contributor.languages.size,
                contributor.languages.distinct().size
            )
        }
    }

    @Test
    fun `every area is one the wall can name and colour`() {
        // The badges and the summary sentence are built from these, so an area with no label would
        // be an empty chip and a hole in the prose.
        val contributors = snapshot()

        contributors.flatMap { it.areas }.distinct().forEach { area ->
            assertTrue("${area.name} has no label", area.labelRes != 0)
        }
        // Three badges is what fits on one row of the card.
        contributors.forEach {
            assertTrue("${it.name} has too many areas", it.areas.size <= 3)
            assertEquals("${it.name} lists an area twice", it.areas.size, it.areas.distinct().size)
        }
    }

    @Test
    fun `an unknown area is dropped rather than failing the whole snapshot`() {
        // A newer snapshot may name an area this build has never heard of. It must lose a chip, not
        // the entire credits screen - the app is not updated in step with the data.
        val payload = """
            {"contributors":[{"name":"Someone","commits":1,"areas":["APP","TELEPATHY"]}]}
        """.trimIndent()

        val parsed = parseContributors(payload)

        assertEquals(1, parsed.size)
        assertEquals(listOf(ContributionArea.APP), parsed.single().areas)
    }

    @Test
    fun `a snapshot with nothing in it is not mistaken for a wall`() {
        assertEquals(emptyList<Contributor>(), parseContributors("{}"))
        assertEquals(emptyList<Contributor>(), parseContributors("{\"contributors\":[]}"))
    }

    @Test
    fun `a field the generator left empty is absent, not the word null`() {
        // The generator writes an explicit null for a contributor it found no account for, and
        // both kinds of field are then missing. Reading one as the four-letter word "null" gave
        // every contributor without an account the same cache key, so the wall drew the first of
        // them for all of them, and offered the rest a link to github.com/null.
        val payload = """
            {"contributors":[{"name":"Someone","login":null,
            "avatarUrl":null,"commits":3,"areas":[],"languages":[]}]}
        """.trimIndent()

        val parsed = parseContributors(payload).single()

        assertNull(parsed.login)
        assertNull(parsed.avatarUrl)
        assertNull(parsed.profileUrl)
        listOf(parsed.login, parsed.avatarUrl).forEach {
            assertNotEquals("the word null is not an absence", "null", it)
        }
    }

    @Test
    fun `two contributors with a picture each are not one contributor`() {
        // The wall keys its picture cache on what two people do not share, which is now their URL.
        val payload = """
            {"contributors":[
            {"name":"One","login":"one","avatarUrl":"https://example.test/1","commits":2,"areas":[],"languages":[]},
            {"name":"Two","login":"two","avatarUrl":"https://example.test/2","commits":1,"areas":[],"languages":[]}]}
        """.trimIndent()

        val parsed = parseContributors(payload)

        assertEquals(2, parsed.size)
        assertEquals("https://example.test/1", parsed[0].avatarUrl)
        assertEquals("https://example.test/2", parsed[1].avatarUrl)
        assertEquals(2, parsed.map { ContributorAvatars.cacheKey(it, 64) }.distinct().size)
    }

    // -- fetching a picture -------------------------------------------------------------------------

    @Test
    fun `no two contributors are held in the cache under one key`() {
        // This is what the wall got wrong: everyone without a bundled picture was keyed by the
        // word "null", so whichever of them was fetched first was drawn for all of them.
        val contributors = snapshot()

        assertTrue("the snapshot is empty", contributors.isNotEmpty())
        for (size in listOf(64, 128, 256, 512)) {
            val keys = contributors.map { ContributorAvatars.cacheKey(it, size) }
            assertEquals(
                "two contributors share a picture at ${size}px",
                keys.size,
                keys.distinct().size
            )
        }
    }

    @Test
    fun `a field whose value is the word null is read as absent`() {
        // The belt to the brace above: Android's reader hands back the word "null" for a JSON
        // null, and the org.json the tests run against hands back "". Neither is a name, and this
        // only has to hold for one of them to be the difference between a wall of faces and a wall
        // of one face.
        val payload = """
            {"contributors":[{"name":"Someone","login":"null",
            "avatarUrl":"null","commits":1,"areas":[],"languages":[]}]}
        """.trimIndent()

        val parsed = parseContributors(payload).single()

        assertNull(parsed.login)
        assertNull(parsed.avatarUrl)
        assertNull(parsed.profileUrl)
    }

    @Test
    fun `a contributor with no picture at all still keys like a person`() {
        val nobody = Contributor(
            name = "Nobody",
            login = null,
            commits = 1,
            avatarUrl = null,
            areas = emptyList(),
            languages = emptyList()
        )

        assertTrue(ContributorAvatars.cacheKey(nobody, 64).startsWith("Nobody@"))
    }


    @Test
    fun `a GitHub avatar is asked for at the size being drawn`() {
        // The snapshot stores the full-size URL; a wall of faces wants thumbnails of it.
        assertEquals(
            "https://avatars.githubusercontent.com/u/1?v=4&s=64",
            ContributorAvatars.requestedSize("https://avatars.githubusercontent.com/u/1?v=4", 64)
        )
        assertEquals(
            "https://avatars.githubusercontent.com/u/1?v=4&s=256",
            ContributorAvatars.requestedSize("https://avatars.githubusercontent.com/u/1?v=4", 200)
        )
        // With no query at all it still has to become a query, not a path.
        assertEquals(
            "https://avatars.githubusercontent.com/u/1?s=64",
            ContributorAvatars.requestedSize("https://avatars.githubusercontent.com/u/1", 64)
        )
        // And a size already in the URL is replaced rather than appended twice.
        assertEquals(
            "https://avatars.githubusercontent.com/u/1?v=4&s=128",
            ContributorAvatars.requestedSize("https://avatars.githubusercontent.com/u/1?v=4&s=64", 128)
        )
    }

    @Test
    fun `any other host is left exactly as it was`() {
        val url = "https://example.com/a/b.png"

        assertEquals(url, ContributorAvatars.requestedSize(url, 64))
    }

    @Test
    fun `a face is drawn at a size taken from a step, so a pinch does not re-decode it`() {
        assertEquals(32, avatarBucket(1))
        assertEquals(64, avatarBucket(64))
        assertEquals(128, avatarBucket(65))
        assertEquals(512, avatarBucket(5_000))
    }
}
