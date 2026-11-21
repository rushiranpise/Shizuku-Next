package moe.shizuku.manager.ui.screen

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.ui.component.RefreshBox
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.TelevisionRefreshButton
import moe.shizuku.manager.ui.component.televisionEnterOnRight
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleCatalog
import moe.shizuku.manager.module.ModuleRepo
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.Release
import moe.shizuku.manager.ui.component.cardOutline
import moe.shizuku.manager.utils.CustomTabsHelper

/**
 * Where modules to install are found, drawn the way KernelSU-Next draws its Module Repo screen.
 *
 * Their shape is kept deliberately: the title turns into the search field when the magnifier is
 * pressed, the order is their four choices behind the sort icon, the list pulls to refresh, and a
 * card carries the repository's picture faintly behind its name, who wrote it, what it is, its
 * label row and a Latest version line with their cloud button beside it. Tapping a card opens the
 * repository, which is what their card does too.
 *
 * What differs is only where the list comes from: this app finds modules by the topic their authors
 * put on them, so there is no repository JSON to manage and no repository manager dialog behind an
 * edit icon. The card still shows the same facts, because a repository gives the same ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleRepoScreen(
    installedKeys: Set<String>,
    onBack: () -> Unit,
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var repos by remember { mutableStateOf<List<ModuleRepo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(RepoSort.load()) }
    var sortDialog by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var releases by remember { mutableStateOf<Map<String, Release?>>(emptyMap()) }
    var installing by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        error = null
        runCatching { ModuleCatalog.modules() }
            .onSuccess { repos = it }
            .onFailure { error = it.message }
    }

    LaunchedEffect(Unit) { load() }

    val all = repos ?: emptyList()

    val shown = remember(all, query, sort) {
        val matched = if (query.isBlank()) {
            all
        } else {
            all.filter { repo ->
                repo.fullName.contains(query, ignoreCase = true) ||
                    repo.name.contains(query, ignoreCase = true) ||
                    repo.description.contains(query, ignoreCase = true) ||
                    (repo.owner?.login?.contains(query, ignoreCase = true) == true)
            }
        }

        matched.sortedWith(sort.order())
    }

    val refresh: () -> Unit = {
        refreshing = true
        scope.launch {
            load()
            refreshing = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                // Their search bar: the title itself becomes the field, rather than a field
                // sitting in the list below it.
                if (searching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().televisionTextInput(TextFieldDefaults.shape),
                        placeholder = { Text(stringResource(R.string.modules_search)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                } else {
                    Text(stringResource(R.string.modules_catalog))
                }
            },
            windowInsets = watchTopAppBarInsets(),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                TelevisionRefreshButton(refreshing, refresh)
                if (searching) {
                    IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                } else {
                    IconButton(onClick = { searching = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.modules_search)
                        )
                    }

                    // Their four orders, one at a time, in their own words.
                    Box {
                        IconButton(onClick = { sortDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Sort,
                                contentDescription = stringResource(R.string.modules_sort)
                            )
                        }
                        DropdownMenu(
                            expanded = sortDialog,
                            onDismissRequest = { sortDialog = false }
                        ) {
                            RepoSortChoice(R.string.modules_sort_a_to_z, sort.aToZ) {
                                sort = sort.copy(
                                    aToZ = true, zToA = false,
                                    starsLowToHigh = false, starsHighToLow = false
                                ).saved()
                            }
                            RepoSortChoice(R.string.modules_sort_z_to_a, sort.zToA) {
                                sort = sort.copy(
                                    aToZ = false, zToA = true,
                                    starsLowToHigh = false, starsHighToLow = false
                                ).saved()
                            }
                            RepoSortChoice(
                                R.string.modules_repo_sort_stars_low_to_high,
                                sort.starsLowToHigh
                            ) {
                                sort = sort.copy(
                                    aToZ = false, zToA = false,
                                    starsLowToHigh = true, starsHighToLow = false
                                ).saved()
                            }
                            RepoSortChoice(
                                R.string.modules_repo_sort_stars_high_to_low,
                                sort.starsHighToLow
                            ) {
                                sort = sort.copy(
                                    aToZ = false, zToA = false,
                                    starsLowToHigh = false, starsHighToLow = true
                                ).saved()
                            }
                        }
                    }
                }
            }
        )

        when {
            // Their loading screen: a spinner in the middle, not a bar under the title.
            repos == null && error == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // Their error screen: what happened, and a way to ask again.
            all.isEmpty() && error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.modules_repo_error),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = { scope.launch { load() } },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text(stringResource(R.string.modules_repo_retry))
                    }
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val listState = rememberLazyListState()
                    RefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = refresh
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(shown.size) { index ->
                            val repo = shown[index]

                            ModuleRepoCard(
                                repo = repo,
                                release = releases[repo.fullName],
                                installed = ModuleSettings.installedMatch(installedKeys, repo),
                                installing = installing == repo.fullName,
                                onLoadRelease = {
                                    if (!releases.containsKey(repo.fullName)) {
                                        scope.launch {
                                            val release = runCatching {
                                                ModuleCatalog.latestRelease(repo.fullName)
                                            }.getOrNull()
                                            releases = releases + (repo.fullName to release)
                                        }
                                    }
                                },
                                onOpen = { CustomTabsHelper.launchUrlOrCopy(context, repo.htmlUrl) },
                                onInstall = { zipUrl ->
                                    installing = repo.fullName
                                    scope.launch {
                                        runCatching {
                                            AdbModuleManager.install(
                                                context,
                                                ModuleCatalog.download(zipUrl),
                                                source = repo.fullName
                                            )
                                        }
                                            .onSuccess { onInstalled() }
                                            .onFailure {
                                                Toast.makeText(context, it.message, Toast.LENGTH_LONG)
                                                    .show()
                                            }
                                        installing = null
                                    }
                                }
                            )
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
        }
    }
}

/**
 * One repository, drawn the way their card is.
 *
 * The picture sits behind the text at 18 percent and fades into the surface towards the bottom,
 * which is their card exactly; the label row and the Latest version line with the install button
 * under it are theirs too. A repository with no picture is the same card without the wash.
 */
@Composable
private fun ModuleRepoCard(
    repo: ModuleRepo,
    release: Release?,
    installed: Boolean,
    installing: Boolean,
    onLoadRelease: () -> Unit,
    onOpen: () -> Unit,
    onInstall: (String) -> Unit,
) {
    // Their card carries the version, so the version is fetched when the card appears rather
    // than held back behind a tap. The callback is idempotent, and a card that scrolls back
    // into view asks for a release already in hand for nothing.
    LaunchedEffect(repo.fullName) { onLoadRelease() }

    val zip = release?.moduleZip

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            // Invisible on the black theme without it: see Modifier.cardOutline.
            .cardOutline()
            // The install button sits inside the card, which is a button of its own.
            .televisionEnterOnRight()
            .clickable(onClick = onOpen)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            RepoBanner(url = repo.avatarUrl)

            Column(modifier = Modifier.padding(22.dp, 18.dp, 22.dp, 12.dp)) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.modules_repo_by_author,
                        repo.owner?.login.orEmpty()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (repo.description.isNotEmpty()) {
                    Text(
                        text = repo.description,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // First in the row, because it is the one fact that decides whether the card is
                    // worth reading at all: this module is already on the phone.
                    if (installed) {
                        RepoLabel(
                            text = stringResource(R.string.modules_catalog_installed),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    RepoLabel(
                        text = repo.stars.toString(),
                        icon = Icons.Filled.Star,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    repo.language?.takeIf { it.isNotEmpty() }?.let { language ->
                        RepoLabel(text = language)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.modules_repo_latest_version),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = when {
                                release == null -> stringResource(R.string.modules_repo_loading)
                                release.tag.isNotEmpty() -> release.tag
                                else -> stringResource(R.string.catalog_no_release)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = { zip?.let { onInstall(it.url) } },
                        modifier = Modifier.defaultMinSize(52.dp, 32.dp),
                        enabled = zip != null && !installing,
                        shape = ButtonDefaults.textShape,
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = stringResource(
                                if (installing) {
                                    R.string.modules_catalog_installing
                                } else {
                                    R.string.modules_catalog_install
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }


            }
        }
    }
}

/**
 * The picture behind a card, the way their banner is: full bleed, faint, and gone by the bottom.
 *
 * A BoxScope extension because it is drawn against the card's own box rather than sized by it - a
 * card sized by its picture would be as tall as the picture, which is not how theirs behaves.
 */
@Composable
private fun BoxScope.RepoBanner(url: String?) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect

        bitmap = runCatching {
            val bytes = ModuleCatalog.download(url)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    val image = bitmap ?: return

    Box(modifier = Modifier.matchParentSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surface),
            contentScale = ContentScale.Crop,
            alpha = 0.18f
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                    )
                )
        )
    }
}

/** One of a card's labels: a rounded chip carrying a fact, their shape and spacing. */
@Composable
private fun RepoLabel(
    text: String,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The order the find-modules list is shown in: one of their four orders at a time, kept under keys
 * of its own so it does not disturb the installed list's order.
 */
private data class RepoSort(
    val aToZ: Boolean = true,
    val zToA: Boolean = false,
    val starsLowToHigh: Boolean = false,
    val starsHighToLow: Boolean = false,
) {
    fun order(): Comparator<ModuleRepo> = when {
        zToA -> compareByDescending { it.name.lowercase() }
        starsLowToHigh -> compareBy { it.stars }
        starsHighToLow -> compareByDescending { it.stars }
        else -> compareBy { it.name.lowercase() }
    }

    fun saved(): RepoSort {
        ModuleSettings.setSortFlag(ModuleSettings.REPO_SORT_A_TO_Z, aToZ)
        ModuleSettings.setSortFlag(ModuleSettings.REPO_SORT_Z_TO_A, zToA)
        ModuleSettings.setSortFlag(ModuleSettings.REPO_SORT_STARS_LOW_TO_HIGH, starsLowToHigh)
        ModuleSettings.setSortFlag(ModuleSettings.REPO_SORT_STARS_HIGH_TO_LOW, starsHighToLow)

        return this
    }

    companion object {
        fun load(): RepoSort = RepoSort(
            aToZ = ModuleSettings.sortFlag(ModuleSettings.REPO_SORT_A_TO_Z, true),
            zToA = ModuleSettings.sortFlag(ModuleSettings.REPO_SORT_Z_TO_A),
            starsLowToHigh = ModuleSettings.sortFlag(ModuleSettings.REPO_SORT_STARS_LOW_TO_HIGH),
            starsHighToLow = ModuleSettings.sortFlag(ModuleSettings.REPO_SORT_STARS_HIGH_TO_LOW)
        )
    }
}

/** One of their sort options: the word, and a tick when it is the one in force. */
@Composable
private fun RepoSortChoice(label: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        trailingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
        onClick = onClick
    )
}
