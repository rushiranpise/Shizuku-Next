package moe.shizuku.manager.ui.screen

import android.content.pm.PackageInfo
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.ForceDark
import moe.shizuku.manager.manage.InstalledPackages
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.AppRowLine
import moe.shizuku.manager.ui.component.AppStatusChips
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.rowKey
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.appLabel
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * One switch per app, over the force-dark rule.
 *
 * A list rather than a settings page, because this is a rule about apps and there are hundreds of
 * them: the same shape the hiding lists use, asking a different question. Only apps that can be
 * opened are listed, which is the whole population the question is about - an app with no launcher
 * is an app nobody sits in front of, and the switches below only matter while something is open.
 *
 * Toggling closes the app. Not tidiness: the switches are read when an app starts, so an app that
 * is already running keeps whatever it found, and without the close the feature looks broken until
 * the user works out that they have to reopen things. The app being toggled is behind this screen
 * rather than in front of it, so closing it is invisible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForceDarkScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    var apps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var forced by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showOnlyForced by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(ShizukuStateMachine.isRunning()) }
    var gateOn by remember { mutableStateOf(ForceDark.isEnabled()) }
    var systemTheme by remember { mutableStateOf(ForceDark.usesSystemTheme()) }
    var justToggled by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            // Only apps with something in the launcher: force dark applies to an app somebody
            // opens. Which those are is asked of the shell, for the same reason the list is.
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
        forced = withContext(Dispatchers.IO) { ForceDark.apps() }
        running = ShizukuStateMachine.isRunning()
        gateOn = ForceDark.isEnabled()
        systemTheme = ForceDark.usesSystemTheme()
        // Opening the list is as good a reason as any to make sure the watch is up: it may have
        // stood itself down while Shizuku was away, and a list that does nothing looks like a
        // broken switch.
        if (ForceDark.hasWorkToDo()) {
            withContext(Dispatchers.IO) { HidingWatchService.refresh(context) }
        }
        loading = false
    }

    /**
     * Flips one app: the list, then the close that makes the list mean something.
     *
     * The close is what the user would otherwise do by hand every single time, and the reason it
     * is here rather than in a note is that a force-dark feature that appears not to work is
     * indistinguishable from one that does not exist.
     */
    fun toggle(packageName: String, wanted: Boolean) {
        forced = if (wanted) forced + packageName else forced - packageName
        justToggled = packageName
        scope.launch {
            withContext(Dispatchers.IO) {
                ForceDark.setForced(packageName, wanted)
                if (running) {
                    // The app is closed whether it is being turned on or off: in both directions
                    // the running process is holding the old answer.
                    ForceDark.restart(packageName)
                    HidingWatchService.refresh(context)
                }
            }
        }
    }

    val shown = remember(apps, forced, query, showOnlyForced) {
        val byState = if (showOnlyForced) apps.filter { it.packageName in forced } else apps
        val trimmed = query.trim()
        val searched = if (trimmed.isBlank()) {
            byState
        } else {
            byState.filter {
                appLabel(pm, it).contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
        // The forced apps first, then by name, for the same reason the hiding lists do it: what
        // somebody opens this screen for is what is already on it. The names are resolved once,
        // before the sort, for the reason the hiding list gives.
        searched
            .map { app -> app to appLabel(pm, app).lowercase() }
            .sortedWith(
                compareBy({ (app, _) -> app.packageName !in forced }, { (_, label) -> label })
            )
            .map { (app, _) -> app }
    }

    // One place for what turning the mode on or off does: the setting, the screen's own state and
    // telling the watch to look again are three things that must not drift apart, and the switch
    // that does them has moved to the header.
    fun setGate(checked: Boolean) {
        gateOn = checked
        scope.launch {
            withContext(Dispatchers.IO) {
                ForceDark.setEnabled(checked)
                // Turning it off has to put the switches back whether or not this app is the one
                // holding them: the user has just asked for exactly that.
                if (!checked) ForceDark.restoreAlways()
                HidingWatchService.refresh(context)
            }
            version++
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.tab_force_dark))
                    Text(
                        text = if (!gateOn) {
                            stringResource(R.string.force_dark_gate_disabled)
                        } else {
                            stringResource(R.string.force_dark_count, forced.size)
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
                // In the header, where one look at the title says which mode is being switched,
                // and inset the way the title is: this bar has no padding of its own, so a switch
                // put in it without any lands on the glass.
                ExpressiveSwitch(
                    checked = gateOn,
                    onCheckedChange = { checked -> setGate(checked) },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        )

        // The switch itself is in the header; what used to be here was a card saying "Enabled" or
        // "Disabled" above the list, which is the one thing a switch already says.

        SegmentedCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.force_dark_system_theme)) },
                trailingContent = {
                    ExpressiveSwitch(
                        checked = systemTheme,
                        onCheckedChange = { checked ->
                            systemTheme = checked
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ForceDark.setUsesSystemTheme(checked)
                                    // Off means the theme goes back now, not on the next app
                                    // change that may never come.
                                    if (!checked) ForceDark.restoreAlways()
                                    HidingWatchService.refresh(context)
                                }
                            }
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }

        // A mode that is switched off keeps its list, dimmed: the list is what the gate exists to
        // preserve, and the per-app switches on it are one of the reasons to come back to a mode
        // that is off.
        val contentAlpha = if (gateOn) 1f else GateOffAlpha

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.force_dark_filter_not),
                count = apps.count { it.packageName !in forced },
                selected = !showOnlyForced,
                enabled = gateOn,
                onClick = { showOnlyForced = false }
            )
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.force_dark_filter_forced),
                count = apps.count { it.packageName in forced },
                selected = showOnlyForced,
                enabled = gateOn,
                onClick = { showOnlyForced = true }
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .televisionTextInput(MaterialTheme.shapes.extraLarge),
            // Shut with the list while the gate is off: what is shown then is what is being kept,
            // not something to sieve.
            enabled = gateOn,
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

        Box(modifier = Modifier.fillMaxSize().alpha(contentAlpha)) {
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
                    val isForced = pi.packageName in forced
                    SegmentedCard {
                        ListItem(
                            leadingContent = { AppIcon(pi) },
                            headlineContent = { Text(appLabel(pm, pi)) },
                            supportingContent = {
                                AppRowLine(
                                    // The line under the name answers the one question the switch
                                    // raises the moment it moves: whether anything happened.
                                    text = if (justToggled == pi.packageName) {
                                        stringResource(R.string.force_dark_toggled)
                                    } else {
                                        pi.packageName
                                    }
                                ) {
                                    AppStatusChips(pi, hidden = false)
                                    ExpressiveSwitch(
                                        checked = isForced,
                                        // Dimmed and inert while the gate is off: the list is being
                                        // kept, not applied, and a switch that changes nothing is
                                        // one to stop offering.
                                        enabled = running && gateOn,
                                        onCheckedChange = { checked -> toggle(pi.packageName, checked) }
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
                                text = stringResource(R.string.apps_needs_shizuku),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            query.isNotBlank() -> Text(
                                text = stringResource(R.string.apps_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            showOnlyForced -> Text(
                                text = stringResource(R.string.force_dark_empty),
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
