package moe.shizuku.manager.ui.screen

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import moe.shizuku.manager.ui.component.AppAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.WifiBackup
import moe.shizuku.manager.manage.WifiControl
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.manage.WifiPasswords
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.SegmentedListItem

/**
 * One row: the name, the key from the privileged read, and the id the platform knows the network
 * by.
 *
 * Two sources, because neither has both halves. The key is only in the privileged list, which
 * carries no id; the id is only in what `cmd wifi` prints, which carries no key. They are joined
 * on the name, which is what a person sees the network as.
 */
private data class WifiRow(
    val ssid: String,
    val password: String,
    val security: String,
    /** Null when the shell's list does not have this name, so nothing can be done to it. */
    val id: Int?
)

/**
 * The passwords of the networks this phone has saved, and what can be done to them.
 *
 * The list Settings keeps is the same one, and every row on it is a row of dots: the value is not
 * there to be read. This screen is the way to the real one, which is what Shizuku buys - see
 * [WifiPasswords] for what the read is and why it is the shell's to make, and [WifiControl] for
 * the other half: connecting to a network and forgetting one, which the platform's own `cmd wifi`
 * will do for this app's uid once it has been handed the key that only the read can produce.
 *
 * Hidden by default and revealed a row at a time, because a screen full of wifi keys is a screen
 * somebody reads off a photograph of. The copy is marked sensitive for the same reason: the
 * system's own clipboard preview puts what was copied at the top of the screen, which is the last
 * place a password should be.
 *
 * The network the phone is joined to is drawn first and said to be connected, because it is the
 * one row somebody is usually looking for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiPasswordsScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<WifiPasswords.Outcome?>(null) }
    var connected by remember { mutableStateOf<String?>(null) }
    // Null while the radio's state is unknown, which is not the same as off: the hint below is
    // only shown when the phone said so.
    var radioOn by remember { mutableStateOf<Boolean?>(null) }
    var ids by remember { mutableStateOf(emptyMap<String, Int>()) }
    // By SSID rather than by index: a re-read can reorder the list, and a row that reopened with
    // the password of the row above it is worse than one that closed again.
    var revealed by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf<WifiRow?>(null) }
    var forgettingAll by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // What an export would write, kept from the last read so the file picker can be answered
    // without asking the phone again for keys the screen is already holding.
    var readable by remember { mutableStateOf(emptyList<WifiPasswords.SavedNetwork>()) }
    var exportRows by remember { mutableStateOf(emptyList<WifiBackup.Network>()) }
    var exportFormat by remember { mutableStateOf(WifiBackup.Format.COMPRESSED) }
    var exportPassword by remember { mutableStateOf("") }
    var choosingExport by remember { mutableStateOf(false) }
    // A file that turned out to be encrypted: kept so the same bytes do not have to be read twice.
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var importPassword by remember { mutableStateOf("") }

    // All three are asked for together: which row is drawn first depends on the radio, and a list
    // that arrives before the name it should be sorted by reorders itself under the reader's eyes.
    LaunchedEffect(version) {
        outcome = null
        val read = withContext(Dispatchers.IO) {
            Triple(WifiPasswords.read(), WifiControl.state(), WifiControl.saved())
        }
        val networks = (read.first as? WifiPasswords.Outcome.Networks)?.items.orEmpty()
        outcome = read.first
        readable = networks
        connected = read.second?.connected
        radioOn = read.second?.enabled

        ids = WifiControl.idsBySsid(read.third.orEmpty(), networks.size, ids)
    }

    // One place for the shape of an action: run it off the main thread, say what happened, and
    // read the list again - every one of these changes what the list should say.
    fun perform(action: suspend () -> String) {
        scope.launch {
            busy = true
            val message = withContext(Dispatchers.IO) { action() }
            busy = false
            toast(context, message)
            version++
        }
    }

    fun importNetworks(networks: List<WifiBackup.Network>) {
        perform {
            val added = WifiControl.importAll(networks)
            context.getString(R.string.wifi_backup_imported, added, networks.size)
        }
    }

    // The file is chosen after the options are, so the picker is asked for a name that already says
    // how the file was written: json, gzipped, or a container with a password on it.
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        perform {
            val bytes = WifiBackup.encode(exportRows, exportFormat, exportPassword)
                ?: return@perform context.getString(R.string.wifi_backup_needs_android8)
            if (!writeFile(context, uri, bytes)) {
                return@perform context.getString(R.string.wifi_backup_unwritable)
            }
            context.getString(R.string.wifi_backup_exported, exportRows.size)
        }
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val bytes = withContext(Dispatchers.IO) { readFile(context, uri) }
            busy = false
            if (bytes == null) {
                toast(context, context.getString(R.string.wifi_backup_unreadable))
                return@launch
            }

            val decoded = withContext(Dispatchers.IO) { WifiBackup.decode(bytes, password = null) }
            when (decoded) {
                is WifiBackup.Decoded.Networks -> importNetworks(decoded.networks)
                WifiBackup.Decoded.NeedsPassword -> {
                    importPassword = ""
                    pendingImport = bytes
                }

                WifiBackup.Decoded.Failed -> {
                    // What kind of file it was and how big, because "could not be read" is the
                    // whole of what the screen can say and the log is where the difference between
                    // a container with the wrong password and a file that is not one can live.
                    Diag.warn(TAG, "import failed: ${bytes.size} bytes, ${WifiBackup.kind(bytes)}")
                    toast(context, context.getString(R.string.wifi_backup_unreadable))
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_wifi_passwords)) },
            windowInsets = watchTopAppBarInsets(),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = { version++ }) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.device_refresh)
                    )
                }
                // Anchored in its own Box rather than left as a sibling of the button in the top
                // bar's row, which is the shape every menu in this app uses - the row menus below
                // included. Nothing here is known to be broken either way; this is the one that
                // was exercised on a device, with all three of its items reached.
                Box {
                    IconButton(onClick = { actionsMenu = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.wifi_passwords_all_actions)
                        )
                    }
                    DropdownMenu(
                        expanded = actionsMenu,
                        onDismissRequest = { actionsMenu = false }
                    ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wifi_backup_export)) },
                        enabled = !busy,
                        onClick = {
                            actionsMenu = false
                            exportRows = readable.map {
                                WifiBackup.Network(it.ssid, it.password, it.security)
                            }
                            exportPassword = ""
                            choosingExport = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wifi_backup_import)) },
                        enabled = !busy,
                        onClick = {
                            actionsMenu = false
                            // Any file, because what it holds decides and its name does not: these
                            // files are json, gzip and ciphertext, and a phone that classifies a
                            // .json as nothing in particular leaves it unpickable when the picker
                            // is asked to show only the types we expect.
                            openFile.launch(arrayOf("*/*"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.wifi_passwords_forget_all)) },
                        enabled = !busy,
                        onClick = {
                            actionsMenu = false
                            forgettingAll = true
                        }
                    )
                    }
                }
            }
        )

        val current = outcome
        if (current == null) {
            CenteredMessage { LoadingIndicator() }
            return@Column
        }

        when (current) {
            is WifiPasswords.Outcome.NoServer -> CenteredMessage {
                Text(
                    text = stringResource(R.string.wifi_passwords_needs_shizuku),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            is WifiPasswords.Outcome.Unsupported -> CenteredMessage {
                Text(
                    text = stringResource(R.string.wifi_passwords_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            is WifiPasswords.Outcome.Failed -> CenteredMessage {
                Text(
                    text = stringResource(R.string.wifi_passwords_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            is WifiPasswords.Outcome.Networks -> {
                if (current.items.isEmpty()) {
                    CenteredMessage {
                        Text(
                            text = stringResource(R.string.wifi_passwords_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    return@Column
                }

                val rows = current.items
                    .filter { query.isBlank() || it.ssid.contains(query, ignoreCase = true) }
                    // The joined network first, then by name: the order somebody reads a list in
                    // when one row is the one they came for.
                    .sortedWith(compareBy({ it.ssid != connected }, { it.ssid.lowercase() }))
                    .map {
                        WifiRow(
                            ssid = it.ssid,
                            password = it.password,
                            security = it.security,
                            id = ids[it.ssid.lowercase()]
                        )
                    }

                SearchField(query = query, onQuery = { query = it })

                // Said before anything is tried rather than only when Connect is tapped: with the
                // radio off, the menu's own answer is the only place the reason would appear.
                if (radioOn == false) {
                    Text(
                        text = stringResource(R.string.wifi_passwords_wifi_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                if (rows.isEmpty()) {
                    CenteredMessage {
                        Text(
                            text = stringResource(R.string.wifi_passwords_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    return@Column
                }

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
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                    items(rows, key = { it.ssid }) { network ->
                        val isConnected = network.ssid == connected
                        val isRevealed = network.ssid in revealed
                        val label = stringResource(R.string.tab_wifi_passwords)

                        SegmentedCard {
                            SegmentedListItem(
                                headlineContent = { Text(network.ssid) },
                                leadingContent = {
                                    Icon(Icons.Outlined.Wifi, contentDescription = null)
                                },
                                // The actions share the line under the name rather than the
                                // space beside it. Beside it they took a third of the card and a
                                // long name like "DIRECT-TF-Rushi's S25 ultra" wrapped to make
                                // room for them, which is the wrong way round: the name is what
                                // somebody is looking for, and the actions are what they do next.
                                supportingContent = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PasswordLine(
                                            password = network.password,
                                            security = network.security,
                                            revealed = isRevealed,
                                            connected = isConnected,
                                            modifier = Modifier.weight(1f)
                                        )

                                        // Nothing to show and nothing to copy on a network with
                                        // no key, so neither control is offered on one.
                                        if (network.password.isNotEmpty()) {
                                            IconButton(
                                                onClick = {
                                                    revealed = if (isRevealed) {
                                                        revealed - network.ssid
                                                    } else {
                                                        revealed + network.ssid
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    if (isRevealed) Icons.Outlined.VisibilityOff
                                                    else Icons.Outlined.Visibility,
                                                    contentDescription = stringResource(
                                                        if (isRevealed) R.string.wifi_passwords_hide
                                                        else R.string.wifi_passwords_show
                                                    )
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    if (copyPassword(context, label, network.password)) {
                                                        toast(
                                                            context,
                                                            context.getString(R.string.toast_copied_to_clipboard)
                                                        )
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Outlined.ContentCopy,
                                                    contentDescription = stringResource(
                                                        R.string.wifi_passwords_copy
                                                    )
                                                )
                                            }
                                        }

                                        // Connecting and forgetting are what a saved network can be
                                        // asked to do, and both are one shell command away.
                                        Box {
                                            IconButton(
                                                enabled = network.id != null,
                                                onClick = { menuFor = network.ssid }
                                            ) {
                                                Icon(
                                                    Icons.Outlined.MoreVert,
                                                    contentDescription = stringResource(
                                                        R.string.wifi_passwords_actions
                                                    )
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = menuFor == network.ssid,
                                                onDismissRequest = { menuFor = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(stringResource(R.string.wifi_passwords_connect))
                                                    },
                                                    enabled = !busy && !isConnected,
                                                    onClick = {
                                                        menuFor = null
                                                        perform {
                                                            val joined = WifiControl.connect(
                                                                network.ssid,
                                                                network.security,
                                                                network.password
                                                            )
                                                            // Three answers, said three ways: a
                                                            // radio that is off is the one a person
                                                            // can do something about, and "that did
                                                            // not work" would send them looking for a
                                                            // fault in the password instead.
                                                            when (joined) {
                                                                WifiControl.Join.CONNECTED ->
                                                                    context.getString(
                                                                        R.string.wifi_passwords_connected_toast,
                                                                        network.ssid
                                                                    )

                                                                WifiControl.Join.RADIO_OFF ->
                                                                    context.getString(R.string.wifi_passwords_wifi_off)

                                                                WifiControl.Join.FAILED ->
                                                                    context.getString(R.string.wifi_passwords_action_failed)
                                                            }
                                                        }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(stringResource(R.string.wifi_passwords_forget))
                                                    },
                                                    enabled = !busy,
                                                    onClick = {
                                                        menuFor = null
                                                        forgetting = network
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                centerSlots = true
                            )
                        }
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

    forgetting?.let { network ->
        AppAlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.wifi_passwords_forget)) },
            text = {
                Text(
                    stringResource(R.string.wifi_passwords_forget_message, network.ssid),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                PillButton(onClick = {
                    forgetting = null
                    val id = network.id ?: return@PillButton
                    perform {
                        if (WifiControl.forget(id)) {
                            context.getString(R.string.wifi_passwords_forgot, network.ssid)
                        } else {
                            context.getString(R.string.wifi_passwords_action_failed)
                        }
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { forgetting = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (forgettingAll) {
        AppAlertDialog(
            onDismissRequest = { forgettingAll = false },
            title = { Text(stringResource(R.string.wifi_passwords_forget_all)) },
            text = {
                Text(
                    stringResource(R.string.wifi_passwords_forget_all_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                PillButton(onClick = {
                    forgettingAll = false
                    perform {
                        val gone = WifiControl.forgetAll()
                        context.getString(R.string.wifi_passwords_forgot_all, gone)
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { forgettingAll = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (choosingExport) {
        AppAlertDialog(
            onDismissRequest = { choosingExport = false },
            title = { Text(stringResource(R.string.wifi_backup_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.wifi_backup_export_message),
                        style = MaterialTheme.typography.bodySmall
                    )
                    WifiBackup.Format.entries.forEach { format ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = exportFormat == format,
                                onClick = { exportFormat = format }
                            )
                            Text(
                                stringResource(
                                    if (format == WifiBackup.Format.PLAIN) {
                                        R.string.wifi_backup_format_plain
                                    } else {
                                        R.string.wifi_backup_format_compressed
                                    }
                                )
                            )
                        }
                    }
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        singleLine = true,
                        enabled = WifiBackup.canEncrypt(),
                        label = { Text(stringResource(R.string.wifi_backup_password)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (WifiBackup.canEncrypt()) {
                                        R.string.wifi_backup_password_hint
                                    } else {
                                        R.string.wifi_backup_needs_android8
                                    }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                PillButton(onClick = {
                    choosingExport = false
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    createFile.launch(
                        WifiBackup.fileName(exportFormat, exportPassword.isNotEmpty(), stamp)
                    )
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { choosingExport = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    pendingImport?.let { bytes ->
        AppAlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.wifi_backup_password)) },
            text = {
                OutlinedTextField(
                    value = importPassword,
                    onValueChange = { importPassword = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.wifi_backup_password)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                PillButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        busy = true
                        val decoded = withContext(Dispatchers.IO) {
                            WifiBackup.decode(bytes, importPassword)
                        }
                        busy = false
                        when (decoded) {
                            is WifiBackup.Decoded.Networks -> importNetworks(decoded.networks)
                            else -> {
                                Diag.warn(TAG, "import failed with a password: $decoded")
                                toast(context, context.getString(R.string.wifi_backup_unreadable))
                            }
                        }
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { pendingImport = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/** The search box, the same shape the app's other lists use. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.wifi_passwords_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .televisionTextInput(MaterialTheme.shapes.extraLarge)
    )
}

/**
 * The key under the name of the network, and, while the key is masked, what kind of network it is
 * and whether it is the one in use.
 */
@Composable
private fun PasswordLine(
    password: String,
    security: String,
    revealed: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                password.isEmpty() -> stringResource(R.string.wifi_passwords_none)
                revealed -> breakable(password)
                // A length that says nothing: the key's own would be a hint, and this is a mask.
                else -> Mask
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (revealed) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // A key is one unbroken word - up to sixty-three characters with no space in it - and
            // text with nothing to break at cannot wrap: it ran out of the row and took the row with
            // it. Weight first, so the labels beside it are measured and it gets what is left, and
            // the lines and the ellipsis are the backstop for a key longer than a real one.
            maxLines = if (revealed) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        // Not beside a key that is showing. Both labels together take most of the line, which
        // left a revealed key wrapping every few characters - "Aloo / 1141 / 3!" - instead of
        // reading as the one value it is. Masked, they stay: the kind of network and whether it
        // is the one in use are the useful part of a row of dots.
        if (!revealed) {
            Text(
                text = security,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Said on the row rather than by a banner above the list: the state belongs to this
            // network, and forgetting it is the one action here that costs a connection.
            if (connected) {
                Text(
                    text = stringResource(R.string.wifi_passwords_connected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** The dots a hidden key is drawn as, whatever the key's length is. */
private const val Mask = "••••••••"

/** The break opportunity [breakable] puts between characters, which draws as nothing. */
private const val Breakable = "\u200B"

/**
 * A key with somewhere to wrap.
 *
 * Four characters at a time, joined by a zero-width space: a break opportunity the eye does not
 * see, which is the only way a run of characters with no spaces in it can be drawn over more than
 * one line. The copy button still copies the key itself, so nothing is inserted into what leaves
 * the screen.
 */
internal fun breakable(password: String): String = password.chunked(4).joinToString(Breakable)

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** Where the screen's own complaints go, for the same reason the control layer's do. */
private const val TAG = "WifiBackup"

/**
 * What the file picker is told a file is.
 *
 * A container is ciphertext and a compressed one is gzip, so neither is JSON whatever the text
 * inside started as; the stream is what is being named, not what it holds.
 */
private const val EXPORT_MIME = "application/octet-stream"

private fun writeFile(context: Context, uri: Uri, bytes: ByteArray): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
}.getOrDefault(false)

private fun readFile(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()

/**
 * Puts a key on the clipboard, marked sensitive.
 *
 * Written out rather than handed to a helper because of the flag: a plain text clip is previewed
 * by the system at the top of the screen and kept in the clipboard's history, and a wifi password
 * is the last thing that should be. The sensitive flag is the platform's own way of saying that,
 * and the one the Settings app marks its own copies with.
 */
private fun copyPassword(context: Context, label: String, value: String): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false

    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
    true
}.getOrDefault(false)
