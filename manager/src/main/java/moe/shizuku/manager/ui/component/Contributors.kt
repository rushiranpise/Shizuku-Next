package moe.shizuku.manager.ui.component

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Which part of the project a contributor's commits actually touched.
 *
 * The wall shows a few of these as badges, and they double as the wording of the person's summary,
 * so a badge and a sentence can never disagree. They are not a claim about what someone can do -
 * they are read off the paths their commits changed, by `ci/update-contributors.py`.
 */
internal enum class ContributionArea(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val tint: Color
) {
    APP(R.string.contributor_area_app, Icons.Outlined.PhoneAndroid, Color(0xFF3DDC84)),
    SERVER(R.string.contributor_area_server, Icons.Outlined.Dns, Color(0xFF4C8DFF)),
    ADB(R.string.contributor_area_adb, Icons.Outlined.Terminal, Color(0xFFB388FF)),
    BUILD(R.string.contributor_area_build, Icons.Outlined.DeveloperMode, Color(0xFFF9A825)),
    I18N(R.string.contributor_area_i18n, Icons.Outlined.Translate, Color(0xFFEF6C00)),
    DOCS(R.string.contributor_area_docs, Icons.Outlined.MenuBook, Color(0xFF26A69A))
}

/**
 * Someone credited on the wall.
 *
 * [login] and [avatarUrl] come from the GitHub account behind a commit identity, where there is
 * one. Neither is guessed - the obvious guess for one contributor now belongs to a different
 * person, and a credit that opens a stranger's profile is worse than a credit with no link - so
 * where there is no account the wall draws the person's initial instead.
 *
 * The picture is the account's own, at the URL it lives at; nothing is carried in the app, so what
 * is drawn is whatever that person is using now rather than a copy from whenever the credits were
 * last generated.
 */
internal data class Contributor(
    val name: String,
    val login: String?,
    val commits: Int,
    val avatarUrl: String?,
    val areas: List<ContributionArea>,
    /** BCP-47 tags, and only for someone whose work is mostly translation. */
    val languages: List<String>
) {
    val profileUrl: String? get() = login?.let { "https://github.com/$it" }
}

/**
 * The credits snapshot, and how it is kept current.
 *
 * The file is generated from this repository's history by `ci/update-contributors.py` and published
 * to a data branch by the contributors workflow, so the wall follows the repository without needing
 * a release: a new contributor shows up on their own. Three copies are in play, in order of
 * preference - the fetched copy, the last one fetched, and the one shipped in the APK - and a
 * failure at any step simply falls back to the next, because a credits screen is never worth
 * failing over.
 */
internal object ContributorCredits {

    /** Shipped in the APK, so the wall is populated before anything has been fetched. */
    private const val SNAPSHOT_ASSET = "contributors.json"

    /**
     * Where the contributors workflow publishes the regenerated snapshot.
     *
     * A branch rather than a release asset or a gist: it needs no extra setup, it is fetched
     * straight from raw.githubusercontent.com, and the file's own history is the record of how the
     * credits changed.
     */
    private const val SNAPSHOT_URL =
        "https://raw.githubusercontent.com/rushiranpise/Shizuku-Next/contributors-data/contributors.json"

    /** Checking once a day is often enough for a list that changes when somebody commits. */
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private fun cacheFile(context: Context) = File(context.filesDir, SNAPSHOT_ASSET)

    /** The snapshot to draw right now: what was fetched last, or what was shipped. */
    fun initial(context: Context): List<Contributor> =
        cached(context) ?: bundled(context)

    fun cached(context: Context): List<Contributor>? =
        cacheFile(context)
            .takeIf { it.isFile }
            ?.let { runCatching { parseContributors(it.readText()) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    fun bundled(context: Context): List<Contributor> =
        runCatching {
            context.assets.open(SNAPSHOT_ASSET).bufferedReader().use { it.readText() }
        }.mapCatching { parseContributors(it) }.getOrDefault(emptyList())

    /**
     * Fetch the published snapshot, unless one was fetched recently.
     *
     * Returns the new list when it arrived, and null when there was nothing to do or nothing came
     * back - the caller keeps whatever it was already drawing.
     */
    suspend fun refresh(context: Context, force: Boolean = false): List<Contributor>? =
        withContext(Dispatchers.IO) {
            val cache = cacheFile(context)
            if (!force && cache.isFile &&
                System.currentTimeMillis() - cache.lastModified() < REFRESH_INTERVAL_MS
            ) {
                return@withContext null
            }

            val payload = runCatching { download() }.getOrNull() ?: return@withContext null
            val fetched = runCatching { parseContributors(payload) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return@withContext null

            // Written to a sibling and renamed, so a download cut short can never leave a half
            // file behind for the next launch to read.
            runCatching {
                val staging = File(cache.parentFile, "$SNAPSHOT_ASSET.tmp")
                staging.writeText(payload)
                if (!staging.renameTo(cache)) {
                    cache.writeText(payload)
                    staging.delete()
                }
            }
            fetched
        }

    private fun download(): String {
        val connection = (URL(SNAPSHOT_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            // raw.githubusercontent.com will serve a cached copy, which is exactly what is wanted
            // for a file that changes weekly.
            setRequestProperty("User-Agent", "Shizuku-Next/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("snapshot fetch returned ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Read a snapshot.
 *
 * Unknown area names are dropped rather than treated as an error, so a newer snapshot that adds one
 * still renders on an older app instead of taking the wall down with it.
 */
internal fun parseContributors(payload: String): List<Contributor> {
    val array = JSONObject(payload).optJSONArray("contributors") ?: return emptyList()

    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optionalText("name") ?: continue

            val areas = item.optJSONArray("areas").toStringList().mapNotNull { key ->
                ContributionArea.entries.firstOrNull { it.name == key }
            }
            val languages = item.optJSONArray("languages").toStringList()

            add(
                Contributor(
                    name = name,
                    login = item.optionalText("login"),
                    commits = item.optInt("commits"),
                    avatarUrl = item.optionalText("avatarUrl"),
                    areas = areas,
                    languages = languages
                )
            )
        }
    }
}

/**
 * A text field of the snapshot, or null where the generator wrote a null.
 *
 * Not `optString`: Android answers a JSON null with the *word* "null", which is then a value
 * everywhere it is used - every contributor without a bundled picture was cached under one key
 * made of that word, so the first of them was drawn for all of them, and one without an account
 * was offered a link to github.com/null. (The org.json the tests run against answers "" instead,
 * which is why this has to be written out rather than left to the library.)
 */
private fun JSONObject.optionalText(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key).trim()
    // The word itself is not a value either, whichever library answered with it.
    return if (value.isEmpty() || value.equals("null", ignoreCase = true)) null else value
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotEmpty()) add(value)
        }
    }
}

/**
 * What to say about a contributor, composed from what the generator found.
 *
 * Prose built out of data rather than a sentence per person, so a new contributor is described the
 * moment the generator sees them and there is no list of strings to keep in step with the list of
 * people. The language names come from the platform, which means they are already written in the
 * reader's own language and need no translation of their own.
 */
@Composable
internal fun contributorSummary(contributor: Contributor): String {
    val locale = LocalConfiguration.current.locales[0]
    val areas = contributor.areas.map { stringResource(it.labelRes) }.joinToString(", ")
    val languages = contributor.languages
        .map { Locale.forLanguageTag(it).getDisplayName(locale) }
        .joinToString(", ")

    return when {
        languages.isEmpty() && areas.isEmpty() -> stringResource(R.string.contributor_summary_default)
        languages.isEmpty() -> stringResource(R.string.contributor_summary_code, areas)
        areas.isEmpty() -> stringResource(R.string.contributor_summary_translations, languages)
        else -> stringResource(R.string.contributor_summary_code_translations, areas, languages)
    }
}
