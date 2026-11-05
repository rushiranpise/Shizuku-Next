package moe.shizuku.manager.ui.screen

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Wysiwyg
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.ui.component.RefreshBox
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.TelevisionRefreshButton
import moe.shizuku.manager.ui.component.televisionEnterOnRight
import moe.shizuku.manager.ui.component.televisionFocusShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleAccessMode
import moe.shizuku.manager.module.ModuleActionResult
import moe.shizuku.manager.module.ModuleCatalog
import moe.shizuku.manager.module.ModuleCommandRequest
import moe.shizuku.manager.module.ModuleInstallException
import moe.shizuku.manager.module.ModuleInstallProblem
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.ModuleUpdate
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.cardOutline

/**
 * The modules this app has installed.
 *
 * The cards are KernelSU-Next's, deliberately and in detail: a module's own banner faintly behind
 * it and faded into the surface, its name in semibold with its version and author under it, the
 * whole card opening on a tap to show its id, its description and its actions as tonal buttons with
 * icons, and a floating button for installing another one. A module is a picture and a paragraph
 * and a button, which is what that shape draws well, so there was no reason to invent a second way
 * of showing one.
 *
 * What stays this fork's own is the tuning icon in the bar: a module's scripts run as shell through
 * Shizuku rather than as root, so what a module may do is a decision somebody has to make, and no
 * command runs that has not been shown first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    bottomPadding: Dp = 0.dp,
    active: Boolean = true,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modules by remember { mutableStateOf<List<AdbModule>>(emptyList()) }
    var updates by remember { mutableStateOf<Map<String, ModuleUpdate>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ModuleSort.load()) }
    var busy by remember { mutableStateOf(false) }
    // A module's state is read off disk rather than stored, and two reads of the same module are
    // equal objects - so a list that was re-read for real, after switching a module off or marking
    // it removed, looks unchanged to Compose and nothing re-reads those flags. This counts the
    // reads instead: it is part of what the list is computed from, so it is what makes the cards
    // re-read the state they actually have.
    var reads by remember { mutableStateOf(0) }
    var webUiFor by remember { mutableStateOf<AdbModule?>(null) }
    var actionFor by remember { mutableStateOf<AdbModule?>(null) }
    var catalogOpen by remember { mutableStateOf(false) }
    var installDialog by remember { mutableStateOf(false) }
    var tuneDialog by remember { mutableStateOf(false) }

    var fullAccess by remember { mutableStateOf(ModuleSettings.getAccessMode() == ModuleAccessMode.FULL) }
    var backgroundAllowed by remember { mutableStateOf(ModuleSettings.allowsBackground()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Which cards have had their Trust action long-pressed into view, which is their own way of
    // asking: a long press shows it, another hides it, and a module that is trusted says so on the
    // card with a Full Trust chip.
    var trustRevealed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingTrust by remember { mutableStateOf<AdbModule?>(null) }

    var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
    var approval by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    var result by remember { mutableStateOf<ModuleActionResult?>(null) }
    var resultTitle by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<AdbModule?>(null) }
    var running by remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * Reads the list back, and with it which services are actually running.
     *
     * The running ones are asked of the processes rather than remembered: a service started before
     * this screen was opened is still running, and its card has to say Stop when it is.
     */
    suspend fun refreshNow() {
        val listed = AdbModuleManager.list(context)
        modules = listed
        reads++
        running = listed.filter { AdbModuleManager.isServiceRunning(it) }.map { it.id }.toSet()
    }

    fun reload() {
        scope.launch { refreshNow() }
    }

    /**
     * What every command goes through first.
     *
     * A module the user has marked trusted runs without being asked again; anything else waits here
     * for the dialog, which is the only place a command is ever approved.
     */
    val approve: suspend (ModuleCommandRequest) -> Boolean = { request ->
        if (ModuleSettings.isTrusted(request.module.id)) {
            true
        } else {
            val answer = CompletableDeferred<Boolean>()
            approval = answer
            pendingCommand = request
            answer.await()
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Their update check, asked once per module that points at a manifest: nothing polls, and a
    // module that has no update to offer costs one file read.
    LaunchedEffect(modules) {
        updates = modules.mapNotNull { module ->
            AdbModuleManager.checkUpdate(module)?.let { module.id to it }
        }.toMap()
    }

    /** What the list shows, which is all of them until something is typed. */
    val shown = remember(modules, query, sort, reads) {
        if (query.isBlank()) {
            modules
        } else {
            modules.filter { module ->
                module.name.contains(query, ignoreCase = true) ||
                    module.id.contains(query, ignoreCase = true) ||
                    module.prop.description?.contains(query, ignoreCase = true) == true
            }
        }.sortedWith(sort.order())
    }

    // A module's page, its action console and the catalog each take the whole tab. While one of
    // them is up a horizontal swipe belongs to it, not to the app's tabs, so the tab pager is told
    // to stop dragging - and told again when the screen is closed.
    val fullscreen = webUiFor != null || actionFor != null || catalogOpen
    LaunchedEffect(fullscreen) { onFullscreenChange(fullscreen) }
    DisposableEffect(Unit) { onDispose { onFullscreenChange(false) } }

    // Android's own way back on the screens that take the whole tab: the system's back gesture and
    // the back button close whichever one is up and land on the module list, which is exactly what
    // its back arrow does. Enabled only while the modules tab is the one on screen, because a page
    // left open behind another tab must not take that tab's back gesture.
    BackHandler(enabled = active && fullscreen) {
        when {
            webUiFor != null -> webUiFor = null
            actionFor != null -> actionFor = null
            else -> catalogOpen = false
        }
    }

    // These take the screen rather than opening over the list: a page, a console and a catalog are
    // not dialogs.
    webUiFor?.let { module ->
        // Settings belong to the module list, so the page closes for them: the list arrives with
        // the settings dialog already open on it, one tap from the switches the page sent the
        // person to.
        ModuleWebUiScreen(
            module = module,
            active = active,
            onBack = { webUiFor = null },
            onOpenSettings = { webUiFor = null; tuneDialog = true }
        )
        return
    }

    actionFor?.let { module ->
        ModuleActionScreen(module = module, onBack = { actionFor = null })
        return
    }

    if (catalogOpen) {
        CatalogScreen(
            kind = CatalogKind.MODULES,
            // What a repository is recognised by when its module is already here: each module's id,
            // and the repository it was installed from when this app is the one that installed it.
            installedKeys = ModuleSettings.installedKeys(modules),
            onBack = { catalogOpen = false },
            onInstalled = {
                catalogOpen = false
                reload()
            }
        )
        return
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            busy = true
            runCatching { AdbModuleManager.install(context, uri) }
                .onFailure { failure ->
                    errorRes = (failure as? ModuleInstallException)
                        ?.let { installProblem(it.problem) }
                        ?: R.string.modules_error_install_failed
                }
                .onSuccess { installed ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.modules_installed, installed.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            busy = false
            reload()
        }
    }

    val refresh: () -> Unit = {
        refreshing = true
        scope.launch {
            refreshNow()
            refreshing = false
        }
    }

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
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
                        Text(stringResource(R.string.tools_modules))
                    }
                },
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
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

                        // Their sort, behind the overflow as a menu: one order at a time and their
                        // three preferences for what comes first.
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
                                SortChoice(R.string.modules_sort_a_to_z, sort.aToZ) {
                                    sort = sort.copy(
                                        aToZ = true, zToA = false, sizeLowToHigh = false,
                                        sizeHighToLow = false
                                    ).saved()
                                }
                                SortChoice(R.string.modules_sort_z_to_a, sort.zToA) {
                                    sort = sort.copy(
                                        aToZ = false, zToA = true, sizeLowToHigh = false,
                                        sizeHighToLow = false
                                    ).saved()
                                }
                                SortChoice(R.string.modules_sort_size_low_to_high, sort.sizeLowToHigh) {
                                    sort = sort.copy(
                                        aToZ = false, zToA = false, sizeLowToHigh = true,
                                        sizeHighToLow = false
                                    ).saved()
                                }
                                SortChoice(R.string.modules_sort_size_high_to_low, sort.sizeHighToLow) {
                                    sort = sort.copy(
                                        aToZ = false, zToA = false, sizeLowToHigh = false,
                                        sizeHighToLow = true
                                    ).saved()
                                }
                                SortChoice(R.string.modules_sort_enabled_first, sort.enabledFirst) {
                                    sort = sort.copy(enabledFirst = !sort.enabledFirst).saved()
                                }
                                SortChoice(R.string.modules_sort_action_first, sort.actionFirst) {
                                    sort = sort.copy(actionFirst = !sort.actionFirst).saved()
                                }
                                SortChoice(R.string.modules_sort_webui_first, sort.webUiFirst) {
                                    sort = sort.copy(webUiFirst = !sort.webUiFirst).saved()
                                }
                            }
                        }

                    // Their modules screen is where repositories are found from, not the
                    // other way round: the catalog belongs to the modules section, and this
                    // is the second way in - the floating button's dialog is the first.
                    IconButton(onClick = { catalogOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Cloud,
                            contentDescription = stringResource(R.string.modules_find_modules)
                        )
                    }
                    IconButton(onClick = { tuneDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.modules_settings)
                        )
                    }
                    }
                }
            )

            if (busy) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Their pull-to-refresh: a module is somebody else's file, and it changes from
            // outside as often as from here.
            RefreshBox(
                isRefreshing = refreshing,
                onRefresh = refresh
            ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = bottomPadding + 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (shown.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                if (query.isBlank()) {
                                    R.string.modules_none
                                } else {
                                    R.string.modules_no_match
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                shown.forEach { module ->
                    item {
                        ModuleCard(
                            module = module,
                            // Passed rather than read inside the card, and that is the whole of the
                            // fix for a switch that flicked and snapped back: a module is a state
                            // read off disk, so two reads of it are equal objects, and the card
                            // that draws the switch could be reused as it was with the state it
                            // had. A value in the parameters cannot be reused when it differs.
                            enabled = module.enabled,
                            trusted = ModuleSettings.isTrusted(module.id),
                            trustRevealed = module.id in trustRevealed,
                            expanded = module.id in expanded,
                            serviceRunning = module.id in running,
                            update = updates[module.id],
                            onExpandToggle = {
                                expanded = if (module.id in expanded) {
                                    expanded - module.id
                                } else {
                                    expanded + module.id
                                }
                            },
                            onEnabledChange = { checked ->
                                if (!AdbModuleManager.setEnabled(module, checked)) {
                                    errorRes = R.string.modules_error_set_enabled
                                }
                                reload()
                            },
                            onTrustReveal = {
                                trustRevealed = if (module.id in trustRevealed) {
                                    trustRevealed - module.id
                                } else {
                                    trustRevealed + module.id
                                }

                                // The action is on the row the card opens to show, so a long
                                // press that reveals it opens the card with it.
                                if (module.id !in expanded) expanded = expanded + module.id
                            },
                            onTrustToggle = { trust ->
                                if (trust) {
                                    // Trusting is the one action here that silences the review, so
                                    // it is asked about rather than done on the spot.
                                    pendingTrust = module
                                } else {
                                    ModuleSettings.setTrusted(module.id, false)
                                    reload()
                                }
                            },
                            onRunAction = { actionFor = module },
                            onToggleService = {
                                if (module.id in running) {
                                    AdbModuleManager.stopService(module)
                                    running = running - module.id
                                } else {
                                    scope.launch {
                                        busy = true
                                        val started = AdbModuleManager.startService(module, approve)
                                        busy = false
                                        if (started.exitCode == 0) {
                                            running = running + module.id
                                        } else {
                                            resultTitle = module.name
                                            result = started
                                        }
                                    }
                                }
                            },
                            onOpenWebUi = { webUiFor = module },
                            onUpdate = { manifest ->
                                scope.launch {
                                    busy = true
                                    runCatching {
                                        AdbModuleManager.install(
                                            context,
                                            ModuleCatalog.download(manifest.zipUrl)
                                        )
                                    }.onFailure { errorRes = R.string.modules_error_install_failed }
                                    busy = false
                                    reload()
                                }
                            },
                            onDelete = { pendingDelete = module }
                        )
                    }
                }
            }
            }
        }

        FloatingActionButton(
            modifier = Modifier
                .televisionFocusShape(FloatingActionButtonDefaults.shape)
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottomPadding + 16.dp),
            onClick = { installDialog = true }
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.modules_install)
            )
        }

        // Above the install button: the two sit in the same corner, and a scroll button that
        // covered it would be the one thing this list already has plenty of.
        ScrollToTopButton(
            listState = listState,
            bottomPadding = bottomPadding,
            modifier = Modifier.align(Alignment.BottomEnd),
            extraBottom = 72.dp
        )
    }

    if (installDialog) {
        AlertDialog(
            onDismissRequest = { installDialog = false },
            title = { Text(stringResource(R.string.modules_install_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleDialogButton(
                        icon = Icons.Outlined.FileOpen,
                        label = stringResource(R.string.modules_install_from_file)
                    ) {
                        installDialog = false
                        picker.launch(arrayOf("*/*"))
                    }
                    ModuleDialogButton(
                        icon = Icons.Filled.Cloud,
                        label = stringResource(R.string.modules_find_modules)
                    ) {
                        installDialog = false
                        catalogOpen = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                PillButtonQuiet(onClick = { installDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (tuneDialog) {
        AlertDialog(
            onDismissRequest = { tuneDialog = false },
            title = { Text(stringResource(R.string.modules_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.modules_access_full),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.modules_access_full_summary_short),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = fullAccess,
                            onCheckedChange = { checked ->
                                fullAccess = checked
                                ModuleSettings.setAccessMode(
                                    if (checked) ModuleAccessMode.FULL else ModuleAccessMode.SAFE
                                )
                                if (!checked && backgroundAllowed) {
                                    backgroundAllowed = false
                                    ModuleSettings.setAllowsBackground(false)
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.modules_allow_background),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.modules_allow_background_summary_short),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = backgroundAllowed,
                            enabled = fullAccess,
                            onCheckedChange = { checked ->
                                backgroundAllowed = checked
                                ModuleSettings.setAllowsBackground(checked)
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                PillButtonQuiet(onClick = { tuneDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    pendingCommand?.let { request ->
        CommandReviewDialog(
            request = request,
            onApprove = { trust ->
                if (trust) ModuleSettings.setTrusted(request.module.id, true)
                pendingCommand = null
                approval?.complete(true)
                approval = null
            },
            onReject = {
                pendingCommand = null
                approval?.complete(false)
                approval = null
            }
        )
    }

    result?.let { actionResult ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(R.string.modules_result_title, resultTitle)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.modules_result_exit, actionResult.exitCode),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = actionResult.combinedOutput,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                PillButton(onClick = { result = null }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }

    pendingTrust?.let { module ->
        AlertDialog(
            onDismissRequest = { pendingTrust = null },
            title = { Text(stringResource(R.string.modules_trust_confirm, module.name)) },
            text = { Text(stringResource(R.string.modules_trust_confirm_message)) },
            confirmButton = {
                PillButton(onClick = {
                    pendingTrust = null
                    ModuleSettings.setTrusted(module.id, true)
                    reload()
                }) { Text(stringResource(R.string.modules_trust)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { pendingTrust = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    pendingDelete?.let { module ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.modules_delete_confirm, module.name)) },
            text = { Text(stringResource(R.string.modules_delete_confirm_message)) },
            confirmButton = {
                PillButton(onClick = {
                    pendingDelete = null
                    running = running - module.id
                    AdbModuleManager.delete(module)
                    reload()
                }) { Text(stringResource(R.string.modules_delete)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { pendingDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    errorRes?.let { messageRes ->
        AlertDialog(
            onDismissRequest = { errorRes = null },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                PillButton(onClick = { errorRes = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

/**
 * One module, drawn the way KernelSU-Next draws one.
 *
 * The banner is behind the card's content at 18 percent and faded into the surface towards the
 * bottom, which is the detail that makes this look like their screen rather than like a list of
 * text: a module that has a picture shows it, and one that has none is a plain card. The card opens
 * on a tap and there is no arrow saying so, because that is how theirs opens too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModuleCard(
    module: AdbModule,
    enabled: Boolean,
    trusted: Boolean,
    trustRevealed: Boolean,
    expanded: Boolean,
    serviceRunning: Boolean,
    update: ModuleUpdate?,
    onExpandToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTrustReveal: () -> Unit,
    onTrustToggle: (Boolean) -> Unit,
    onRunAction: () -> Unit,
    onToggleService: () -> Unit,
    onOpenWebUi: () -> Unit,
    onUpdate: (ModuleUpdate) -> Unit,
    onDelete: () -> Unit,
) {
    var bitmap by remember(module.banner) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(module.banner) {
        val banner = module.banner ?: return@LaunchedEffect

        bitmap = runCatching {
            BitmapFactory.decodeFile(banner.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            // Otherwise invisible on the black theme, where the card and the page are the same
            // colour: the same hairline the app's own cards draw.
            .cardOutline()
            // The switch and, once expanded, the actions sit inside the card, which is a button
            // of its own.
            .televisionEnterOnRight()
            // Their gesture for the trust action: a long press reveals it, another hides it.
            .combinedClickable(
                onClick = onExpandToggle,
                onLongClick = onTrustReveal
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            bitmap?.let { image ->
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

            Column(modifier = Modifier.padding(22.dp, 18.dp, 22.dp, 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = module.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.modules_card_version,
                                module.prop.version ?: "-"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(
                                R.string.modules_card_author,
                                module.prop.author ?: "-"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(R.string.modules_card_id, module.id),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange
                    )
                }

                // Their chip row: what a module is, at a glance, always there rather than
                // folded away - the size, and a chip for each thing it can do.
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Their size pill is the one label drawn in the app's own accent colour - a
                    // filled primary chip, from the library's own default - where the rest of the
                    // row is the muted container. It is the one fact about the module itself
                    // rather than about something it can do, and it reads as the bright one.
                    ModuleChip(
                        text = formatSize(module.sizeBytes),
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary
                    )

                    // A trusted module says so on its card, their chip and their word for it.
                    if (trusted) {
                        ModuleChip(text = stringResource(R.string.modules_full_trust))
                    }
                    if (!enabled) {
                        ModuleChip(
                            text = stringResource(R.string.modules_disabled),
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    if (module.hasWebUi) {
                        ModuleChip(text = stringResource(R.string.modules_web_ui_short))
                    }
                    if (module.hasAction) {
                        ModuleChip(text = stringResource(R.string.modules_action_short))
                    }
                    if (module.hasService) {
                        ModuleChip(
                            text = stringResource(
                                if (serviceRunning) {
                                    R.string.modules_service_running_short
                                } else {
                                    R.string.modules_service_short
                                }
                            )
                        )
                    }

                    // Their chip for it, in their colour: an update waiting is the one thing on a
                    // card that is worth noticing without opening it.
                    update?.let {
                        ModuleChip(
                            text = stringResource(R.string.module_update_available),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Their action row, to the letter: compact tonal buttons in one line, their icons
                // at 20dp and their words only when there is no second button to make room for.
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (module.hasAction) {
                            ModuleCompactButton(
                                icon = Icons.Outlined.Terminal,
                                label = stringResource(R.string.modules_action_short),
                                // Their rule would hide this word behind the WebUI button; here it
                                // is the one thing on the row worth naming, so it keeps its word and
                                // the icon buttons give up theirs.
                                showLabel = true,
                                onClick = onRunAction
                            )
                            Spacer(modifier = Modifier.weight(0.1f, true))
                        }

                        if (module.hasService) {
                            ModuleCompactButton(
                                icon = Icons.Outlined.Stop,
                                label = stringResource(
                                    if (serviceRunning) {
                                        R.string.modules_service_stop
                                    } else {
                                        R.string.modules_service_start
                                    }
                                ),
                                showLabel = !module.hasAction && !module.hasWebUi,
                                onClick = onToggleService
                            )
                            Spacer(modifier = Modifier.weight(0.1f, true))
                        }

                        if (module.hasWebUi) {
                            ModuleCompactButton(
                                icon = Icons.AutoMirrored.Outlined.Wysiwyg,
                                label = stringResource(R.string.modules_web_ui_short),
                                showLabel = !module.hasAction && !module.hasService,
                                onClick = onOpenWebUi
                            )
                            Spacer(modifier = Modifier.weight(0.1f, true))
                        }

                        // Only while it has been long-pressed into view, and never the other way
                        // round: this is the one action on the row that a person has to ask for.
                        if (trustRevealed) {
                            ModuleCompactButton(
                                icon = Icons.Outlined.VerifiedUser,
                                label = stringResource(
                                    if (trusted) R.string.modules_untrust else R.string.modules_trust
                                ),
                                showLabel = true,
                                onClick = { onTrustToggle(!trusted) }
                            )
                            Spacer(modifier = Modifier.weight(0.1f, true))
                        }

                        Spacer(modifier = Modifier.weight(1f, true))

                        // A waiting update sits to the right of the row, before the destructive
                        // button, the way theirs does.
                        update?.let { manifest ->
                            ModuleCompactButton(
                                icon = Icons.Outlined.Download,
                                label = stringResource(R.string.modules_update),
                                showLabel = !module.hasAction && !module.hasWebUi && !module.hasService,
                                onClick = { onUpdate(manifest) }
                            )
                            Spacer(modifier = Modifier.weight(0.1f, true))
                        }

                        // Delete is the only destructive thing a card offers: a module is
                        // switched off with the switch above, and Delete is what ends it.
                        ModuleCompactButton(
                            icon = Icons.Outlined.DeleteForever,
                            label = stringResource(R.string.modules_delete),
                            showLabel = false,
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

/**
 * One of a card's actions, the size and shape theirs are: a 52 by 32 tonal button with a 20dp
 * icon, whose word appears only when it is the only button on the row.
 */
@Composable
private fun ModuleCompactButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        modifier = Modifier.defaultMinSize(52.dp, 32.dp),
        onClick = onClick,
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) {
        Icon(modifier = Modifier.size(20.dp), imageVector = icon, contentDescription = label)
        if (showLabel) {
            Text(
                modifier = Modifier.padding(start = 7.dp),
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * One of a dialog's actions, which are full width there the way theirs are: an icon and a word,
 * stacked down the dialog rather than laid along a row.
 */
@Composable
private fun ModuleDialogButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) {
        Icon(modifier = Modifier.size(20.dp), imageVector = icon, contentDescription = null)
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/**
 * Their label: a small rounded chip carrying one fact about the module.
 *
 * The chip is the whole reason their cards read at a glance - the size and what a module can do
 * are pills under its name, needing no room and no glance into the card's own expansion.
 */
@Composable
private fun ModuleChip(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** A size the way their card writes one. */
private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"

    val kb = size / 1024.0
    if (kb < 1024.0) return "${(kb * 10).toLong() / 10.0} KB"

    return "${(kb / 1024.0 * 10).toLong() / 10.0} MB"
}

/** Why a zip was refused, said in the terms the user can act on. */
private fun installProblem(problem: ModuleInstallProblem): Int = when (problem) {
    ModuleInstallProblem.NOT_A_MODULE -> R.string.modules_error_not_a_module
    ModuleInstallProblem.UNSAFE_PATH -> R.string.modules_error_unsafe_path
    ModuleInstallProblem.TOO_MANY_ENTRIES -> R.string.modules_error_too_many_entries
    ModuleInstallProblem.TOO_LARGE -> R.string.modules_error_too_large
    ModuleInstallProblem.SCRIPT_TOO_LARGE -> R.string.modules_error_script_too_large
}

/**
 * The order the module list is shown in: one of their orders at a time, plus their three
 * preferences for what comes first - kept under their own key names in this app's preferences, so
 * the choices mean the same thing to anybody who has seen both screens.
 */
private data class ModuleSort(
    val aToZ: Boolean = true,
    val zToA: Boolean = false,
    val sizeLowToHigh: Boolean = false,
    val sizeHighToLow: Boolean = false,
    val enabledFirst: Boolean = false,
    val actionFirst: Boolean = false,
    val webUiFirst: Boolean = false,
) {
    fun order(): Comparator<AdbModule> {
        val byDirection = when {
            zToA -> compareByDescending<AdbModule> { it.name.lowercase() }
            sizeLowToHigh -> compareBy { it.sizeBytes }
            sizeHighToLow -> compareByDescending { it.sizeBytes }
            else -> compareBy { it.name.lowercase() }
        }

        return compareByDescending<AdbModule> { enabledFirst && it.enabled }
            .thenByDescending { actionFirst && it.hasAction }
            .thenByDescending { webUiFirst && it.hasWebUi }
            .then(byDirection)
    }

    fun saved(): ModuleSort {
        ModuleSettings.setSortFlag(ModuleSettings.SORT_A_TO_Z, aToZ)
        ModuleSettings.setSortFlag(ModuleSettings.SORT_Z_TO_A, zToA)
        ModuleSettings.setSortFlag(ModuleSettings.SORT_SIZE_LOW_TO_HIGH, sizeLowToHigh)
        ModuleSettings.setSortFlag(ModuleSettings.SORT_SIZE_HIGH_TO_LOW, sizeHighToLow)
        ModuleSettings.setSortFlag(ModuleSettings.SORT_ENABLED_FIRST, enabledFirst)
        ModuleSettings.setSortFlag(ModuleSettings.SORT_ACTION_FIRST, actionFirst)
        ModuleSettings.setSortFlag(ModuleSettings.SORT_WEBUI_FIRST, webUiFirst)

        return this
    }

    companion object {
        fun load(): ModuleSort = ModuleSort(
            aToZ = ModuleSettings.sortFlag(ModuleSettings.SORT_A_TO_Z, true),
            zToA = ModuleSettings.sortFlag(ModuleSettings.SORT_Z_TO_A),
            sizeLowToHigh = ModuleSettings.sortFlag(ModuleSettings.SORT_SIZE_LOW_TO_HIGH),
            sizeHighToLow = ModuleSettings.sortFlag(ModuleSettings.SORT_SIZE_HIGH_TO_LOW),
            enabledFirst = ModuleSettings.sortFlag(ModuleSettings.SORT_ENABLED_FIRST),
            actionFirst = ModuleSettings.sortFlag(ModuleSettings.SORT_ACTION_FIRST),
            webUiFirst = ModuleSettings.sortFlag(ModuleSettings.SORT_WEBUI_FIRST)
        )
    }
}

/** One of their sort options: the word, and a tick when it is the one in force. */
@Composable
private fun SortChoice(label: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        trailingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
        onClick = onClick
    )
}

/**
 * The command a module is about to run, shown before it runs.
 *
 * This is the whole of the review: the command as it will be handed to the shell, in the font a
 * command is read in, with no summary in between - a summary is what a person would nod at.
 *
 * Beside it sits the one choice a person asked often enough starts looking for: to stop being
 * asked. The review is the only place a command is ever approved, so it is where the asking is
 * turned off; the switch on a card stays as hidden as it was, and a ticked box approves nothing
 * on its own - the command still waits for the button.
 */
@Composable
fun CommandReviewDialog(
    request: ModuleCommandRequest,
    onApprove: (trustModule: Boolean) -> Unit,
    onReject: () -> Unit,
) {
    var trustModule by remember(request) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.modules_review_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.modules_review_message, request.module.name),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = request.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { trustModule = !trustModule }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = trustModule, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.modules_review_trust, request.module.name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            PillButton(onClick = { onApprove(trustModule) }) {
                Text(stringResource(R.string.modules_review_approve))
            }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onReject) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
