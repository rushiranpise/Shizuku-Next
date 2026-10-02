package moe.shizuku.manager.ui.screen

import android.content.pm.ApplicationInfo
import android.util.Log
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.AppConstants
import android.content.pm.PackageInfo
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.manage.InstalledPackages
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.AppStatusChips
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ChipEmphasis
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.StatusChip
import moe.shizuku.manager.ui.component.stripHtmlTags
import moe.shizuku.manager.utils.ShizukuStateMachine

enum class SortOrder {
    /** Most recently installed first: when the app first appeared on the device. */
    RECENTLY_INSTALLED,

    /** Most recently updated first: when the APK last changed. */
    RECENTLY_UPDATED,

    ALPHABETICAL
}

/**
 * The Shizuku half of the filter: whether this app is allowed to use the service.
 *
 * The other half - what kind of app it is - is the same list every other app list in the app
 * uses, so the words are the same wherever the question is asked. Filtering by kind was on the
 * app-ops and Labs lists long before it was here, and a list of the same apps with a different
 * set of chips is how two screens end up disagreeing about what "hidden" means.
 */
enum class AppFilter {
    ALL,

    /** Shizuku's permission is granted to these. */
    GRANTED,

    /** It is not the apps you can still hand it to. */
    REVOKED
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(bottomPadding: Dp, active: Boolean = true, warmUp: Boolean = false) {
    val context = LocalContext.current
    val pm = context.packageManager

    var all by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(SortOrder.RECENTLY_INSTALLED) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var version by remember { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(AppFilter.ALL) }
    // The kind of app, from the same set of filters the app-ops and Labs lists use.
    var kind by remember { mutableStateOf(ManageFilter.ALL) }
    var pendingBatch by remember { mutableStateOf<Boolean?>(null) }
    // Set to the state every listed app should end up in, once the user confirms.
    var pendingToggleAll by remember { mutableStateOf<Boolean?>(null) }
    var permissionLimited by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(ShizukuStateMachine.isRunning()) }
    // Pull-to-refresh. While this is set the list is being read again behind the rows
    // already on screen, so the gesture's own indicator stands in for the page spinner
    // and the list never blinks back to blank.
    var refreshing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // The list comes from the server, so it has to be re-read when that comes or goes
    // (and the answer while it is down is "there is nothing to list", not an empty page).
    DisposableEffect(Unit) {
        val listener: (ShizukuStateMachine.State) -> Unit = {
            running = it == ShizukuStateMachine.State.RUNNING
        }
        ShizukuStateMachine.addListener(listener)
        onDispose { ShizukuStateMachine.removeListener(listener) }
    }

    // Read once per version: either when this is the tab on screen, or once the app has
    // settled after launch, so that the swipe towards it stays cheap and the first visit does
    // not wait for the server to be asked about every package.
    var loadedFor by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    LaunchedEffect(running, active, warmUp, refreshKey) {
        if (!active && !warmUp) return@LaunchedEffect
        // A pull starts from a list that is already on screen, so it is the one case that
        // skips the guard: reading again is the whole point of the gesture. Every other
        // trigger still waits until its version is the one that was read.
        if (!refreshing && loadedFor == (running to version)) return@LaunchedEffect

        loading = true
        Log.d(AppConstants.TAG, "Apps: reading the authorised apps")
        all = withContext(Dispatchers.IO) {
            runCatching {
                AuthorizationManager.getPackages(exclude = listOf(context.packageName))
            }.getOrDefault(emptyList())
        }
        loadedFor = running to version
        loading = false
        refreshing = false
    }

    // Both sets are worked out once per loaded list, off the main thread: the granted one is
    // a server round trip per app, and the chips want its size before anyone picks that
    // filter. Recomputed when a toggle changes something (version), which is what keeps the
    // counts honest.
    var grantedNames by remember { mutableStateOf(emptySet<String>()) }
    var launcherless by remember { mutableStateOf(emptySet<String>()) }
    var derivedFor by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    LaunchedEffect(all, version, active, warmUp) {
        if ((!active && !warmUp) || all.isEmpty()) {
            grantedNames = emptySet()
            launcherless = emptySet()
            return@LaunchedEffect
        }
        if (derivedFor == (running to version)) return@LaunchedEffect
        val (granted, withoutLauncher) = withContext(Dispatchers.IO) {
            val granted = all.filter {
                val uid = it.applicationInfo?.uid ?: return@filter false
                runCatching { AuthorizationManager.granted(it.packageName, uid) }.getOrDefault(false)
            }.map { it.packageName }.toSet()

            // An app with no launcher entry is what "hidden" means: it is in the list, but
            // never in the app drawer. The leanback category counts as an entry on TV.
            val withoutLauncher = InstalledPackages.launcherless(context, all)

            granted to withoutLauncher
        }
        grantedNames = granted
        launcherless = withoutLauncher
    }

    // What kind of app it is, which needs no server: the flags and the record are on the app
    // already, so these three are worked out from the list the package manager just handed over.
    val systemPackages = remember(all) {
        all.filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { it.packageName }.toSet()
    }
    val userPackages = remember(all, systemPackages) {
        all.filter { it.applicationInfo != null && it.packageName !in systemPackages }
            .map { it.packageName }.toSet()
    }
    val disabledPackages = remember(all) {
        all.filter {
            val ai = it.applicationInfo ?: return@filter false
            !ai.enabled || (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        }.map { it.packageName }.toSet()
    }

    val shown = remember(
        all, query, sortOrder, filter, kind,
        userPackages, systemPackages, disabledPackages, launcherless, grantedNames
    ) {
        val q = query.trim()
        val byKind = when (kind) {
            ManageFilter.ALL -> all
            ManageFilter.USER -> all.filter { it.packageName in userPackages }
            ManageFilter.SYSTEM -> all.filter { it.packageName in systemPackages }
            ManageFilter.DISABLED -> all.filter { it.packageName in disabledPackages }
            ManageFilter.HIDDEN -> all.filter { it.packageName in launcherless }
        }
        val byFilter = when (filter) {
            AppFilter.ALL -> byKind
            AppFilter.GRANTED -> byKind.filter { it.packageName in grantedNames }
            AppFilter.REVOKED -> byKind.filter { it.packageName !in grantedNames }
        }
        val filtered = if (q.isBlank()) {
            byFilter
        } else {
            byFilter.filter {
                val label = runCatching { it.applicationInfo?.loadLabel(pm)?.toString() ?: "" }.getOrDefault("")
                label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
        when (sortOrder) {
            // The two timestamps the package manager keeps: when it was installed, and when
            // the APK was last replaced. They differ for anything that has been updated.
            SortOrder.RECENTLY_INSTALLED -> filtered.sortedByDescending { it.firstInstallTime }
            SortOrder.RECENTLY_UPDATED -> filtered.sortedByDescending { it.lastUpdateTime }
            SortOrder.ALPHABETICAL -> filtered.sortedBy {
                runCatching { it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() }
                    .getOrDefault(it.packageName)
            }
        }
    }

    val selectionMode = selected.isNotEmpty()
    val scope = rememberCoroutineScope()
    // Remembered for the whole screen rather than per batch, so a second batch replaces
    // the first offer to undo instead of stacking snackbars.
    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * Applies a batch and offers to take it back.
     *
     * Flipping every listed app is one tap and one confirmation, which is a lot of
     * permission to change by accident, so the snackbar that follows carries Undo and
     * puts the same apps back the way they were. A batch the server refused is not
     * reversible and gets no offer to undo it.
     */
    fun applyBatch(grant: Boolean, apps: List<PackageInfo>) {
        scope.launch {
            val limited = withContext(Dispatchers.IO) {
                var limited = false
                for (pi in apps) {
                    val result = runCatching {
                        if (grant) AuthorizationManager.grant(pi.packageName, pi.applicationInfo!!.uid)
                        else AuthorizationManager.revoke(pi.packageName, pi.applicationInfo!!.uid)
                    }
                    if (result.exceptionOrNull() is SecurityException) limited = true
                }
                limited
            }
            if (limited) permissionLimited = true
            version++
            if (limited) return@launch

            val result = snackbarHostState.showSnackbar(
                message = context.getString(
                    if (grant) R.string.app_management_batch_granted
                    else R.string.app_management_batch_revoked,
                    apps.size
                ),
                actionLabel = context.getString(R.string.action_undo),
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                // Back the other way, over the same apps.
                withContext(Dispatchers.IO) {
                    for (pi in apps) {
                        runCatching {
                            if (grant) AuthorizationManager.revoke(pi.packageName, pi.applicationInfo!!.uid)
                            else AuthorizationManager.grant(pi.packageName, pi.applicationInfo!!.uid)
                        }
                    }
                }
                version++
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_selected_count, selected.size)) },
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                navigationIcon = {
                    IconButton(onClick = { selected = emptySet() }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                },
                actions = {
                    PillButtonQuiet(onClick = { selected = shown.map { it.packageName }.toSet() }) {
                        Text(stringResource(R.string.app_management_action_select_all))
                    }
                    PillButtonQuiet(onClick = { pendingBatch = true }) {
                        Text(stringResource(R.string.app_management_action_grant))
                    }
                    PillButtonQuiet(onClick = { pendingBatch = false }) {
                        Text(stringResource(R.string.app_management_action_revoke))
                    }
                }
            )
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_apps)) },
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                actions = {
                    // Long-press and Select all exist for picking individual apps; this is
                    // the one-tap version for when every listed app should be flipped.
                    if (shown.isNotEmpty()) {
                        PillButtonQuiet(onClick = {
                            scope.launch {
                                val allGranted = withContext(Dispatchers.IO) {
                                    shown.all {
                                        runCatching {
                                            AuthorizationManager.granted(
                                                it.packageName,
                                                it.applicationInfo!!.uid
                                            )
                                        }.getOrDefault(false)
                                    }
                                }
                                pendingToggleAll = !allGranted
                            }
                        }) { Text(stringResource(R.string.app_management_toggle_all)) }
                    }
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Filled.Sort, contentDescription = null)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.app_management_sort_last_added)) },
                            onClick = { sortOrder = SortOrder.RECENTLY_INSTALLED; sortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.app_management_sort_updated)) },
                            onClick = { sortOrder = SortOrder.RECENTLY_UPDATED; sortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.app_management_sort_alphabetical)) },
                            onClick = { sortOrder = SortOrder.ALPHABETICAL; sortMenu = false }
                        )
                    }
                }
            )
        }

        // A search field, not just a text field: Material 3 gives search boxes the fully
        // rounded shape and a quieter outline, so this reads as "search" at a glance
        // instead of as a box someone put a magnifier in.
        // Same two rows as the app-ops and Labs lists: what kind of app it is on a row that
        // scrolls, because five labels do not fit on a phone, and Shizuku's own question on a
        // row of its own. Granted and revoked are the only two answers to that one, so neither
        // being chosen shows everything.
        // Filter first, search within it: the two answer different questions and the search
        // box alone cannot say "only what is granted".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ManageFilter.entries.forEach { option ->
                val count = when (option) {
                    ManageFilter.ALL -> all.size
                    ManageFilter.USER -> userPackages.size
                    ManageFilter.SYSTEM -> systemPackages.size
                    ManageFilter.DISABLED -> disabledPackages.size
                    ManageFilter.HIDDEN -> launcherless.size
                }
                AppFilterChip(
                    label = stringResource(
                        when (option) {
                            ManageFilter.ALL -> R.string.manage_filter_all
                            ManageFilter.USER -> R.string.manage_filter_user
                            ManageFilter.SYSTEM -> R.string.manage_filter_system
                            ManageFilter.DISABLED -> R.string.manage_filter_disabled
                            ManageFilter.HIDDEN -> R.string.manage_filter_hidden
                        }
                    ),
                    count = count,
                    selected = kind == option,
                    fill = false,
                    onClick = { kind = option }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.apps_filter_granted),
                count = grantedNames.size,
                selected = filter == AppFilter.GRANTED,
                onClick = {
                    filter = if (filter == AppFilter.GRANTED) AppFilter.ALL else AppFilter.GRANTED
                }
            )
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.apps_filter_revoked),
                count = all.size - grantedNames.size,
                selected = filter == AppFilter.REVOKED,
                onClick = {
                    filter = if (filter == AppFilter.REVOKED) AppFilter.ALL else AppFilter.REVOKED
                }
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.app_management_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                // Clearing a search is the one thing you always end up wanting.
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(android.R.string.cancel))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                // The derived sets are worked out per list, so a re-read has to ask for
                // them again too: a pull is how you pick up a grant made elsewhere.
                derivedFor = null
                refreshKey++
            },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Every app is its own card, so they need room between them; the padding
            // keeps the cards off the edges like the cards on the other tabs.
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(shown, key = { it.packageName }) { pi ->
                val uid = pi.applicationInfo!!.uid
                val granted = remember(pi.packageName, version) {
                    runCatching { AuthorizationManager.granted(pi.packageName, uid) }.getOrDefault(false)
                }
                val isSelected = pi.packageName in selected

                SegmentedCard(
                    // A selected row tints its card, so a multi-select pass reads at a
                    // glance instead of needing the checkbox to be spotted each time.
                    color = if (selectionMode && isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                ) {
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            if (selectionMode) {
                                selected = if (isSelected) selected - pi.packageName else selected + pi.packageName
                            } else {
                                val result = runCatching {
                                    if (granted) AuthorizationManager.revoke(pi.packageName, uid)
                                    else AuthorizationManager.grant(pi.packageName, uid)
                                }
                                if (result.exceptionOrNull() is SecurityException) {
                                    permissionLimited = true
                                }
                                version++
                            }
                        },
                        onLongClick = {
                            selected = if (isSelected) selected - pi.packageName else selected + pi.packageName
                        }
                    ),
                    leadingContent = { AppIcon(pi) },
                    headlineContent = {
                        Text(
                            runCatching { pi.applicationInfo!!.loadLabel(pm).toString() }
                                .getOrDefault(pi.packageName)
                        )
                    },
                    // The package name is the whole line and ellipsises, because the chip and
                    // the switch now share the row's right side.
                    supportingContent = {
                        Text(pi.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        // One Row, so the chip and the control sit beside each other rather than
                        // on top of one another: the slot places what it is given as a single
                        // child.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The same chips every app list carries, worked out in one place:
                            // what kind of app it is, and what is worth knowing about it.
                            AppStatusChips(pi, hidden = pi.packageName in launcherless)
                            Spacer(modifier = Modifier.width(8.dp))
                            if (selectionMode) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                            } else {
                                ExpressiveSwitch(
                                    checked = granted,
                                    onCheckedChange = { checked ->
                                        val result = runCatching {
                                            if (checked) AuthorizationManager.grant(pi.packageName, uid)
                                            else AuthorizationManager.revoke(pi.packageName, uid)
                                        }
                                        if (result.exceptionOrNull() is SecurityException) {
                                            permissionLimited = true
                                        }
                                        version++
                                    }
                                )
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                }
            }
        }

        // While the first read is in flight the rows are stood in for rather than left as
        // a blank page with a spinner over it; the list keeps its shape and the wait is
        // spent looking at where the content will be. A pull is not this case: the rows
        // already there stay, which is why the skeleton asks for an empty list as well.
        if (loading && shown.isEmpty()) {
            CenteredMessage { LoadingIndicator() }
        }

        // Say why the page is empty: no server to ask, nothing matching the search, or
        // genuinely no apps a blank page explains nothing.
        if (!loading && shown.isEmpty()) {
            CenteredMessage {
                when {
                    !running -> {
                        Text(
                            text = stringResource(R.string.apps_needs_shizuku),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            modifier = Modifier.padding(top = 12.dp),
                            onClick = {
                                ShizukuReceiverStarter.start(context, userInitiated = true)
                            }
                        ) { Text(stringResource(R.string.action_start)) }
                    }

                    query.isNotBlank() -> Text(
                        text = stringResource(R.string.apps_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    // A filter can legitimately hold nothing (Hidden often does), which is
                    // not the same as there being no apps at all.
                    filter != AppFilter.ALL || kind != ManageFilter.ALL -> Text(
                        text = stringResource(R.string.apps_filter_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    else -> Text(
                        text = stringResource(R.string.apps_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        }
    }

    // The undo offer sits above the list and above the floating bar, which is drawn over
    // the page rather than beside it, so the same padding the list uses to clear the bar is
    // what keeps the snackbar from appearing underneath it.
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp)
    )
    }

    if (permissionLimited) {
        val adbUrl = runCatching { Helps.ADB.get() }.getOrDefault("")
        AlertDialog(
            onDismissRequest = { permissionLimited = false },
            title = { Text(stringResource(R.string.app_management_dialog_adb_is_limited_title)) },
            text = {
                // The string is written for the View UI, which parses its markup: here the tags
                // were drawn as tags. It links the document it names, and the link is inside the
                // markup, so the address is printed under the sentence rather than lost with it.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.app_management_dialog_adb_is_limited_message, adbUrl)
                            .stripHtmlTags()
                    )
                    if (adbUrl.isNotEmpty()) {
                        Text(
                            adbUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                PillButton(onClick = { permissionLimited = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    pendingToggleAll?.let { grant ->
        AlertDialog(
            onDismissRequest = { pendingToggleAll = null },
            title = {
                Text(
                    stringResource(
                        if (grant) R.string.app_management_batch_grant_title
                        else R.string.app_management_batch_revoke_title
                    )
                )
            },
            // Only what is listed: searching first is how you narrow this down.
            text = { Text(stringResource(R.string.app_management_toggle_all_message, shown.size)) },
            confirmButton = {
                PillButton(onClick = {
                    val target = shown.toList()
                    pendingToggleAll = null
                    applyBatch(grant, target)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { pendingToggleAll = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    pendingBatch?.let { grant ->
        AlertDialog(
            onDismissRequest = { pendingBatch = null },
            title = {
                Text(
                    stringResource(
                        if (grant) R.string.app_management_batch_grant_title
                        else R.string.app_management_batch_revoke_title
                    )
                )
            },
            text = { Text(stringResource(R.string.app_management_batch_message, selected.size)) },
            confirmButton = {
                PillButton(onClick = {
                    // Same path as Toggle all, so a hand-picked batch can be taken back
                    // just as easily.
                    val target = all.filter { it.packageName in selected }
                    pendingBatch = null
                    selected = emptySet()
                    applyBatch(grant, target)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { pendingBatch = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
