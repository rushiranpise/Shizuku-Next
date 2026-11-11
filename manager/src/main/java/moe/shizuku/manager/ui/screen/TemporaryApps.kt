package moe.shizuku.manager.ui.screen

import android.content.pm.PackageInfo
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.authorization.TemporaryApp
import moe.shizuku.manager.authorization.TemporaryEntry
import moe.shizuku.manager.authorization.TemporarySession
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.SegmentedCard
import rikka.shizuku.server.ServerConstants

/**
 * The Temporary tab: apps holding a grant that ends with the process that asked for it, and the
 * privileged resources the server created for them.
 *
 * <p>The kill actions stop those resources, never the app. They are best-effort: a process that
 * left its session or changed user may survive, and the confirmation says so.
 */
@Composable
fun TemporaryAppsList(
    apps: List<TemporaryApp>,
    packages: List<PackageInfo>,
    supported: Boolean,
    onKill: (uid: Int, sessionPid: Int, kind: Int, pid: Int) -> Unit,
    onRestart: () -> Unit
) {
    var pendingKill by remember { mutableStateOf<(() -> Unit)?>(null) }

    if (!supported) {
        CenteredMessage {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.temporary_restart_needed),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onRestart) {
                    Text(stringResource(R.string.temporary_restart))
                }
            }
        }
        return
    }

    if (apps.isEmpty()) {
        CenteredMessage {
            Text(
                text = stringResource(R.string.temporary_empty),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        apps.forEach { app ->
            TemporaryAppCard(
                app = app,
                pi = packages.firstOrNull { it.applicationInfo?.uid == app.uid },
                onKill = onKill,
                requestKill = { pendingKill = it }
            )
        }
    }

    pendingKill?.let { kill ->
        AlertDialog(
            onDismissRequest = { pendingKill = null },
            title = { Text(stringResource(R.string.temporary_kill_confirm_title)) },
            text = { Text(stringResource(R.string.temporary_kill_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    kill()
                    pendingKill = null
                }) {
                    Text(stringResource(R.string.temporary_kill))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKill = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/** The HTML {@code <details>} chevron: right when closed, rotated a quarter turn when open. */
@Composable
private fun DisclosureButton(expanded: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) 90f else 0f)
        )
    }
}

@Composable
private fun TemporaryAppCard(
    app: TemporaryApp,
    pi: PackageInfo?,
    onKill: (uid: Int, sessionPid: Int, kind: Int, pid: Int) -> Unit,
    requestKill: ((() -> Unit)?) -> Unit
) {
    var expanded by remember(app.uid) { mutableStateOf(false) }
    val context = LocalContext.current
    // A uid with a temporary grant may not be in the server's app list at all (Termux is not
    // a Shizuku client; only the rish loader process is), so resolve it here for the label.
    val resolved = remember(app.uid, pi) {
        pi ?: AuthorizationManager.packageForUid(context, app.uid)
    }

    SegmentedCard {
        Column {
            ListItem(
                leadingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DisclosureButton(expanded) { expanded = !expanded }
                        if (resolved != null) AppIcon(resolved)
                    }
                },
                headlineContent = {
                    Text(
                        text = resolved?.let {
                            runCatching { it.applicationInfo?.loadLabel(context.packageManager)?.toString() }
                                .getOrNull()
                        } ?: "uid ${app.uid}"
                    )
                },
                supportingContent = {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.temporary_counts,
                                app.shells,
                                app.services,
                                app.hosts
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (app.since > 0) {
                            Text(
                                text = stringResource(R.string.temporary_since, relative(app.since)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                trailingContent = {
                    // While expanded, the stops belong to the visible children; collapsing
                    // brings the app-level stop back.
                    if (!expanded) {
                        FilledIconButton(
                            onClick = {
                                requestKill { onKill(app.uid, 0, ServerConstants.TEMPORARY_KIND_ALL, 0) }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.temporary_kill)
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            if (expanded) {
                HorizontalDivider()
                app.sessions.forEach { session ->
                    TemporarySessionRow(app.uid, session, onKill, requestKill)
                }
                if (app.unattributed.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.temporary_unattributed),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    app.unattributed.forEach { entry ->
                        TemporaryEntryRow(app.uid, entry, onKill, requestKill)
                    }
                }
            }
        }
    }
}

@Composable
private fun TemporarySessionRow(
    uid: Int,
    session: TemporarySession,
    onKill: (uid: Int, sessionPid: Int, kind: Int, pid: Int) -> Unit,
    requestKill: ((() -> Unit)?) -> Unit
) {
    var expanded by remember(session.pid) { mutableStateOf(false) }
    var showCmdline by remember(session.pid) { mutableStateOf<String?>(null) }

    Column {
        ListItem(
            modifier = Modifier.padding(start = 16.dp),
            leadingContent = if (session.entries.isNotEmpty()) {
                { DisclosureButton(expanded) { expanded = !expanded } }
            } else {
                null
            },
            headlineContent = {
                Text(stringResource(R.string.temporary_session, session.pid))
            },
            supportingContent = {
                Column {
                    if (session.cmdline.isNotEmpty()) {
                        Text(
                            text = session.cmdline,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showCmdline = session.cmdline }
                        )
                    }
                    if (session.since > 0) {
                        Text(
                            text = stringResource(R.string.temporary_since, relative(session.since)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            trailingContent = {
                // Same rule one level down: an expanded session leaves the stops to its entries.
                if (!expanded) {
                    FilledIconButton(
                        onClick = {
                            requestKill { onKill(uid, session.pid, ServerConstants.TEMPORARY_KIND_ALL, 0) }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.temporary_kill)
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        if (expanded) {
            session.entries.forEach { entry ->
                // Past the chevron and its spacing, plus a little air.
                TemporaryEntryRow(uid, entry, onKill, requestKill, indent = 112.dp)
            }
        }
    }

    showCmdline?.let { cmd ->
        AlertDialog(
            onDismissRequest = { showCmdline = null },
            title = { Text(stringResource(R.string.temporary_cmdline_title)) },
            text = { Text(cmd) },
            confirmButton = {
                TextButton(onClick = { showCmdline = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun TemporaryEntryRow(
    uid: Int,
    entry: TemporaryEntry,
    onKill: (uid: Int, sessionPid: Int, kind: Int, pid: Int) -> Unit,
    requestKill: ((() -> Unit)?) -> Unit,
    indent: Dp = 32.dp
) {
    val kind = kindLabel(entry.kind)
    // A rish host's server-side label was "shell" before the label fix; the kind is the
    // stable thing to show, so an old running server does not make it read as a newProcess
    // shell.
    val headline = if (entry.kind == ServerConstants.TEMPORARY_KIND_HOST) {
        stringResource(R.string.temporary_kind_host_name)
    } else {
        entry.label.ifEmpty { kind }
    }
    // A compact row rather than a ListItem: the latter's 56dp minimum and centred content
    // made the gap above an entry larger than the gap below it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // No top padding: the entry belongs to the session above it. The bottom padding
            // is what separates it from the next session.
            .padding(start = indent, end = 16.dp, top = 0.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge)
            if (kind != headline) {
                Text(kind, style = MaterialTheme.typography.bodySmall)
            }
        }
        FilledIconButton(
            onClick = {
                // A service whose pid could not be found has -1; 0 means "all of this kind
                // in the session", which is the only thing that can be done for it.
                requestKill {
                    onKill(uid, entry.sessionPid, entry.kind, if (entry.pid > 0) entry.pid else 0)
                }
            },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Icon(
                Icons.Rounded.Stop,
                contentDescription = stringResource(R.string.temporary_kill)
            )
        }
    }
}

@Composable
private fun kindLabel(kind: Int): String = when (kind) {
    ServerConstants.TEMPORARY_KIND_SERVICE -> stringResource(R.string.temporary_kind_service)
    ServerConstants.TEMPORARY_KIND_HOST -> stringResource(R.string.temporary_kind_host)
    else -> stringResource(R.string.temporary_kind_shell)
}

private fun relative(since: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        since,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    )
