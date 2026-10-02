package moe.shizuku.manager.ui.screen

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.R
import moe.shizuku.manager.home.isAccessibilityEnabled
import moe.shizuku.manager.manage.HidingGrants
import moe.shizuku.manager.start.hasPermission
import moe.shizuku.manager.start.isPermissionPermanentlyDenied
import moe.shizuku.manager.start.openAppSettings
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.start.localNetworkPermission
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.SettingsPage
import rikka.core.util.ClipboardUtils

/**
 * Everything this app is not allowed to do until the user (or adb) says so, and whether each
 * one is currently allowed.
 *
 * Kept in one place because the states are spread across three different mechanisms runtime
 * permissions, an adb-only permission, and the battery whitelist and the app used to ask
 * for them at the moment they were needed, from whatever screen happened to trigger it. The
 * battery row moved here from settings for the same reason: it is the same question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var notifications by remember { mutableStateOf(context.hasPermission(POST_NOTIFICATIONS)) }
    var writeSecureSettings by remember { mutableStateOf(context.hasPermission(WRITE_SECURE_SETTINGS)) }
    var batteryIgnored by remember { mutableStateOf(SettingsHelper.isIgnoringBatteryOptimizations(context)) }
    var accessibility by remember { mutableStateOf(context.isAccessibilityEnabled()) }
    var localNetwork by remember {
        mutableStateOf(localNetworkPermission()?.let { context.hasPermission(it) } ?: true)
    }
    var usageAccess by remember { mutableStateOf(HidingGrants.usageAccess()) }
    var dump by remember { mutableStateOf(HidingGrants.dump()) }

    fun refresh() {
        notifications = context.hasPermission(POST_NOTIFICATIONS)
        writeSecureSettings = context.hasPermission(WRITE_SECURE_SETTINGS)
        batteryIgnored = SettingsHelper.isIgnoringBatteryOptimizations(context)
        accessibility = context.isAccessibilityEnabled()
        localNetwork = localNetworkPermission()?.let { context.hasPermission(it) } ?: true
        usageAccess = HidingGrants.usageAccess()
        dump = HidingGrants.dump()
    }

    // Which permission the request on screen is for, so the answer can be attributed to it,
    // and which ones this session has learned the system will no longer ask about.
    var askedPermission by remember { mutableStateOf<String?>(null) }
    var stuckPermissions by remember { mutableStateOf(emptySet<String>()) }
    // The two grants below are shell commands rather than dialogs, so they are run from a
    // coroutine rather than through the permission launcher.
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val asked = askedPermission
        askedPermission = null
        // A denial with no rationale left means the system will never show the dialog for
        // this permission again: sending them to app settings is the only way forward, and
        // the row below remembers it so the button stops offering a request that can't
        // appear.
        if (!granted && asked != null && context.isPermissionPermanentlyDenied(asked)) {
            stuckPermissions = stuckPermissions + asked
            Toast.makeText(
                context,
                context.getString(R.string.permissions_open_settings_hint),
                Toast.LENGTH_LONG
            ).show()
            context.openAppSettings()
        }
        refresh()
    }

    fun askFor(permission: String, action: () -> Unit) {
        if (permission in stuckPermissions) {
            context.openAppSettings()
        } else {
            askedPermission = permission
            action()
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // Coming back from the system's own screens (accessibility, battery) changes these.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val writeSecureSettingsCommand =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_permissions)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                PermissionRow(
                    icon = Icons.Rounded.Notifications,
                    headline = stringResource(R.string.permissions_notifications),
                    reason = stringResource(R.string.permissions_notifications_summary),
                    granted = notifications,
                    actionLabel = stringResource(
                        if (POST_NOTIFICATIONS in stuckPermissions) R.string.permissions_action_settings
                        else R.string.permissions_action_allow
                    ),
                    onAction = {
                        askFor(POST_NOTIFICATIONS) { permissionLauncher.launch(POST_NOTIFICATIONS) }
                    }
                )
            }

            item {
                PermissionRow(
                    icon = Icons.Rounded.Wifi,
                    headline = stringResource(R.string.permissions_nearby),
                    reason = stringResource(R.string.permissions_nearby_summary),
                    granted = localNetwork,
                    actionLabel = stringResource(
                        if (localNetworkPermission() in stuckPermissions) R.string.permissions_action_settings
                        else R.string.permissions_action_allow
                    ),
                    onAction = {
                        localNetworkPermission()?.let { permission ->
                            askFor(permission) { permissionLauncher.launch(permission) }
                        }
                    }
                )
            }

            item {
                PermissionRow(
                    icon = Icons.Rounded.AdminPanelSettings,
                    headline = stringResource(R.string.permissions_write_secure_settings),
                    reason = stringResource(R.string.permissions_write_secure_settings_summary),
                    granted = writeSecureSettings,
                    // There is no dialog for this one: only adb can grant it, so the action
                    // copies the command to run.
                    actionLabel = stringResource(R.string.intents_copy),
                    onAction = {
                        if (ClipboardUtils.put(context, writeSecureSettingsCommand)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_copied_to_clipboard),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            // The two the hiding lists need, which have no dialog between them: both are
            // commands, and Shizuku is the one that can run them. The usage access row keeps
            // Android's own page as the way round a device with no running server, because that
            // page is what somebody without Shizuku would use anyway. Read app exit reasons has
            // no such page, so its fallback is the command itself, copied.
            item {
                PermissionRow(
                    icon = Icons.Rounded.Lock,
                    headline = stringResource(R.string.permissions_usage_access),
                    reason = stringResource(R.string.permissions_usage_access_summary),
                    granted = usageAccess,
                    actionLabel = stringResource(R.string.permissions_action_allow),
                    onAction = {
                        scope.launch {
                            withContext(Dispatchers.IO) { HidingGrants.ensure() }
                            refresh()
                            if (!HidingGrants.usageAccess()) {
                                SettingsPage.UsageAccess.launch(context)
                            }
                        }
                    }
                )
            }

            item {
                PermissionRow(
                    icon = Icons.Rounded.Info,
                    headline = stringResource(R.string.permissions_dump),
                    reason = stringResource(R.string.permissions_dump_summary),
                    granted = dump,
                    actionLabel = stringResource(R.string.permissions_action_allow),
                    onAction = {
                        scope.launch {
                            withContext(Dispatchers.IO) { HidingGrants.ensure() }
                            dump = HidingGrants.dump()
                            if (!HidingGrants.dump()) {
                                val command = "adb shell pm grant ${context.packageName} " +
                                    "android.permission.DUMP"
                                if (ClipboardUtils.put(context, command)) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_copied_to_clipboard),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                )
            }

            item {
                PermissionRow(
                    icon = Icons.Rounded.Visibility,
                    headline = stringResource(R.string.permissions_accessibility),
                    reason = stringResource(R.string.permissions_accessibility_summary),
                    granted = accessibility,
                    actionLabel = stringResource(R.string.enable),
                    onAction = { SettingsPage.Accessibility.launch(context) }
                )
            }

            item {
                PermissionRow(
                    icon = Icons.Rounded.BatterySaver,
                    headline = stringResource(R.string.tools_battery),
                    reason = stringResource(R.string.permissions_battery_summary),
                    granted = batteryIgnored,
                    actionLabel = stringResource(R.string.snackbar_action_fix),
                    onAction = {
                        SettingsHelper.requestIgnoreBatteryOptimizationsPrivileged(context) {
                            refresh()
                        }
                    }
                )
            }
        }
    }
}

/**
 * One required permission: what it is for, whether it is allowed, and how to change that.
 *
 * Laid out by hand rather than with a list item, because these reasons run to several
 * lines and a list item puts its trailing content at the top of a tall row the state
 * ended up level with the headline while the text carried on below it, which read as a
 * label for the paragraph rather than the answer for the row.
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    headline: String,
    reason: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    SegmentedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The icon-led badge the KernelSU-style cards use: the icon in a tinted rounded
            // square, so a page of these can be told apart before any of the text is read.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.bodyLarge)
                Text(
                    reason,
                    // A step down from the headline and no more: these are one-line
                    // reminders, and at body size they read as paragraphs to work through.
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // A tick is enough for "this one is fine": the word beside it only added
            // width to every row on the page.
            if (granted) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.permissions_allowed),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                PillButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
