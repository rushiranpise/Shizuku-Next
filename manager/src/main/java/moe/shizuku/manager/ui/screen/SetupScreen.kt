package moe.shizuku.manager.ui.screen

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.home.isAccessibilityEnabled
import moe.shizuku.manager.start.hasPermission
import moe.shizuku.manager.start.isPermissionPermanentlyDenied
import moe.shizuku.manager.start.localNetworkPermission
import moe.shizuku.manager.start.openAppSettings
import moe.shizuku.manager.ui.component.ExpressiveCard
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.SettingsPage

/**
 * The first thing a fresh install shows, and the only screen in the app that is allowed to ask
 * for things before the person has asked for anything.
 *
 * The order is the argument: what this is, how it will start, what it needs, and then the home
 * screen, where starting already lives. Starting from here was the wrong end of a wizard: a
 * wireless start needs a pairing that has not happened yet on a fresh install, so the page could
 * only ever show a failure. The home screen is where that start belongs, with its own Pair action
 * beside it.
 * Every page is skippable, because a setup a person cannot leave is a setup they resent, and
 * nothing here is required for the app to open: anything left undone is still on the home screen
 * afterwards, where it was before.
 *
 * One decision per page, and each page written to be understood on its own - the notification
 * permission is asked here with the sentence that says what it is for, which is the whole reason
 * this screen exists. It used to be asked from `MainActivity.onCreate`, before anything had been
 * drawn, and the first thing the app then showed was a battery warning in error colours.
 */
@Composable
fun SetupScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pager = rememberPagerState(pageCount = { PAGES })

    // The chosen method, so the rest of the pages can tell whether wireless is even on the table.
    // Read from the setting rather than probed: asking for root here would put a superuser
    // prompt in the middle of a welcome screen, and the card says what root needs anyway.
    var method by remember {
        mutableIntStateOf(
            if (ShizukuSettings.getStartMethod() == ShizukuSettings.StartMethod.ROOT) {
                ShizukuSettings.StartMethod.ROOT
            } else {
                ShizukuSettings.StartMethod.WIRELESS
            }
        )
    }
    val wireless = method == ShizukuSettings.StartMethod.WIRELESS

    var notifications by remember { mutableStateOf(context.hasPermission(POST_NOTIFICATIONS)) }
    var battery by remember {
        mutableStateOf(SettingsHelper.isIgnoringBatteryOptimizations(context))
    }
    var nearby by remember {
        mutableStateOf(localNetworkPermission()?.let { context.hasPermission(it) } ?: true)
    }

    fun refresh() {
        notifications = context.hasPermission(POST_NOTIFICATIONS)
        battery = SettingsHelper.isIgnoringBatteryOptimizations(context)
        nearby = localNetworkPermission()?.let { context.hasPermission(it) } ?: true
    }

    // The same trick the permissions page uses for a permission Android will not ask about
    // twice: remember the state rather than the question.
    var stuck by remember { mutableStateOf(emptySet<String>()) }
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // A denial with no rationale left means the system dialog will never come back, so the
        // row stops offering a request that cannot appear and says where the switch is instead.
        val permission = pendingPermission
        pendingPermission = null
        if (!granted && permission != null && context.isPermissionPermanentlyDenied(permission)) {
            stuck = stuck + permission
        }
        refresh()
    }

    fun ask(permission: String) {
        if (permission in stuck) {
            context.openAppSettings()
        } else {
            pendingPermission = permission
            permissionLauncher.launch(permission)
        }
    }

    // Coming back from the battery dialog or from app settings changes these.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    // Back walks the pages before it leaves: a wizard that the back gesture dismisses from page
    // three would throw away the choice the pages were for.
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.setup_step,
                        pager.currentPage + 1,
                        PAGES
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                // Skip is on every page and means the same thing on all of them: this will not
                // come back, and the home screen keeps saying what is missing.
                PillButtonQuiet(onClick = onFinish) {
                    Text(stringResource(R.string.setup_action_skip))
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> StartMethodPage(
                        method = method,
                        onMethod = { method = it }
                    )

                    2 -> PermissionsPage(
                        wireless = wireless,
                        notifications = notifications,
                        battery = battery,
                        nearby = nearby,
                        notificationsStuck = POST_NOTIFICATIONS in stuck,
                        nearbyStuck = localNetworkPermission() in stuck,
                        onNotifications = { ask(POST_NOTIFICATIONS) },
                        onBattery = {
                            SettingsHelper.requestIgnoreBatteryOptimizationsPrivileged(context) {
                                battery = SettingsHelper.isIgnoringBatteryOptimizations(context)
                            }
                        },
                        onNearby = {
                            localNetworkPermission()?.let { ask(it) }
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(PAGES) { index ->
                    val selected = index == pager.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(if (selected) 24.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            PillButton(
                onClick = {
                    when (pager.currentPage) {
                        // The choice is written down on the way out of its page, not on the way
                        // into the home screen: somebody who skips after this still set their
                        // method.
                        1 -> {
                            ShizukuSettings.setStartMethod(method)
                            scope.launch { pager.animateScrollToPage(2) }
                        }

                        2 -> onFinish()

                        else -> scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
            ) {
                Text(
                    text = if (pager.currentPage < PAGES - 1) {
                        stringResource(R.string.setup_action_continue)
                    } else {
                        stringResource(R.string.setup_action_done)
                    }
                )
            }
        }
    }
}

/** Three pages, and the number is used by the step label as well as the pager. */
private const val PAGES = 3

@Composable
private fun WelcomePage() {
    SetupPage {
        SetupHero(Icons.Rounded.AdminPanelSettings)
        SetupTitle(stringResource(R.string.setup_welcome_title))
        SetupBody(stringResource(R.string.setup_welcome_body))
    }
}

/**
 * The one fork that decides everything after it.
 *
 * Both cards stay tappable whatever the setting says, because the setting knows how the last
 * start went, not what this device is: a phone that has just been rooted still reads as wireless
 * until a root start has worked once.
 */
@Composable
private fun StartMethodPage(method: Int, onMethod: (Int) -> Unit) {
    SetupPage {
        SetupTitle(stringResource(R.string.setup_start_title))
        SetupBody(stringResource(R.string.setup_start_body))

        ExpressiveCard(
            icon = Icons.Rounded.Numbers,
            title = stringResource(R.string.setup_root_title),
            body = stringResource(R.string.setup_root_body),
            onClick = { onMethod(ShizukuSettings.StartMethod.ROOT) },
            trailing = {
                if (method == ShizukuSettings.StartMethod.ROOT) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        ExpressiveCard(
            icon = Icons.Rounded.Wifi,
            title = stringResource(R.string.setup_wireless_title),
            body = stringResource(R.string.setup_wireless_body),
            onClick = { onMethod(ShizukuSettings.StartMethod.WIRELESS) },
            trailing = {
                if (method == ShizukuSettings.StartMethod.WIRELESS) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )
    }
}

/**
 * The page that ends the setup.
 *
 * It does not start anything. A wireless start on a fresh install has no pairing behind it, so a
 * start offered here could only fail; the home screen is where a start belongs, with its own
 * action for pairing beside the failure it can report.
 */
@Composable
private fun PermissionsPage(
    wireless: Boolean,
    notifications: Boolean,
    battery: Boolean,
    nearby: Boolean,
    notificationsStuck: Boolean,
    nearbyStuck: Boolean,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onNearby: () -> Unit
) {
    val context = LocalContext.current

    // Whether the accessibility switch is on, read again when this screen comes back: the two
    // system screens this row sends people to are exactly where it changes.
    var accessibilityOn by remember { mutableStateOf(context.isAccessibilityEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityOn = context.isAccessibilityEnabled()
    }

    // Android's restricted settings are opened in two steps, and in this order only. The "Allow
    // restricted settings" entry is not in App info until the platform has refused the
    // accessibility switch once, so opening App info first is a page with no such entry - which
    // is what this row used to do, and why it read as a dead end. The first tap therefore goes to
    // the accessibility list, where the refusal happens; the next one goes to App info, where the
    // entry has appeared by then.
    var visitedAccessibility by rememberSaveable { mutableStateOf(false) }
    val sideloaded = remember { context.isSideloaded() }
    val offerRestricted = wireless &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        sideloaded

    SetupPage {
        SetupTitle(stringResource(R.string.setup_permissions_title))
        SetupBody(stringResource(R.string.setup_permissions_body))

        PermissionRow(
            icon = Icons.Rounded.Notifications,
            headline = stringResource(R.string.permissions_notifications),
            reason = stringResource(R.string.permissions_notifications_summary),
            granted = notifications,
            actionLabel = stringResource(
                if (notificationsStuck) R.string.permissions_action_settings
                else R.string.permissions_action_allow
            ),
            onAction = onNotifications
        )

        PermissionRow(
            icon = Icons.Rounded.BatterySaver,
            headline = stringResource(R.string.tools_battery),
            reason = stringResource(R.string.permissions_battery_summary),
            granted = battery,
            actionLabel = stringResource(R.string.permissions_action_allow),
            onAction = onBattery
        )

        // Only for the method that needs it: discovery on Android 16 finds the wireless
        // debugging port through this permission and nothing else.
        if (wireless && localNetworkPermission() != null) {
            PermissionRow(
                icon = Icons.Rounded.Wifi,
                headline = stringResource(R.string.permissions_nearby),
                reason = stringResource(R.string.permissions_nearby_summary),
                granted = nearby,
                actionLabel = stringResource(
                    if (nearbyStuck) R.string.permissions_action_settings
                    else R.string.permissions_action_allow
                ),
                onAction = onNearby
            )
        }

        // The accessibility switch of a sideloaded app is greyed out on Android 13+ until
        // restricted settings are allowed, and the platform only offers that allowance after it
        // has refused the switch once. So the row walks the same two steps the system does.
        if (offerRestricted) {
            PermissionRow(
                icon = Icons.Rounded.AdminPanelSettings,
                headline = stringResource(R.string.permissions_pairing_service),
                // One line, like every other row here. Android's own screen explains the
                // restriction; the second line only says where the way out is.
                reason = stringResource(
                    if (visitedAccessibility && !accessibilityOn) {
                        R.string.permissions_pairing_service_restricted
                    } else {
                        R.string.permissions_pairing_service_reason
                    }
                ),
                granted = accessibilityOn,
                actionLabel = stringResource(
                    if (visitedAccessibility) R.string.permissions_action_settings
                    else R.string.permissions_action_allow
                ),
                onAction = {
                    if (visitedAccessibility) {
                        SettingsPage.ApplicationDetails.launch(context)
                    } else {
                        visitedAccessibility = true
                        SettingsPage.Accessibility.launch(context)
                    }
                }
            )
        }
    }
}

/**
 * Whether this copy came from a store, which is the one thing Android's restricted settings are
 * about: a package installed by adb or a browser has the accessibility switch blocked until the
 * user allows it by hand.
 */
private fun Context.isSideloaded(): Boolean {
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstallerPackageName(packageName)
    }
    return installer != "com.android.vending"
}

/** The column every page sits in, so the four share their margins and their scrolling. */
@Composable
private fun SetupPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        content()
    }
}

/** The mark at the top of a page: one icon, tinted, with nothing to read beside it. */
@Composable
private fun SetupHero(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun SetupTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun SetupBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

