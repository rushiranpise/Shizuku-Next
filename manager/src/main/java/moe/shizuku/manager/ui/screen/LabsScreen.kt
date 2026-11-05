package moe.shizuku.manager.ui.screen

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.accessibility.AccessibilityKeepAlive
import moe.shizuku.manager.manage.ForceDark
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.manage.PackageTools
import moe.shizuku.manager.ui.Detail
import moe.shizuku.manager.ui.component.FeaturePlateIcon
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.ui.televisionReturnFocus

/** The dot that says a feature named on a row is doing something. */
private val ActiveDotSize = 10.dp

/**
 * Deliberately not a theme role.
 *
 * The palette is seeded from the wallpaper, so a role here would come out teal on a teal phone,
 * amber on an amber one - and the one thing this dot has to do is mean the same thing on every
 * device. The home screen's Restart button is a fixed blue for the same reason.
 */
private val ActiveGreen = Color(0xFF34C759)

/**
 * The things that are gone to rather than lived in.
 *
 * Every one of these was a tab of its own before, which is most of the bar's width spent on
 * screens that are largely empty when you land on them: you open the app-ops list to look one
 * app up, and you open the shell to run something and leave. A tab is for a place the app keeps
 * you in, so this is a list of the others instead, and the next one to earn a place here costs a
 * line rather than a fifth of the bar.
 *
 * It used to be a grid of tiles. A grid has to be told how many columns it is, and a count that
 * is not a multiple of that number leaves a partly empty last row - three columns and thirteen
 * features did exactly that. A list has no such arithmetic: it takes any number of rows, a long
 * name like "Hide wireless debugging" reads on one line, and it is the shape every other list in
 * the app already has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabsScreen(bottomPadding: Dp, onOpenDetail: (Detail) -> Unit) {
    val context = LocalContext.current

    // What each feature is doing, so the list can be read without opening eight screens to find
    // out. It is the only place every one of them is in sight at once, and a hiding mode that is
    // on with apps listed looks exactly like one that is off from here.
    //
    // Re-read whenever the tab is come back to, which is when a change made in one of the lists
    // is about to be looked at.
    var refresh by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<Set<Detail>>(emptySet()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) {
        active = withContext(Dispatchers.IO) { activeFeatures(context) }
    }
    // Every feature there is, in no particular order. The order is worked out below.
    val entries = listOf(
        LabEntry(R.string.tab_manage, Icons.Outlined.AdminPanelSettings, Detail.APP_OPS),
        LabEntry(R.string.tab_shell, Icons.Outlined.Terminal, Detail.SHELL),
        LabEntry(
            AppToggleFeature.FIREWALL.titleRes,
            AppToggleFeature.FIREWALL.icon,
            Detail.FIREWALL
        ),
        LabEntry(
            AppToggleFeature.AUTOSTART.titleRes,
            AppToggleFeature.AUTOSTART.icon,
            Detail.AUTOSTART
        ),
        // The six hiding modes are one row rather than six: they are the same list with a
        // different setting behind it, and six rows of the same shape was most of the list.
        LabEntry(R.string.tab_hiding, Icons.Outlined.VisibilityOff, Detail.HIDING),
        LabEntry(R.string.tab_log, Icons.AutoMirrored.Outlined.ReceiptLong, Detail.LOG),
        LabEntry(R.string.tab_device_info, Icons.Outlined.PhoneAndroid, Detail.DEVICE_INFO),
        LabEntry(R.string.tab_force_dark, Icons.Outlined.DarkMode, Detail.FORCE_DARK),
        LabEntry(R.string.tab_accessibility, Icons.Outlined.AccessibilityNew, Detail.ACCESSIBILITY),
        LabEntry(R.string.tab_activities, Icons.Outlined.Widgets, Detail.ACTIVITIES),
        LabEntry(R.string.tab_power, Icons.Outlined.PowerSettingsNew, Detail.POWER),
        LabEntry(R.string.tab_display, Icons.Outlined.AspectRatio, Detail.DISPLAY),
        LabEntry(R.string.tab_wifi_passwords, Icons.Outlined.Wifi, Detail.WIFI_PASSWORDS),
        LabEntry(R.string.tab_files, Icons.Outlined.FolderOpen, Detail.FILES),
        LabEntry(R.string.tab_install, Icons.Outlined.Android, Detail.INSTALL),
        LabEntry(R.string.lab_more_apps, Icons.Outlined.Extension, Detail.MORE_APPS)
    )

    // By name, not by the order these features arrived in. A list is read by looking something up:
    // the order they were built in put the six hiding lists after the two that need a running
    // server, so finding one meant reading the whole list. Sorted here rather than written out in
    // order above, so a row added later cannot land in the wrong place - it goes wherever its name
    // goes.
    val rows = entries
        .map { entry -> entry to stringResource(entry.labelRes) }
        .sortedBy { (_, label) -> label.lowercase() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_labs)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
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
                    top = 16.dp,
                    end = 16.dp,
                    bottom = bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
            items(rows, key = { (entry, _) -> entry.detail.name }) { (entry, label) ->
                SegmentedCard {
                    SegmentedListItem(
                        // The item that opened a detail takes the focus back when it closes, on a
                        // TV; off one this is nothing.
                        modifier = Modifier.televisionReturnFocus(entry.detail),
                        centerSlots = true,
                        // The icon keeps the tinted plate it had on the tiles. It is what makes
                        // the list scannable the way a launcher's icons are: at this size the
                        // outline of the icon alone is not enough to tell one row from another at
                        // a glance, and the colour is what the eye lands on first.
                        leadingContent = {
                            Box {
                                FeaturePlateIcon(entry.icon)

                                // On the plate's corner, the same mark the tiles carried: a mode
                                // that is on with apps listed looks exactly like one that is off
                                // from here, and this is the one thing about a row that changes on
                                // its own.
                                if (entry.detail in active) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 2.dp, y = 2.dp)
                                            .size(ActiveDotSize)
                                            .background(ActiveGreen, CircleShape)
                                    )
                                }
                            }
                        },
                        headlineContent = { Text(label) },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null
                            )
                        },
                        onClick = { onOpenDetail(entry.detail) }
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

/**
 * The Lab features that are doing something right now.
 *
 * A list counts only when it is switched on *and* has apps on it. The switch arms a hiding mode
 * and the list is what it acts on, so either without the other is a setting rather than a state -
 * and a dot that lit up for an empty list would say a phone was hiding something it was not.
 *
 * Every answer here but one is this app's own record, which is a preference read. Autostart is the
 * exception: its list belongs to the platform, and the platform is asked one command for all of
 * it. That, and the size of the hiding lists, is why this is worked out off the main thread once
 * when the tab is shown rather than inside each row.
 *
 * Two features are left out on purpose, and not because they were forgotten. App ops is a browser
 * rather than a mode: nothing about it is on or off, and the one change it can make that is
 * recorded at all - blocking an app's network - writes the firewall's list, which already has a
 * dot of its own. Activities, device info, the log, the shell and More Apps are places to look
 * rather than things that run, so there is no state of theirs to report either.
 */
private fun activeFeatures(context: Context): Set<Detail> = buildSet {
    AppToggleFeature.entries.forEach { feature ->
        // Every hiding signal answers for the grouped row rather than for a detail of its own:
        // whichever of the six is doing something lights the one dot, and the set is what keeps
        // six answers from becoming six entries.
        val detail = if (feature.signal != null) {
            Detail.HIDING
        } else {
            Detail.of(feature) ?: return@forEach
        }

        val count = when {
            // The mode has to be on for the list to be doing anything, which is the whole
            // difference between the two hiding lists that look alike from here.
            feature.signal != null ->
                if (Hiding.isSignalEnabled(feature.signal)) Hiding.appsFor(feature.signal).size
                else 0

            feature == AppToggleFeature.AUTOSTART ->
                PackageTools.readOpDeniedInstalled(feature.op).size
            else -> PackageTools.readFirewallBlocked(context).size
        }

        if (count > 0) add(detail)
    }

    // The other switch in the list, and the same question asked of it: on, and with apps to act
    // on.
    if (ForceDark.hasWorkToDo()) add(Detail.FORCE_DARK)

    // The accessibility manager is doing something when it is armed and at least one service is
    // locked to keep on; either one without the other is a setting, not a state.
    if (AccessibilityKeepAlive.isEnabled(context) &&
        AccessibilityKeepAlive.pinned(context).isNotEmpty()
    ) {
        add(Detail.ACCESSIBILITY)
    }
}

/** One row of the list: what it is called, what is on it, and where it goes. */
private data class LabEntry(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val detail: Detail
)
