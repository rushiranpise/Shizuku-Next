package moe.shizuku.manager.ui.screen

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import moe.shizuku.manager.ui.component.televisionTextInput
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.AutoStartGate
import moe.shizuku.manager.manage.BlockedGates
import moe.shizuku.manager.manage.FirewallKey
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.manage.HidingGrants
import moe.shizuku.manager.manage.InstalledPackages
import moe.shizuku.manager.manage.PackageTools
import moe.shizuku.manager.manage.PinnedApps
import moe.shizuku.manager.manage.Signal
import moe.shizuku.manager.manage.firewallKey
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.AppIcon
import moe.shizuku.manager.ui.component.rowKey
import moe.shizuku.manager.ui.component.AppRowLine
import moe.shizuku.manager.ui.component.AppStatusChips
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ChoiceRow
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.appLabel
import moe.shizuku.manager.ui.component.packageLine
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat

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

/** What one bulk change will write, and how many pinned apps it leaves alone. */
private data class BulkToggleRequest(
    val block: Boolean,
    val packages: List<String>,
    val pinnedSkipped: Int
)

private sealed interface BulkToggleState {
    data class Confirm(val request: BulkToggleRequest) : BulkToggleState
    data class Running(
        val request: BulkToggleRequest,
        val completed: Int,
        val failed: Int
    ) : BulkToggleState

    data class Finished(val request: BulkToggleRequest, val failed: Int) : BulkToggleState
}

/**
 * How much of itself a list keeps while its mode is switched off.
 *
 * Dimmed rather than absent: the list is *kept* while the gate is off - that is what the gate is
 * for - and what is kept is work somebody came back for, so it is shown as what it is, out of
 * effect rather than gone. Far enough down to read as out of effect, far enough up to read.
 */
internal const val GateOffAlpha = 0.45f

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
    var mobileOnly by remember { mutableStateOf<Set<String>>(emptySet()) }
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
        mutableStateOf(
            feature.signal?.let { Hiding.isSignalEnabled(it) }
                ?: BlockedGates.isEnabled(feature.name)
        )
    }
    // The name rather than the package, with the package as the fallback: what a person chose is
    // PairVPN, and "com.pairvpn" is the answer to a different question.
    val vpnClientLabel = vpnClient
        ?.let { chosen ->
            apps.firstOrNull { it.packageName == chosen }?.let { appLabel(pm, it) } ?: chosen
        }
        ?: stringResource(R.string.hiding_vpn_client_none)
    var pickingVpn by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(PinnedApps.all()) }
    var bulkMenu by remember { mutableStateOf(false) }
    var bulkToggle by remember { mutableStateOf<BulkToggleState?>(null) }
    val scope = rememberCoroutineScope()

    /** The key one row's state is kept under: a package, or a package under another user. */
    fun slotKey(pi: PackageInfo): String =
        if (feature == AppToggleFeature.FIREWALL) pi.firewallKey() else pi.packageName

    /** What putting one app on or off this list writes, whichever kind of list it is. */
    suspend fun writeBlocked(packageName: String, shouldBlock: Boolean): Boolean = when {
        feature.signal != null -> Hiding.setOnList(feature.signal, packageName, shouldBlock)
        feature == AppToggleFeature.AUTOSTART ->
            PackageTools.setOpBlocked(context, packageName, feature.op, shouldBlock)

        else -> PackageTools.setNetworkBlocked(context, packageName, shouldBlock)
    }

    LaunchedEffect(version) {
        loading = true
        Log.d(AppConstants.TAG, "${feature.name}: reading the apps and their state")

        apps = withContext(Dispatchers.IO) {
            if (feature == AppToggleFeature.FIREWALL) {
                InstalledPackages.allUsers(context, 0)
            } else {
                InstalledPackages.all(context, 0)
            }
        }
        blocked = withContext(Dispatchers.IO) { readBlocked(feature, context) }
        mobileOnly = withContext(Dispatchers.IO) {
            if (feature == AppToggleFeature.FIREWALL) {
                PackageTools.readFirewallMobileOnly(context)
            } else {
                emptySet()
            }
        }
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
        // Read back the same way it is first read, and for both kinds of list: the two that are about
        // an app op keep their gate in this app's settings, and reading "on" for them here would put
        // the switch back wherever it was before the tap that changed it.
        gateOn = feature.signal?.let { Hiding.isSignalEnabled(it) }
            ?: BlockedGates.isEnabled(feature.name)
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
                when {
                    // A hiding list is a rule about an app and not a state: what it writes down
                    // is that this app objects, and whether anything is hidden right now is a
                    // question for the watch, which is told to look again.
                    feature.signal != null -> writeBlocked(packageName, shouldBlock)

                    // While the gate is off the rows edit what is being kept rather than the
                    // platform: nothing is applied until it is switched back on, which is the whole
                    // of what a gate is for.
                    !BlockedGates.isEnabled(feature.name) -> {
                        val kept = BlockedGates.kept(feature.name)
                        BlockedGates.setKept(
                            feature.name,
                            if (shouldBlock) kept + packageName else kept - packageName
                        )
                        true
                    }

                    else -> writeBlocked(packageName, shouldBlock)
                }
            }
            if (!applied) {
                Diag.warn(AppConstants.TAG, "${feature.name}: $packageName would not change")
                Toast.makeText(context, R.string.app_action_failed, Toast.LENGTH_SHORT).show()
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

    val shown = remember(apps, blocked, launcherless, systemPackages, userPackages, disabledPackages, query, filter, kind, pinned) {
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
            ToggleFilter.BLOCKED -> byKind.filter { slotKey(it) in blocked }
            ToggleFilter.ALLOWED -> byKind.filter { slotKey(it) !in blocked }
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
        // The apps this mode applies to first, then by name. The list is long - three hundred
        // packages on a phone - and the rows somebody opens this screen for are the ones already on
        // it, which sorting by name alone buries wherever the alphabet happens to put them. Inside
        // each group the order is still the name, so nothing moves except across the line between
        // applied and not.
        //
        // Each name is resolved once, before the sort rather than inside it: a comparator is asked
        // for its keys about n log n times, and this one runs again on every keystroke of the
        // search above it, so a lookup per comparison was the same few labels fetched over and over.
        searched
            .map { app -> app to appLabel(pm, app).lowercase() }
            .sortedWith(
                compareBy(
                    { (app, _) -> app.packageName !in pinned },
                    { (app, _) -> slotKey(app) !in blocked },
                    { (_, label) -> label }
                )
            )
            .map { (app, _) -> app }
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
                        key = { it.rowKey() }
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

    // One place for what turning the mode on or off does, because the switch that does it sits in
    // the header: the setting, what the list applies, the screen's own state and telling the watch
    // to look again are four things that must not drift apart.
    fun setGate(checked: Boolean) {
        gateOn = checked
        scope.launch {
            // Whether the platform refused to put something back, which is the one outcome the
            // switch is not allowed to hide.
            var refused = false
            val now = withContext(Dispatchers.IO) {
                val signal = feature.signal
                if (signal != null) {
                    Hiding.setSignalEnabled(signal, checked)
                } else if (feature == AppToggleFeature.AUTOSTART) {
                    // The one gate that also exists outside this screen, on a tile and a widget: the
                    // same code answers both, so a list disarmed from one place is disarmed in all
                    // of them and a refusal reads the same wherever it was asked for.
                    refused = !AutoStartGate.setOn(context, checked)
                } else {
                    // The firewall's list is about an app op and has no rule of its own - what it
                    // acts on is what the platform reports - so the gate keeps the list itself: off
                    // writes it down and puts every app on it back, on puts those apps back again.
                    // A round trip therefore returns exactly what was there, which is what makes
                    // offering it safe.
                    val key = feature.name
                    if (checked) {
                        BlockedGates.kept(key).forEach { app -> writeBlocked(app, true) }
                        BlockedGates.setEnabled(key, true)
                    } else {
                        // Only a list that was actually put back is remembered, and only then is
                        // the gate switched off: reporting off over apps the platform still blocks
                        // is a switch that says it worked while nothing changed, which is how this
                        // was reported (issue #101).
                        val stayed = blocked.filterNot { app -> writeBlocked(app, false) }
                        if (stayed.isEmpty()) {
                            BlockedGates.setKept(key, blocked)
                            BlockedGates.setEnabled(key, false)
                        } else {
                            refused = true
                            BlockedGates.setEnabled(key, true)
                        }
                    }
                }
                // The watch starts and stops with the gates, so it is told to look again: turning
                // the last one off has nothing left to do.
                HidingWatchService.refresh(context)
                readBlocked(feature, context)
            }
            blocked = now
            if (refused) {
                Toast.makeText(context, R.string.labs_gate_stays_on, Toast.LENGTH_SHORT).show()
            }
            version++
        }
    }

    // Bulk actions act on what is on screen, so a search or a state filter is a way to choose
    // a subset. Pinned apps are deliberately left out: a pin is the answer to "do not change
    // this one when I change everything else".
    fun beginBulk(block: Boolean) {
        val packages = shown.map { slotKey(it) }.filterNot { it in pinned }
        if (packages.isEmpty()) return
        bulkMenu = false
        bulkToggle = BulkToggleState.Confirm(
            BulkToggleRequest(
                block = block,
                packages = packages,
                pinnedSkipped = shown.count { it.packageName in pinned }
            )
        )
    }

    fun applyBulk(request: BulkToggleRequest) {
        bulkToggle = BulkToggleState.Running(request, 0, 0)
        scope.launch {
            var failed = 0
            // A list whose gate is off is being kept rather than applied. The whole kept set can
            // be written once, so a bulk edit there is as cheap as one row's switch.
            if (feature.signal == null && !BlockedGates.isEnabled(feature.name)) {
                val kept = BlockedGates.kept(feature.name)
                BlockedGates.setKept(
                    feature.name,
                    if (request.block) kept + request.packages else kept - request.packages
                )
                bulkToggle = BulkToggleState.Running(request, request.packages.size, 0)
            } else {
                request.packages.forEachIndexed { index, packageName ->
                    val applied = withContext(Dispatchers.IO) {
                        runCatching { writeBlocked(packageName, request.block) }
                            .getOrDefault(false)
                    }
                    if (!applied) {
                        failed++
                        Log.w(AppConstants.TAG, "${feature.name}: bulk $packageName would not change")
                    }
                    bulkToggle = BulkToggleState.Running(request, index + 1, failed)
                }
                if (feature.signal != null) {
                    withContext(Dispatchers.IO) { HidingWatchService.refresh(context) }
                }
            }
            blocked = withContext(Dispatchers.IO) { readBlocked(feature, context) }
            bulkToggle = BulkToggleState.Finished(request, failed)
        }
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
            },
            actions = {
                // In the header, where the whole screen is about it and one look at the title says
                // which mode is being switched: on a card of its own it took the top of the list
                // and said nothing the switch does not say itself.
                // Every list here has a gate: the hiding ones had it already, and the two that are
                // about an app op have one now, which keeps the list itself.
                ExpressiveSwitch(
                    checked = gateOn,
                    onCheckedChange = { checked -> setGate(checked) },
                    // Inset the way the title is, rather than hard against the edge: the bar has no
                    // padding of its own, so a switch put in it lands on the glass.
                    modifier = Modifier.padding(start = 4.dp)
                )
                Box(modifier = Modifier.padding(end = 8.dp)) {
                    IconButton(
                        onClick = { bulkMenu = true },
                        enabled = shown.isNotEmpty() || pinned.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.labs_bulk_actions)
                        )
                    }
                    DropdownMenu(
                        expanded = bulkMenu,
                        onDismissRequest = { bulkMenu = false }
                    ) {
                        val canBulk = gateOn && !carriesSession &&
                            (!feature.needsShell || running) &&
                            shown.any { it.packageName !in pinned }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (feature.signal == null) R.string.labs_bulk_allow_all
                                        else R.string.labs_bulk_show_all
                                    )
                                )
                            },
                            onClick = { beginBulk(false) },
                            enabled = canBulk
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (feature.signal == null) R.string.labs_bulk_block_all
                                        else R.string.labs_bulk_hide_all
                                    )
                                )
                            },
                            onClick = { beginBulk(true) },
                            enabled = canBulk
                        )
                        if (pinned.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.labs_clear_pins)) },
                                onClick = {
                                    PinnedApps.clear()
                                    pinned = emptySet()
                                    bulkMenu = false
                                }
                            )
                        }
                    }
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
        // The switch itself is in the header, so the body of the screen is the list and nothing
        // else: what used to be here was a card saying "Enabled" or "Disabled" above it, which is
        // the one thing a switch already says.

        // A mode that is switched off keeps its list, dimmed. Letting go of it was the wrong thing
        // to lose: it is what the gate exists to preserve, and the rows stay usable, because taking
        // an app off a list is one of the reasons to come back to one that is switched off.
        val contentAlpha = if (gateOn) 1f else GateOffAlpha

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

        // Every chip gets an equal slot and shrinks its label to fit. A wide font changes
        // the text size, not the number of rows.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
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
                    modifier = Modifier.weight(1f),
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
                    enabled = gateOn,
                    onClick = { kind = option }
                )
            }
        }

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
                label = stringResource(feature.offLabelRes),
                count = apps.count { slotKey(it) !in blocked },
                selected = filter == ToggleFilter.ALLOWED,
                enabled = gateOn,
                // Tapping the chosen one again lets go of it: with no filter the list shows
                // both, which is what opening the screen should do.
                onClick = {
                    filter = if (filter == ToggleFilter.ALLOWED) ToggleFilter.ALL else ToggleFilter.ALLOWED
                }
            )
            AppFilterChip(
                modifier = Modifier.weight(1f),
                label = stringResource(feature.onLabelRes),
                count = apps.count { slotKey(it) in blocked },
                selected = filter == ToggleFilter.BLOCKED,
                enabled = gateOn,
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
                .alpha(contentAlpha)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .televisionTextInput(MaterialTheme.shapes.extraLarge),
            // A search is part of working through the list, and the list is shut while the gate is
            // off: what is shown then is what is being kept, not something to sieve.
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

                if (pinned.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.labs_pinned_note),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .padding(bottom = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(shown, key = { it.rowKey() }) { pi ->
                    val slot = slotKey(pi)
                    val isBlocked = slot in blocked
                    SegmentedCard {
                        ListItem(
                            leadingContent = { AppIcon(pi) },
                            headlineContent = { Text(appLabel(pm, pi)) },
                            supportingContent = {
                                AppRowLine(pi.packageLine()) {
                                    // Hiding an app while it is starting is the only way that works
                                    // for a check made during startup, so the list can open an app
                                    // the way the watch cannot: hide first, then launch. Offered only
                                    // where hiding it is something this app can actually do.
                                    val openWithHiding = feature.signal?.takeIf {
                                        isBlocked && gateOn && !carriesSession &&
                                            (!feature.needsShell || running)
                                    }
                                    if (openWithHiding != null) {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        Hiding.openWithHiding(
                                                            openWithHiding,
                                                            pi.packageName
                                                        )
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.OpenInNew,
                                                contentDescription = stringResource(
                                                    R.string.hiding_open_with_hiding
                                                )
                                            )
                                        }
                                    }

                                    // The chips the other app lists carry, so an app says the
                                    // same things about itself wherever it is listed.
                                    AppStatusChips(
                                        pi,
                                        hidden = FirewallKey.userIdOf(slot) ==
                                            UserHandleCompat.myUserId() &&
                                            pi.packageName in launcherless
                                    )
                                    if (feature == AppToggleFeature.FIREWALL &&
                                        PackageTools.supportsMobileOnly()
                                    ) {
                                        val isMobileOnly = slot in mobileOnly
                                        IconButton(
                                            enabled = gateOn && (!feature.needsShell || running),
                                            onClick = {
                                                scope.launch {
                                                    val changed = withContext(Dispatchers.IO) {
                                                        PackageTools.setFirewallMobileOnly(
                                                            context,
                                                            slot,
                                                            !isMobileOnly
                                                        )
                                                    }
                                                    if (!changed) {
                                                        Toast.makeText(
                                                            context,
                                                            R.string.firewall_mobile_only_failed,
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                    mobileOnly = withContext(Dispatchers.IO) {
                                                        PackageTools.readFirewallMobileOnly(context)
                                                    }
                                                    blocked = withContext(Dispatchers.IO) {
                                                        readBlocked(feature, context)
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.SignalCellularAlt,
                                                contentDescription = stringResource(
                                                    if (isMobileOnly) {
                                                        R.string.firewall_mobile_only_on
                                                    } else {
                                                        R.string.firewall_mobile_only_off
                                                    }
                                                ),
                                                tint = if (isMobileOnly) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val nowPinned = PinnedApps.toggle(pi.packageName)
                                            pinned = if (nowPinned) {
                                                pinned + pi.packageName
                                            } else {
                                                pinned - pi.packageName
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.PushPin,
                                            contentDescription = stringResource(
                                                if (pi.packageName in pinned) R.string.labs_unpin_app
                                                else R.string.labs_pin_app
                                            ),
                                            tint = if (pi.packageName in pinned) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                    ExpressiveSwitch(
                                        checked = isBlocked,
                                        // The gate off means the list is not being applied, and a
                                        // switch that changes nothing is a switch to stop offering:
                                        // it is dimmed with the rest of the list.
                                        enabled = (!feature.needsShell || running) &&
                                            !carriesSession && gateOn,
                                        onCheckedChange = { checked ->
                                            blocked = if (checked) blocked + slot else blocked - slot
                                            toggle(slot, checked)
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

            ScrollToTopButton(
                listState = listState,
                bottomPadding = bottomPadding,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }

    when (val state = bulkToggle) {
        is BulkToggleState.Confirm -> BulkConfirmDialog(
            feature = feature,
            request = state.request,
            onDismiss = { bulkToggle = null },
            onConfirm = { applyBulk(state.request) }
        )

        is BulkToggleState.Running -> BulkProgressDialog(feature, state)
        is BulkToggleState.Finished -> BulkResultDialog(
            feature = feature,
            state = state,
            onDismiss = { bulkToggle = null }
        )

        null -> Unit
    }
}

@Composable
private fun BulkConfirmDialog(
    feature: AppToggleFeature,
    request: BulkToggleRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.labs_bulk_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.labs_bulk_confirm_count, request.packages.size))
                Text(
                    stringResource(
                        R.string.labs_bulk_confirm_state,
                        stringResource(if (request.block) feature.onLabelRes else feature.offLabelRes)
                    )
                )
                if (request.pinnedSkipped > 0) {
                    Text(
                        stringResource(
                            R.string.labs_bulk_confirm_pinned,
                            request.pinnedSkipped
                        )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.labs_bulk_confirm_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            PillButton(onClick = onConfirm) {
                Text(stringResource(R.string.labs_bulk_apply))
            }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun BulkProgressDialog(feature: AppToggleFeature, state: BulkToggleState.Running) {
    val total = state.request.packages.size
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.labs_bulk_progress_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.labs_bulk_confirm_state,
                        stringResource(
                            if (state.request.block) feature.onLabelRes else feature.offLabelRes
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = {
                        if (total == 0) 0f else state.completed.toFloat() / total
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.labs_bulk_progress, state.completed, total))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun BulkResultDialog(
    feature: AppToggleFeature,
    state: BulkToggleState.Finished,
    onDismiss: () -> Unit
) {
    val changed = state.request.packages.size - state.failed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.labs_bulk_result_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.labs_bulk_result_summary, changed, state.failed),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.labs_bulk_confirm_state,
                        stringResource(
                            if (state.request.block) feature.onLabelRes else feature.offLabelRes
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.request.pinnedSkipped > 0) {
                    Text(
                        stringResource(
                            R.string.labs_bulk_confirm_pinned,
                            state.request.pinnedSkipped
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.failed > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.labs_bulk_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            PillButton(onClick = onDismiss) {
                Text(stringResource(R.string.labs_bulk_done))
            }
        }
    )
}

/** The state this feature is about: which apps are blocked, according to its own source. */
private suspend fun readBlocked(
    feature: AppToggleFeature,
    context: android.content.Context
): Set<String> = when {
    // This app's own record, one list per setting: the platform can be asked about a setting
    // but not about which apps asked for it to be hidden.
    feature.signal != null -> Hiding.appsFor(feature.signal)

    // A gate that is off is keeping a list rather than applying it, so what the rows show is what
    // will be put back when it is switched on again.
    !BlockedGates.isEnabled(feature.name) -> BlockedGates.kept(feature.name)

    // One command for every app in the mode.
    feature == AppToggleFeature.AUTOSTART -> PackageTools.readOpDeniedInstalled(feature.op)

    // This app's own record; the platform cannot be asked for the list.
    else -> PackageTools.readFirewallBlocked(context)
}
