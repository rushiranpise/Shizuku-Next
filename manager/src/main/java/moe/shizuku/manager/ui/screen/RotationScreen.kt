@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.shizuku.manager.ui.screen

import android.content.pm.PackageInfo
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ScreenLockLandscape
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.InstalledPackages
import moe.shizuku.manager.manage.RotationMode
import moe.shizuku.manager.manage.RotationOverride
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.AppRowLine
import moe.shizuku.manager.ui.component.AppStatusChips
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ChoiceRow
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.appLabel
import moe.shizuku.manager.ui.component.packageLine
import moe.shizuku.manager.ui.component.rowKey
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine

private fun RotationMode.icon(): ImageVector = when (this) {
    RotationMode.OFF -> Icons.Outlined.Block
    RotationMode.AUTO -> Icons.Outlined.ScreenRotation
    RotationMode.PORTRAIT -> Icons.Outlined.ScreenLockPortrait
    RotationMode.LANDSCAPE -> Icons.Outlined.ScreenLockLandscape
}

/**
 * One rotation mode per app.
 *
 * Auto is the compat change from issue #210. It makes an app that requests landscape follow the
 * system rotation, but an app targeting Android 16 can keep its own request, which is why the two
 * forced modes exist. Those hold the display in portrait or landscape for as long as the app is
 * in front and put it back when the app leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotationScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    var apps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var modes by remember { mutableStateOf<Map<String, RotationMode>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showOnlyConfigured by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<PackageInfo?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(ShizukuStateMachine.isRunning()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            val all = InstalledPackages.all(context, 0)
            val launchable = InstalledPackages.launchable()
            if (launchable != null) {
                all.filter { it.packageName in launchable }
            } else {
                all.filter {
                    runCatching { pm.getLaunchIntentForPackage(it.packageName) != null }
                        .getOrDefault(false)
                }
            }
        }
        modes = withContext(Dispatchers.IO) { RotationOverride.modes() }
        running = ShizukuStateMachine.isRunning()
        loading = false
    }

    fun choose(packageName: String, mode: RotationMode) {
        picking = null
        if (!running) return
        scope.launch {
            val changed = withContext(Dispatchers.IO) {
                runCatching { RotationOverride.setMode(packageName, mode) }
                    .getOrDefault(false)
            }
            if (!changed) {
                Diag.warn(AppConstants.TAG, "rotation: $packageName would not change")
                Toast.makeText(context, R.string.app_action_failed, Toast.LENGTH_SHORT).show()
            }
            // Starting or stopping the watch is what makes a forced mode live. A mode saved
            // without this would look applied and do nothing until the next service pass.
            withContext(Dispatchers.IO) { HidingWatchService.refresh(context) }
            modes = withContext(Dispatchers.IO) { RotationOverride.modes() }
        }
    }

    val shown = remember(apps, modes, query, showOnlyConfigured) {
        val byState = if (showOnlyConfigured) {
            apps.filter { it.packageName in modes }
        } else {
            apps
        }
        val trimmed = query.trim()
        val searched = if (trimmed.isBlank()) {
            byState
        } else {
            byState.filter {
                appLabel(pm, it).contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
        // The configured apps first, then by name: the app somebody opens this screen for is
        // usually one already set, and the names are resolved once before the sort.
        searched
            .map { app -> app to appLabel(pm, app).lowercase() }
            .sortedWith(
                compareBy(
                    { (app, _) -> app.packageName !in modes },
                    { (_, label) -> label }
                )
            )
            .map { (app, _) -> app }
    }

    picking?.let { app ->
        val current = modes[app.packageName] ?: RotationMode.OFF
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(R.string.rotation_mode_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        appLabel(pm, app),
                        style = MaterialTheme.typography.titleSmall
                    )
                    RotationMode.entries.forEach { option ->
                        ChoiceRow(
                            selected = current == option,
                            label = stringResource(option.labelRes),
                            supporting = stringResource(option.noteRes),
                            onClick = { choose(app.packageName, option) }
                        )
                    }
                }
            },
            confirmButton = {
                PillButtonQuiet(onClick = { picking = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.tab_rotation))
                    Text(
                        text = stringResource(R.string.rotation_count, modes.size),
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

        Text(
            text = stringResource(R.string.rotation_note),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.apps_filter_all),
                count = apps.size,
                selected = !showOnlyConfigured,
                onClick = { showOnlyConfigured = false }
            )
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.rotation_filter_configured),
                count = apps.count { it.packageName in modes },
                selected = showOnlyConfigured,
                onClick = { showOnlyConfigured = true }
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .televisionTextInput(MaterialTheme.shapes.extraLarge),
            placeholder = { Text(stringResource(R.string.app_management_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(android.R.string.cancel)
                        )
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

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shown, key = { it.rowKey() }) { pi ->
                    val mode = modes[pi.packageName] ?: RotationMode.OFF
                    SegmentedCard {
                        ListItem(
                            onClick = { picking = pi },
                            enabled = running,
                            leadingContent = { AppIcon(pi) },
                            supportingContent = {
                                AppRowLine(pi.packageLine()) {
                                    AppStatusChips(pi, hidden = false)
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = mode.icon(),
                                        contentDescription = stringResource(mode.labelRes),
                                        tint = if (mode == RotationMode.OFF) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        ) {
                            Text(appLabel(pm, pi))
                        }
                    }
                }
            }

            if (loading || shown.isEmpty()) {
                if (loading) {
                    CenteredMessage { LoadingIndicator() }
                } else {
                    CenteredMessage {
                        when {
                            !running -> Text(
                                text = stringResource(R.string.rotation_needs_shizuku),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            query.isNotBlank() -> Text(
                                text = stringResource(R.string.apps_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            showOnlyConfigured -> Text(
                                text = stringResource(R.string.rotation_empty),
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

            ScrollToTopButton(
                listState = listState,
                bottomPadding = bottomPadding,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
