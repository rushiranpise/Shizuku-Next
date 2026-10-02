package moe.shizuku.manager.ui.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.InstalledPackages
import moe.shizuku.manager.manage.Logcat
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.appLabel
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand

/** Which lines the list is showing. */
private enum class LogFilter { ALL, PROBLEMS, CRASHES }

/**
 * What this app has done, and what the device is saying.
 *
 * Two different logs, and they are two tabs rather than one merged list because they answer
 * different questions. The first is this app's own record - the watch, the watchdog, the state
 * machine - which is the one to read when something this app did went wrong. The second is
 * logcat: the device's own log, which an app cannot read for itself because `READ_LOGS` has been
 * signature|privileged since Android 4.1 and is reached here through the shell this app already
 * holds. That one is what to record when *another* app is misbehaving, which is the reason
 * anybody installs a log reader at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(bottomPadding: Dp, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.tab_log))
                    Text(
                        text = stringResource(
                            when (tab) {
                                0 -> R.string.log_tab_app
                                1 -> R.string.log_tab_capture
                                else -> R.string.log_tab_saved
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.log_tab_app)) }
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.log_tab_capture)) }
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text(stringResource(R.string.log_tab_saved)) }
            )
        }

        when (tab) {
            0 -> AppLogTab(bottomPadding)
            1 -> CaptureTab(bottomPadding)
            else -> SavedTab(bottomPadding)
        }
    }
}

/**
 * This app's own log, as it was before there was anything else on this screen: the last day of
 * lines, searchable, filterable by tag, and a pull that brings in the shell-written tags this
 * app does not write itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLogTab(bottomPadding: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<Diag.Entry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var tag by remember { mutableStateOf<String?>(null) }
    var pulling by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        entries = withContext(Dispatchers.IO) { Diag.entries() }
    }

    val tags = remember(entries) { Diag.tags().take(6) }
    val shown = remember(entries, query, filter, tag) {
        val byKind = when (filter) {
            LogFilter.ALL, LogFilter.CRASHES -> entries
            LogFilter.PROBLEMS -> entries.filter { it.level == 'W' || it.level == 'E' }
        }
        val byTag = tag?.let { chosen -> byKind.filter { it.tag == chosen } } ?: byKind
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            byTag
        } else {
            byTag.filter {
                it.message.contains(trimmed, ignoreCase = true) ||
                    it.tag.contains(trimmed, ignoreCase = true)
            }
        }.reversed()
    }

    fun pull() {
        scope.launch {
            pulling = true
            val added = withContext(Dispatchers.IO) { pullLogcat() }
            pulling = false
            version++
            if (added > 0) {
                Toast.makeText(context, context.getString(R.string.log_pulled, added), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilterChip(
                label = stringResource(R.string.log_filter_all),
                count = entries.size,
                selected = filter == LogFilter.ALL && tag == null,
                fill = false,
                onClick = { filter = LogFilter.ALL; tag = null }
            )
            AppFilterChip(
                label = stringResource(R.string.log_filter_problems),
                count = entries.count { it.level == 'W' || it.level == 'E' },
                selected = filter == LogFilter.PROBLEMS,
                fill = false,
                onClick = {
                    filter = if (filter == LogFilter.PROBLEMS) LogFilter.ALL else LogFilter.PROBLEMS
                }
            )
            tags.forEach { name ->
                AppFilterChip(
                    label = name,
                    count = entries.count { it.tag == name },
                    selected = tag == name,
                    fill = false,
                    onClick = { tag = if (tag == name) null else name }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { pull() }, enabled = !pulling) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.log_refresh))
            }
            IconButton(
                onClick = {
                    val file = Diag.export()
                    Toast.makeText(
                        context,
                        if (file == null) context.getString(R.string.log_nothing_to_save)
                        else context.getString(R.string.log_saved, file.name),
                        Toast.LENGTH_LONG
                    ).show()
                }
            ) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = stringResource(R.string.log_save))
            }
            IconButton(
                onClick = {
                    Diag.clear()
                    version++
                    Toast.makeText(context, context.getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.log_clear))
            }
        }

        SearchField(query) { query = it }

        Box(modifier = Modifier.fillMaxSize()) {
            // Identified once per read rather than per frame: the entries are the same objects
            // until the log is read again, and handing them new ids every recomposition would
            // make the list re-key itself on every pass.
            val rows = remember(shown) { shown.mapIndexed { index, entry -> entry.toRow(index.toLong()) } }
            LogLineList(
                lines = rows,
                query = query,
                bottomPadding = bottomPadding
            )

            if (shown.isEmpty()) {
                CenteredMessage {
                    Text(
                        text = stringResource(
                            if (query.isNotBlank() || tag != null) R.string.apps_no_match
                            else R.string.log_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * Recording the device's log, for one app or for everything.
 *
 * The scope is picked before anything starts, because a log is not something to begin by
 * accident: whole-device recording is every app's output at once, and one app's is what somebody
 * reproducing a crash actually needs.
 */
@Composable
private fun CaptureTab(bottomPadding: Dp) {
    val context = LocalContext.current
    val state by Logcat.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var chosen by remember { mutableStateOf<Logcat.Scope?>(null) }
    var picking by remember { mutableStateOf(false) }

    val shown = remember(state.lines, query, filter) {
        filterLines(state.lines, query, filter)
    }

    // A crash is why somebody is recording, so the button that goes to the newest one is the
    // first thing on the row: scrolling a live list by hand while the app under test is
    // misbehaving is not a thing anybody can do.
    val lastCrash = remember(shown.lastOrNull()) { shown.indexOfLast { it.crash } }

    // Follow the tail while somebody is watching, and stop following the moment they scroll up -
    // a live list that keeps yanking itself back down is one nobody can read a line of.
    LaunchedEffect(shown.size, listState.isScrollInProgress) {
        if (listState.isScrollInProgress || shown.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (last >= shown.size - 3) listState.animateScrollToItem(shown.lastIndex)
    }

    state.stoppedBy?.let { reason ->
        LaunchedEffect(reason) {
            Toast.makeText(
                context,
                context.getString(
                    when (reason) {
                        Logcat.StopReason.SIZE -> R.string.log_capture_stopped_size
                        Logcat.StopReason.TIME -> R.string.log_capture_stopped_time
                        Logcat.StopReason.ENDED -> R.string.log_capture_stopped_ended
                    }
                ),
                Toast.LENGTH_LONG
            ).show()
            Logcat.clearStopReason()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (Logcat.isRunning()) {
                        Logcat.stop()
                    } else {
                        val scope = chosen ?: Logcat.Scope.Device
                        if (!Logcat.start(context, scope)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.log_capture_needs_shizuku),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = ShizukuStateMachine.isRunning() || Logcat.isRunning()
            ) {
                Icon(
                    if (state.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = null
                )
                Text(
                    text = stringResource(
                        if (state.running) R.string.log_capture_stop else R.string.log_capture_start
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Outlined, and not a text button: a text button has no border and no container, so
            // the scope sat next to Start as bare words and read as a label rather than as the
            // one thing on this row that can be changed. The arrow says it opens something.
            OutlinedButton(
                onClick = { picking = true },
                enabled = !state.running,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = chosen?.label ?: stringResource(R.string.log_capture_device),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }

        Text(
            text = if (state.running) {
                stringResource(
                    R.string.log_capture_running,
                    state.scopeLabel,
                    state.lines.size,
                    sizeText(state.bytes)
                )
            } else {
                stringResource(R.string.log_capture_idle)
            },
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilterChip(
                label = stringResource(R.string.log_filter_all),
                count = state.lines.size,
                selected = filter == LogFilter.ALL,
                fill = false,
                onClick = { filter = LogFilter.ALL }
            )
            AppFilterChip(
                label = stringResource(R.string.log_filter_problems),
                count = state.lines.count { it.level == 'W' || it.level == 'E' },
                selected = filter == LogFilter.PROBLEMS,
                fill = false,
                onClick = { filter = LogFilter.PROBLEMS }
            )
            AppFilterChip(
                label = stringResource(R.string.log_filter_crashes),
                count = state.lines.count { it.crash },
                selected = filter == LogFilter.CRASHES,
                fill = false,
                onClick = { filter = LogFilter.CRASHES }
            )
            if (lastCrash >= 0) {
                val jump = rememberCoroutineScope()
                PillButtonQuiet(onClick = { jump.launch { listState.animateScrollToItem(lastCrash) } }) {
                    Text(stringResource(R.string.log_capture_jump))
                }
            }
        }

        SearchField(query) { query = it }

        Box(modifier = Modifier.fillMaxSize()) {
            LogLineList(lines = shown, query = query, bottomPadding = bottomPadding, listState = listState)

            if (shown.isEmpty()) {
                CenteredMessage {
                    Text(
                        text = stringResource(
                            if (query.isNotBlank() || filter != LogFilter.ALL) R.string.apps_no_match
                            else if (state.running) R.string.log_capture_waiting
                            else R.string.log_capture_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            onDismiss = { picking = false },
            onPick = { picked ->
                chosen = picked
                picking = false
            }
        )
    }
}

/** What has been recorded, newest first, to read again, share or delete. */
@Composable
private fun SavedTab(bottomPadding: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<Logcat.Session>>(emptyList()) }
    var viewing by remember { mutableStateOf<Logcat.Session?>(null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        sessions = withContext(Dispatchers.IO) { Logcat.sessions(context) }
    }

    val open = viewing
    if (open != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewing = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Text(open.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Box(modifier = Modifier.weight(1f))
                IconButton(onClick = { share(context, open) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.log_session_share))
                }
            }
            LogLineList(
                lines = Logcat.read(open),
                query = "",
                bottomPadding = bottomPadding
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            items(sessions, key = { it.name }) { session ->
                SessionRow(
                    session = session,
                    onOpen = { viewing = session },
                    onShare = { share(context, session) },
                    onDelete = {
                        scope.launch {
                            val done = withContext(Dispatchers.IO) { Logcat.delete(session) }
                            version++
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (done) R.string.log_session_deleted else R.string.log_session_busy,
                                    session.name
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                HorizontalDivider()
            }
        }

        if (sessions.isEmpty()) {
            CenteredMessage {
                Text(
                    text = stringResource(R.string.log_saved_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: Logcat.Session,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val running = Logcat.isRunning() && Logcat.state.value.file == session.file

    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(session.file.name, maxLines = 1) },
        supportingContent = {
            Text(
                text = buildString {
                    append(
                        if (session.packageName.isEmpty()) stringResource(R.string.log_capture_device)
                        else session.packageName
                    )
                    append(" · ")
                    append(sizeText(session.bytes))
                    if (running) {
                        append(" · ")
                        append(stringResource(R.string.log_session_running))
                    }
                },
                maxLines = 1
            )
        },
        trailingContent = {
            Row {
                if (!running) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.log_session_delete))
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.log_session_share))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** Picking the app to record, from the same shell-backed list every other list uses. */
@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (Logcat.Scope.App) -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    var apps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { InstalledPackages.all(context, 0) }
    }

    val shown = remember(apps, query) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isBlank()) {
            apps
        } else {
            apps.filter {
                appLabel(pm, it).contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
        filtered.sortedBy { appLabel(pm, it).lowercase() }.take(200)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_capture_choose)) },
        text = {
            Column {
                SearchField(query, stringResource(R.string.log_capture_choose_hint)) { query = it }
                LazyColumn(modifier = Modifier.height(420.dp)) {
                    items(shown, key = { it.packageName }) { pi ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onPick(
                                    Logcat.Scope.App(
                                        packageName = pi.packageName,
                                        label = appLabel(pm, pi),
                                        uid = pi.applicationInfo?.uid ?: 0
                                    )
                                )
                            },
                            headlineContent = { Text(appLabel(pm, pi), maxLines = 1) },
                            supportingContent = { Text(pi.packageName, maxLines = 1) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

// ---- the pieces both tabs use ----------------------------------------------------

/** The filter every list of lines answers to. */
private fun filterLines(lines: List<Logcat.Line>, query: String, filter: LogFilter): List<Logcat.Line> {
    val byKind = when (filter) {
        LogFilter.ALL -> lines
        LogFilter.PROBLEMS -> lines.filter { it.level == 'W' || it.level == 'E' }
        LogFilter.CRASHES -> lines.filter { it.crash }
    }
    val trimmed = query.trim()
    if (trimmed.isBlank()) return byKind
    return byKind.filter {
        it.message.contains(trimmed, ignoreCase = true) ||
            it.tag.contains(trimmed, ignoreCase = true)
    }
}

/** The search box, the same shape on both tabs. */
@Composable
private fun SearchField(
    query: String,
    hint: String = stringResource(R.string.log_search_hint),
    onQuery: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(android.R.string.cancel))
                }
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

/**
 * The lines, the way the shell's transcript draws them: fixed width, the time in its own column,
 * the search term marked where it was found, and a crash's announcing line picked out.
 */
@Composable
private fun LogLineList(
    lines: List<Logcat.Line>,
    query: String,
    bottomPadding: Dp,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Keyed on the line's own identity rather than on what it says: a log holds lines that
        // are identical in every field, and a key that repeats is a crash rather than a duplicate
        // row.
        items(lines, key = { it.id }) { line ->
            LogLine(line, query)
        }
    }
}

@Composable
private fun LogLine(line: Logcat.Line, query: String) {
    val tone = when {
        line.crash -> MaterialTheme.colorScheme.error
        line.level == 'E' -> MaterialTheme.colorScheme.error
        line.level == 'W' -> MaterialTheme.colorScheme.tertiary
        line.level == 'D' -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        color = if (line.crash) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else Color.Transparent
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("${Diag.stamp(line.at)}  ")
                }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.tag) }
                append("  ")
                if (query.isBlank()) append(line.message) else appendMarked(line.message, query)
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = tone
        )
    }
}

/** The message with every occurrence of [query] picked out. */
private fun AnnotatedString.Builder.appendMarked(message: String, query: String) {
    var index = 0
    while (true) {
        val at = message.indexOf(query, index, ignoreCase = true)
        if (at < 0) {
            append(message.substring(index))
            return
        }
        append(message.substring(index, at))
        withStyle(SpanStyle(background = Color(0x66FFB74D))) {
            append(message.substring(at, at + query.length))
        }
        index = at + query.length
    }
}

/**
 * Reads logcat through the shell and keeps what this app has not already stored.
 *
 * The API and the server are other processes, so their lines only exist in logcat; the tags here
 * are the ones Shizuku writes under, and the lines this app writes itself are skipped because
 * [Diag] already has them - which is what makes a second refresh add nothing rather than
 * everything twice.
 *
 * Runs shell commands; not for the main thread.
 */
private fun pullLogcat(): Int {
    val filter = SHIZUKU_TAGS.joinToString(" ") { "$it:V" }
    val out = runShellCommand("logcat -d -v epoch -t 600 $filter '*:S' 2>/dev/null") ?: return 0

    var added = 0
    out.lineSequence().forEach { line ->
        val match = LOGCAT.find(line) ?: return@forEach
        val tag = match.groupValues[3].trim()
        // Ours are already stored, with the tag this app uses for them.
        if (OWN_TAGS.any { tag.startsWith(it) }) return@forEach
        val at = match.groupValues[1].toDoubleOrNull()?.times(1000)?.toLong() ?: return@forEach
        Diag.log(match.groupValues[2].firstOrNull() ?: 'I', tag, match.groupValues[4].trim())
        added++
    }
    return added
}

private val SHIZUKU_TAGS = listOf(
    "Shizuku", "ShizukuServer", "ShizukuStarter", "ShizukuApi", "ShizukuManager",
    "ShizukuWatchdog", "ShizukuStateMachine", "ShizukuApplication"
)

/**
 * `-v epoch`: `"         1790809925.077 11885 11885 I ShizukuApi: message"`.
 *
 * The timestamp is padded to a fixed width, so the line starts with spaces - the first version
 * of this anchored on a digit and matched nothing at all, which looked exactly like there being
 * nothing to pull.
 */
private val LOGCAT = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?):\s?(.*)$""")

private val OWN_TAGS = listOf(AppConstants.TAG, "ShizukuWatchdog", "ShizukuStateMachine")

private fun Diag.Entry.toRow(id: Long) =
    Logcat.Line(at = at, level = level, pid = 0, tag = tag, message = message, id = id)

private fun sizeText(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} kB"
    else -> "$bytes B"
}

/** Hands a capture to whatever the user picks, which is what a log is for. */
private fun share(context: Context, session: Logcat.Session) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            session.file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, session.file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure { Diag.warn(AppConstants.TAG, "could not share the capture", it) }
}
