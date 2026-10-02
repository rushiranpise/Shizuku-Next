package moe.shizuku.manager.ui.screen

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WifiOff
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.manage.HidingGrants
import moe.shizuku.manager.manage.InstalledPackages
import moe.shizuku.manager.manage.PackageTools
import moe.shizuku.manager.manage.Signal
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.AppStatusChips
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ChoiceRow
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.appLabel
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * One switch per app, over one state, with the filters that make such a list usable: everything,
 * what is blocked, and what is not.
 *
 * The two features that use it differ only in where that state lives. Autostart is an app op, and
 * the platform will hand over every app in it in a single command. The firewall's deny bit cannot
 * be asked for in bulk - the command takes one package, and six hundred round trips is not a
 * screen anyone waits for - so that list keeps its own record and says so, which is what a
 * firewall without root does, and why the app's own page reads the truth from the platform
 * instead.
 *
 * A row is the row the Apps tab uses, a card per app with its icon and its switch, because this
 * is the same kind of list asking a different question.
 */
enum class AppToggleFeature(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    /** The setting this list hides, or null for the two lists that are about an app op. */
    val signal: Signal? = null
) {
    FIREWALL(R.string.tab_firewall, Icons.Outlined.Shield),
    AUTOSTART(R.string.tab_autostart, Icons.Outlined.RestartAlt),
    HIDE_DEVELOPER_OPTIONS(
        R.string.tab_hide_developer_options,
        Icons.Outlined.DeveloperMode,
        Signal.DEVELOPER_OPTIONS
    ),

    HIDE_USB_DEBUGGING(
        R.string.tab_hide_usb_debugging,
        Icons.Outlined.Usb,
        Signal.USB_DEBUGGING
    ),

    HIDE_WIRELESS_DEBUGGING(
        R.string.tab_hide_wireless_debugging,
        Icons.Outlined.WifiOff,
        Signal.WIRELESS_DEBUGGING
    ),

    HIDE_ACCESSIBILITY(
        R.string.tab_hide_accessibility,
        Icons.Outlined.AccessibilityNew,
        Signal.ACCESSIBILITY
    ),

    HIDE_PRIVATE_DNS(
        R.string.tab_hide_private_dns,
        Icons.Outlined.Dns,
        Signal.PRIVATE_DNS
    ),

    HIDE_VPN(
        R.string.tab_hide_vpn,
        Icons.Outlined.VpnKey,
        Signal.VPN
    );

    /**
     * The op behind Autostart. The firewall's bit is not an app op at all, so nothing reads
     * this for it.
     */
    val op: String
        get() = "RUN_ANY_IN_BACKGROUND"

    /** What the count under the title is called: the hiding lists count apps, not blocked ones. */
    @get:StringRes
    val countRes: Int
        get() = if (signal != null) R.string.hiding_hidden_count else R.string.labs_blocked_count

    @get:StringRes
    val emptyRes: Int
        get() = if (signal != null) R.string.hiding_filter_empty else R.string.labs_blocked_empty

    @get:StringRes
    val onLabelRes: Int
        get() = if (signal != null) R.string.hiding_filter_hidden else R.string.labs_filter_blocked

    @get:StringRes
    val offLabelRes: Int
        get() = if (signal != null) R.string.hiding_filter_shown else R.string.labs_filter_allowed

    /**
     * Whether the list is only about apps somebody installed.
     *
     * A setting like this is not one a system app asks about: the packages that read developer
     * options to decide whether to run are the ones you installed, and six hundred system
     * packages listed against a question they never ask would bury the six that do.
     */
    val userAppsOnly: Boolean get() = signal != null

    /** Whether the list cannot do anything at all without the shell. */
    val needsShell: Boolean get() = signal != null || this == AUTOSTART
}

/**
 * Whether the app is blocked. Its two named states are the only two there are, so the row holds
 * exactly those two and neither being chosen means both: unlike the kind of app, which has a
 * list to choose from, this has nothing else to offer.
 */
private enum class ToggleFilter { ALL, ALLOWED, BLOCKED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabsToggleScreen(
    feature: AppToggleFeature,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    var apps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var blocked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ToggleFilter.ALL) }
    // The other question the list answers, and the one the app-ops list already asked: what
    // kind of app it is, so a system package can be told from something you installed.
    var kind by remember { mutableStateOf(ManageFilter.ALL) }
    var version by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(ShizukuStateMachine.isRunning()) }
    var vpnClient by remember { mutableStateOf(Hiding.chosenVpnClient()) }
    var gateOn by remember {
        mutableStateOf(feature.signal?.let { Hiding.isSignalEnabled(it) } ?: true)
    }
    // The name rather than the package, with the package as the fallback: what a person chose is
    // PairVPN, and "com.pairvpn" is the answer to a different question.
    val vpnClientLabel = vpnClient
        ?.let { chosen ->
            apps.firstOrNull { it.packageName == chosen }?.let { appLabel(pm, it) } ?: chosen
        }
        ?: stringResource(R.string.hiding_vpn_client_none)
    var pickingVpn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        loading = true
        Log.d(AppConstants.TAG, "${feature.name}: reading the apps and their state")

        apps = withContext(Dispatchers.IO) {
            InstalledPackages.all(context, 0)
        }
        blocked = withContext(Dispatchers.IO) { readBlocked(feature, context) }
        running = ShizukuStateMachine.isRunning()
        // Opening a list is as good a reason as any to make sure the watch is up: it may have
        // stood itself down while Shizuku was away, and a list that does nothing would look
        // like a broken switch.
        if (feature.signal != null) {
            // Opening a hiding list is the first time the feature could be used, so the two
            // grants it needs are asked for here rather than left to a settings page somebody
            // has to find. Nothing is shown: there is no decision in it.
            withContext(Dispatchers.IO) { HidingGrants.ensureQuietly() }
        }
        if (feature.signal != null && Hiding.isActive()) {
            withContext(Dispatchers.IO) { HidingWatchService.refresh(context) }
        }
        vpnClient = Hiding.chosenVpnClient()
        gateOn = feature.signal?.let { Hiding.isSignalEnabled(it) } ?: true
        loading = false
    }

    /**
     * Flips one app, then reads the state back.
     *
     * The switch moves with the tap and the read is what settles it: a change the platform
     * refuses leaves the row where it was rather than showing something that did not happen,
     * which is the same trade the app-ops dialog makes.
     */
    fun toggle(packageName: String, shouldBlock: Boolean) {
        scope.launch {
            val applied = withContext(Dispatchers.IO) {
                val signal = feature.signal
                when {
                    // A hiding list is a rule about an app and not a state: what it writes down
                    // is that this app objects, and whether anything is hidden right now is a
                    // question for the watch, which is told to look again.
                    signal != null ->
                        Hiding.setOnList(signal, packageName, shouldBlock)

                    feature == AppToggleFeature.AUTOSTART ->
                        PackageTools.setOpBlocked(context, packageName, feature.op, shouldBlock)

                    else ->
                        PackageTools.setNetworkBlocked(context, packageName, shouldBlock)
                }
            }
            if (!applied) {
                Log.w(AppConstants.TAG, "${feature.name}: $packageName would not change")
            }
            if (feature.signal != null) {
                withContext(Dispatchers.IO) { HidingWatchService.refresh(context) }
            }
            blocked = withContext(Dispatchers.IO) { readBlocked(feature, context) }
        }
    }

    // The one list that cannot be applied: the setting hides, and takes the shell that hid it
    // with it, so nothing is left to notice the app is gone. Saying so beats a switch that
    // appears to work and an app that dies on it.
    val carriesSession = feature.signal?.let { Hiding.carriesSession(it) } == true

    // What "hidden" means here as well: installed, and never in the app drawer. Asked of the
    // package manager once per app, off the main thread, which is why it is worked out once per
    // read rather than per keystroke.
    val systemPackages = remember(apps) {
        apps.filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { it.packageName }.toSet()
    }
    val userPackages = remember(apps, systemPackages) {
        apps.filter { it.applicationInfo != null && it.packageName !in systemPackages }
            .map { it.packageName }.toSet()
    }
    val disabledPackages = remember(apps) {
        apps.filter {
            val ai = it.applicationInfo ?: return@filter false
            !ai.enabled || (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        }.map { it.packageName }.toSet()
    }
    var launcherless by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(apps) {
        launcherless = if (apps.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) {
                InstalledPackages.launcherless(context, apps)
            }
        }
    }

    val shown = remember(apps, blocked, launcherless, systemPackages, userPackages, disabledPackages, query, filter, kind) {
        val byKind = if (feature.userAppsOnly) {
            apps.filter { it.packageName in userPackages }
        } else when (kind) {
            ManageFilter.ALL -> apps
            ManageFilter.USER -> apps.filter { it.packageName in userPackages }
            ManageFilter.SYSTEM -> apps.filter { it.packageName in systemPackages }
            ManageFilter.DISABLED -> apps.filter { it.packageName in disabledPackages }
            ManageFilter.HIDDEN -> apps.filter { it.packageName in launcherless }
        }
        val byState = when (filter) {
            ToggleFilter.ALL -> byKind
            ToggleFilter.BLOCKED -> byKind.filter { it.packageName in blocked }
            ToggleFilter.ALLOWED -> byKind.filter { it.packageName !in blocked }
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
        searched.sortedBy { appLabel(pm, it).lowercase() }
    }

    if (pickingVpn) {
        val candidates = remember(apps) { Hiding.vpnCandidates() }
        AlertDialog(
            onDismissRequest = { pickingVpn = false },
            title = { Text(stringResource(R.string.hiding_vpn_client)) },
            text = {
                LazyColumn {
                    items(
                        candidates.mapNotNull { pkg ->
                            apps.firstOrNull { it.packageName == pkg }
                        },
                        key = { it.packageName }
                    ) { pi ->
                        // The same row the rest of the app's single-choice lists use, with the
                        // app's icon after the radio: which app is chosen is a choice like any
                        // other, and the icon is only how the row is found among the rest.
                        ChoiceRow(
                            selected = pi.packageName == vpnClient,
                            label = appLabel(pm, pi),
                            supporting = pi.packageName,
                            leading = { AppIcon(pi) },
                            onClick = {
                                Hiding.chooseVpnClient(pi.packageName)
                                vpnClient = pi.packageName
                                pickingVpn = false
                                version++
                            }
                        )
                    }
                }
            },
            confirmButton = {
                PillButtonQuiet(onClick = { pickingVpn = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(feature.titleRes))
                    // How many are blocked, next to the name: the list's filters can say it,
                    // but the count is the thing somebody opens this screen to see.
                    Text(
                        // "Off" rather than a count while the mode is switched off: the number
                        // of apps on the list is true either way, and it is not the thing this
                        // screen is currently doing.
                        // Off shows what is being kept rather than repeating the word below it,
                        // which is the one question this screen still answers with the list out
                        // of sight.
                        text = if (!gateOn) {
                            stringResource(R.string.hiding_gate_kept, blocked.size)
                        } else {
                            stringResource(feature.countRes, blocked.size)
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
            }
        )

        // Two rows, because they answer two questions and one row cannot say which is which:
        // what kind of app this is, and whether it is blocked. Five kind labels do not fit on a
        // phone, so that row scrolls like the app-ops list's does; the state row is two chips
        // and fits, and leaves room for the next kind to be added without moving anything.
        // The one list that has to be told what to act on, because a tunnel does not say whose
        // it is: eight apps on this device answer VpnService, and stopping the wrong one leaves a
        // VPN down that nothing knows how to bring back.
        //
        // A sibling of the chip rows and not inside one. It was inside the kind row, which
        // scrolls horizontally - and a scrolling row hands its children an unbounded width, so
        // a card that asked to fill the width hugged its own text and changed size with the
        // length of a package name.
        // The mode itself, off and on, above the list it applies to. A list of apps that object
        // is worth keeping while the hiding is turned off - for a day, for an app that no longer
        // minds, or to prove the hiding is what caused something - and until this switch the only
        // way to stop a mode was to empty its list, which threw that work away.
        feature.signal?.let { signal ->
            SegmentedCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                ListItem(
                    // The state word and the switch, nothing under them. The word used to be
                    // fixed at "Enabled" while the switch said otherwise, and the sentence
                    // under it explained the state the screen was not in; both are gone, and the
                    // one thing the row has to say is what the switch is doing now.
                    headlineContent = {
                        Text(
                            stringResource(
                                if (gateOn) R.string.hiding_gate else R.string.hiding_gate_disabled
                            )
                        )
                    },
                    trailingContent = {
                        ExpressiveSwitch(
                            checked = gateOn,
                            onCheckedChange = { checked ->
                                Hiding.setSignalEnabled(signal, checked)
                                gateOn = checked
                                // The watch starts and stops with the gates, so it is told to
                                // look again: turning the last one off has nothing left to do.
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        HidingWatchService.refresh(context)
                                    }
                                    version++
                                }
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        // A mode that is switched off has nothing to list. The list is kept - that is the whole
        // point of the gate - and it is not drawn, because a list of apps somebody is not being
        // hidden from is something to come back to, not something to work through now.
        if (feature.signal != null && !gateOn) return@Column

        if (feature.signal == Signal.VPN) {
            SegmentedCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                ListItem(
                    modifier = Modifier.clickable { pickingVpn = true },
                    headlineContent = { Text(stringResource(R.string.hiding_vpn_client)) },
                    supportingContent = { Text(vpnClientLabel) },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        // As wide as the row is: the first filter against one edge, the last against the other,
        // and the slack shared out between them rather than scrolled past.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Not on a hiding list: everything on one is an app somebody installed, so there
            // is no second kind of app to tell it apart from.
            if (!feature.userAppsOnly) ManageFilter.entries.forEach { option ->
                val count = when (option) {
                    ManageFilter.ALL -> apps.size
                    ManageFilter.USER -> userPackages.size
                    ManageFilter.SYSTEM -> systemPackages.size
                    ManageFilter.DISABLED -> disabledPackages.size
                    ManageFilter.HIDDEN -> launcherless.size
                }
                AppFilterChip(
                    label = stringResource(
                        when (option) {
                            ManageFilter.ALL -> R.string.manage_filter_all
                            ManageFilter.USER -> R.string.manage_filter_user
                            ManageFilter.SYSTEM -> R.string.manage_filter_system
                            ManageFilter.DISABLED -> R.string.manage_filter_disabled
                            ManageFilter.HIDDEN -> R.string.manage_filter_hidden
                        }
                    ),
                    count = count,
                    selected = kind == option,
                    // Hugging its label rather than filling a slot: a row that scrolls sizes
                    // each chip to its own text.
                    fill = false,
                    onClick = { kind = option }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(feature.offLabelRes),
                count = apps.count { it.packageName !in blocked },
                selected = filter == ToggleFilter.ALLOWED,
                // Tapping the chosen one again lets go of it: with no filter the list shows
                // both, which is what opening the screen should do.
                onClick = {
                    filter = if (filter == ToggleFilter.ALLOWED) ToggleFilter.ALL else ToggleFilter.ALLOWED
                }
            )
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(feature.onLabelRes),
                count = apps.count { it.packageName in blocked },
                selected = filter == ToggleFilter.BLOCKED,
                onClick = {
                    filter = if (filter == ToggleFilter.BLOCKED) ToggleFilter.ALL else ToggleFilter.BLOCKED
                }
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 4.dp,
                    end = 16.dp,
                    bottom = bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Said here rather than in one setting somewhere: the firewall's list is this
                // app's own record, and the app's page is where the platform is asked.
                if (carriesSession) {
                    item {
                        Text(
                            text = stringResource(
                                if (feature == AppToggleFeature.HIDE_WIRELESS_DEBUGGING) {
                                    R.string.hiding_carries_session_wireless
                                } else {
                                    R.string.hiding_carries_session_usb
                                }
                            ),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .padding(bottom = 4.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                items(shown, key = { it.packageName }) { pi ->
                    val isBlocked = pi.packageName in blocked
                    SegmentedCard {
                        ListItem(
                            leadingContent = { AppIcon(pi) },
                            headlineContent = { Text(appLabel(pm, pi)) },
                            supportingContent = {
                                Text(
                                    pi.packageName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // The chips the other app lists carry, so an app says the
                                    // same things about itself wherever it is listed.
                                    AppStatusChips(pi, hidden = pi.packageName in launcherless)
                                    ExpressiveSwitch(
                                        checked = isBlocked,
                                        enabled = (!feature.needsShell || running) && !carriesSession,
                                        onCheckedChange = { checked ->
                                            blocked = if (checked) blocked + pi.packageName
                                            else blocked - pi.packageName
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
                            !running && feature.needsShell -> Text(
                                text = stringResource(R.string.apps_needs_shizuku),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            query.isNotBlank() -> Text(
                                text = stringResource(R.string.apps_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            filter == ToggleFilter.BLOCKED -> Text(
                                text = stringResource(feature.emptyRes),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )

                            kind != ManageFilter.ALL -> Text(
                                text = stringResource(R.string.apps_filter_empty),
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
        }
    }
}

/** The state this feature is about: which apps are blocked, according to its own source. */
private suspend fun readBlocked(
    feature: AppToggleFeature,
    context: android.content.Context
): Set<String> = when {
    // This app's own record, one list per setting: the platform can be asked about a setting
    // but not about which apps asked for it to be hidden.
    feature.signal != null -> Hiding.appsFor(feature.signal)
    // One command for every app in the mode.
    feature == AppToggleFeature.AUTOSTART -> PackageTools.readOpDenied(feature.op)
    // This app's own record; the platform cannot be asked for the list.
    else -> PackageTools.readFirewallBlocked(context)
}
