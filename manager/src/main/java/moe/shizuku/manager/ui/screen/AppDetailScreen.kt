@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.shizuku.manager.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.ExpressiveSwitch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.AppDetail
import moe.shizuku.manager.manage.AppOp
import moe.shizuku.manager.manage.AppPermission
import moe.shizuku.manager.manage.OpMode
import moe.shizuku.manager.manage.PackageTools
import moe.shizuku.manager.manage.PermissionKind
import moe.shizuku.manager.manage.StandbyBucket
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.AppStatusLabels
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ChipEmphasis
import moe.shizuku.manager.ui.component.ChoiceRow
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.ui.component.StatusChip
import moe.shizuku.manager.ui.component.statusChipMinWidth
import moe.shizuku.manager.utils.ShizukuStateMachine

/** A destructive action waiting for a yes. */
private class PendingConfirm(
    @StringRes val title: Int,
    @StringRes val message: Int,
    val name: String,
    @StringRes val done: Int,
    val action: () -> Boolean
)

/**
 * One app, and everything about it that normally needs a computer: its system permissions,
 * the app ops the platform runs on, whether it is running, and its battery policy.
 *
 * Every row that changes something says so honestly when it cannot: rows are dimmed while
 * Shizuku is stopped, permissions the platform will not let adb change are read-only, and a
 * change that did not take is reported instead of being shown as done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember(packageName) { mutableStateOf<AppDetail?>(null) }
    var loading by remember(packageName) { mutableStateOf(true) }
    var version by remember(packageName) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // Read rather than remembered, so a server that comes or goes while this screen is up
    // is picked up by the next recomposition instead of leaving the rows lying.
    val running = ShizukuStateMachine.isRunning()

    val snackbarHostState = remember { SnackbarHostState() }
    var confirmGrant by remember { mutableStateOf<AppPermission?>(null) }
    var opPicker by remember { mutableStateOf<AppOp?>(null) }
    var bucketPicker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    LaunchedEffect(packageName, version) {
        loading = detail == null
        val loaded = withContext(Dispatchers.IO) { PackageTools.loadDetail(context, packageName) }
        detail = loaded
        loading = false
    }

    fun report(ok: Boolean, @StringRes success: Int) {
        scope.launch {
            snackbarHostState.showSnackbar(
                context.getString(if (ok) success else R.string.app_action_failed)
            )
        }
    }

    /**
     * Runs something that changes the device, then re-reads the app: the screen shows the
     * state the platform reports afterwards rather than the state we asked for.
     */
    fun runAction(@StringRes done: Int, block: () -> Boolean) {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.getOrDefault(false) }
            busy = false
            version++
            onChanged()
            report(ok, done)
        }
    }

    fun changePermission(permission: AppPermission, grant: Boolean) {
        runAction(if (grant) R.string.app_permission_granted else R.string.app_permission_revoked) {
            PackageTools.setPermission(context, packageName, permission.name, grant)
        }
    }

    val loaded = detail
    // The dialogs live outside the content column, so the label they show is resolved here
    // rather than from the non-null copy below.
    val label = loaded?.label ?: packageName

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(label, maxLines = 1) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                // The platform's own page for the app: everything this screen deliberately
                // does not carry notification settings, storage, the uninstall button,
                // granted-at-install permissions. Away from a removed app it is the
                // fastest way to anything the app itself owns.
                if (loaded?.uninstalled != true) {
                    IconButton(onClick = { openAppInfo(context, packageName) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.app_open_info)
                        )
                    }
                }
            }
        )

        if (loaded == null) {
            if (loading) {
                CenteredMessage { LoadingIndicator() }
            } else {
                CenteredMessage {
                    Text(
                        stringResource(R.string.app_detail_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return@Column
        }

        // Non-null from here on: everything below reads it directly.
        val app = loaded

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SegmentedColumn {
                        item {
                            val status = when {
                                app.uninstalled -> stringResource(R.string.manage_status_removed)
                                app.suspended -> stringResource(R.string.manage_status_suspended)
                                !app.enabled -> stringResource(R.string.manage_status_disabled)
                                app.systemApp -> stringResource(R.string.manage_status_system)
                                else -> null
                            }
                            SegmentedListItem(
                                leadingContent = { AppIcon(app.packageName) },
                                headlineContent = { Text(app.label) },
                                // The same shape the list rows use: the package name is the line,
                                // the state is a chip on the right where it cannot be cut off.
                                supportingContent = {
                                    Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                trailingContent = {
                                    if (status != null) {
                                        StatusChip(
                                            status,
                                            // The same width the list rows use, so the chip does
                                            // not change size as the screen it is on does.
                                            minWidth = statusChipMinWidth(AppStatusLabels),
                                            emphasis = when {
                                                app.uninstalled -> ChipEmphasis.WARN
                                                app.suspended || !app.enabled -> ChipEmphasis.SOFT
                                                else -> ChipEmphasis.NONE
                                            }
                                        )
                                    }
                                },
                                centerSlots = true
                            )
                        }
                    }
                }

                // ---- permissions ------------------------------------------------------
                item { SectionHeader(R.string.app_section_permissions) }

                val privileged = app.permissions.filter { it.kind == PermissionKind.PRIVILEGED }
                val runtime = app.permissions.filter { it.kind == PermissionKind.RUNTIME }

                item { NoteCard(R.string.app_permissions_note) }

                if (app.permissions.isEmpty()) {
                    item { NoteCard(R.string.app_permissions_none) }
                } else {
                    if (privileged.isNotEmpty()) {
                        item {
                            SegmentedColumn {
                                privileged.forEach { permission ->
                                    item {
                                        PermissionRow(permission, running && !busy) { checked ->
                                            // Confirmation is for handing an app adb-level power.
                                            // A runtime permission is the user turning on a
                                            // switch they can see, and asking again would be
                                            // ceremony for a decision they just made.
                                            if (checked && permission.dangerous) confirmGrant = permission
                                            else changePermission(permission, checked)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (runtime.isNotEmpty()) {
                        item { SectionHeader(R.string.app_section_runtime) }
                        item {
                            SegmentedColumn {
                                runtime.forEach { permission ->
                                    item {
                                        // No confirmation here: these are the permissions the
                                        // user can also change in system settings, and the
                                        // switch they just touched is the whole decision.
                                        PermissionRow(permission, running && !busy) { checked ->
                                            changePermission(permission, checked)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- app ops ----------------------------------------------------------
                item { SectionHeader(R.string.app_section_ops) }
                item { NoteCard(R.string.app_ops_note) }

                if (!running || app.ops.isEmpty()) {
                    item { NoteCard(R.string.app_ops_needs_shizuku) }
                } else {
                    item {
                        SegmentedColumn {
                            app.ops.forEach { op ->
                                item {
                                    SegmentedListItem(
                                        headlineContent = { Text(stringResource(op.label)) },
                                        supportingContent = { Text(stringResource(op.note)) },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    modeLabel(op.mode),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = if (op.mode == OpMode.DEFAULT) {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = { opPicker = op },
                                        enabled = !busy,
                                        centerSlots = true
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- actions ----------------------------------------------------------
                item { SectionHeader(R.string.app_section_actions) }

                item {
                    SegmentedColumn {
                        if (app.uninstalled) {
                            // Nothing to stop or clear: the only thing left to do with a
                            // removed app is put it back.
                            item {
                                ActionRow(
                                    label = R.string.app_action_restore,
                                    note = R.string.app_action_restore_note,
                                    enabled = running && !busy
                                ) {
                                    runAction(R.string.app_action_restore) {
                                        PackageTools.restoreSystemApp(context, packageName)
                                    }
                                }
                            }
                        } else {
                            item {
                                ActionRow(
                                    label = R.string.app_action_force_stop,
                                    note = R.string.app_action_force_stop_note,
                                    enabled = running && !busy
                                ) {
                                    runAction(R.string.app_action_force_stop) {
                                        PackageTools.forceStop(context, packageName)
                                    }
                                }
                            }
                            item {
                                ActionRow(
                                    label = if (app.suspended) R.string.app_action_unsuspend
                                    else R.string.app_action_suspend,
                                    note = R.string.app_action_suspend_note,
                                    enabled = running && !busy
                                ) {
                                    runAction(
                                        if (app.suspended) R.string.app_action_unsuspend
                                        else R.string.app_action_suspend
                                    ) {
                                        PackageTools.setSuspended(context, packageName, !app.suspended)
                                    }
                                }
                            }
                            // Disabling a system app is how a device stops booting, so it is
                            // only offered for apps the user brought, or to undo it again.
                            if (!app.systemApp || !app.enabled) {
                                item {
                                    ActionRow(
                                        label = if (app.enabled) R.string.app_action_disable
                                        else R.string.app_action_enable,
                                        note = R.string.app_action_disable_note,
                                        enabled = running && !busy
                                    ) {
                                        if (app.enabled) {
                                            confirm = PendingConfirm(
                                                title = R.string.app_confirm_title,
                                                message = R.string.app_confirm_disable,
                                                name = app.label,
                                                done = R.string.app_action_disable
                                            ) {
                                                PackageTools.setDisabled(context, packageName, true)
                                            }
                                        } else {
                                            runAction(R.string.app_action_enable) {
                                                PackageTools.setDisabled(context, packageName, false)
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                ActionRow(
                                    label = R.string.app_action_clear_data,
                                    note = R.string.app_action_clear_data_note,
                                    enabled = running && !busy
                                ) {
                                    confirm = PendingConfirm(
                                        title = R.string.app_confirm_title,
                                        message = R.string.app_confirm_clear_data,
                                        name = app.label,
                                        done = R.string.app_action_clear_data
                                    ) {
                                        PackageTools.clearData(context, packageName)
                                    }
                                }
                            }
                            item {
                                ActionRow(
                                    label = R.string.app_action_uninstall,
                                    note = R.string.app_action_uninstall_note,
                                    enabled = running && !busy
                                ) {
                                    confirm = PendingConfirm(
                                        title = R.string.app_confirm_title,
                                        message = R.string.app_confirm_uninstall,
                                        name = app.label,
                                        done = R.string.app_action_uninstall
                                    ) {
                                        PackageTools.uninstallForUser(context, packageName)
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- battery ----------------------------------------------------------
                item { SectionHeader(R.string.app_section_battery) }

                if (!running) {
                    item { NoteCard(R.string.app_battery_needs_shizuku) }
                } else {
                    item {
                        SegmentedColumn {
                            item {
                                SegmentedListItem(
                                    headlineContent = { Text(stringResource(R.string.app_battery_bucket)) },
                                    supportingContent = {
                                        Text(
                                            app.bucket?.let { stringResource(it.label) }
                                                ?: stringResource(R.string.status_value_none)
                                        )
                                    },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = { bucketPicker = true },
                                    enabled = !busy,
                                    centerSlots = true
                                )
                            }
                            item {
                                SegmentedListItem(
                                    headlineContent = { Text(stringResource(R.string.app_battery_unrestricted)) },
                                    supportingContent = { Text(stringResource(R.string.app_battery_unrestricted_note)) },
                                    trailingContent = {
                                        ExpressiveSwitch(
                                            checked = app.batteryUnrestricted,
                                            enabled = !busy,
                                            onCheckedChange = { checked ->
                                                runAction(R.string.app_battery_unrestricted) {
                                                    PackageTools.setBatteryUnrestricted(context, packageName, checked)
                                                }
                                            }
                                        )
                                    },
                                    centerSlots = true
                                )
                            }
                        }
                    }
                }

                // ---- network ----------------------------------------------------------
                item { SectionHeader(R.string.app_section_network) }
                if (!running) {
                    item { NoteCard(R.string.app_network_needs_shizuku) }
                } else if (app.networkBlocked == null) {
                    // The chain this uses is Android 11 and up, and its own description calls
                    // it one for debugging: saying so beats a switch that does nothing.
                    item { NoteCard(R.string.app_network_unsupported) }
                } else {
                    item {
                        SegmentedColumn {
                            item {
                                SegmentedListItem(
                                    headlineContent = { Text(stringResource(R.string.app_network_block)) },
                                    supportingContent = { Text(stringResource(R.string.app_network_block_note)) },
                                    trailingContent = {
                                        ExpressiveSwitch(
                                            checked = app.networkBlocked == true,
                                            enabled = !busy,
                                            onCheckedChange = { checked ->
                                                runAction(R.string.app_network_block) {
                                                    PackageTools.setNetworkBlocked(context, packageName, checked)
                                                }
                                            }
                                        )
                                    },
                                    centerSlots = true
                                )
                            }
                        }
                    }
                }

                // ---- info -------------------------------------------------------------
                item { SectionHeader(R.string.app_section_info) }
                item {
                    val yes = stringResource(R.string.app_info_yes)
                    val no = stringResource(R.string.app_info_no)
                    SegmentedColumn {
                        facts(app, yes, no).forEach { (label, value) ->
                            item {
                                SegmentedListItem(
                                    headlineContent = { Text(stringResource(label)) },
                                    supportingContent = { Text(value) }
                                )
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp)
            )
        }
    }

    confirmGrant?.let { permission ->
        AlertDialog(
            onDismissRequest = { confirmGrant = null },
            title = {
                Text(stringResource(R.string.app_grant_confirm_title, permission.short, label))
            },
            text = { Text(stringResource(R.string.app_grant_confirm_message)) },
            confirmButton = {
                PillButton(onClick = {
                    confirmGrant = null
                    changePermission(permission, true)
                }) { Text(stringResource(R.string.app_grant_confirm_ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { confirmGrant = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    opPicker?.let { op ->
        ModeDialog(
            title = stringResource(op.label),
            note = stringResource(op.note),
            current = op.mode,
            onDismiss = { opPicker = null }
        ) { mode ->
            opPicker = null
            runAction(R.string.app_op_set) { PackageTools.setOp(context, packageName, op.name, mode) }
        }
    }

    if (bucketPicker) {
        BucketDialog(
            current = loaded?.bucket,
            onDismiss = { bucketPicker = false }
        ) { bucket ->
            bucketPicker = false
            runAction(R.string.app_battery_bucket) {
                PackageTools.setBucket(context, packageName, bucket)
            }
        }
    }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(pending.title)) },
            text = { Text(stringResource(pending.message, pending.name)) },
            confirmButton = {
                PillButton(onClick = {
                    confirm = null
                    runAction(pending.done, pending.action)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { confirm = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

// ---- rows -----------------------------------------------------------------------

@Composable
private fun SectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp, end = 16.dp)
    )
}

@Composable
private fun NoteCard(@StringRes text: Int) {
    SegmentedCard(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun PermissionRow(
    permission: AppPermission,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    // Both lines matter: the short name is what reads, and the full name is what decides
    // which permission this actually is a row reading WRITE could be any of several.
    val supporting = buildString {
        append(permission.name)
        append('\n')
        append(
            if (permission.changeable) permission.protection
            else stringResource(R.string.app_permission_locked, permission.protection)
        )
    }

    SegmentedListItem(
        headlineContent = { Text(permission.short) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            ExpressiveSwitch(
                checked = permission.granted,
                // A permission adb cannot change stays readable but not switchable; the
                // row says why, so it does not look like a bug.
                enabled = enabled && permission.changeable,
                onCheckedChange = onToggle
            )
        },
        // The protection string can wrap, and a switch level with the headline looks
        // broken rather than deliberate.
        centerSlots = true
    )
}

@Composable
private fun ActionRow(
    @StringRes label: Int,
    @StringRes note: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SegmentedListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(note)) },
        onClick = onClick,
        enabled = enabled
    )
}

/** The static facts, in the order they are worth reading. */
private fun facts(app: AppDetail, yes: String, no: String): List<Pair<Int, String>> {
    val facts = ArrayList<Pair<Int, String>>()

    fun fact(@StringRes label: Int, value: String?) {
        facts.add(label to (value?.takeIf { it.isNotBlank() } ?: ""))
    }

    fact(R.string.app_info_version, app.versionName?.let { "$it (${app.versionCode})" })
    fact(R.string.app_info_uid, app.uid.takeIf { it != 0 }?.toString())
    fact(R.string.app_info_target_sdk, app.targetSdk.takeIf { it > 0 }?.toString())
    fact(R.string.app_info_min_sdk, app.minSdk.takeIf { it > 0 }?.toString())
    fact(R.string.app_info_abi, app.abi)
    fact(R.string.app_info_data_dir, app.dataDir)
    fact(R.string.app_info_signature, app.signature)
    fact(R.string.app_info_installer, app.installer)
    fact(R.string.app_info_installed, date(app.firstInstall))
    fact(R.string.app_info_updated, date(app.lastUpdate))
    fact(R.string.app_info_debuggable, if (app.debuggable) yes else no)
    fact(R.string.app_info_backup, if (app.backupAllowed) yes else no)
    fact(R.string.app_info_launcher, if (app.hasLauncher) yes else no)

    return facts
}

/** Opens the system page for [packageName], which is where the platform keeps the rest. */
private fun openAppInfo(context: Context, packageName: String) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun date(millis: Long): String? =
    if (millis <= 0) null
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@Composable
private fun modeLabel(mode: OpMode): String = stringResource(
    when (mode) {
        OpMode.DEFAULT -> R.string.app_op_mode_default
        OpMode.ALLOW -> R.string.app_op_mode_allow
        OpMode.IGNORE -> R.string.app_op_mode_ignore
        OpMode.DENY -> R.string.app_op_mode_deny
    }
)

/**
 * A picker with radio buttons rather than ticks: these are alternatives where exactly one is
 * in force, which a tick reads as a set and a radio reads as a choice.
 */
@Composable
private fun ModeDialog(
    title: String,
    note: String,
    current: OpMode,
    onDismiss: () -> Unit,
    onPick: (OpMode) -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(note, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                OpMode.entries.forEach { mode ->
                    ChoiceRow(
                        selected = selected == mode,
                        label = modeLabel(mode),
                        onClick = { selected = mode }
                    )
                }
            }
        },
        confirmButton = {
            PillButton(onClick = { onPick(selected) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun BucketDialog(
    current: StandbyBucket?,
    onDismiss: () -> Unit,
    onPick: (StandbyBucket) -> Unit
) {
    // Only the buckets adb can assign are offered. An exempted app starts with nothing
    // selected and OK disabled, with the reason above, instead of quietly claiming the
    // choice it cannot change.
    val options = StandbyBucket.entries.filter { it.settable }
    var selected by remember { mutableStateOf(current?.takeIf { it.settable }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_battery_pick_title)) },
        text = {
            Column {
                if (current == StandbyBucket.EXEMPTED) {
                    Text(
                        stringResource(R.string.app_bucket_exempted_note),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                options.forEach { bucket ->
                    ChoiceRow(
                        selected = selected == bucket,
                        label = stringResource(bucket.label),
                        supporting = stringResource(bucket.note),
                        onClick = { selected = bucket }
                    )
                }
            }
        },
        confirmButton = {
            PillButton(
                onClick = { selected?.let(onPick) },
                enabled = selected != null
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
