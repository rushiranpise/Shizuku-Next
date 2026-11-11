package moe.shizuku.manager.ui.screen

import android.content.pm.PackageInfo
import android.widget.Toast
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
import moe.shizuku.manager.manage.RotationOverride
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.AppRowLine
import moe.shizuku.manager.ui.component.AppStatusChips
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.appLabel
import moe.shizuku.manager.ui.component.packageLine
import moe.shizuku.manager.ui.component.rowKey
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * One switch per app, over the platform's own rotation override.
 *
 * The feature exists for apps and games that hold the phone in landscape. Only apps with a
 * launcher are listed: that is the population somebody can sit in front of, and the list of every
 * package would bury them under services and overlays.
 *
 * Toggling closes the app. The compat change is read when an app starts, so leaving the old
 * process running would make the switch look like it did nothing until the app was reopened by
 * hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotationScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    var apps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var overridden by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showOnlyOverridden by remember { mutableStateOf(false) }
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
        overridden = withContext(Dispatchers.IO) { RotationOverride.enabledPackages() }
        running = ShizukuStateMachine.isRunning()
        loading = false
    }

    /**
     * Flips one app and reads the platform back.
     *
     * The switch moves with the tap so the row does not lag behind the finger, but the read is
     * what settles it: a command the platform refuses leaves the row where it was rather than
     * showing something that did not happen.
     */
    fun toggle(packageName: String, wanted: Boolean) {
        overridden = if (wanted) overridden + packageName else overridden - packageName
        scope.launch {
            val applied = withContext(Dispatchers.IO) {
                runCatching { RotationOverride.setEnabled(packageName, wanted) }
                    .getOrDefault(false)
            }
            if (!applied) {
                Diag.warn(AppConstants.TAG, "rotation: $packageName would not change")
                Toast.makeText(context, R.string.app_action_failed, Toast.LENGTH_SHORT).show()
            }
            overridden = withContext(Dispatchers.IO) { RotationOverride.enabledPackages() }
        }
    }

    val shown = remember(apps, overridden, query, showOnlyOverridden) {
        val byState = if (showOnlyOverridden) {
            apps.filter { it.packageName in overridden }
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
        // What is already overridden first, then by name: the apps somebody opens this screen for
        // are the ones already on the list, and the names are resolved once before the sort.
        searched
            .map { app -> app to appLabel(pm, app).lowercase() }
            .sortedWith(
                compareBy(
                    { (app, _) -> app.packageName !in overridden },
                    { (_, label) -> label }
                )
            )
            .map { (app, _) -> app }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.tab_rotation))
                    Text(
                        text = stringResource(R.string.rotation_count, overridden.size),
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
                selected = !showOnlyOverridden,
                onClick = { showOnlyOverridden = false }
            )
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.rotation_filter_rotated),
                count = apps.count { it.packageName in overridden },
                selected = showOnlyOverridden,
                onClick = { showOnlyOverridden = true }
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
                    val isOverridden = pi.packageName in overridden
                    SegmentedCard {
                        ListItem(
                            leadingContent = { AppIcon(pi) },
                            headlineContent = { Text(appLabel(pm, pi)) },
                            supportingContent = {
                                AppRowLine(pi.packageLine()) {
                                    AppStatusChips(pi, hidden = false)
                                    ExpressiveSwitch(
                                        checked = isOverridden,
                                        enabled = running,
                                        onCheckedChange = { checked ->
                                            toggle(pi.packageName, checked)
                                        }
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
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

                            showOnlyOverridden -> Text(
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
