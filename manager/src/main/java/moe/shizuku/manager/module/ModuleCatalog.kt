package moe.shizuku.manager.module

import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A repository in the community's module index, as the index lists it. */
@Serializable
data class ModuleRepo(
    @SerialName("full_name") val fullName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val description: String = "",
    @SerialName("stargazers_count") val stars: Int = 0,
    val language: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val owner: Owner? = null,
    /** The branch a module's `module.prop` is read from when the repository is checked. */
    @SerialName("default_branch") val defaultBranch: String = "",
    // The rest is what the directory publishes beyond GitHub's own fields, and it is what its
    // filters and sort orders are built from: a category, when the app was added, and its topics.
    val category: String = "",
    @SerialName("added_at") val addedAt: String = "",
    val topics: List<String> = emptyList(),
    val release: RepoRelease? = null,
) {
    @Serializable
    data class Owner(
        val login: String = "",
        @SerialName("avatar_url") val avatarUrl: String = "",
    )

    @Serializable
    data class RepoRelease(
        val tag: String = "",
        val name: String = "",
        @SerialName("published_at") val publishedAt: String = "",
        /** The apk of that release, when it has one: what an app is installed from. */
        @SerialName("apk_url") val apkUrl: String = "",
    )

    /** The half of a `owner/name` a list shows, the owner being the less interesting one. */
    val name: String get() = fullName.substringAfter('/')

    val avatarUrl: String? get() = owner?.avatarUrl?.takeIf { it.isNotEmpty() }

    /** When the newest release was published, which is what the site sorts "Latest release" by. */
    val releasedAt: String get() = release?.publishedAt.orEmpty()
}

/** The shape GitHub's repository search answers in. */
@Serializable
private data class RepoSearch(val items: List<ModuleRepo> = emptyList())

/** One file of a release. A module is the one among them that is a zip. */
@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String = "",
    val name: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** The module zip this release offers, when it offers one. */
    val moduleZip: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }

    /** What a row calls this release: its name if it has one, its tag otherwise. */
    val label: String get() = name.ifEmpty { tag }
}

/**
 * Where modules to install are found.
 *
 * Two sources, and neither of them needs a GitHub token. The list of repositories is a plain file
 * over https - the community's own index - so reading it costs nothing and never runs into a rate
 * limit. A repository's releases do come from the GitHub API, which without a token is rate limited
 * to sixty calls an hour: that is a limit on *browsing*, which a person does slowly, and it is
 * reported as what it is rather than hidden behind a token nobody asked for.
 */
object ModuleCatalog {

    /** The community index: repositories worth looking at, in the shape the index publishes. */
    const val INDEX_URL =
        "https://raw.githubusercontent.com/rushiranpise/shizuku-modules/main/data/repos.json"

    /**
     * Public GitHub proxies, tried only after a direct request fails.
     *
     * gh-proxy serves the REST API as well as files; the other two refuse API requests, so they
     * are only used for raw files and release assets. No token or other credential is sent to
     * any of them, and only public GitHub URLs are ever proxied.
     */
    private const val GH_PROXY = "https://gh-proxy.com/"
    private val API_MIRRORS = listOf(GH_PROXY)
    private val FILE_MIRRORS = listOf(
        GH_PROXY,
        "https://ghfast.top/",
        "https://ghproxy.net/"
    )

    /**
     * What a module is, as far as anything on the internet can tell: a repository whose owner put
     * the modules' topic on it.
     *
     * The format's own name for the topic, and the same way the format's own implementations find
     * modules. A topic search is a claim by the author rather than a proof - anyone can add a topic
     * - which is why every answer is checked before it is offered; see [modules].
     */
    const val MODULE_TOPIC = "shevery-modules"

    /**
     * Every topic a module repository is tagged with.
     *
     * The format's own name and this app's two, because an author tags their repository with
     * whichever implementation they wrote it against - a module for one of these is a module for
     * the others, and a list that knew only one of the names would not show the rest at all.
     */
    val MODULE_TOPICS = listOf(MODULE_TOPIC, "shizuku-modules", "shizuku-next-modules")

    /**
     * The modules written for this format, most starred first.
     *
     * Every topic is searched and the answers are merged, because one repository is one module
     * however many of these names are on it ([mergeModules]).
     *
     * Then each answer is checked, and that is the part worth being plain about: a topic is a claim
     * anyone can make, and the claims are wrong often enough to matter - the community's own apps
     * directory carries `shizuku-modules` and holds no module at all. What settles it is the file a
     * module *is*, `module.prop` at the repository's root, read by the same parser the installer
     * uses. A repository that does not answer with one is not a module and is not listed.
     *
     * The search API is unauthenticated here and rate limited to ten calls a minute, which a person
     * browsing a list will never reach. The checks are not API calls at all: a `module.prop` is
     * read from the raw host, which is a file server with no such limit, a few repositories at a
     * time rather than all of them at once.
     */
    suspend fun modules(topics: List<String> = MODULE_TOPICS): List<ModuleRepo> =
        withContext(Dispatchers.IO) {
            val answers = topics.map { topic -> runCatching { searchTopic(topic) } }

            // One topic failing is one topic's worth of modules missing; every one of them failing
            // is the search being unavailable, and that is worth saying rather than showing an
            // empty list.
            if (answers.all { it.isFailure }) throw answers.first().exceptionOrNull() ?: return@withContext emptyList()

            val candidates = mergeModules(*answers.mapNotNull { it.getOrNull() }.toTypedArray())

            candidates.chunked(MODULE_CHECKS_AT_ONCE).flatMap { chunk ->
                coroutineScope {
                    chunk.map { repo -> async { repo.takeIf { isModule(it) } } }.awaitAll()
                }
            }.filterNotNull()
        }

    /**
     * The repositories of [lists], one entry per repository.
     *
     * The same repository carries a topic per platform it was written against, so searching each
     * topic and adding the answers up would otherwise show one module once per topic it happens to
     * be tagged with. The first answer for a repository is the one kept, and a name differing only
     * in case is the same repository: GitHub spells a repository's name one way, but an answer from
     * a search does not have to agree with it.
     */
    internal fun mergeModules(vararg lists: List<ModuleRepo>): List<ModuleRepo> {
        val seen = HashSet<String>()

        return lists.flatMap { it }.filter { repo -> seen.add(repo.fullName.lowercase()) }
    }

    private suspend fun searchTopic(topic: String): List<ModuleRepo> =
        json.decodeFromString<RepoSearch>(
            get("https://api.github.com/search/repositories?q=topic:$topic&sort=stars&per_page=50")
        ).items

    /** Whether [repo] holds a module at its root, asked once per repository per run. */
    private suspend fun isModule(repo: ModuleRepo): Boolean {
        val key = repo.fullName.lowercase()
        verified[key]?.let { return it }

        val holds = holdsModule(repo)
        verified[key] = holds

        return holds
    }

    /**
     * Whether a `module.prop` at the repository's root reads as a module.
     *
     * The file the format names, read by the format's own parser: an id that could have been a
     * directory name and a name to show are the whole of what a repository has to prove to be
     * listed. The branch is the repository's own, with the two conventions tried after it, because
     * a search answer does not always carry one.
     */
    private suspend fun holdsModule(repo: ModuleRepo): Boolean {
        val branches = (listOf(repo.defaultBranch) + listOf("main", "master"))
            .filter { it.isNotBlank() }
            .distinct()

        for (branch in branches) {
            val text = runCatching {
                get("https://raw.githubusercontent.com/${repo.fullName}/$branch/module.prop")
            }.getOrNull() ?: continue

            return ModuleProp.parse(text) != null
        }

        return false
    }

    /** The newest release of [repoFullName], or null when it has never published one. */
    suspend fun latestRelease(repoFullName: String): Release? =
        withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<Release>(get("https://api.github.com/repos/$repoFullName/releases/latest")) }
                .getOrNull()
        }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun index(url: String = INDEX_URL): List<ModuleRepo> =
        withContext(Dispatchers.IO) { json.decodeFromString(get(url)) }

    /** The releases of [repoFullName], newest first, as GitHub lists them. */
    suspend fun releases(repoFullName: String): List<Release> =
        withContext(Dispatchers.IO) {
            json.decodeFromString(get("https://api.github.com/repos/$repoFullName/releases"))
        }

    /**
     * The manifest a module's `update.json` points at.
     *
     * Null for anything that is not one: a module whose update URL answers with an error, or with
     * something that is not an update, simply has no update to offer.
     */
    suspend fun updateManifest(url: String): ModuleUpdate? =
        withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<ModuleUpdate>(get(url)) }.getOrNull()
        }

    /**
     * Hands the body of [url] to [consume] as it arrives, and answers what [consume] answered.
     *
     * [download] returns a module whole, which is a few hundred kilobytes and fine; an app's release
     * is tens of megabytes, and where it has to end up is not a file this app can open - a package
     * being installed is read by the shell, so it belongs in the shell's own directory. Streaming it
     * there through [consume] is what keeps a copy of it from existing at all.
     */
    suspend fun <T> stream(url: String, consume: suspend (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            var failure: Exception? = null

            for (candidate in candidateUrls(url)) {
                val response = try {
                    open(candidate)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    if (!canTryAnother(e)) break
                    continue
                }

                return@withContext response.use { answer ->
                    val body = answer.body
                        ?: throw ModuleCatalogException("The download came back empty.")
                    body.byteStream().use { stream -> consume(stream) }
                }
            }

            throw failure ?: ModuleCatalogException("The download could not be reached.")
        }

    /** The bytes of an asset. A module is a few hundred kilobytes at most, so it is held whole. */
    suspend fun download(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            var failure: Exception? = null

            for (candidate in candidateUrls(url)) {
                try {
                    return@withContext open(candidate).use { response ->
                        response.body?.bytes()
                            ?: throw ModuleCatalogException("The download came back empty.")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    if (!canTryAnother(e)) break
                }
            }

            throw failure ?: ModuleCatalogException("The download could not be reached.")
        }

    private fun get(url: String): String {
        var failure: Exception? = null

        for (candidate in candidateUrls(url)) {
            try {
                return getOnce(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                if (!canTryAnother(e)) break
            }
        }

        throw failure ?: ModuleCatalogException("The request could not be reached.")
    }

    private fun getOnce(url: String): String =
        open(url).use { response ->
            response.body?.string() ?: throw ModuleCatalogException("The answer came back empty.")
        }

    private fun open(url: String): Response {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw HttpStatusException(code, httpMessage(code))
        }

        return response
    }

    /**
     * The direct GitHub URL followed by its mirrors, for a public GitHub URL.
     *
     * The API and file mirrors are separate: ghfast and ghproxy.net serve raw files and release
     * assets but refuse API requests, so an API call is never sent to them.
     */
    internal fun candidateUrls(url: String): List<String> {
        if (!isGitHub(url)) return listOf(url)

        val mirrors = if (isGitHubApi(url)) API_MIRRORS else FILE_MIRRORS
        return listOf(url) + mirrors.map { mirror -> mirror + url }
    }

    private fun isGitHub(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "api.github.com" ||
            host == "github.com" ||
            host == "codeload.github.com" ||
            host.endsWith(".githubusercontent.com")
    }

    private fun isGitHubApi(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() }.getOrNull() == "api.github.com"

    /**
     * A 403 is both a rate limit and a refusal, and this app only asks for public resources, so
     * trying a mirror is worthwhile. A 404 or a bad request will be the same everywhere.
     */
    private fun canTryAnother(e: Exception): Boolean =
        e !is HttpStatusException || e.code !in setOf(400, 401, 404, 422)

    private class HttpStatusException(val code: Int, message: String) :
        ModuleCatalogException(message)

    /** How many repositories are checked at once: enough to be quick, few enough to be polite. */
    private const val MODULE_CHECKS_AT_ONCE = 6

    /** What each repository turned out to hold, so a refresh does not ask the same question twice. */
    private val verified = ConcurrentHashMap<String, Boolean>()

    private const val USER_AGENT = "Shizuku-Next"

    /**
     * What a failed call means to the person who made it.
     *
     * The one worth spelling out is 403: the API says that both for a token that is not good enough
     * and for having asked too often without one, and only the second can happen here.
     */
    private fun httpMessage(code: Int): String = when (code) {
        403, 429 -> "GitHub or its mirror is rate limiting this device. Try again in a little while."
        404 -> "That repository is not there any more."
        else -> "GitHub answered $code."
    }
}

open class ModuleCatalogException(message: String) : Exception(message)
