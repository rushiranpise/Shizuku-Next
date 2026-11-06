package moe.shizuku.manager.ui.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.R
import moe.shizuku.manager.files.PrivilegedFiles
import moe.shizuku.manager.install.AppInstall
import moe.shizuku.manager.install.PackageInspect
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem

/** Where a package may be put, as `pm` numbers it. */
private val Locations = listOf(
    0 to R.string.install_location_auto,
    1 to R.string.install_location_internal,
    2 to R.string.install_location_shared
)

/**
 * The compiler filters `pm` takes for an install's own dexopt, by name.
 *
 * Left as `pm` spells them: they are its vocabulary, and a translation of "speed-profile" into
 * something friendlier would be a translation back again the moment it is in the log.
 */
private val DexoptFilters = listOf(
    R.string.install_dexopt_default to "",
    R.string.install_dexopt_verify to "verify",
    R.string.install_dexopt_quicken to "quicken",
    R.string.install_dexopt_speed_profile to "speed-profile",
    R.string.install_dexopt_speed to "speed",
    R.string.install_dexopt_everything to "everything"
)

/** Who it is installed for. `all` is also what puts it on a work profile. */
private val Users = listOf(
    "current" to R.string.install_user_current,
    "all" to R.string.install_user_all
)

/** Common packages Android can be told installed an app. */
private val InstallSources: List<Pair<Int, String?>> = listOf(
    R.string.install_source_none to null,
    R.string.install_source_play_store to "com.android.vending",
    R.string.install_source_shell to "com.android.shell",
    R.string.install_source_google_package_installer to "com.google.android.packageinstaller",
    R.string.install_source_aosp_package_installer to "com.android.packageinstaller"
)

/**
 * Installing a package with the flags a terminal would need a cable for.
 *
 * Everything on this screen exists to become a word in one command line - see [AppInstall] for
 * what runs - so it is a form and a log, and deliberately not a package installer: the system is
 * never asked what to do, and this app never becomes the thing Android routes installs through.
 *
 * The output is shown rather than swallowed because `pm` says useful things: which split failed to
 * verify, that a newer build is already installed, that a signature does not match the one on the
 * device. A line reading "install failed" would throw all of that away.
 *
 * [path] comes from a file already on disk, which is how the file browser gets here; with none,
 * the screen asks for one and puts a copy where the shell can read it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallerScreen(path: String?, bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var target by remember(path) { mutableStateOf(path) }
    // A picked file has no path of its own, so a copy of it is made here and it is this screen's
    // to remove. The copy outlives the install - installing it again with different flags is a
    // thing somebody does - and goes when the screen does.
    var staged by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf(AppInstall.Options()) }
    var sourceMenu by remember { mutableStateOf(false) }
    // The options as they were when the install was started, not as they are now: a switch moved
    // while the result is on screen must not rewrite what that install was told to do.
    var lastOptions by remember { mutableStateOf<AppInstall.Options?>(null) }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AppInstall.Outcome?>(null) }

    // What the package says about itself, read as soon as one is chosen: this is the screen's
    // only chance to be told before it is installed rather than after.
    var details by remember { mutableStateOf<PackageInspect.Details?>(null) }
    var installed by remember { mutableStateOf<PackageInspect.Installed?>(null) }
    var reading by remember { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }

    // Only a finished install has something to open, and only a package with a launcher can be
    // opened. Resolved again once the details arrive, since a successful install and the package
    // name the screen read from the file are not guaranteed to become state in the same order.
    val openIntent = remember(result, details) {
        (result as? AppInstall.Outcome.Done)?.let {
            details?.packageName?.let { packageName -> launcherIntent(context, packageName) }
        }
    }

    LaunchedEffect(target) {
        val file = target
        details = null
        installed = null
        unreadable = false
        if (file == null) return@LaunchedEffect
        reading = true
        val read = withContext(Dispatchers.IO) { PackageInspect.read(context, file) }
        // Only once the package has a name is there anything to look up, and a package that cannot
        // be read at all has nothing to look up.
        val there = read?.let { withContext(Dispatchers.IO) { PackageInspect.installed(it.packageName) } }
        reading = false
        details = read
        installed = there
        unreadable = read == null
    }

    suspend fun perform(file: String) {
        running = true
        output = ""
        result = null
        lastOptions = options
        val (outcome, text) = withContext(Dispatchers.IO) {
            var collected = ""
            AppInstall.install(file, options) { collected += it } to collected
        }
        output = text
        result = outcome
        running = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val chosen = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            running = true
            output = ""
            result = null

            // A picked file has a uri and no path, and the shell needs a path. The copy is what
            // turns one into the other, into the one directory both processes can reach.
            val copied = withContext(Dispatchers.IO) { stage(context, chosen) }
            running = false
            if (copied == null) {
                result = AppInstall.Outcome.Failed(
                    context.getString(R.string.install_could_not_read)
                )
                return@launch
            }

            // Choosing a second package replaces the first: the old copy is nobody's now.
            val previous = staged
            if (previous != null && previous != copied) {
                withContext(Dispatchers.IO) { runCatching { PrivilegedFiles.delete(previous) } }
            }

            // Chosen, not installed. Picking a file and pressing Install are two decisions, and
            // running the install from the picker's callback is what made a second press fail on
            // a copy that had already been cleaned up.
            staged = copied
            target = copied
        }
    }

    // The staged copy is left until the screen is, which is what keeps a second press working.
    DisposableEffect(Unit) {
        onDispose {
            val leftover = staged
            if (leftover != null) {
                // A plain thread rather than the screen's scope: that scope is cancelled on the
                // way out, which is exactly when this has to run.
                Thread { runCatching { PrivilegedFiles.delete(leftover) } }.start()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_install)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

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
            item {
                SegmentedCard {
                    SegmentedListItem(
                        headlineContent = {
                            Text(
                                target?.substringAfterLast('/')
                                    ?: stringResource(R.string.install_no_file),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                target ?: stringResource(R.string.install_choose_hint),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            PillButton(
                                enabled = !running,
                                onClick = { picker.launch(arrayOf("*/*")) }
                            ) { Text(stringResource(R.string.install_choose)) }
                        },
                        centerSlots = true
                    )
                }
            }

            if (target != null) {
                item { PackageDetailsCard(details, installed, reading, unreadable) }
            }

            item {
                SegmentedCard {
                    SegmentedColumn {
                        Toggle(
                            R.string.install_replace,
                            R.string.install_replace_desc,
                            options.replace
                        ) { options = options.copy(replace = it) }
                        Toggle(
                            R.string.install_downgrade,
                            R.string.install_downgrade_desc,
                            options.downgrade
                        ) { options = options.copy(downgrade = it) }
                        Toggle(
                            R.string.install_grant_all,
                            R.string.install_grant_all_desc,
                            options.grantAll
                        ) { options = options.copy(grantAll = it) }
                        Toggle(
                            R.string.install_allow_test,
                            R.string.install_allow_test_desc,
                            options.allowTest
                        ) { options = options.copy(allowTest = it) }
                        Toggle(
                            R.string.install_keep_running,
                            R.string.install_keep_running_desc,
                            options.keepRunning
                        ) { options = options.copy(keepRunning = it) }
                        Toggle(
                            R.string.install_skip_verification,
                            R.string.install_skip_verification_desc,
                            options.skipVerification
                        ) { options = options.copy(skipVerification = it) }
                        Toggle(
                            R.string.install_bypass_sdk,
                            R.string.install_bypass_sdk_desc,
                            options.bypassLowTargetSdk
                        ) { options = options.copy(bypassLowTargetSdk = it) }
                        Toggle(
                            R.string.install_instant,
                            R.string.install_instant_desc,
                            options.instant
                        ) { options = options.copy(instant = it) }
                        Toggle(
                            R.string.install_rollback,
                            R.string.install_rollback_desc,
                            options.rollback
                        ) { options = options.copy(rollback = it) }
                        Toggle(
                            R.string.install_ignore_dexopt,
                            R.string.install_ignore_dexopt_desc,
                            options.ignoreDexoptProfile
                        ) { options = options.copy(ignoreDexoptProfile = it) }
                    }
                }
            }

            item {
                SegmentedCard {
                    SegmentedColumn {
                        Choice(
                            R.string.install_user,
                            stringResource(Users.first { it.first == options.user }.second),
                            Users.map { (value, label) -> stringResource(label) to value }
                        ) { options = options.copy(user = it) }
                        Choice(
                            R.string.install_location,
                            stringResource(
                                Locations.first { it.first == options.location }.second
                            ),
                            Locations.map { (value, label) ->
                                stringResource(label) to value.toString()
                            }
                        ) { options = options.copy(location = it.toIntOrNull() ?: 0) }
                        Choice(
                            R.string.install_dexopt,
                            options.dexoptFilter ?: stringResource(R.string.install_dexopt_default),
                            DexoptFilters.map { (label, value) -> stringResource(label) to value }
                        ) { options = options.copy(dexoptFilter = it.ifBlank { null }) }
                    }
                }
            }

            item {
                SegmentedCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = options.installerPackage.orEmpty(),
                                onValueChange = { entered ->
                                    options = options.copy(installerPackage = entered.ifBlank { null })
                                },
                                label = { Text(stringResource(R.string.install_spoof_source)) },
                                supportingText = {
                                    Text(stringResource(R.string.install_spoof_source_desc))
                                },
                                trailingIcon = {
                                    IconButton(onClick = { sourceMenu = true }) {
                                        Icon(
                                            Icons.Outlined.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.install_source_presets)
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().televisionTextInput(OutlinedTextFieldDefaults.shape)
                            )
                            DropdownMenu(
                                expanded = sourceMenu,
                                onDismissRequest = { sourceMenu = false }
                            ) {
                                InstallSources.forEach { (label, packageName) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        onClick = {
                                            options = options.copy(installerPackage = packageName)
                                            sourceMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Sits under the spoofed source because it is the same claim from the other
                        // end: which package the app is told installed it, and which URI it is
                        // told sent it there.
                        OutlinedTextField(
                            value = options.referrer.orEmpty(),
                            onValueChange = { entered ->
                                options = options.copy(referrer = entered.ifBlank { null })
                            },
                            label = { Text(stringResource(R.string.install_referrer)) },
                            supportingText = { Text(stringResource(R.string.install_referrer_desc)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .televisionTextInput(OutlinedTextFieldDefaults.shape)
                        )
                    }
                }
            }

            item {
                PillButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = target != null && !running,
                    onClick = { target?.let { file -> scope.launch { perform(file) } } }
                ) { Text(stringResource(R.string.install_action)) }
            }

            if (running) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator()
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.install_running))
                    }
                }
            }

            // One card for the whole answer. The verdict used to be printed above the card as
            // well, which said the same thing twice; and what the card is for is not only whether
            // it worked but what it ran with, since the options are the reason this screen exists.
            if (result != null || output.isNotEmpty()) {
                item {
                    SegmentedCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            result?.let { outcome ->
                                Text(
                                    text = when (outcome) {
                                        is AppInstall.Outcome.Done ->
                                            stringResource(R.string.install_done)
                                        is AppInstall.Outcome.Failed ->
                                            stringResource(R.string.install_failed, outcome.message)
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = when (outcome) {
                                        is AppInstall.Outcome.Done ->
                                            MaterialTheme.colorScheme.primary
                                        is AppInstall.Outcome.Failed ->
                                            MaterialTheme.colorScheme.error
                                    }
                                )

                                if (openIntent != null) {
                                    PillButton(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp),
                                        onClick = {
                                            runCatching { context.startActivity(openIntent) }
                                                .onFailure {
                                                    Toast.makeText(
                                                        context,
                                                        R.string.install_open_failed,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                        }
                                    ) { Text(stringResource(R.string.install_open)) }
                                }

                                // The options the install was given, as rows: what each one
                                // became, and - where it became nothing - that it is not on this
                                // platform, which is the only way a switch that did nothing is
                                // told apart from one that did.
                                val rows = appliedRows(lastOptions, outcome.flags)
                                if (rows.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.install_options_heading),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 10.dp)
                                    )
                                    rows.forEach { row ->
                                        Row(
                                            modifier = Modifier.padding(top = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (row.applied) Icons.Outlined.CheckCircle
                                                else Icons.Outlined.RemoveCircleOutline,
                                                contentDescription = null,
                                                tint = if (row.applied) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = row.label,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                row.note?.let { note ->
                                                    Text(
                                                        text = note,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            val rest = detail(output)
                            if (rest.isNotEmpty()) {
                                Text(
                                    text = rest,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
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

/**
 * The package, as its own manifest declares it.
 *
 * Above the options, because this is the part that decides whether to go on: an app built for an
 * Android older than the block allows, or one asking for permissions nobody expected, is worth
 * seeing while there is still a decision to make.
 */
@Composable
private fun PackageDetailsCard(
    details: PackageInspect.Details?,
    installed: PackageInspect.Installed?,
    reading: Boolean,
    unreadable: Boolean
) {
    var permissionsOpen by remember { mutableStateOf(false) }

    SegmentedCard {
        SegmentedColumn {
            when {
                reading -> item {
                    SegmentedListItem(
                        headlineContent = { Text(stringResource(R.string.install_details_reading)) },
                        leadingContent = { LoadingIndicator() },
                        centerSlots = true
                    )
                }

                unreadable -> item {
                    SegmentedListItem(
                        headlineContent = {
                            Text(stringResource(R.string.install_details_unreadable))
                        },
                        centerSlots = true
                    )
                }

                details != null -> {
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(PackageInspect.title(details, "")) },
                            supportingContent = { Text(details.packageName) },
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Android,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            centerSlots = true
                        )
                    }
                    item {
                        Detail(
                            stringResource(R.string.install_details_version),
                            "${details.versionName ?: "-"}  (${details.versionCode})"
                        )
                    }
                    item {
                        val android = PackageInspect.androidName(details.targetSdk)
                        Detail(
                            stringResource(R.string.install_details_target),
                            if (android.isEmpty()) {
                                details.targetSdk.toString()
                            } else {
                                "${details.targetSdk}  \u00b7  Android $android"
                            }
                        )
                    }
                    item {
                        // The one row that cannot come from the file: whether the phone already
                        // has it, and how this file compares with what is there. It is what makes
                        // the switches above a decision rather than a guess - replacing is on by
                        // default because installing over an app that is already there is refused
                        // without it, and this is the row that says whether that is the case.
                        val installedVersion = installed?.versionName
                            ?: installed?.versionCode?.toString()
                        val comparison = when {
                            installed == null ->
                                stringResource(R.string.install_details_not_installed_note)

                            details.versionCode > 0 && installed.versionCode != null &&
                                details.versionCode > installed.versionCode ->
                                stringResource(R.string.install_details_update)

                            details.versionCode > 0 && installed.versionCode != null &&
                                details.versionCode < installed.versionCode ->
                                stringResource(
                                    R.string.install_details_downgrade,
                                    installedVersion.orEmpty()
                                )

                            else -> stringResource(R.string.install_details_same)
                        }

                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.install_details_installed)) },
                            supportingContent = { Text(comparison) },
                            trailingContent = {
                                Text(
                                    installedVersion
                                        ?: stringResource(R.string.install_details_not_installed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            centerSlots = true
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        R.string.install_details_permissions,
                                        details.permissions.size
                                    )
                                )
                            },
                            // Closed, the three that matter most are named; open, the rows below
                            // carry the whole list rather than the same three in a smaller font.
                            supportingContent = {
                                if (!permissionsOpen) Text(permissionPreview(details.permissions))
                            },
                            trailingContent = {
                                Icon(
                                    if (permissionsOpen) Icons.Outlined.KeyboardArrowUp
                                    else Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            },
                            onClick = { permissionsOpen = !permissionsOpen },
                            centerSlots = true
                        )
                    }
                    if (permissionsOpen) {
                        details.permissions.forEach { permission ->
                            item {
                                Text(
                                    text = permission,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The first few permissions by their last name, so the row says something with the list shut. */
private fun permissionPreview(permissions: List<String>): String {
    if (permissions.isEmpty()) return ""
    val named = permissions.take(3).joinToString("\n") { it.substringAfterLast('.') }
    return if (permissions.size > 3) "$named\n\u2026" else named
}

/** One line of a package's details: what it is called on the left, what it says on the right. */
@Composable
private fun Detail(label: String, value: String) {
    SegmentedListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        centerSlots = true
    )
}

/** One flag, as a switch with the reason it exists underneath it. */
@Composable
private fun Toggle(label: Int, description: Int, checked: Boolean, onChecked: (Boolean) -> Unit) {
    SegmentedListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(description)) },
        switchState = checked,
        onClick = { onChecked(!checked) }
    )
}

/** One of a few answers, as a row that opens into them. */
@Composable
private fun Choice(
    label: Int,
    current: String,
    choices: List<Pair<String, String>>,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    SegmentedListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(current) },
        trailingContent = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
        onClick = { open = true },
        centerSlots = true
    )

    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        choices.forEach { (text, value) ->
            DropdownMenuItem(
                text = { Text(text) },
                onClick = {
                    open = false
                    onPick(value)
                }
            )
        }
    }
}

/** One line of the report: an option that was switched on, and what became of it. */
private data class AppliedRow(val label: String, val applied: Boolean, val note: String?)

/**
 * What the install was told to do, read back from the command it ran.
 *
 * From the options rather than from the flags, because the question this list answers is about the
 * switches: one that did nothing has to appear as not applied, where a list built out of the flags
 * would simply leave it out and look identical to one nobody turned on.
 *
 * `null` options - an install that never got as far as a command, like a package that could not be
 * read - produce no rows at all, since nothing was applied and there is nothing to report.
 */
@Composable
private fun appliedRows(options: AppInstall.Options?, flags: List<String>): List<AppliedRow> {
    if (options == null) return emptyList()

    val rows = ArrayList<AppliedRow>()
    val unsupported = stringResource(R.string.install_option_unsupported)

    if (options.replace) {
        rows += AppliedRow(stringResource(R.string.install_replace), flags.contains("-r"), null)
    }
    if (options.downgrade) {
        rows += AppliedRow(stringResource(R.string.install_downgrade), flags.contains("-d"), null)
    }
    if (options.grantAll) {
        rows += AppliedRow(stringResource(R.string.install_grant_all), flags.contains("-g"), null)
    }
    if (options.allowTest) {
        rows += AppliedRow(stringResource(R.string.install_allow_test), flags.contains("-t"), null)
    }
    if (options.keepRunning) {
        rows += AppliedRow(
            stringResource(R.string.install_keep_running),
            flags.contains("--dont-kill"),
            null
        )
    }
    if (options.skipVerification) {
        rows += AppliedRow(
            stringResource(R.string.install_skip_verification),
            flags.contains("--skip-verification"),
            null
        )
    }
    if (options.bypassLowTargetSdk) {
        // The one flag with a floor under it: before Android 14 there is no switch to pass, and
        // the row says so rather than leaving the reader to guess why it is not in the command.
        val exists = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        rows += AppliedRow(
            stringResource(R.string.install_bypass_sdk),
            flags.contains("--bypass-low-target-sdk-block"),
            if (exists) null else unsupported
        )
    }

    if (options.instant) {
        rows += AppliedRow(
            stringResource(R.string.install_instant),
            flags.contains("--instant"),
            null
        )
    }
    if (options.rollback) {
        rows += AppliedRow(
            stringResource(R.string.install_rollback),
            flags.contains("--enable-rollback"),
            null
        )
    }
    if (options.ignoreDexoptProfile) {
        rows += AppliedRow(
            stringResource(R.string.install_ignore_dexopt),
            flags.contains("--ignore-dexopt-profile"),
            null
        )
    }
    options.dexoptFilter?.let { filter ->
        rows += AppliedRow(
            stringResource(R.string.install_dexopt) + ": $filter",
            flags.contains("--dexopt-compiler-filter"),
            null
        )
    }
    options.referrer?.let { uri ->
        rows += AppliedRow(
            stringResource(R.string.install_referrer) + ": $uri",
            flags.contains("--referrer"),
            null
        )
    }

    // These three are always part of the command: the user it is installed for, and the two that
    // say the same thing twice if reported only when they differ from nothing.
    rows += AppliedRow(
        stringResource(R.string.install_user) + ": " + stringResource(
            Users.firstOrNull { it.first == options.user }?.second ?: R.string.install_user_current
        ),
        flags.contains("--user"),
        null
    )

    if (options.location in 1..2) {
        rows += AppliedRow(
            stringResource(R.string.install_location) + ": " + stringResource(
                Locations.firstOrNull { it.first == options.location }?.second
                    ?: R.string.install_location_auto
            ),
            flags.contains("--install-location"),
            null
        )
    }

    options.installerPackage?.let { name ->
        rows += AppliedRow(
            stringResource(R.string.install_spoof_source) + ": $name",
            flags.contains("-i"),
            null
        )
    }

    return rows
}

/**
 * What `pm` said beyond its verdict.
 *
 * Its first line is "Success" or "Failure [...]" and the card states that already, so repeating it
 * underneath would be the same sentence twice. What is left is the part worth reading: which split
 * failed to verify, that the signatures differ, or the trace of a command that never ran.
 */
private fun detail(output: String): String = output.lineSequence()
    .map { it.trim() }
    .filterNot { it.startsWith("Success") || it.startsWith("Failure") }
    .filter { it.isNotEmpty() }
    .joinToString("\n")

/**
 * The intent that opens [packageName], including apps that only put a launcher on Android TV.
 *
 * [android.content.pm.PackageManager.getLaunchIntentForPackage] only looks for
 * [Intent.CATEGORY_LAUNCHER], while a TV app may declare only
 * [Intent.CATEGORY_LEANBACK_LAUNCHER]. The manifest already asks to see both.
 */
private fun launcherIntent(context: Context, packageName: String): Intent? = runCatching {
    context.packageManager.getLaunchIntentForPackage(packageName)
        ?: context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setPackage(packageName),
            0
        ).firstOrNull()?.let { activity ->
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(
                    ComponentName(
                        activity.activityInfo.packageName,
                        activity.activityInfo.name
                    )
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}.getOrNull()

/**
 * Puts a picked file where the shell can read it.
 *
 * The two processes can both see `/data/local/tmp` and disagree about almost everything else, and
 * the copy goes through the same descriptor route the rest of the file work uses, so a file from
 * any provider lands somewhere the install command can name.
 *
 * Returns the path, or null when the file could not be read at all.
 */
private suspend fun stage(context: Context, uri: Uri): String? = runCatching {
    val name = displayName(context, uri) ?: "package.apk"

    val input = context.contentResolver.openInputStream(uri) ?: return null
    input.use { source -> AppInstall.stage(source, name) }
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
}.getOrNull()
