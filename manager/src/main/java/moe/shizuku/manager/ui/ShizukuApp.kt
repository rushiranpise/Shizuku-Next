package moe.shizuku.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.component.TelevisionFocusOutline
import moe.shizuku.manager.ui.component.televisionFocusShape
import moe.shizuku.manager.ui.screen.AppsScreen
import moe.shizuku.manager.ui.screen.ActivitiesScreen
import moe.shizuku.manager.ui.screen.FilesScreen
import moe.shizuku.manager.ui.screen.ForceDarkScreen
import moe.shizuku.manager.ui.screen.HidingScreen
import moe.shizuku.manager.ui.screen.HomeScreen
import moe.shizuku.manager.ui.screen.AppToggleFeature
import moe.shizuku.manager.ui.screen.InstallerScreen
import moe.shizuku.manager.ui.screen.PowerScreen
import moe.shizuku.manager.ui.screen.IntentsScreen
import moe.shizuku.manager.ui.screen.DeviceInfoScreen
import moe.shizuku.manager.ui.screen.DisplayScreen
import moe.shizuku.manager.ui.screen.LabsScreen
import moe.shizuku.manager.ui.screen.LogScreen
import moe.shizuku.manager.ui.screen.LabsToggleScreen
import moe.shizuku.manager.ui.screen.ManageScreen
import moe.shizuku.manager.ui.screen.CatalogKind
import moe.shizuku.manager.ui.screen.CatalogScreen
import moe.shizuku.manager.ui.screen.PermissionsScreen
import moe.shizuku.manager.ui.screen.SettingsScreen
import moe.shizuku.manager.ui.screen.ShellScreen
import moe.shizuku.manager.ui.screen.ModulesScreen
import moe.shizuku.manager.ui.screen.StealthScreen
import moe.shizuku.manager.ui.screen.TerminalScreen
import moe.shizuku.manager.ui.screen.WifiPasswordsScreen
import moe.shizuku.manager.ui.theme.LocalAmoledTheme
import moe.shizuku.manager.ui.theme.LocalTelevision
import moe.shizuku.manager.ui.theme.ShizukuTheme

/**
 * A secondary screen shown on top of the tab pager.
 *
 * The first two are what the Labs tab opens, and they are the reason it exists: an app's ops
 * and a shell are things you go to, not places you live, and giving each of them a tab meant
 * the bar spent its whole width on five icons while the two screens behind two of them were
 * mostly empty when you arrived.
 */
enum class Detail(
    /** The list this screen opens, for the six that are one list with one switch per app. */
    val feature: AppToggleFeature? = null
) {
    APP_OPS,
    SHELL,
    FIREWALL(AppToggleFeature.FIREWALL),
    AUTOSTART(AppToggleFeature.AUTOSTART),

    /**
     * The six hiding modes, as one tile. Each mode still has its own detail below, which is what
     * the list screens are addressed by, but the grid offers this one and the modes are reached
     * through the screen it opens.
     */
    HIDING,
    HIDE_DEVELOPER_OPTIONS(AppToggleFeature.HIDE_DEVELOPER_OPTIONS),
    HIDE_USB_DEBUGGING(AppToggleFeature.HIDE_USB_DEBUGGING),
    HIDE_WIRELESS_DEBUGGING(AppToggleFeature.HIDE_WIRELESS_DEBUGGING),
    HIDE_ACCESSIBILITY(AppToggleFeature.HIDE_ACCESSIBILITY),
    HIDE_PRIVATE_DNS(AppToggleFeature.HIDE_PRIVATE_DNS),
    HIDE_VPN(AppToggleFeature.HIDE_VPN),
    LOG,
    DEVICE_INFO,
    FORCE_DARK,
    ACTIVITIES,

    /** Reboot, recovery, bootloader, power off and the sleep key, through the shell. */
    POWER,

    /** Resolution, density and refresh rate, as the shell changes them. */
    DISPLAY,

    /** The saved wifi networks and their keys, read as the shell. */
    WIFI_PASSWORDS,

    /** The filesystem, as the shell sees it: Android/data and what an app cannot open itself. */
    FILES,

    /** Installing a package with the flags a terminal would need a cable for. */
    INSTALL,
    STEALTH,
    TERMINAL,
    INTENTS,
    PERMISSIONS,

    /** The community's module index, shown here only when there was no browser to open it in. */
    MORE_APPS;

    companion object {
        /** The detail that opens [feature]'s list. Every feature has exactly one. */
        fun of(feature: AppToggleFeature): Detail? = entries.firstOrNull { it.feature == feature }
    }
}

/**
 * On wide windows (tablets, foldables, desktop mode, mirrored displays) a
 * single-column layout stretched edge to edge looks sparse, so the content is
 * capped at this width and centred. On a phone the window is narrower than the
 * cap, so this has no effect.
 */
private val MaxContentWidth = 600.dp



private data class Tab(
    val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

/**
 * The key the tab pages' saveable state is filed under, so it survives while a detail screen is
 * the one on screen. One key rather than one per tab: the pager keeps every page it has already
 * visited, so the pages are all inside this one provider and none of them needs its own.
 */
private const val TAB_PAGES = "tab-pages"

/**
 * The tab bar's pill, and where the tabs it travels between sit on the bar.
 *
 * Both live above the switch that shows a detail screen, because the bar leaves the composition
 * while a detail is open. Measured again from nothing on the way back, the pill began its travel
 * at the bar's own zero - the left end, which is Home - so coming back from Intents the pill walked
 * the width of the bar to Settings before coming to rest on the tab that had never stopped being
 * the selected one. It is the same shape of bug as the pager's, one level down: state the way back
 * needs was remembered by the thing that goes away.
 *
 * The pill is an [Animatable] rather than an `animateFloatAsState` so that the first place it is
 * given can be given to it rather than travelled to. Only a tab actually chosen is a move.
 */
private class TabPill {
    val bounds = mutableStateMapOf<Int, Rect>()
    val left = Animatable(0f)
    val width = Animatable(0f)

    /** False until the pill has been placed once, which is the one move there is nothing to make. */
    var placed = false
}

private val tabs = listOf(
    Tab(R.string.tab_home, Icons.Filled.Home, Icons.Outlined.Home),
    // The two icons their own app uses for these two screens: the panel for what an app is
    // allowed to do, the stacked layers for the modules that add to it.
    Tab(R.string.tab_apps, Icons.Filled.AdminPanelSettings, Icons.Outlined.AdminPanelSettings),
    Tab(R.string.tab_modules, Icons.Filled.Layers, Icons.Outlined.Layers),
    // Labs holds the things that are gone to rather than lived in: the app-ops list and the
    // shell to begin with, and whatever else turns out to belong there. Each was a tab of its
    // own before, which is a lot of the bar for two screens that are mostly a list you read
    // once, and it also made the shell's own session the price of switching to Settings.
    Tab(R.string.tab_labs, Icons.Filled.Science, Icons.Outlined.Science),
    Tab(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun ShizukuApp() {
    ShizukuTheme {
        // Detail screens are shown outside the Scaffold, so wrap everything in a
        // Surface otherwise LocalContentColor falls back to black and plain
        // Text becomes unreadable in dark themes.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            TelevisionFocusOutline {
            // Apply the status bar inset exactly once for every screen: the app
            // bars themselves have no insets, and the Scaffold opts out too.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            ) {
                // Both the open detail and the pager live here, above the switch between
                // the two, for two reasons: the pager used to be remembered inside the tab
                // layout, so opening a detail threw it away and closing the detail started
                // again on the first tab (back from a Settings screen landed on Home). And
                // neither was saveable, so a rotation dropped both and did the same thing.
                var detail by rememberSaveable { mutableStateOf<Detail?>(null) }
                val pagerState = rememberPagerState(pageCount = { tabs.size })
                val pill = remember { TabPill() }
                // The pages leave the composition while a detail is open, and where a list is
                // scrolled to is saveable state - which is discarded when the composable holding
                // it goes away. So going back from Intents landed at the top of Settings rather
                // than where it was left, and the pager alone could not fix that: this holds the
                // state of the pages while they are gone, which is the same thing a navigation
                // library does, and for the same reason.
                val pages = rememberSaveableStateHolder()
                // The tab a TV is on. Kept up here beside the pager for the same reason the
                // pager is: a detail screen takes the tabs out of the composition, and the way
                // back should land on the tab it left.
                var tvTab by rememberSaveable { mutableIntStateOf(0) }
                // Whether a detail screen has been open, so the TV's tabs coming back after it
                // put the focus on the page it was opened from rather than on the rail.
                var tvDetailWasOpen by remember { mutableStateOf(false) }
                val tvReturn = remember { TelevisionReturn() }
                val current = detail

                if (current != null) {
                    SideEffect { tvDetailWasOpen = true }
                    BackHandler { detail = null }
                    CenteredContent(
                        // A detail screen has no tab bar under it, so it is the one that has
                        // to keep clear of the navigation bar itself. The tab layout adds
                        // that inset to its own bottom padding; a detail had none, which on
                        // a device with navigation buttons put the shell's input row under
                        // them.
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        )
                    ) {
                        when (current) {
                            // Both get the bar's padding of zero: a detail has no floating
                            // bar under it to keep clear of, and this wrapper already keeps
                            // them off the navigation bar.
                            Detail.APP_OPS -> ManageScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.SHELL -> ShellScreen(bottomPadding = 0.dp, onBack = { detail = null })
                            // The six hiding modes are one tile in the grid, and this is what
                            // it opens: all of them, what each covers and whether it is on.
                            Detail.HIDING -> HidingScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null },
                                onOpenDetail = { detail = it }
                            )

                            Detail.FIREWALL,
                            Detail.AUTOSTART,
                            Detail.HIDE_DEVELOPER_OPTIONS,
                            Detail.HIDE_USB_DEBUGGING,
                            Detail.HIDE_WIRELESS_DEBUGGING,
                            Detail.HIDE_ACCESSIBILITY,
                            Detail.HIDE_PRIVATE_DNS,
                            Detail.HIDE_VPN -> LabsToggleScreen(
                                // Every one of these is a list with a switch per app, so the
                                // feature comes with the screen rather than beside it.
                                feature = requireNotNull(current.feature),
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.DEVICE_INFO -> DeviceInfoScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.FORCE_DARK -> ForceDarkScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.ACTIVITIES -> ActivitiesScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.WIFI_PASSWORDS -> WifiPasswordsScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.FILES -> FilesScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.INSTALL -> InstallerScreen(
                                path = null,
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )


                            Detail.DISPLAY -> DisplayScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.POWER -> PowerScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.LOG -> LogScreen(
                                bottomPadding = 0.dp,
                                onBack = { detail = null }
                            )

                            Detail.MORE_APPS -> CatalogScreen(
                                kind = CatalogKind.APPS,
                                // The apps list is not modules, so nothing here can be
                                // already installed as one.
                                installedKeys = emptySet(),
                                onBack = { detail = null },
                                onInstalled = {}
                            )

                            Detail.STEALTH -> StealthScreen(onBack = { detail = null })
                            Detail.TERMINAL -> TerminalScreen(onBack = { detail = null })
                            Detail.INTENTS -> IntentsScreen(onBack = { detail = null })
                            Detail.PERMISSIONS -> PermissionsScreen(onBack = { detail = null })
                        }
                    }
                } else if (LocalTelevision.current) {
                    TelevisionTabs(
                        selected = tvTab,
                        onSelect = { tvTab = it },
                        pages = pages,
                        focusPageFirst = tvDetailWasOpen,
                        returnFocus = tvReturn,
                        onOpenDetail = {
                            tvReturn.detail = it
                            detail = it
                        }
                    )
                } else {
                    pages.SaveableStateProvider(TAB_PAGES) {
                        MainTabs(
                            pagerState = pagerState,
                            pill = pill,
                            onOpenDetail = { detail = it }
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun CenteredContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                // widthIn must come first: fillMaxWidth sets min == max, which
                // would defeat a later widthIn cap.
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            content()
        }
    }
}/**
 * The tabs walked through to get to [page], oldest first, as one visit more or one visit less.
 *
 * [page] is the tab the pager has settled on. Landing on the tab immediately before the last one
 * is a move back - the back button, or a swipe in that direction - so it takes the last entry with
 * it rather than adding another. A list that grew on the way back would walk between two tabs for
 * ever instead of reaching Home and then leaving the app.
 */
internal fun afterSettling(visited: List<Int>, page: Int): List<Int> = when {
    visited.size > 1 && page == visited[visited.size - 2] -> visited.dropLast(1)
    visited.lastOrNull() == page -> visited
    else -> visited + page
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainTabs(
    pagerState: PagerState,
    pill: TabPill,
    onOpenDetail: (Detail) -> Unit
) {
    val scope = rememberCoroutineScope()

    // The tabs walked through to get here, oldest first. Five tabs are five places in one app,
    // not five apps, so back walks this list rather than closing the app from wherever somebody
    // is - leaving Settings used to take the one press that leaves Home. Home is still where the
    // app leaves, which is the press Android has always answered there.
    var visited by remember { mutableStateOf(listOf(0)) }

    // Recorded from where the pager settles rather than from the bar's clicks, because a swipe and
    // a tap are the same visit.
    LaunchedEffect(pagerState.settledPage) {
        visited = afterSettling(visited, pagerState.settledPage)
    }

    // A page's own back - the Activities launcher's app list, a module's full screen - is composed
    // inside this one, so its handler is the one that answers first.
    BackHandler(enabled = pagerState.currentPage != 0 && visited.size > 1) {
        scope.launch { pagerState.animateScrollToPage(visited[visited.size - 2]) }
    }

    // Whether the app has settled enough to read the lists nobody is looking at yet. Both app
    // lists are expensive to read - six hundred packages each - and a pager composes the page
    // being dragged in, so reading on composition spent swipes on them. Reading when the tab
    // is landed on fixed the swipe and made the first visit wait instead, which is what this
    // takes back: shortly after launch, while nobody is touching anything, the two lists read
    // themselves, so the swipe is still cheap and the tab is already full when it is opened.
    var warmUp by remember { mutableStateOf(false) }

    // Whether the module screen has one of its own full screens up - a module's page, its
    // action console or the catalog. Those take the whole tab, and a horizontal swipe there
    // belongs to what is on screen rather than to the app's tabs, so the pager stops dragging
    // while one of them is up.
    var modulesFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1500)
        warmUp = true
    }

    // The number of apps Shizuku's permission is granted to, as last reported by the Apps page.
    // That page owns the read - it is the one with the list - and this is where its answer is
    // kept so the tab bar can show it without a second pass over the same packages.
    var grantedCount by remember { mutableIntStateOf(0) }

    val scrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom
    )
    // The bar's height as a constant, rather than as something it reports back. Measuring it
    // and feeding that measurement into every page as bottom padding made the pages re-lay out
    // whenever the bar re-measured mid-animation — a scroll, a tab change — which is what read
    // as the content bouncing and left a gap the size of the bar's largest frame.
    val bottomPadding = FloatingToolbarDefaults.ContainerSize +
        FloatingToolbarDefaults.ScreenOffset +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior)
    ) {
        CenteredContent {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !(pagerState.currentPage == 2 && modulesFullscreen),
                modifier = Modifier.fillMaxSize(),
                // Every page stays composed. The pager otherwise builds a page only as it is
                // dragged in, which is what the app lists were reading on; keeping them all
                // alive is what lets the warm-up below reach them before anyone opens them,
                // and it is also what keeps a tab's contents across a visit - the android
                // tab bar's own pages are cheap to hold, and only the rows on screen are
                // ever built.
                beyondViewportPageCount = tabs.size
            ) { page ->
                // Each page is told whether it is the one on screen, because the pager
                // composes the page being dragged in as well. The two that read every
                // installed package - six hundred of them, twice over in Manage's case -
                // started that the moment they were half on screen, which is what made
                // swiping towards the shell stutter: the swipe passes straight through both
                // app lists on its way there. A page that is not the settled one now waits,
                // and loads when it is landed on.
                val active = page == pagerState.settledPage
                when (page) {
                    0 -> HomeScreen(bottomPadding = bottomPadding)
                    1 -> AppsScreen(
                        bottomPadding = bottomPadding,
                        active = active,
                        warmUp = warmUp,
                        onGrantedCount = { grantedCount = it }
                    )
                    2 -> ModulesScreen(
                        bottomPadding = bottomPadding,
                        active = active,
                        onFullscreenChange = { modulesFullscreen = it }
                    )
                    3 -> LabsScreen(bottomPadding = bottomPadding, onOpenDetail = onOpenDetail)
                    4 -> SettingsScreen(bottomPadding = bottomPadding, onOpenDetail = onOpenDetail)
                }
            }
        }

        // A module's own page, its action console and the catalog take the whole tab, and the
        // bar leaves with them: a floating bar sitting over somebody else's page is this app's
        // own furniture showing through. Its back arrow - or the back gesture - brings it back.
        if (!modulesFullscreen) {
            // The bar's own band, and the fade the pages run into. It is drawn here rather than
            // by the pages for two reasons: it leaves with the bar, and it is already there
            // before a page has scrolled a long list sitting at its top still has rows under
            // the bar, which a scrim that waited for a scroll would leave with a hard edge.
            // A gradient and not a blur: blurring a scrolling page means drawing it into an
            // offscreen layer and re-blurring it every frame.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Before the insets, so the fade reaches the bottom of the screen instead of
                    // stopping at the top of the gesture area: below the bar is page the bar is
                    // floating over too. It lands on the colour the pages themselves draw, so it
                    // dissolves into them in every theme, black included.
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = FloatingToolbarDefaults.ScreenOffset),
                contentAlignment = Alignment.TopCenter
            ) {
                // The bar hides by moving down by its own height, and it stands clear of the
                // gesture area — so it came to rest exactly that gap above the bottom of the
                // screen with a strip of itself still showing. Clipping this box to its own edge
                // cuts that strip off, and leaves the library's own timing alone: the box is
                // exactly the bar's height, so at rest nothing is cut, and on the way down all of
                // it is.
                // Centre-aligned, and so is the band: a full-width box with the bar in it would
                // otherwise leave the bar sitting against its left edge.
                Box(
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    // Where each tab sits on the bar, so the selected pill can be drawn between
                    // them: one shape that travels reads as a move, where a container colour that
                    // appears on the new tab and leaves the old one reads as a flicker. The pill
                    // is measured from the tab itself, so it is the tab's own size wherever the
                    // bar's padding puts it.
                    val selectedBounds = pill.bounds[pagerState.currentPage]
                    val pillSpec = spring<Float>(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                    LaunchedEffect(selectedBounds) {
                        val bounds = selectedBounds ?: return@LaunchedEffect
                        if (pill.placed) {
                            // Both ends of the move at once, which is the one thing that would have
                            // been lost to doing this by hand: a tab that grows while it slides.
                            launch { pill.left.animateTo(bounds.left, pillSpec) }
                            launch { pill.width.animateTo(bounds.width, pillSpec) }
                        } else {
                            pill.left.snapTo(bounds.left)
                            pill.width.snapTo(bounds.width)
                            pill.placed = true
                        }
                    }
                    val pillColor = MaterialTheme.colorScheme.secondaryContainer

                    HorizontalFloatingToolbar(
                        expanded = true,
                        modifier = Modifier,
                        contentPadding = PaddingValues(0.dp),
                        scrollBehavior = scrollBehavior
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = FloatingToolbarDefaults.ContainerSize)
                                .then(
                                    if (LocalAmoledTheme.current) {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            shape = FloatingToolbarDefaults.ContainerShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(FloatingToolbarDefaults.ContentPadding)
                                // Last in the chain on purpose: the padding sits outside the Row, so
                                // drawing here happens in the same space the tabs are placed in, and
                                // their measured bounds can be used as they are.
                                .drawWithContent {
                                    val bounds = selectedBounds
                                    val left = pill.left.value
                                    val width = pill.width.value
                                    if (bounds != null && width > 0f) {
                                        drawRoundRect(
                                            color = pillColor,
                                            topLeft = Offset(left, bounds.top),
                                            size = Size(width, bounds.height),
                                            cornerRadius = CornerRadius(bounds.height / 2f)
                                        )
                                    }
                                    drawContent()
                                },
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                val selected = pagerState.currentPage == index
                                // The icon's colour is animated rather than handed to the button's
                                // checked state, because the pill behind it is still travelling when
                                // the state flips: a flip would leave the icon dark on a pill that has
                                // not arrived yet.
                                val iconColor by animateColorAsState(
                                    targetValue = if (selected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    animationSpec = MaterialTheme.motionScheme
                                        .defaultEffectsSpec<Color>(),
                                    label = "tabIconColor"
                                )
                                ToggleButton(
                                    checked = selected,
                                    modifier = Modifier.onGloballyPositioned {
                                        pill.bounds[index] = it.boundsInParent()
                                    },
                                onCheckedChange = {
                                    // Straight to the page, not through the ones between: the pager
                                    // composes what it scrolls past, so going from Settings to Home
                                    // started loading the Manage tab's six hundred apps on the way.
                                    // Swiping still animates, because that is the gesture.
                                    if (!selected) scope.launch { pagerState.scrollToPage(index) }
                                },
                                    shapes = ToggleButtonShapes(
                                        shape = CircleShape,
                                        pressedShape = CircleShape,
                                        checkedShape = CircleShape
                                    ),
                                    colors = ToggleButtonDefaults.toggleButtonColors(
                                        // Transparent either way: the pill is drawn by the bar, so the
                                        // button must not draw a second one under it.
                                        containerColor = Color.Transparent,
                                        contentColor = iconColor,
                                        checkedContainerColor = Color.Transparent,
                                        checkedContentColor = iconColor
                                    )
                                ) {
                                    // Icons only, with the name kept for anyone reading it aloud: a
                                    // label that grows out of the selected tab makes the bar wider
                                    // and taller, and the pages under it move. The filled pill says
                                    // which tab is current, and the outlined icon resolving into the
                                    // filled one says the pill has arrived.
                                    // The count of granted apps rides on the Apps tab as a badge: it
                                    // is the one number about the service that changes on its own,
                                    // and drawn over the icon rather than beside it, so the bar's
                                    // size does not depend on how large the number is.
                                    BadgedBox(
                                        badge = {
                                            if (index == 1 && grantedCount > 0) {
                                                // The accent at its low-emphasis tone rather than
                                                // the badge's own default, which is the error red:
                                                // a count of granted apps is not a problem, and
                                                // the container role is the accent's quiet one.
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme
                                                        .primaryContainer
                                                ) { Text(grantedCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Crossfade(
                                            targetState = selected,
                                            animationSpec = MaterialTheme.motionScheme
                                                .defaultEffectsSpec<Float>(),
                                            label = "tabIcon"
                                        ) { isSelected ->
                                            Icon(
                                                if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = stringResource(tab.label)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The overscan a TV may cut from every edge. Televisions are free to crop the picture, and the
 * guidance for what is always safe to draw in is about five percent of the screen: 48dp at the
 * sides and 27dp at the top and bottom of a 960 x 540dp screen.
 */
private val TelevisionSafeHorizontal = 48.dp
private val TelevisionSafeVertical = 27.dp

/**
 * The tabs on a TV: the same five pages, with the bar as a column down the left edge instead
 * of a row floating along the bottom.
 *
 * The floating bar is a thumb's bar. It hides as a page scrolls, which a remote has no gesture
 * to undo, and it can only be reached by walking the focus down past everything on the page.
 * A rail on the left is always there and always one press away: left from anywhere on a page.
 *
 * Only the selected page is composed. The phone keeps every page alive in the pager so that
 * swiping is cheap, but a page that is composed is also a page the D-pad can move focus into,
 * and on a TV it did: focus walked off the page on screen into one that was not, the pager
 * followed it, and once it was stranded there the remote appeared to do nothing at all. With
 * one page there is nowhere for focus to go but what is visible. Each page's scroll position
 * is filed under its own key in [pages], so leaving a tab and coming back still lands where it
 * was left.
 */
@Composable
private fun TelevisionTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    pages: SaveableStateHolder,
    focusPageFirst: Boolean,
    returnFocus: TelevisionReturn,
    onOpenDetail: (Detail) -> Unit
) {
    var grantedCount by remember { mutableIntStateOf(0) }
    val railItems = remember { List(tabs.size) { FocusRequester() } }
    val page = remember { FocusRequester() }

    // Back from a page goes to the rail first, the way a TV app's back does: left only gets
    // there once nothing on the page is further left, which on a wide page can be a while.
    // From the rail, back leaves the app as it always has. A page's own back - a module's full
    // screen - is composed after this one, so it still comes first.
    var pageFocused by remember { mutableStateOf(false) }

    // The tab follows the focus, so walking the rail from Home to Labs passes through Apps on
    // the way - and Apps reads every installed package the moment it is active, a binder call
    // that cannot be called back once it has started. It is let go only once the focus has
    // stayed on Apps for a moment, the same way the phone's pager waits for the swipe to settle.
    var appsActive by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        appsActive = false
        if (selected == 1) {
            delay(300)
            appsActive = true
        }
    }

    // Whether the focus was last on the page rather than the rail, and whether it is leaving
    // the page because it was sent - left, or back. A page can take away the thing that held
    // the focus: Find modules opens over the Modules list, and the button that opened it is
    // gone. The platform then gives the focus to the first thing it finds, which is the rail,
    // and the next press down moved on from the open tab to the next one. So focus that turns
    // up on the rail without having been sent there is given back to the page, once the frame
    // that changed the page is over, at whatever is now first on it.
    var focusOnPage by remember { mutableStateOf(false) }
    var leavingPage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val returnToPage: () -> Unit = {
        scope.launch {
            // Two frames: one for the old item to go, one for what replaces it.
            withFrameNanos {}
            withFrameNanos {}
            // A page with nothing on it to focus gives the rail the focus for good, or every
            // move along the rail would be taken for the page handing focus away again.
            if (focusOnPage && !pageFocused && !page.requestFocus()) focusOnPage = false
        }
    }

    // Somewhere for the focus to start. With nothing focused the first press only found the
    // rail on its way somewhere else - down from nowhere landed on Apps - so the open tab takes
    // the focus as the tabs appear; or the page does, when they appear again because a detail
    // screen opened from it has closed, which is where the focus was when it opened.
    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (focusPageFirst) {
            focusOnPage = true
            // A frame for the item that opened the detail to take the focus back itself (see
            // televisionReturnFocus); the page's first item only if it could not. Either way the
            // claim is over after this, so an item scrolled into view later cannot take it.
            withFrameNanos {}
            returnFocus.detail = null
            if (!pageFocused && !page.requestFocus()) railItems[selected].requestFocus()
        } else {
            railItems[selected].requestFocus()
        }
    }

    BackHandler(enabled = pageFocused) {
        leavingPage = true
        railItems[selected].requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = TelevisionSafeVertical)
    ) {
        NavigationRail(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = TelevisionSafeHorizontal - 24.dp)
                // The rail and the page are two groups, and only left and right cross between
                // them. The rail is close beside everything on the page, so without this a
                // press down on the page could land on whichever tab sat level with it rather
                // than on the next thing below; and coming back to the rail lands on the tab
                // that is open, not on whichever tab was level with the focus.
                //
                // Going right lands on the page's first item rather than on whatever sat level
                // with the tab: from Settings, the fifth tab, that was the fourth setting down.
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection == FocusDirection.Left) leavingPage = true
                        railItems[selected].requestFocus()
                    }
                    onExit = {
                        when {
                            requestedFocusDirection.isVertical -> cancelFocusChange()
                            requestedFocusDirection == FocusDirection.Right -> page.requestFocus()
                        }
                    }
                }
                .focusGroup(),
            containerColor = Color.Transparent
        ) {
            Spacer(modifier = Modifier.weight(1f))
            tabs.forEachIndexed { index, tab ->
                val isSelected = selected == index
                NavigationRailItem(
                    selected = isSelected,
                    onClick = { onSelect(index) },
                    // A tab opens as the focus reaches it, the way a TV app's tabs do: moving
                    // through the rail is already the choice, and OK on top of it was a second
                    // press for nothing.
                    // No outline on the rail: the selected tab's pill already marks it, and with
                    // the tab following the focus the two are always the same tab.
                    modifier = Modifier
                        .focusRequester(railItems[index])
                        .onFocusChanged {
                            if (!it.isFocused) return@onFocusChanged
                            if (focusOnPage && !leavingPage) {
                                // Taken by the rail rather than sent to it: see focusOnPage.
                                returnToPage()
                            } else {
                                focusOnPage = false
                                onSelect(index)
                            }
                            leavingPage = false
                        }
                        .televisionFocusShape(null),
                    icon = {
                        BadgedBox(
                            badge = {
                                if (index == 1 && grantedCount > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme
                                            .primaryContainer
                                    ) { Text(grantedCount.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = null
                            )
                        }
                    },
                    label = { Text(stringResource(tab.label)) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        CenteredContent(
            modifier = Modifier
                .weight(1f)
                .padding(end = TelevisionSafeHorizontal)
                .onFocusChanged {
                    pageFocused = it.hasFocus
                    if (it.hasFocus) {
                        focusOnPage = true
                    } else if (focusOnPage) {
                        // Lost to nothing at all, which a page can also do: given back the same
                        // way. Focus that went to the rail on purpose has cleared focusOnPage by
                        // the time this looks.
                        returnToPage()
                    }
                }
                // Asked for as a whole, a group hands focus to its first item.
                .focusRequester(page)
                .focusProperties {
                    onExit = { if (requestedFocusDirection.isVertical) cancelFocusChange() }
                }
                .focusGroup()
        ) {
            pages.SaveableStateProvider("$TAB_PAGES-$selected") {
                CompositionLocalProvider(LocalTelevisionReturn provides returnFocus) {
                when (selected) {
                    0 -> HomeScreen(bottomPadding = 0.dp)
                    1 -> AppsScreen(
                        bottomPadding = 0.dp,
                        active = appsActive,
                        warmUp = false,
                        onGrantedCount = { grantedCount = it }
                    )
                    // The rail stays when a module opens one of its full screens: on a TV it
                    // is the way to the other tabs, and it covers nothing of the module's.
                    2 -> ModulesScreen(
                        bottomPadding = 0.dp,
                        active = true,
                        onFullscreenChange = {}
                    )
                    3 -> LabsScreen(bottomPadding = 0.dp, onOpenDetail = onOpenDetail)
                    4 -> SettingsScreen(bottomPadding = 0.dp, onOpenDetail = onOpenDetail)
                }
                }
            }
        }
    }
}

/**
 * The detail screen the TV tabs last opened, until the tabs are back and the item that opened it
 * has had its chance to take the focus again.
 */
class TelevisionReturn {
    var detail: Detail? = null
}

private val LocalTelevisionReturn = staticCompositionLocalOf<TelevisionReturn?> { null }

/**
 * On a TV, the focus comes back to this item when the detail screen it opened closes.
 *
 * A detail screen takes the whole window, so the tab it was opened from leaves the composition
 * and is built again on the way back. The page's first item taking the focus then is better than
 * nothing taking it, but it is not where anyone was: back from Log landed on Activities Launcher,
 * a row and four tiles away. Put this on the item that opens [detail], before its clickable.
 * Off a TV it is nothing.
 */
@Composable
fun Modifier.televisionReturnFocus(detail: Detail): Modifier {
    val target = LocalTelevisionReturn.current ?: return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // A frame for the page to be laid out, so the item is in place to be focused.
        withFrameNanos {}
        if (target.detail == detail) {
            target.detail = null
            requester.requestFocus()
        }
    }
    return focusRequester(requester)
}

private val FocusDirection.isVertical: Boolean
    get() = this == FocusDirection.Up || this == FocusDirection.Down
