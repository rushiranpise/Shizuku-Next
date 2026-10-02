package moe.shizuku.manager.ui.screen

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.ForceDark
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.manage.PackageTools
import moe.shizuku.manager.ui.Detail
import moe.shizuku.manager.utils.MoreApps
import moe.shizuku.manager.ui.theme.LocalAmoledTheme

/** Padding above, below and beside the tile's contents. */
private val TilePadding = 10.dp

/**
 * How much of the width the icon plate takes.
 *
 * A fraction rather than a size, because the plate was 40dp at every screen width and looked
 * small wherever the tile was wider than a phone's third of a screen. The plate and the icon
 * inside it scale with the tile, and so does the height below, which is what keeps a three-across
 * grid looking the same on a tablet as on a phone.
 */
private val PlateFraction = 0.50f

/**
 * How much of the plate the icon itself fills.
 *
 * Two thirds, which is what a launcher icon in a tinted plate is: the plate is the colour the eye
 * lands on and the glyph is what it reads, so a glyph at a bit over half the plate reads as a
 * small mark floating in a square rather than as an icon.
 */
private val IconFraction = 0.66f

/** The space between the plate and the name. */
private val PlateGap = 8.dp

/** The dot that says a tile is doing something. */
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
 * The room reserved for the name: two lines, which is what every name in the list needs at three
 * across.
 *
 * Reserved, not imposed. The tile is always this much taller than its plate, so every tile in the
 * grid is the same height - but the label inside is only as tall as its own text and the whole
 * plate-and-name block is centred in what is left. A fixed box of this height was the first
 * attempt and it was wrong in a way that showed: a one-line name centred its text inside two
 * lines' worth of box, which pushed the block up and left the slack at the bottom of the tile.
 *
 * A name that outgrows the two lines is a name to shorten: it ellipsizes, which is visible,
 * where letting it run on would move the plate.
 */
private val LabelHeight = 40.dp

/**
 * The things that are gone to rather than lived in.
 *
 * Every one of these was a tab of its own before, which is most of the bar's width spent on
 * screens that are largely empty when you land on them: you open the app-ops list to look one
 * app up, and you open the shell to run something and leave. A tab is for a place the app keeps
 * you in, so this is a grid of the others instead, and the next one to earn a place here costs a
 * line rather than a fifth of the bar.
 *
 * A grid and a name, nothing more: these are features, not decisions, so each one is its icon
 * and what it is called. A name that needs a sentence under it to be understood is a name to
 * change, and the grid is what makes room for many of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabsScreen(bottomPadding: Dp, onOpenDetail: (Detail) -> Unit) {
    val context = LocalContext.current

    // What each tile is doing, so the grid can be read without opening eight screens to find
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
    // Every tile there is, in no particular order. The order is worked out below.
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
        LabEntry(
            AppToggleFeature.HIDE_DEVELOPER_OPTIONS.titleRes,
            AppToggleFeature.HIDE_DEVELOPER_OPTIONS.icon,
            Detail.HIDE_DEVELOPER_OPTIONS
        ),
        LabEntry(
            AppToggleFeature.HIDE_USB_DEBUGGING.titleRes,
            AppToggleFeature.HIDE_USB_DEBUGGING.icon,
            Detail.HIDE_USB_DEBUGGING
        ),
        LabEntry(
            AppToggleFeature.HIDE_WIRELESS_DEBUGGING.titleRes,
            AppToggleFeature.HIDE_WIRELESS_DEBUGGING.icon,
            Detail.HIDE_WIRELESS_DEBUGGING
        ),
        LabEntry(
            AppToggleFeature.HIDE_ACCESSIBILITY.titleRes,
            AppToggleFeature.HIDE_ACCESSIBILITY.icon,
            Detail.HIDE_ACCESSIBILITY
        ),
        LabEntry(
            AppToggleFeature.HIDE_PRIVATE_DNS.titleRes,
            AppToggleFeature.HIDE_PRIVATE_DNS.icon,
            Detail.HIDE_PRIVATE_DNS
        ),
        LabEntry(
            AppToggleFeature.HIDE_VPN.titleRes,
            AppToggleFeature.HIDE_VPN.icon,
            Detail.HIDE_VPN
        ),
        LabEntry(R.string.tab_log, Icons.Outlined.ReceiptLong, Detail.LOG),
        LabEntry(R.string.tab_device_info, Icons.Outlined.PhoneAndroid, Detail.DEVICE_INFO),
        LabEntry(R.string.tab_force_dark, Icons.Outlined.DarkMode, Detail.FORCE_DARK),
        LabEntry(R.string.tab_activities, Icons.Outlined.Widgets, Detail.ACTIVITIES),
        LabEntry(R.string.lab_more_apps, Icons.Outlined.Extension, Detail.MORE_APPS)
    )

    // By name, not by the order these features arrived in. The grid is a list, and a list is read
    // by looking something up: the order they were built in put the six hiding lists after the two
    // that need a running server, so finding one meant reading the whole grid. Sorted here rather
    // than written out in order above, so a tile added later cannot land in the wrong place - it
    // goes wherever its name goes.
    val tiles = entries
        .map { entry -> entry to stringResource(entry.labelRes) }
        .sortedBy { (_, label) -> label.lowercase() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_labs)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        )

        LazyVerticalGrid(
            // Three to a row, asked for rather than derived from a width: at two the grid spent
            // a lot of screen on ten short names, and a count says what the layout is instead of
            // leaving it to whatever a device's width happens to produce.
            //
            // The cost is real and is paid in the tiles: at three across a phone a tile is about
            // 120dp, so a name like "Hide wireless debugging" has nowhere to sit on one line and
            // has to stack. The tile below is built for that.
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = bottomPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            tiles.forEach { (entry, label) ->
                item(key = entry.detail.name) {
                    LabTile(
                        icon = entry.icon,
                        label = label,
                        active = entry.detail in active,
                        onClick = {
                            // The module index is somebody else's website, so it goes to the
                            // browser when there is one, and the screen inside the app is only
                            // for the device that has no browser to open it in.
                            if (entry.detail != Detail.MORE_APPS || !MoreApps.openInBrowser(context)) {
                                onOpenDetail(entry.detail)
                            }
                        }
                    )
                }
            }
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
 * when the tab is shown rather than inside each tile.
 *
 * Two tiles are left out on purpose, and not because they were forgotten. App ops is a browser
 * rather than a mode: nothing about it is on or off, and the one change it can make that is
 * recorded at all - blocking an app's network - writes the firewall's list, which already has a
 * dot of its own. Activities, device info, the log, the shell and More Apps are places to look
 * rather than things that run, so there is no state of theirs to report either.
 */
private fun activeFeatures(context: Context): Set<Detail> {
    val byFeature = Detail.entries
        .filter { it.feature != null }
        .associateBy { it.feature!! }

    return buildSet {
        AppToggleFeature.entries.forEach { feature ->
            val detail = byFeature[feature] ?: return@forEach

            val count = when {
                // The mode has to be on for the list to be doing anything, which is the whole
                // difference between the two hiding lists that look alike from the grid.
                feature.signal != null ->
                    if (Hiding.isSignalEnabled(feature.signal)) Hiding.appsFor(feature.signal).size
                    else 0

                feature == AppToggleFeature.AUTOSTART -> PackageTools.readOpDenied(feature.op).size
                else -> PackageTools.readFirewallBlocked(context).size
            }

            if (count > 0) add(detail)
        }

        // The other switch in the grid, and the same question asked of it: on, and with apps to
        // act on.
        if (ForceDark.hasWorkToDo()) add(Detail.FORCE_DARK)
    }
}

/** One tile of the grid: what it is called, what is on it, and where it goes. */
private data class LabEntry(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val detail: Detail
)

/**
 * One feature: its icon on a tinted plate, its name under it, and nothing else.
 *
 * The plate is what makes the grid scannable, the way a launcher's icons do: at this size the
 * outline of the icon alone is not enough to tell one tile from another at a glance, and the
 * colour is what the eye lands on first.
 */
@Composable
private fun LabTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    BoxWithConstraints {
        // Every part is a fraction of the width the tile was given, and the height is what those
        // parts add up to. Nothing here is a fixed size, which is the point of it: the same tile
        // is a third of a phone and a third of a tablet, and a plate fixed at 40dp looked small
        // on whichever of the two was wider.
        val plate = (maxWidth - TilePadding * 2) * PlateFraction

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // The sum of the parts, so every tile in the grid is the same height whatever its
                // name runs to - a grid whose rows differ in height does not read as a grid.
                .height(TilePadding * 2 + plate + PlateGap + LabelHeight)
                .clickable(onClick = onClick)
                .then(
                    if (LocalAmoledTheme.current) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = MaterialTheme.shapes.large
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = TilePadding, vertical = TilePadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(PlateFraction)
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.fillMaxSize(IconFraction)
                            )
                        }
                    }

                    // On the plate's bottom corner, the way an unread count sits on an icon: the
                    // grid is read by shape and colour at a glance, and this is the one thing
                    // about a tile that changes on its own. Below rather than above, because the
                    // status bar the grid scrolls under is up there and a mark at the top of a
                    // tile reads as part of whatever is on its way past.
                    if (active) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 2.dp, y = 2.dp)
                                .size(ActiveDotSize)
                                .background(ActiveGreen, CircleShape)
                        )
                    }
                }

                Text(
                    text = label,
                    modifier = Modifier
                        .padding(top = PlateGap)
                        .fillMaxWidth(),
                    // A step down from the size this was, which is what keeps a long word whole: at
                    // three across the name has to stack word by word, and every point of size the
                    // text does not take is a word that does not have to be broken.
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
