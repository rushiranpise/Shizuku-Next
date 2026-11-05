package moe.shizuku.manager.ui.screen

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.accessibility.AccessibilityKeepAlive
import moe.shizuku.manager.accessibility.AccessibilityKeepAliveService
import moe.shizuku.manager.accessibility.AccessibilityServiceEntry
import moe.shizuku.manager.accessibility.AccessibilitySettings
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.AppRowLine
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.televisionTextInput

/** Which services the list is showing, the same two-chip filter its sibling lists use. */
private enum class ServiceFilter { ALL, OFF, ON }

/**
 * Every accessibility service on the device, in the shape this app's other app lists have.
 *
 * The header switch is the keep-alive, not a service: the services themselves stay switches on
 * their rows, because they exist whether the keep-alive is armed or not. A lock on a row is what
 * makes the keep-alive act on that service, and turning a service off unlocks it so the watch
 * cannot put it back a second later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var services by remember { mutableStateOf<List<AccessibilityServiceEntry>>(emptyList()) }
    var enabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    var keepAlive by remember { mutableStateOf(false) }
    var autoBoot by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ServiceFilter.ALL) }

    // The system accessibility screen can switch a service too, so this is read again whenever
    // the page is come back to.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    LaunchedEffect(refresh) {
        keepAlive = AccessibilityKeepAlive.isEnabled(context)
        autoBoot = AccessibilityKeepAlive.isAutoBoot(context)
        val loaded = withContext(Dispatchers.IO) {
            AccessibilitySettings.installed(context) to AccessibilitySettings.enabled(context)
        }
        services = loaded.first
        enabled = loaded.second
        loading = false
    }

    fun setService(id: String, on: Boolean) {
        val before = enabled
        enabled = if (on) before + id else before - id
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                AccessibilitySettings.setEnabled(context, id, on)
            }
            if (!ok) {
                enabled = before
                Toast.makeText(
                    context,
                    R.string.accessibility_write_failed,
                    Toast.LENGTH_LONG
                ).show()
            } else if (on) {
                // With the lock on, a service switched on here is one of the services it keeps.
                if (keepAlive) AccessibilityKeepAlive.pin(context, id)
            } else {
                // Off here must stay off: a lock left behind would have the watch turn it back on.
                AccessibilityKeepAlive.unpin(context, id)
                AccessibilityKeepAliveService.refresh(context)
            }
        }
    }

    val onCount = services.count { it.id in enabled }
    val shown = remember(services, enabled, query, filter) {
        val byState = when (filter) {
            ServiceFilter.ALL -> services
            ServiceFilter.ON -> services.filter { it.id in enabled }
            ServiceFilter.OFF -> services.filter { it.id !in enabled }
        }
        val trimmed = query.trim()
        val searched = if (trimmed.isBlank()) {
            byState
        } else {
            byState.filter {
                it.label.contains(trimmed, ignoreCase = true) ||
                    it.appLabel.contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
        // On first, then by name, like the other lists: what somebody opens this screen for is
        // what is already switched on.
        searched.sortedWith(compareBy({ it.id !in enabled }, { it.label.lowercase() }))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.tab_accessibility))
                    Text(
                        text = if (!keepAlive) {
                            stringResource(R.string.accessibility_gate_off)
                        } else {
                            stringResource(R.string.accessibility_count, onCount)
                        },
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
            },
            actions = {
                // The keep-alive lives in the header, where one look says whether the screen is
                // keeping anything on. The per-service switches are the list below it.
                ExpressiveSwitch(
                    checked = keepAlive,
                    onCheckedChange = { checked ->
                        keepAlive = checked
                        AccessibilityKeepAlive.setEnabled(context, checked)
                        if (checked) {
                            // One switch for every service, so turning it on locks whatever is
                            // on at that moment. Services switched on later are locked by
                            // [setService].
                            AccessibilityKeepAlive.pinAll(context, enabled)
                            AccessibilityKeepAliveService.refresh(context)
                        } else {
                            AccessibilityKeepAlive.clearPinned(context)
                            AccessibilityKeepAliveService.stop(context)
                        }
                    },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        )

        // The one setting the keep-alive owns besides the lock, in the same place the force-dark
        // screen puts its own: a single row above the list, not a card in it.
        SegmentedCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.accessibility_boot)) },
                supportingContent = {
                    Text(stringResource(R.string.accessibility_boot_summary))
                },
                trailingContent = {
                    ExpressiveSwitch(
                        checked = autoBoot,
                        enabled = keepAlive,
                        onCheckedChange = {
                            autoBoot = it
                            AccessibilityKeepAlive.setAutoBoot(context, it)
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.accessibility_filter_off),
                count = services.size - onCount,
                selected = filter == ServiceFilter.OFF,
                onClick = {
                    filter = if (filter == ServiceFilter.OFF) ServiceFilter.ALL
                    else ServiceFilter.OFF
                }
            )
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.accessibility_filter_on),
                count = onCount,
                selected = filter == ServiceFilter.ON,
                onClick = {
                    filter = if (filter == ServiceFilter.ON) ServiceFilter.ALL
                    else ServiceFilter.ON
                }
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

        Box(modifier = Modifier.fillMaxSize()) {
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
                items(shown, key = { it.id }) { entry ->
                    SegmentedCard {
                        ListItem(
                            leadingContent = { AppIcon(entry.packageName) },
                            headlineContent = { Text(entry.label) },
                            supportingContent = {
                                // The app list's own line: package name, then the switch, so a
                                // long name gives way on one line instead of wrapping the row.
                                AppRowLine(entry.packageName) {
                                    ExpressiveSwitch(
                                        checked = entry.id in enabled,
                                        onCheckedChange = { setService(entry.id, it) }
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            if (loading) {
                CenteredMessage { CircularProgressIndicator() }
            } else if (shown.isEmpty()) {
                CenteredMessage {
                    Text(
                        text = stringResource(
                            if (query.isNotBlank()) R.string.accessibility_no_match
                            else R.string.accessibility_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
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
