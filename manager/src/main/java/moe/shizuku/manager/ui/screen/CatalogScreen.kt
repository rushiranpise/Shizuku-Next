package moe.shizuku.manager.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import moe.shizuku.manager.ui.component.AppAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.files.PrivilegedFiles
import moe.shizuku.manager.install.AppInstall
import moe.shizuku.manager.module.ModuleCatalog
import moe.shizuku.manager.module.ModuleCatalogException
import moe.shizuku.manager.module.ModuleRepo
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.cardOutline
import moe.shizuku.manager.utils.CustomTabsHelper

/** What a catalog lists. */
enum class CatalogKind {
    /** Modules, found by the topic their authors put on them. */
    MODULES,

    /** Everything in the community's index of repositories. */
    APPS,
}

/** Their order options, in their order, each with the comparison it means. */
private enum class CatalogSort(val label: Int, val order: Comparator<ModuleRepo>) {
    UPDATED(R.string.catalog_sort_updated, latestFirst { it.updatedAt }),
    STARS(R.string.catalog_sort_stars, compareByDescending { it.stars }),
    NAME(R.string.catalog_sort_name, compareBy { it.name.lowercase() }),
    RELEASED(R.string.catalog_sort_released, latestFirst { it.releasedAt }),
    ADDED(R.string.catalog_sort_added, latestFirst { it.addedAt }),
}

/**
 * How recently an app must have changed, which is their own "Updated" menu: three windows and any
 * time at all, the last spelled as a day count of none.
 */
private enum class UpdatedWithin(val label: Int, val days: Int) {
    ANY(R.string.catalog_updated_any, 0),
    LAST_7(R.string.catalog_updated_last_7, 7),
    LAST_30(R.string.catalog_updated_last_30, 30),
    LAST_90(R.string.catalog_updated_last_90, 90),
}

/** Newest first, with an app that has no such date at all last rather than first. */
private fun latestFirst(date: (ModuleRepo) -> String): Comparator<ModuleRepo> =
    compareBy<ModuleRepo> { date(it).isEmpty() }.thenByDescending { date(it) }

/**
 * Whether a stamp from the directory is within the last [days] days, where none means any time.
 *
 * The stamp is parsed by hand: the app runs on Android 7, where java.time is not available without
 * desugaring, and SimpleDateFormat is what the rest of this app reads dates with anyway.
 */
private fun withinDays(timestamp: String, days: Int, now: Long): Boolean {
    if (days <= 0) return true

    val at = isoMillis(timestamp) ?: return false

    return now - at <= days * 24L * 60L * 60L * 1000L
}

/** An ISO 8601 UTC stamp in milliseconds, or null when it is empty or not one. */
private fun isoMillis(timestamp: String): Long? {
    if (timestamp.isBlank()) return null

    // GitHub's stamps carry whole seconds and the directory's added_at carries milliseconds, so
    // the fraction is trimmed and the one pattern reads both.
    val whole = timestamp.substringBefore('.').substringBefore('Z') + "Z"

    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(whole)
            ?.time
    }.getOrNull()
}

/**
 * One of the site's three controls: a button carrying the value in force, and its options behind it.
 *
 * A menu rather than a row of chips, because that is what the site has: the chips this screen used
 * to show were its own invention, and there are nine options between the three of these.
 */
@Composable
private fun <T> CatalogFilter(
    label: String,
    text: @Composable (T) -> String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        FilledTonalButton(onClick = { open = true }) {
            // The name of the control rather than the value in force: three of these sit in a row,
            // and three answers spelled out do not fit. What each is set to is in its menu.
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    trailingIcon = { Checkbox(checked = option == selected, onCheckedChange = null) },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * Repositories as a list of cards, for modules and for apps alike.
 *
 * One screen for both because they are the same list of the same kind of thing - a repository with
 * an avatar, a paragraph and a release - and the only differences are where the list comes from and
 * what a card offers: a module's card installs, an app's card opens on GitHub. The search, the sort
 * and the language filter are the app's own, since a list of over a thousand repositories is not
 * something to read in order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    kind: CatalogKind,
    installedKeys: Set<String>,
    onBack: () -> Unit,
    onInstalled: () -> Unit,
) {
    // The modules list is their Module Repo screen; this screen is only the apps list now. The
    // kind is kept so both callers read the same either way.
    if (kind == CatalogKind.MODULES) {
        ModuleRepoScreen(
            installedKeys = installedKeys,
            onBack = onBack,
            onInstalled = onInstalled
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var repos by remember { mutableStateOf<List<ModuleRepo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // The directory site's three controls, with its own defaults: most recently updated, every
    // category, any age.
    var sort by remember { mutableStateOf(CatalogSort.UPDATED) }
    var category by remember { mutableStateOf<String?>(null) }
    var updatedWithin by remember { mutableStateOf(UpdatedWithin.ANY) }

    var searching by remember { mutableStateOf(false) }
    var openRepo by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var installOutcome by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind) {
        runCatching {
            ModuleCatalog.index()
        }
            .onSuccess { repos = it }
            .onFailure { error = it.message }
    }

    val all = repos ?: emptyList()

    // Every category the list carries, which is what the site's own category menu offers. The
    // few languages this screen used to filter by were this app's invention, not the site's.
    val categories = remember(all) {
        all.map { it.category }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val shown = remember(all, query, sort, category, updatedWithin) {
        val now = System.currentTimeMillis()

        all.filter { repo ->
            // Their search box matches what theirs does: what the app is called, what it says
            // about itself, the topics on it, its language, and who wrote it.
            val matchesQuery = query.isBlank() ||
                repo.fullName.contains(query, ignoreCase = true) ||
                repo.description.contains(query, ignoreCase = true) ||
                repo.language.orEmpty().contains(query, ignoreCase = true) ||
                repo.owner?.login.orEmpty().contains(query, ignoreCase = true) ||
                repo.topics.any { it.contains(query, ignoreCase = true) }

            val matchesCategory = category == null || repo.category == category
            val matchesAge = withinDays(repo.updatedAt, updatedWithin.days, now)

            matchesQuery && matchesCategory && matchesAge
        }.sortedWith(sort.order)
    }

    /**
     * Installs the newest apk of [repo], from the release the index carries.
     *
     * The download is streamed straight into the shell's own directory, the same staging the
     * Installer screen uses for a file somebody picked. That is not a detail: `pm` is run by the
     * shell, so a package sitting in this app's own storage is one the shell cannot open - and the
     * installer checks a package by walking its archive before installing it, so the app's own
     * cache comes back as "an archive that could not be read" rather than as an install. The copy
     * is removed once the install is over, whoever it ends.
     */
    fun installApk(repo: ModuleRepo, url: String) {
        installing = repo.fullName

        scope.launch {
            val name = url.substringAfterLast('/').ifBlank { "${repo.name}.apk" }
            var staged: String? = null

            val outcome = runCatching {
                val path = ModuleCatalog.stream(url) { body -> AppInstall.stage(body, name) }
                    ?: throw ModuleCatalogException(context.getString(R.string.catalog_install_failed))

                staged = path
                AppInstall.install(path, AppInstall.Options())
            }.getOrElse {
                AppInstall.Outcome.Failed(it.message ?: context.getString(R.string.catalog_install_failed))
            }

            staged?.let { path -> runCatching { PrivilegedFiles.delete(path) } }

            // What the install said, in its own words: a package the platform refused says why,
            // and that is the whole of what the person needs to know.
            installOutcome = when (outcome) {
                is AppInstall.Outcome.Done -> context.getString(R.string.catalog_apk_installed, repo.name)
                is AppInstall.Outcome.Failed -> outcome.message
            }
            installing = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                // The search the module screens have: the title itself becomes the field when the
                // magnifier is pressed, rather than a field sitting in the list under it.
                if (searching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().televisionTextInput(TextFieldDefaults.shape),
                        placeholder = { Text(stringResource(R.string.catalog_search)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                } else {
                    Text(stringResource(R.string.catalog_apps_title))
                }
            },
            windowInsets = watchTopAppBarInsets(),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                if (searching) {
                    IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                } else {
                    IconButton(onClick = { searching = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.catalog_search)
                        )
                    }
                }
            }
        )

        if (repos == null && error == null) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            item {
                // The site's three controls, one row: what each is set to is inside its menu, so
                // the buttons carry the name of the control and nothing else.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        CatalogFilter(
                            label = stringResource(R.string.catalog_filter_sort),
                            text = { stringResource(it.label) },
                            options = CatalogSort.entries,
                            selected = sort,
                            onSelect = { sort = it }
                        )
                    }

                    item {
                        CatalogFilter(
                            label = stringResource(R.string.catalog_filter_category),
                            text = { it ?: stringResource(R.string.catalog_filter_all_categories) },
                            options = listOf(null) + categories,
                            selected = category,
                            onSelect = { category = it }
                        )
                    }

                    item {
                        CatalogFilter(
                            label = stringResource(R.string.catalog_filter_updated),
                            text = { stringResource(it.label) },
                            options = UpdatedWithin.entries,
                            selected = updatedWithin,
                            onSelect = { updatedWithin = it }
                        )
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            items(shown.size) { index ->
                val repo = shown[index]

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        // Invisible on the black theme without it: see Modifier.cardOutline.
                        .cardOutline(),
                    onClick = {
                        openRepo = if (openRepo == repo.fullName) null else repo.fullName
                    }
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(url = repo.avatarUrl)

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp)
                            ) {
                                Text(
                                    text = repo.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = repo.owner?.login.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Icon(
                                imageVector = if (openRepo == repo.fullName) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                },
                                contentDescription = null
                            )
                        }

                        if (repo.description.isNotEmpty()) {
                            Text(
                                text = repo.description,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 10.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(text = " ${repo.stars}", style = MaterialTheme.typography.labelMedium)
                            repo.language?.let { language ->
                                Text(text = "  -  $language", style = MaterialTheme.typography.labelMedium)
                            }
                            repo.updatedAt.take(10).takeIf { it.isNotEmpty() }?.let { updated ->
                                Text(text = "  -  $updated", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (openRepo == repo.fullName) {
                            // The newest release comes from the index this list is, which is where
                            // the site reads it too: no second request per card is needed to learn
                            // what a repository's latest build is, and what it is installed from is
                            // the apk of that release rather than a module's zip.
                            val release = repo.release
                            val apkUrl = release?.apkUrl?.takeIf { it.isNotBlank() }

                            Text(
                                text = stringResource(
                                    R.string.catalog_latest_release,
                                    release?.let { it.tag.ifEmpty { it.name } }
                                        ?.ifEmpty { stringResource(R.string.catalog_no_release) }
                                        ?: stringResource(R.string.catalog_no_release)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )

                            Text(
                                text = apkUrl?.substringAfterLast('/')
                                    ?: stringResource(R.string.catalog_no_apk),
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (apkUrl != null) {
                                    PillButton(
                                        enabled = installing != repo.fullName,
                                        onClick = { installApk(repo, apkUrl) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = stringResource(
                                                if (installing == repo.fullName) {
                                                    R.string.modules_catalog_installing
                                                } else {
                                                    R.string.modules_catalog_install
                                                }
                                            ),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }

                                PillButtonQuiet(onClick = {
                                    CustomTabsHelper.launchUrlOrCopy(context, repo.htmlUrl)
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.catalog_view_github),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }

                        }
                    }
                }
            }
            }

            ScrollToTopButton(
                listState = listState,
                bottomPadding = 16.dp,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }

    installOutcome?.let { message ->
        AppAlertDialog(
            onDismissRequest = { installOutcome = null },
            title = { Text(stringResource(R.string.catalog_apps_title)) },
            text = { Text(message) },
            confirmButton = {
                PillButton(onClick = { installOutcome = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

/** A repository's avatar, fetched once and kept for as long as the row is. */
@Composable
private fun Avatar(url: String?) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect

        bitmap = runCatching {
            val bytes = ModuleCatalog.download(url)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    val image = bitmap

    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        // A placeholder of the same size, so the row does not move when the image lands.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
