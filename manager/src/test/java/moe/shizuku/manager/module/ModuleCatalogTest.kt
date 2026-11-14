package moe.shizuku.manager.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the module list is put together out of GitHub's answers.
 *
 * One repository is one module however many topics it carries, and each topic is a separate search -
 * so the same module arrives more than once, and a list that added the answers up would show it once
 * per topic. These hold the merge to one entry per repository, and hold the topics to the names
 * GitHub actually matches.
 */
class ModuleCatalogTest {

    private fun repo(fullName: String, stars: Int = 0) = ModuleRepo(fullName = fullName, stars = stars)

    @Test
    fun `one repository tagged for two platforms is listed once`() {
        val merged = ModuleCatalog.mergeModules(
            listOf(repo("author/module", stars = 5), repo("other/one")),
            listOf(repo("author/module", stars = 5), repo("other/two"))
        )

        assertEquals(
            listOf("author/module", "other/one", "other/two"),
            merged.map { it.fullName }
        )
    }

    @Test
    fun `a name differing only in case is the same repository`() {
        val merged = ModuleCatalog.mergeModules(
            listOf(repo("Author/Module")),
            listOf(repo("author/module"))
        )

        assertEquals(1, merged.size)
        assertEquals("Author/Module", merged.first().fullName)
    }

    @Test
    fun `the first answer for a repository is the one kept`() {
        val merged = ModuleCatalog.mergeModules(
            listOf(repo("author/module", stars = 3)),
            listOf(repo("author/module", stars = 99))
        )

        assertEquals(3, merged.single().stars)
    }

    @Test
    fun `finding nothing is an empty list rather than a failure`() {
        assertTrue(ModuleCatalog.mergeModules(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `the topics are the format's name and this app's, spelled the way GitHub matches`() {
        assertTrue(ModuleCatalog.MODULE_TOPICS.contains(ModuleCatalog.MODULE_TOPIC))
        assertTrue(ModuleCatalog.MODULE_TOPICS.contains("shizuku-modules"))
        assertTrue(ModuleCatalog.MODULE_TOPICS.contains("shizuku-next-modules"))

        // A topic is lower case with hyphens on GitHub: a name typed with a capital, or with a
        // space in it, finds nothing at all and would look like a list that had no modules.
        assertTrue(ModuleCatalog.MODULE_TOPICS.all { topic -> topic == topic.lowercase() })
        assertTrue(ModuleCatalog.MODULE_TOPICS.none { topic -> topic.contains(' ') })
    }

    @Test
    fun `GitHub API calls add only an API capable mirror`() {
        val url = "https://api.github.com/search/repositories?q=shizuku&per_page=1"

        assertEquals(
            listOf(url, "https://gh-proxy.com/$url"),
            ModuleCatalog.candidateUrls(url)
        )
    }

    @Test
    fun `GitHub files add the file mirrors`() {
        val url = "https://raw.githubusercontent.com/author/module/main/module.prop"

        assertEquals(
            listOf(
                url,
                "https://gh-proxy.com/$url",
                "https://ghfast.top/$url",
                "https://ghproxy.net/$url"
            ),
            ModuleCatalog.candidateUrls(url)
        )
    }

    @Test
    fun `non GitHub URLs are left alone`() {
        val url = "https://example.com/module.prop"

        assertEquals(listOf(url), ModuleCatalog.candidateUrls(url))
    }
}
