package moe.shizuku.manager.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.files.FileKind
import moe.shizuku.manager.files.FileOperations
import moe.shizuku.manager.files.PrivilegedFiles
import moe.shizuku.manager.files.icon
import moe.shizuku.manager.files.kindOf
import moe.shizuku.manager.files.mimeOf
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.utils.AppStrings

/** How the rows of a folder are ordered. Folders are always first, whatever this says. */
private enum class Sort {
    NAME,
    SIZE,
    MODIFIED
}

/** A question the screen has to ask before it can do something: a name, usually. */
private sealed interface Ask {
    val title: Int
    val initial: String

    data class Rename(override val initial: String) : Ask {
        override val title = R.string.files_action_rename
    }

    data class Folder(override val initial: String = "") : Ask {
        override val title = R.string.files_action_new_folder
    }

    data class Archive(override val initial: String) : Ask {
        override val title = R.string.files_dialog_compress
    }
}

/**
 * The filesystem, as the shell sees it.
 *
 * The purpose is the directories this app cannot open by itself. `Android/data` is the one
 * everybody wants - an app's own files, where a game keeps its saves and a messenger keeps its
 * downloads - and on any modern Android it is readable only by that app, the shell, and root.
 * Shizuku is the shell, so this screen shows it, and everything it can do it does there too.
 *
 * Tapping a file hands it to whatever app owns that kind of file, through a grant that lasts as
 * long as the intent. That matters more here than in an ordinary file manager: the file is one
 * this app cannot read, so "open with" is the only way to do anything with it at all.
 *
 * The operations are the app's own work over the service's descriptors - see [FileOperations] -
 * which is what lets a copy of a two-gigabyte folder show how far it has got and take no for an
 * answer part way through.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf(Internal) }
    var entries by remember { mutableStateOf<List<PrivilegedFiles.Entry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(Sort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Preview?>(null) }

    // Selected by full path rather than by name: two folders in one pane can hold the same name,
    // and a selection that survives a rename has to be about the file, not the row.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }

    // What was copied or cut, and whether the originals are still there. Kept across a folder
    // change, because that is the whole point of a clipboard: copy in one place, paste in another.
    var clipboard by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }

    var progress by remember { mutableStateOf<FileOperations.Progress?>(null) }
    val cancelling = remember { AtomicBoolean(false) }

    // Installing is the one thing a package is for, so tapping one goes to the installer rather
    // than through "open with" - which would offer a package installer the system has to ask
    // about, where this runs the command itself.
    var installing by remember { mutableStateOf<String?>(null) }

    val selecting = selected.isNotEmpty()

    /**
     * Runs one operation, with the progress and the cancelling it needs.
     *
     * The state the operation reports into is written from the worker thread deliberately: it is
     * the operation's own thread that knows how far it has got, and every write here is a single
     * assignment of an immutable value.
     */
    fun start(
        stage: FileOperations.Stage,
        work: suspend ((FileOperations.Progress) -> Unit, () -> Boolean) -> FileOperations.Outcome
    ) {
        if (progress != null) return
        cancelling.set(false)
        progress = FileOperations.Progress(stage, "", 0, 0)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                work({ report -> progress = report }, { cancelling.get() })
            }
            progress = null
            if (result is FileOperations.Outcome.Failed) {
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
            version++
        }
    }

    fun selectedPaths(): List<String> = entries.orEmpty()
        .filter { join(path, it.name) in selected }
        .map { join(path, it.name) }

    LaunchedEffect(path, version) {
        entries = null
        failure = null
        val loaded = withContext(Dispatchers.IO) { runCatching { PrivilegedFiles.list(path) } }
        entries = loaded.getOrNull()
        // The platform's own words when it gave any: "Permission denied" is the answer to why
        // somebody cannot see a folder, and a generic failure would throw that away.
        failure = loaded.exceptionOrNull()?.message
    }

    // A selection belongs to the folder it was made in; nothing below this is about the old one.
    LaunchedEffect(path) { selected = emptySet() }

    val shown = remember(entries, sort, descending, showHidden) {
        val visible = entries.orEmpty().filter { showHidden || !it.name.startsWith(".") }
        val by = when (sort) {
            Sort.NAME -> compareBy<PrivilegedFiles.Entry> { it.name.lowercase() }
            Sort.SIZE -> compareBy { it.size }
            Sort.MODIFIED -> compareBy { it.modified }
        }
        visible.sortedWith(
            compareByDescending<PrivilegedFiles.Entry> { it.directory }
                .then(if (descending) by.reversed() else by)
        )
    }

    installing?.let { file ->
        InstallerScreen(
            path = file,
            bottomPadding = bottomPadding,
            onBack = {
                installing = null
                // An install can replace the package under it, so the listing is read again on
                // the way back rather than left as it was.
                version++
            }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            TopAppBar(
                // One line, and room for it: six action icons in this bar left the title about
                // seventy points wide, which broke "1 selected" across three lines. They live in
                // the overflow now and the count is the only thing up here.
                title = {
                    Text(
                        stringResource(R.string.files_selected_count, selected.size),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                navigationIcon = {
                    IconButton(onClick = { selected = emptySet() }) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                    }
                },
                actions = {
                    // Delete is out here on its own because it is the one that cannot be undone,
                    // so it should not be a menu away from the finger that just selected things.
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.files_action_delete)
                        )
                    }
                    IconButton(onClick = { actionsMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = actionsMenu, onDismissRequest = { actionsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_select_all)) },
                            leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                            onClick = {
                                actionsMenu = false
                                selected = shown.map { join(path, it.name) }.toSet()
                            }
                        )
                        if (selected.size == 1) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_action_rename)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null)
                                },
                                onClick = {
                                    actionsMenu = false
                                    val only = selectedPaths().firstOrNull() ?: return@DropdownMenuItem
                                    ask = Ask.Rename(only.substringAfterLast('/'))
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_copy)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                actionsMenu = false
                                clipboard = selectedPaths() to false
                                selected = emptySet()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_cut)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCut, contentDescription = null) },
                            onClick = {
                                actionsMenu = false
                                clipboard = selectedPaths() to true
                                selected = emptySet()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_compress)) },
                            leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                            onClick = {
                                actionsMenu = false
                                val only = selectedPaths()
                                if (only.size == 1 && only.first().endsWith(".zip", ignoreCase = true)) {
                                    val archive = only.first()
                                    start(FileOperations.Stage.EXTRACTING) { report, cancelled ->
                                        FileOperations.extract(
                                            archive,
                                            join(
                                                path,
                                                archive.substringAfterLast('/').removeSuffix(".zip")
                                            ),
                                            report,
                                            cancelled
                                        )
                                    }
                                } else if (only.isNotEmpty()) {
                                    ask = Ask.Archive(defaultArchiveName(only))
                                }
                                selected = emptySet()
                            }
                        )
                    }
                }
            )
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_files)) },
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    clipboard?.let { (paths, cut) ->
                        IconButton(onClick = {
                            val target = paths
                            start(
                                if (cut) FileOperations.Stage.MOVING else FileOperations.Stage.COPYING
                            ) { report, cancelled ->
                                if (cut) {
                                    FileOperations.move(target, path, report, cancelled)
                                } else {
                                    FileOperations.copy(target, path, report, cancelled)
                                }
                            }
                            // A cut is spent once it is pasted; a copy is not, so the same files
                            // can be dropped into a second folder afterwards.
                            if (cut) clipboard = null
                            selected = emptySet()
                        }) {
                            Icon(
                                Icons.Outlined.ContentPaste,
                                contentDescription = stringResource(
                                    if (cut) R.string.files_action_paste_move
                                    else R.string.files_action_paste_copy
                                )
                            )
                        }
                    }
                    IconButton(onClick = { ask = Ask.Folder() }) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = stringResource(R.string.files_action_new_folder)
                        )
                    }
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(
                            Icons.Outlined.Sort,
                            contentDescription = stringResource(R.string.files_sort)
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        Sort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            when (option) {
                                                Sort.NAME -> R.string.files_sort_name
                                                Sort.SIZE -> R.string.files_sort_size
                                                Sort.MODIFIED -> R.string.files_sort_modified
                                            }
                                        )
                                    )
                                },
                                onClick = { sort = option; sortMenu = false }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (descending) R.string.files_sort_ascending
                                        else R.string.files_sort_descending
                                    )
                                )
                            },
                            onClick = { descending = !descending; sortMenu = false }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (showHidden) R.string.files_hide_hidden
                                        else R.string.files_show_hidden
                                    )
                                )
                            },
                            onClick = { showHidden = !showHidden; sortMenu = false }
                        )
                    }
                    IconButton(onClick = { version++ }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.device_refresh)
                        )
                    }
                }
            )
        }

        // Where we are, on a row that scrolls sideways rather than one that ellipsises: a deep
        // path is long by nature, and the end of it is the part being read.
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Roots.forEach { root ->
                RootChip(
                    label = stringResource(root.label),
                    selected = path == root.path,
                    onClick = { path = root.path }
                )
            }
        }

        val current = entries
        when {
            current == null && failure == null -> CenteredMessage { LoadingIndicator() }

            !PrivilegedFiles.isAvailable() -> CenteredMessage {
                Text(
                    text = stringResource(R.string.files_needs_shizuku),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // A refusal is the platform's, and it is worth showing rather than flattening into
            // "empty": /data as the shell is denied, and saying so is the answer to the question
            // that sent somebody here.
            failure != null -> CenteredMessage {
                Text(
                    text = stringResource(R.string.files_failed, path) +
                        "\n\n" + failure.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            shown.isEmpty() -> CenteredMessage {
                Text(
                    text = stringResource(
                        if (!showHidden && current.orEmpty().isNotEmpty()) {
                            R.string.files_all_hidden
                        } else {
                            R.string.files_empty
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 4.dp,
                    end = 16.dp,
                    bottom = bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                File(path).parent?.let { parent ->
                    item {
                        FileRow(
                            // The app's own back arrow rather than an up arrow: this row and the
                            // button in the bar do the same thing one level apart, and they read
                            // as the same control when they are drawn as the same mark.
                            //
                            // Where it goes is not written beside it: the path at the top of the
                            // screen is where you are, and its last part is the folder this row
                            // leaves for - saying it twice is what made this row read as a second
                            // heading rather than as a way out.
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            name = "..",
                            detail = null,
                            selected = false,
                            selecting = selecting,
                            onClick = { path = parent },
                            onLongClick = {}
                        )
                    }
                }

                items(shown, key = { it.name }) { entry ->
                    val full = join(path, entry.name)
                    FileRow(
                        icon = kindOf(entry.name, entry.directory).icon,
                        name = entry.name,
                        detail = if (entry.directory) null else detail(entry),
                        selected = full in selected,
                        selecting = selecting,
                        onClick = {
                            when {
                                selecting -> selected = toggle(selected, full)
                                entry.directory -> path = full
                                kindOf(entry.name, false) == FileKind.PACKAGE -> installing = full
                                !openWith(context, full, entry.name) ->
                                    preview = Preview.Loading(full)
                            }
                        },
                        onLongClick = { selected = toggle(selected, full) }
                    )
                }
            }

            ScrollToTopButton(
                listState = listState,
                bottomPadding = bottomPadding,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
            }
            }
        }
    }

    preview?.let { shown ->
        PreviewDialog(
            preview = shown,
            onDismiss = { preview = null },
            onLoaded = { loaded -> preview = loaded }
        )
    }

    progress?.let { running ->
        ProgressDialog(progress = running, onCancel = { cancelling.set(true) })
    }

    ask?.let { question ->
        NameDialog(
            ask = question,
            onDismiss = { ask = null },
            onConfirm = { name ->
                ask = null
                val trimmed = name.trim()
                if (trimmed.isEmpty()) return@NameDialog
                when (question) {
                    is Ask.Rename -> {
                        val from = selectedPaths().firstOrNull() ?: return@NameDialog
                        val to = join(path, trimmed)
                        start(FileOperations.Stage.MOVING) { _, _ ->
                            runCatching {
                                if (PrivilegedFiles.rename(from, to)) {
                                    FileOperations.Outcome.Done
                                } else {
                                    FileOperations.Outcome.Failed(AppStrings.get(R.string.files_err_rename_to, trimmed))
                                }
                            }.getOrElse { FileOperations.Outcome.Failed(it.message ?: AppStrings.get(R.string.files_err_rename)) }
                        }
                        selected = emptySet()
                    }

                    is Ask.Folder -> start(FileOperations.Stage.MOVING) { _, _ ->
                        runCatching {
                            PrivilegedFiles.mkdir(join(path, trimmed))
                            FileOperations.Outcome.Done
                        }.getOrElse {
                            FileOperations.Outcome.Failed(it.message ?: AppStrings.get(R.string.files_err_mkdir))
                        }
                    }

                    is Ask.Archive -> {
                        val target = join(path, ensureZipSuffix(trimmed))
                        val sources = selectedPaths()
                        start(FileOperations.Stage.COMPRESSING) { report, cancelled ->
                            FileOperations.compress(sources, target, report, cancelled)
                        }
                    }
                }
            }
        )
    }

    if (confirmingDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.files_dialog_delete_title, count)) },
            text = { Text(stringResource(R.string.files_dialog_delete_message)) },
            confirmButton = {
                PillButton(onClick = {
                    confirmingDelete = false
                    val targets = selectedPaths()
                    selected = emptySet()
                    start(FileOperations.Stage.DELETING) { report, _ ->
                        FileOperations.remove(targets, report)
                    }
                }) { Text(stringResource(R.string.files_action_delete)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { confirmingDelete = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/** One row: the mark for what it is, what it is called, and what is worth knowing about it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    icon: ImageVector,
    name: String,
    detail: String?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    SegmentedCard(
        // A selected row tints its card, so a multi-select pass reads at a glance instead of
        // needing the checkbox to be spotted on every row.
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        SegmentedListItem(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
            focusableOnTelevision = false,
            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                if (detail != null) {
                    Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
            trailingContent = {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = null)
                }
            },
            centerSlots = true
        )
    }
}

/**
 * A place to start from.
 *
 * The chips the app's lists use carry a count of what they hold, which is the wrong shape here -
 * a folder's size is not known until it is listed, and a chip reading "0" beside "Internal" says
 * something untrue about it.
 */
@Composable
private fun RootChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(34.dp)
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        border = BorderStroke(
            1.dp,
            if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** What an operation is doing, with the one thing a long operation has to offer: stopping it. */
@Composable
private fun ProgressDialog(progress: FileOperations.Progress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                stringResource(
                    when (progress.stage) {
                        FileOperations.Stage.COPYING -> R.string.files_working_copying
                        FileOperations.Stage.MOVING -> R.string.files_working_moving
                        FileOperations.Stage.DELETING -> R.string.files_working_deleting
                        FileOperations.Stage.COMPRESSING -> R.string.files_working_compressing
                        FileOperations.Stage.EXTRACTING -> R.string.files_working_extracting
                    }
                )
            )
        },
        text = {
            Column {
                Text(progress.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(12.dp))
                val fraction = progress.fraction
                if (fraction == null) {
                    // Nothing knows the total yet: the walk that counts the work is itself the
                    // first part of the work, and a bar at zero would say it had not started.
                    LoadingIndicator()
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            PillButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/** A name to type: the three operations that need one, and nothing else about them. */
@Composable
private fun NameDialog(ask: Ask, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(ask) { mutableStateOf(ask.initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(ask.title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.files_field_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            PillButton(onClick = { onConfirm(value) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/**
 * Hands the file to another app.
 *
 * The uri points at this app's own provider, and the flag is what lets the receiving app read
 * through it - the grant is scoped to the uri and lasts as long as the intent, so nothing is
 * exposed to anybody the user did not just hand a file to.
 *
 * Returns whether anything took it, so the caller can fall back to showing the file.
 */
private fun openWith(context: Context, path: String, name: String): Boolean {
    val uri = Uri.Builder()
        .scheme("content")
        // The authority as it is now: the rename rewrites the manifest's authorities to the new
        // package, so a URI built from the build-time id addresses a provider that is not there.
        .authority("${context.packageName}.files")
        .path(path)
        .build()

    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * Reads the start of a file for the preview dialog.
 *
 * Off the main thread and capped, because this is a path the app has no idea about: a directory
 * entry saying 40 GB is a real thing in `/proc` and reading it would be the end of the app.
 */
private fun loadPreview(path: String): Preview {
    val descriptor = try {
        PrivilegedFiles.openRead(path)
    } catch (t: Throwable) {
        return Preview.Unreadable(path)
    }

    return try {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> readHead(input, path) }
    } catch (t: Throwable) {
        Preview.Unreadable(path)
    }
}

/** The first [PreviewLimit] bytes, or fewer if that is all there is. */
private fun readHead(input: java.io.InputStream, path: String): Preview {
    val buffer = ByteArray(PreviewLimit)
    var filled = 0
    while (filled < buffer.size) {
        val read = input.read(buffer, filled, buffer.size - filled)
        if (read <= 0) break
        filled += read
    }

    val text = buffer.copyOf(filled).toString(Charsets.UTF_8)
    // A NUL is what tells a text file from a photo, and it is what every viewer uses.
    return if (text.contains('\u0000')) {
        Preview.NotText(path)
    } else {
        Preview.Shown(path, text, filled == PreviewLimit)
    }
}

/**
 * What the preview dialog is showing, including the two states before there is anything to.
 *
 * The names avoid `Text`, which in this file would be the Material composable rather than the
 * state: a state called Text inside a @Composable reads as the thing that draws it.
 */
private sealed interface Preview {
    val path: String

    data class Loading(override val path: String) : Preview
    data class Shown(override val path: String, val text: String, val truncated: Boolean) : Preview
    data class NotText(override val path: String) : Preview
    data class Unreadable(override val path: String) : Preview
}

@Composable
private fun PreviewDialog(preview: Preview, onDismiss: () -> Unit, onLoaded: (Preview) -> Unit) {
    // The read belongs to the dialog that asked for it: it is the thing on screen while the file
    // is being opened, and the state it lands in - text, not text, unreadable - is its own to
    // show. Keyed by path, so opening a second file replaces the first rather than racing it.
    LaunchedEffect(preview.path) {
        if (preview !is Preview.Loading) return@LaunchedEffect
        onLoaded(withContext(Dispatchers.IO) { loadPreview(preview.path) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                preview.path.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            when (preview) {
                is Preview.Loading -> LoadingIndicator()

                is Preview.Shown -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (preview.truncated) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.files_preview_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is Preview.NotText -> Text(stringResource(R.string.files_preview_binary))

                is Preview.Unreadable -> Text(stringResource(R.string.files_preview_failed))
            }
        },
        confirmButton = {
            PillButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}

/** A place to start from, which is most of what a file browser's roots are for. */
private data class Root(val path: String, val label: Int)

private val Internal = "/sdcard"

private val Roots = listOf(
    Root(Internal, R.string.files_root_internal),
    Root("/sdcard/Android/data", R.string.files_root_app_data),
    Root("/sdcard/Android/obb", R.string.files_root_app_obb),
    // Where the shell itself keeps things, and where every instruction that starts a daemon or
    // pushes a binary into place points: somewhere an app can neither read nor write.
    Root("/data/local/tmp", R.string.files_root_tmp),
    Root("/system", R.string.files_root_system),
    Root("/", R.string.files_root_root)
)

/** How much of a file the preview reads. Enough for a config, a log tail or a script. */
private const val PreviewLimit = 64 * 1024

private fun join(parent: String, name: String): String =
    if (parent.endsWith("/")) parent + name else "$parent/$name"

private fun toggle(selection: Set<String>, path: String): Set<String> =
    if (path in selection) selection - path else selection + path

/** What to call a new zip: one item's name, or the count when several were picked. */
private fun defaultArchiveName(paths: List<String>): String = when {
    paths.isEmpty() -> "archive"
    paths.size == 1 -> paths.first().substringAfterLast('/')
    else -> "${paths.size} items"
}

private fun ensureZipSuffix(name: String): String =
    if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"

/** Size and date on one line: the two things that decide whether a file is the one you want. */
private fun detail(entry: PrivilegedFiles.Entry): String {
    val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.modified))
    return "${sizeText(entry.size)} · $date"
}

/** Sizes as a person reads them, because a byte count in a list is a number nobody parses. */
private fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
