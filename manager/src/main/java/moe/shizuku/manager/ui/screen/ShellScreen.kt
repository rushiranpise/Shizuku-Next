@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.R
import moe.shizuku.manager.shell.ShellBackend
import moe.shizuku.manager.shell.LibraryCommand
import moe.shizuku.manager.shell.ShellHistory
import moe.shizuku.manager.shell.ShellHostCommands
import moe.shizuku.manager.ui.component.stripHtmlTags
import moe.shizuku.manager.shell.ShellBookmarks
import moe.shizuku.manager.shell.ShellContinuity
import moe.shizuku.manager.shell.ShellCommands
import moe.shizuku.manager.shell.ShellOutput
import moe.shizuku.manager.shell.ShellSuggestion
import moe.shizuku.manager.shell.ShellSuggestions
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.appLabel
import moe.shizuku.manager.manage.InstalledPackages
import moe.shizuku.manager.shell.ShellLine
import moe.shizuku.manager.shell.ShellSession
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

/** How much scrollback to keep. A command like `logcat` would otherwise grow without end. */
private const val MAX_LINES = 2000


/**
 * One of the commands the shell offers, which are the ones the manager already runs itself.
 *
 * [insertOnly] is the split that matters: something that only reads the device can be run by
 * a tap, and something that changes it cannot `pm grant` needs a package and a permission,
 * and a chip that fired it half-written would be a trap. Those write their command into the
 * input instead, so what runs is what you can read.
 */
private data class QuickCommand(
    @StringRes val label: Int,
    val command: String,
    val insertOnly: Boolean = false,
    /**
     * Whether a package alone finishes the command. Nothing here is complete on a package
     * except force stop: the rest take an argument after it, and the space left behind is
     * where that argument goes.
     */
    val needsArgument: Boolean = true
)

private val QUICK = listOf(
    QuickCommand(R.string.shell_quick_battery, "dumpsys battery"),
    QuickCommand(R.string.shell_quick_storage, "df -h /data /sdcard"),
    QuickCommand(R.string.shell_quick_device, "getprop ro.product.model; getprop ro.build.version.release"),
    QuickCommand(R.string.shell_quick_apps, "pm list packages -3 | sort"),
    QuickCommand(R.string.shell_quick_grant, "pm grant ", insertOnly = true),
    QuickCommand(R.string.shell_quick_revoke, "pm revoke ", insertOnly = true),
    QuickCommand(R.string.shell_quick_app_ops, "cmd appops set ", insertOnly = true),
    QuickCommand(R.string.shell_quick_force_stop, "am force-stop ", insertOnly = true, needsArgument = false)
)

/**
 * A `su` with nothing to run, which is the form that needs a terminal: `su -c id` carries
 * its command and does not.
 */
private val BARE_SU = Regex("""^su(?:\s+-\s*)?$""")

/**
 * The shell, in the app rather than in a terminal app.
 *
 * Every command is its own process see [ShellSession] for why a real tty is not on offer
 * here with the working directory and anything exported to `export` carried from one to the
 * next, so it reads like a session even though nothing outlives a command. Two backends, and
 * the same screen for both: through Shizuku (whatever uid the server runs as: 2000 over adb,
 * 0 with root, 1000 with the exploit) or through `su`, which works with Shizuku stopped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(bottomPadding: Dp = 0.dp, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // The session, the output and the channel it arrives on outlive this screen, because it is
    // opened from the Labs list rather than being a page: leaving it to check something and
    // coming back has to find what was run still there. See ShellContinuity.
    val session = ShellContinuity.session
    val listState = rememberLazyListState()

    val lines = ShellContinuity.lines
    // The command runs off the main thread and writes here; the UI drains it on the main
    // one, which is why streaming output does not need a recomposition per line.
    val incoming = ShellContinuity.incoming

    // A TextFieldValue rather than a plain String, for one reason: a chip that fills the
    // input has to leave the caret at the end of what it wrote. With a String the caret
    // stayed where it was at the start of an empty field so the rest of the command was
    // typed in front of the template ("com.foo pm grant").
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var backend by rememberSaveable { mutableStateOf(ShellBackend.SHIZUKU) }
    var running by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf(session.cwd) }
    // A chip that fills the input leaves the cursor in it, so the command can be finished
    // without reaching for the field again.
    val focus = remember { FocusRequester() }
    // A chip that needs an app to act on asks for one, rather than handing over a template
    // with a hole where the package goes.
    var pickFor by remember { mutableStateOf<QuickCommand?>(null) }

    /** Whether the list of what has been run is open. */
    var historyOpen by remember { mutableStateOf(false) }

    // Commands worth keeping: the ones that took a while to work out. Read once per screen.
    var bookmarks by remember { mutableStateOf(ShellBookmarks.load(context)) }
    var sheetOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<NameRequest?>(null) }
    var sortByName by remember { mutableStateOf(false) }
    val feedback = remember { SnackbarHostState() }
    // Deleting happens in the sheet, and a snackbar lives behind a modal sheet rather than over
    // it, so the offer to undo is made where the deletion was: in the sheet's own header, for
    // as long as the undo is useful.
    var undoable by remember { mutableStateOf<ShellBookmarks.Bookmark?>(null) }

    // The library, and the command being filled in from it. A command with placeholders is
    // asked about before it is put in the input, so what is filled in is a whole command.
    var libraryOpen by remember { mutableStateOf(false) }
    var libraryQuery by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<LibraryCommand?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var packageFor by remember { mutableStateOf<String?>(null) }

    // Finding one line in a few thousand. The rows are kept and the matches marked rather than
    // filtered: in a dump, the line above the answer is usually part of the answer.
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchAt by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // Opening the search puts the caret in it, because a search box that has to be tapped
    // after being opened is a search box with a step missing.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) runCatching { searchFocus.requestFocus() } }

    // Recomputed when the output grows or the query changes, and never per frame.
    val printed = remember(lines.size) { lines.map { it.text } }
    val matches = remember(lines.size, searchQuery) { ShellOutput.matchingLines(printed, searchQuery) }

    // Writing the output out, through the system file picker: no storage permission, and the
    // file lands where the user chose rather than somewhere only this app can reach.
    val saveOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val saved = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(printed.joinToString("\n").toByteArray())
            }
            true
        }.getOrDefault(false)
        scope.launch {
            feedback.showSnackbar(
                context.getString(
                    if (saved) R.string.shell_output_saved else R.string.shell_output_save_failed
                )
            )
        }
    }

    // Read once, and shared: the suggestions offer these apps and their permissions, and the
    // picker is the same list in a dialog.
    var installed by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    val suggestions = remember(installed) {
        ShellSuggestions.from(context.packageManager, installed)
    }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var uid by remember { mutableIntStateOf(-1) }
    // What has been run, newest first, kept in the app's preferences: the arrows above the
    // input walk it and the history sheet lists the same list, so a command survived leaving
    // the screen. It was in memory only, which lost everything on navigation.
    var history by remember { mutableStateOf(ShellHistory.load(context)) }
    var historyIndex by remember { mutableIntStateOf(-1) }

    val shizukuRunning = ShizukuStateMachine.isRunning()

    fun feed(line: ShellLine) {
        incoming.trySend(line)
    }

    LaunchedEffect(Unit) {
        // Probing root spawns a shell, so it happens once, off the main thread.
        rootAvailable = withContext(Dispatchers.IO) {
            runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
        }
        // The suggestions are about other apps, so this has to be the shell's list rather than
        // this app's: a permission held by a package the app cannot see is still a permission
        // worth completing.
        installed = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            InstalledPackages.all(context, PackageManager.GET_PERMISSIONS)
        }
        uid = withContext(Dispatchers.IO) {
            runCatching { if (Shizuku.pingBinder()) Shizuku.getUid() else -1 }.getOrDefault(-1)
        }
        feed(ShellLine(context.getString(R.string.shell_intro), ShellLine.Kind.INFO))
        // Where it opens is the shell's call: shared storage if the device has it, the tmp
        // directory otherwise. Either way it is only a starting point.
        val opened = withContext(Dispatchers.IO) {
            runCatching { session.openInPreferredDirectory(::feed) }.getOrDefault(session.cwd)
        }
        cwd = opened
    }

    // A search that counted the matches without going to them would be a riddle, so each step
    // through them is a scroll.
    LaunchedEffect(matchAt, matches) {
        matches.getOrNull(matchAt)?.let { listState.animateScrollToItem(it) }
    }

    LaunchedEffect(Unit) {
        for (line in incoming) {
            val atBottom = !listState.canScrollForward
            lines.add(line)
            if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
            // Follow the output unless the reader has scrolled back to read something.
            if (atBottom) listState.scrollToItem(lines.lastIndex)
        }
    }

    /**
     * Runs one line of input, or handles the two commands that are this side's business:
     * `cd`, which the shell resolves for us, and `export`, which has nothing to live in.
     */
    fun submit(raw: String) {
        // `adb shell ls` is what a computer types, and it arrives here out of habit. The
        // prefix is dropped and said out loud, because a command that ran somewhere other
        // than where it looks like it ran is worth one line of honesty.
        val inner = ShellSuggestions.withoutAdbPrefix(raw)
        var command = (inner ?: raw).trim()

        // A command copied out of a guide often arrives wrapped in quotes, which the shell
        // would otherwise hand to the command as part of its first argument.
        if (command.length >= 2 &&
            ((command.startsWith("\"") && command.endsWith("\"")) ||
                (command.startsWith("'") && command.endsWith("'")))
        ) {
            command = command.substring(1, command.length - 1).trim()
        }

        if (command.isEmpty() || running) return

        field = TextFieldValue("")
        // `clear` is this screen's own command, and it runs before the echo so the echo goes
        // with it: a shell with no terminal would instead paint a screen it does not have and
        // hand back the escape codes for it.
        if (command == "clear") {
            lines.clear()
            return
        }
        if (inner != null) {
            feed(ShellLine(context.getString(R.string.shell_adb_prefix_dropped), ShellLine.Kind.INFO))
        }
        feed(ShellLine("$cwd $ $command", ShellLine.Kind.COMMAND))
        history = ShellHistory.record(context, command)
        historyIndex = -1

        if (session.isPlainCd(command)) {
            val target = session.cdTarget(command)
            running = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { session.cd(target, ::feed) }.getOrDefault(false)
                }
                if (!ok) feed(ShellLine("cd: no such directory: $target", ShellLine.Kind.ERROR))
                cwd = session.cwd
                running = false
            }
            return
        }

        if (session.export(command)) {
            feed(ShellLine("exported ${command.trim().removePrefix("export").trim()}", ShellLine.Kind.INFO))
            return
        }

        // Two more that the shell cannot answer for itself: a bare `su` waits at a permission
        // prompt that nothing here can show, and `exit` would exit a shell that does not
        // outlive the command anyway.
        if (command == "exit") {
            feed(ShellLine(context.getString(R.string.shell_exit_note), ShellLine.Kind.INFO))
            return
        }
        if (BARE_SU.matches(command) && backend != ShellBackend.ROOT) {
            feed(ShellLine(context.getString(R.string.shell_su_needs_root), ShellLine.Kind.INFO))
            return
        }

        // The computer's half of adb, answered here for the same reason as the three above:
        // "not found" from a shell that never had these commands explains nothing, and this
        // is the one mistake somebody used to a computer makes first.
        ShellHostCommands.hintFor(command)?.let { hint ->
            feed(ShellLine(context.getString(hint).stripHtmlTags(), ShellLine.Kind.INFO))
            return
        }

        // Running it through Shizuku after the user asked for root would answer with the
        // wrong uid and no hint that it did, so say it instead.
        if (backend == ShellBackend.ROOT && rootAvailable != true) {
            feed(ShellLine(context.getString(R.string.shell_root_refused), ShellLine.Kind.ERROR))
            return
        }
        val targetBackend = backend

        // Recorded where the command actually runs rather than where it is submitted, so the
        // ones filled in from a chip, the library or a suggestion are in the list as well:
        // what is worth keeping is what ran, not what was typed.
        history = ShellHistory.record(context, command)

        running = true
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching { session.run(targetBackend, command, ::feed) }.getOrDefault(-1)
            }
            if (code != 0) {
                feed(ShellLine("exit $code", ShellLine.Kind.EXIT))
            }
            running = false
        }
    }

    /**
     * Fills the input from a chip and a picked package: the template, the package, and the
     * space the next argument goes in, with the caret after it.
     */
    /** Puts a whole command in the input, with the caret after it, ready to run or edit. */
    fun fill(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
        focus.requestFocus()
    }

    fun fillFromChip(quick: QuickCommand, packageName: String) {
        fill(
            buildString {
                append(quick.command)
                append(packageName)
                if (quick.needsArgument) append(' ')
            }
        )
    }

    /**
     * Puts whatever is on the clipboard in the input.
     *
     * The clipboard is read on the tap rather than watched, so the button never reports the
     * state of something else, and a multi-line copy is joined: a shell runs one line, and
     * pasting three lines into the field would only look like three commands.
     */
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()

        if (text.isNullOrEmpty()) {
            scope.launch { feedback.showSnackbar(context.getString(R.string.shell_paste_empty)) }
            return
        }

        val single = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        fill(single)
    }

    fun saveBookmark(command: String, name: String) {
        if (command.isBlank()) return
        bookmarks = listOf(ShellBookmarks.add(context, name, command)) + bookmarks
        scope.launch { feedback.showSnackbar(context.getString(R.string.shell_bookmark_saved)) }
    }

    /**
     * Removing one offers to put it back, the way the rest of the app treats a destructive tap:
     * a name worth keeping is usually a command worth keeping, and a mis-tap should cost one
     * more tap rather than the entry.
     */
    fun deleteBookmark(bookmark: ShellBookmarks.Bookmark) {
        bookmarks = bookmarks.filterNot { it.id == bookmark.id }
        ShellBookmarks.remove(context, bookmark.id)
        undoable = bookmark
    }

    fun recall(direction: Int) {
        if (history.isEmpty()) return

        // Newest first in the store: -1 walks back through what was run and +1 forward, and
        // walking past the newest is the blank input the walk started from.
        historyIndex = (historyIndex - direction).coerceIn(-1, history.size - 1)
        val recalled = if (historyIndex < 0) "" else history[historyIndex].command
        field = TextFieldValue(recalled, TextRange(recalled.length))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The keyboard covers the input otherwise, and a shell without its input line is
            // just a log.
            .imePadding()
            // The input line is the last thing on this screen and the navigation bar floats
            // over the bottom of every page, so the page has to end above it: without this the
            // field and the Run button sit under the bar, which is the one control a shell
            // cannot do without.
            .padding(bottom = bottomPadding)
    ) {
        if (searching) {
            // The search takes the bar rather than a row of its own: the title says nothing
            // while you are looking for a line, and the space is worth more to the query.
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it; matchAt = 0 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocus),
                        placeholder = { Text(stringResource(R.string.shell_search_hint)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                },
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                navigationIcon = {
                    IconButton(onClick = { searching = false; searchQuery = "" }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.shell_search_close)
                        )
                    }
                },
                actions = {
                    if (searchQuery.isNotBlank()) {
                        Text(
                            stringResource(
                                R.string.shell_search_position,
                                if (matches.isEmpty()) 0 else matchAt + 1,
                                matches.size
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { matchAt = (matchAt - 1 + matches.size) % matches.size },
                            enabled = matches.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.shell_search_previous)
                            )
                        }
                        IconButton(
                            onClick = { matchAt = (matchAt + 1) % matches.size },
                            enabled = matches.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.shell_search_next)
                            )
                        }
                    }
                }
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.shell_title))
                        Text(
                            text = when {
                                backend == ShellBackend.ROOT ->
                                    stringResource(R.string.shell_backend_root_status)
                                uid >= 0 -> stringResource(R.string.shell_backend_shizuku_status, uid)
                                else -> stringResource(R.string.shell_backend_offline)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                // An arrow only when this was opened from the Labs list: it used to be a tab,
                // where the bar underneath was how you left, and it is a screen over the tabs
                // now, so the arrow is the way back.
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    }
                },
                actions = {
                    // Always offered, because the most common thing to do with a shell is run
                    // something copied from somewhere else, and that is the first thing you
                    // want on an empty screen as much as on a full one.
                    IconButton(onClick = { pasteFromClipboard() }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = stringResource(R.string.shell_paste)
                        )
                    }
                    if (field.text.isNotBlank()) {
                        IconButton(onClick = { naming = NameRequest(command = field.text.trim()) }) {
                            Icon(
                                Icons.Outlined.BookmarkAdd,
                                contentDescription = stringResource(R.string.shell_bookmark_save)
                            )
                        }
                    }
                    if (lines.isNotEmpty()) {
                        IconButton(onClick = { searching = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.shell_search)
                            )
                        }
                        // The rest are used often enough to keep, and rare enough not to hold a
                        // place in the bar on a screen where the log wants the room.
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.shell_more)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_copy)) },
                                onClick = {
                                    menuOpen = false
                                    clipboard.setText(AnnotatedString(printed.joinToString("\n")))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_save_output)) },
                                onClick = {
                                    menuOpen = false
                                    saveOutput.launch(defaultFileName())
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (expanded) R.string.shell_collapse
                                            else R.string.shell_expand
                                        )
                                    )
                                },
                                onClick = { menuOpen = false; expanded = !expanded }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_clear)) },
                                onClick = { menuOpen = false; lines.clear() }
                            )
                        }
                    }
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = backend == ShellBackend.SHIZUKU,
                onClick = { backend = ShellBackend.SHIZUKU },
                enabled = shizukuRunning,
                label = { Text(stringResource(R.string.shell_backend_shizuku)) }
            )
            // Never disabled: a chip that cannot be pressed is also a chip that cannot ask
            // for root, and asking is what makes the root manager offer the grant.
            FilterChip(
                selected = backend == ShellBackend.ROOT,
                onClick = {
                    backend = ShellBackend.ROOT
                    if (rootAvailable != true) {
                        scope.launch {
                            val granted = withContext(Dispatchers.IO) {
                                runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
                            }
                            rootAvailable = granted
                            if (granted) {
                                feed(ShellLine(context.getString(R.string.shell_root_granted), ShellLine.Kind.INFO))
                            } else {
                                feed(ShellLine(context.getString(R.string.shell_root_refused), ShellLine.Kind.ERROR))
                                backend = ShellBackend.SHIZUKU
                            }
                        }
                    }
                },
                label = { Text(stringResource(R.string.shell_backend_root)) }
            )
            Text(
                // The tail is the part that says where you are, so a long path keeps its
                // end and loses its beginning rather than the other way round.
                text = if (cwd.length > 24) "…" + cwd.takeLast(23) else cwd,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }

        if (!shizukuRunning && rootAvailable != true) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    stringResource(R.string.shell_needs_a_backend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Selectable, so one line or part of one can be copied without taking the lot.
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        OutputLineText(
                            line = line,
                            query = if (searching) searchQuery else "",
                            current = matches.getOrNull(matchAt) == index
                        )
                    }
                }
            }
            // Only while the log has run on past what is on screen: a button in the corner
            // that is almost always doing nothing is a button that should not be there. New
            // output scrolls the log itself, so this is for coming back after reading back.
            if (listState.canScrollForward) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Rounded.KeyboardDoubleArrowDown,
                        contentDescription = stringResource(R.string.shell_jump_to_newest)
                    )
                }
            }

            // At the bottom of the log rather than the screen: the input row and the keyboard
            // are both down there, and a message about a bookmark does not need to sit on them.
            SnackbarHost(
                hostState = feedback,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
            )
        }

        // One row with two jobs, at the same height either way so starting to type does not
        // move the log under the reader's eyes: the quick commands when nothing is typed, and
        // suggestions for what is being typed once there is. The saved commands lead the row in
        // both a row that lost its way into them the moment something was typed would hide
        // the very command that was just saved.
        // The tools give the log the whole screen when it is expanded: an output worth
        // searching is an output worth reading without a chip row in the way.
        if (!expanded) {
            Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                val offered = remember(field.text, suggestions) {
                    if (field.text.isBlank()) emptyList()
                    else ShellSuggestions.forInput(field.text, suggestions)
                }
                LazyRow(
                    // CenterStart, not CenterVertically: this is a Box, and the row should start at
                    // the left edge while it is centred in the row's height.
                    modifier = Modifier.align(Alignment.CenterStart),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        AssistChip(
                            onClick = { historyOpen = true },
                            enabled = !running,
                            label = { Text(stringResource(R.string.shell_history)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { sheetOpen = true },
                            enabled = !running,
                            label = { Text(stringResource(R.string.shell_bookmarks)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { libraryOpen = true },
                            enabled = !running,
                            label = { Text(stringResource(R.string.shell_library)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.LibraryBooks,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                    if (field.text.isBlank()) {
                        items(QUICK) { quick ->
                            AssistChip(
                                onClick = {
                                    if (quick.insertOnly) {
                                        pickFor = quick
                                    } else {
                                        submit(quick.command)
                                    }
                                },
                                enabled = !running,
                                label = { Text(stringResource(quick.label)) }
                            )
                        }
                    } else {
                        items(offered) { suggestion ->
                            SuggestionCard(
                                suggestion = suggestion,
                                onClick = {
                                    val filled = ShellSuggestions.insertInto(field.text, suggestion.insert)
                                    field = TextFieldValue(filled, TextRange(filled.length))
                                    focus.requestFocus()
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.shell_input_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(field.text) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                IconButton(onClick = { recall(-1) }, enabled = history.isNotEmpty()) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.shell_history_previous)
                    )
                }
                IconButton(onClick = { recall(1) }, enabled = history.isNotEmpty()) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.shell_history_next)
                    )
                }
                if (running) {
                    // Only the Shizuku backend can be cut short: a root command runs inside the
                    // root shell's job and there is no handle on the process it ends up in.
                    IconButton(
                        onClick = { session.stop() },
                        enabled = backend == ShellBackend.SHIZUKU
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.shell_stop)
                        )
                    }
                } else {
                    IconButton(onClick = { submit(field.text) }, enabled = field.text.isNotBlank()) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = stringResource(R.string.shell_run)
                        )
                    }
                }
            }
        }
    }

    naming?.let { request ->
        NameDialog(
            title = stringResource(
                if (request.bookmark != null) R.string.shell_bookmark_rename
                else R.string.shell_bookmark_save
            ),
            initial = request.bookmark?.name ?: request.command.orEmpty().substringBefore(' '),
            onDismiss = { naming = null },
            onConfirm = { name ->
                val editing = request.bookmark
                if (editing != null) {
                    // No notice for this one: the name changes in the list in front of you.
                    ShellBookmarks.rename(context, editing.id, name)
                    bookmarks = ShellBookmarks.load(context)
                } else {
                    saveBookmark(request.command.orEmpty(), name)
                }
                naming = null
            }
        )
    }

    if (historyOpen) {
        ShellHistorySheet(
            entries = history,
            onFill = { command ->
                historyOpen = false
                fill(command)
            },
            onRun = { command ->
                historyOpen = false
                submit(command)
            },
            onForget = { command -> history = ShellHistory.forget(context, command) },
            onClear = { history = ShellHistory.clear(context) },
            onDismiss = { historyOpen = false }
        )
    }

    if (libraryOpen) {
        LibrarySheet(
            query = libraryQuery,
            onQueryChange = { libraryQuery = it },
            onDismiss = { libraryOpen = false },
            onPick = { entry ->
                libraryOpen = false
                if (ShellCommands.variablesOf(entry.command).isEmpty()) {
                    fill(entry.command)
                } else {
                    // The sheet goes first so the dialog is not stacked behind it.
                    pending = entry
                    values = emptyMap()
                }
            }
        )
    }

    pending?.let { entry ->
        VariablesDialog(
            command = entry.command,
            values = values,
            onValueChange = { name, value -> values = values + (name to value) },
            onPickPackage = { name -> packageFor = name },
            onDismiss = { pending = null; values = emptyMap() },
            onConfirm = {
                fill(ShellCommands.filled(entry.command, values))
                pending = null
                values = emptyMap()
            }
        )
    }

    packageFor?.let { name ->
        PackagePickerDialog(
            title = stringResource(R.string.shell_variables_pick_app),
            installed = installed,
            onDismiss = { packageFor = null }
        ) { packageName ->
            values = values + (name to packageName)
            packageFor = null
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            val ordered = if (sortByName) {
                bookmarks.sortedBy { it.name.lowercase() }
            } else {
                bookmarks.sortedByDescending { it.addedAt }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.shell_bookmarks),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (bookmarks.size > 1) {
                    PillButtonQuiet(onClick = { sortByName = !sortByName }) {
                        Text(
                            stringResource(
                                if (sortByName) R.string.shell_bookmark_sort_name
                                else R.string.shell_bookmark_sort_newest
                            )
                        )
                    }
                }
            }

            // The undo offer, in the sheet rather than in a snackbar: a snackbar is behind a
            // modal sheet, which is exactly where nobody would see it.
            undoable?.let { deleted ->
                LaunchedEffect(deleted.id) {
                    kotlinx.coroutines.delay(8000)
                    if (undoable?.id == deleted.id) undoable = null
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.shell_bookmark_deleted_name, deleted.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    PillButtonQuiet(onClick = {
                        ShellBookmarks.restore(context, deleted)
                        bookmarks = ShellBookmarks.load(context)
                        undoable = null
                    }) { Text(stringResource(R.string.action_undo)) }
                }
            }

            if (ordered.isEmpty()) {
                Text(
                    stringResource(R.string.shell_bookmark_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(ordered, key = { it.id }) { bookmark ->
                        ListItem(
                            // The row fills the input rather than running it. A saved command is
                            // one that changes something often enough to have been worth saving,
                            // so it is put where it can be read and run deliberately; the play
                            // button beside it is for when it is already known to be right.
                            modifier = Modifier.clickable {
                                fill(bookmark.command)
                                sheetOpen = false
                            },
                            headlineContent = { Text(bookmark.name) },
                            supportingContent = {
                                Text(
                                    bookmark.command,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        sheetOpen = false
                                        submit(bookmark.command)
                                    }) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = stringResource(R.string.shell_bookmark_run)
                                        )
                                    }
                                    IconButton(onClick = { naming = NameRequest(bookmark = bookmark) }) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = stringResource(R.string.shell_bookmark_rename)
                                        )
                                    }
                                    IconButton(onClick = { deleteBookmark(bookmark) }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.shell_bookmark_delete)
                                        )
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    pickFor?.let { quick ->
        PackagePickerDialog(
            title = stringResource(quick.label),
            installed = installed,
            onDismiss = { pickFor = null }
        ) { packageName ->
            pickFor = null
            fillFromChip(quick, packageName)
        }
    }
}

/**
 * One suggestion, as a small card: what tapping it writes, and what it is. The second line is
 * what makes it usable `Greenify` and `com.oasisfeng.greenify` are the same thing only once
 * you have seen both, and `CAMERA` is only the end of a permission nobody types out in full.
 */
/** A name for a saved output that sorts by when it was taken and says what made it. */
private fun defaultFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        .format(java.util.Date())
    return "shell-$stamp.txt"
}

/**
 * One line of output, with the matches in the current search marked.
 *
 * The line holding the match being looked at is marked more strongly than the rest, so the
 * counter in the bar and the place on screen are the same thing.
 */
@Composable
private fun OutputLineText(line: ShellLine, query: String, current: Boolean) {
    val colour = when (line.kind) {
        ShellLine.Kind.COMMAND -> MaterialTheme.colorScheme.primary
        ShellLine.Kind.ERROR -> MaterialTheme.colorScheme.error
        ShellLine.Kind.EXIT -> MaterialTheme.colorScheme.error
        ShellLine.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        ShellLine.Kind.OUTPUT -> MaterialTheme.colorScheme.onSurface
    }

    if (query.isBlank()) {
        Text(
            text = line.text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = colour
        )
        return
    }

    val mark = if (current) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val onMark = if (current) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    val marked = buildAnnotatedString {
        var cursor = 0
        ShellOutput.matchRangesOf(line.text, query).forEach { range ->
            if (range.first > cursor) append(line.text.substring(cursor, range.first))
            withStyle(SpanStyle(background = mark, color = onMark)) {
                append(line.text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < line.text.length) append(line.text.substring(cursor))
    }

    Text(
        text = marked,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = colour
    )
}

@Composable
private fun SuggestionCard(suggestion: ShellSuggestion, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(min = 148.dp, max = 288.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                suggestion.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                suggestion.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** What the name dialog is for: a command being saved, or a bookmark being renamed. */
private class NameRequest(
    val command: String? = null,
    val bookmark: ShellBookmarks.Bookmark? = null
)

/** Asks what to call a command, for saving it or renaming it. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.shell_bookmark_name_hint)) },
                singleLine = true
            )
        },
        confirmButton = {
            PillButton(onClick = { onConfirm(name) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/**
 * The app a chip is about to act on.
 *
 * Read straight from the local package manager, so it needs no server and lists what is
 * installed rather than what has asked Shizuku for anything. Search is by label or package
 * name, which is why it sits above a list of every app on the device.
 */
@Composable
private fun PackagePickerDialog(
    title: String,
    installed: List<PackageInfo>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    var query by remember { mutableStateOf("") }
    val loading = installed.isEmpty()
    val apps = remember(installed) { installed.sortedBy { appLabel(pm, it).lowercase() } }

    val shown = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                appLabel(pm, it).contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.manage_search_hint)) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) { LoadingIndicator() }

                    shown.isEmpty() -> Text(
                        stringResource(R.string.apps_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )

                    else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.packageName }) { pi ->
                            ListItem(
                                modifier = Modifier.clickable { onPick(pi.packageName) },
                                leadingContent = { AppIcon(pi) },
                                headlineContent = { Text(appLabel(pm, pi)) },
                                supportingContent = {
                                    Text(
                                        pi.packageName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            }
        },
        // Picking an app is the whole answer, so there is nothing left to confirm.
        confirmButton = {},
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
