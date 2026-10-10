@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.shizuku.manager.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.BuildConfig
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.Helps
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.home.showAccessibilityDialog
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.start.StartFailureKind
import moe.shizuku.manager.start.StartStatus
import moe.shizuku.manager.start.StartStatusReporter
import moe.shizuku.manager.start.stillInTheWay
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.start.isDeveloperOptionsEnabled
import moe.shizuku.manager.start.isPermissionPermanentlyDenied
import moe.shizuku.manager.start.restoreDeveloperOptions
import moe.shizuku.manager.start.localNetworkPermission
import moe.shizuku.manager.start.needsLocalNetworkPermissionFor
import moe.shizuku.manager.start.openAppSettings
import moe.shizuku.manager.start.openAdbPortAndStart
import moe.shizuku.manager.start.StartMethodGuard
import moe.shizuku.manager.start.startMethodLabelRes
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.ui.component.ExpressiveCard
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.theme.LocalAmoledTheme
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.ui.component.stripHtmlTags
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.PermissionOwnership
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.permissionOwnership
import moe.shizuku.manager.utils.runShellCommand
import moe.shizuku.manager.utils.UpdateHelper
import rikka.core.util.ClipboardUtils
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(bottomPadding: Dp) {
    val context = LocalContext.current

    var running by remember { mutableStateOf(ShizukuStateMachine.isRunning()) }
    var uid by remember { mutableStateOf(if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1) }
    var batteryIgnored by remember {
        mutableStateOf(SettingsHelper.isIgnoringBatteryOptimizations(context))
    }
    var showAdbCommand by remember { mutableStateOf(false) }
    // What Android says about who owns Shizuku's permission. Reported, never enforced: neither
    // answer it can give keeps Shizuku from working, see PermissionOwnership.
    var permissionOwner by remember { mutableStateOf<PermissionOwnership>(PermissionOwnership.Ours) }
    // The warning already read and put away. Only that one is hidden, so a state that changes
    // still comes up.
    var permissionWarningDismissed by remember { mutableStateOf<PermissionOwnership?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var rooted by remember { mutableStateOf(false) }
    var startMethod by remember { mutableStateOf(ShizukuSettings.getStartMethod()) }
    var developerOptionsOn by remember { mutableStateOf(context.isDeveloperOptionsEnabled()) }
    var hidingActive by remember { mutableStateOf(false) }
    // Saved, so the section somebody opened is still open after a rotation.
    var otherMethodsOpen by rememberSaveable { mutableStateOf(false) }
    var selinuxRes by remember { mutableStateOf<Int?>(null) }
    var seccompRes by remember { mutableStateOf<Int?>(null) }
    var diagnostics by remember { mutableStateOf("") }
    val startStatus by StartStatusReporter.status.collectAsState()
    val scope = rememberCoroutineScope()

    // Android 16+ gates local-network discovery behind a runtime permission, and discovery is
    // the first thing a wireless start does. Asking here on the path that needs it, when the
    // user asks for a start is what keeps a fresh install from failing to find the port with
    // only the pairing tutorial able to grant it.
    var pendingLocalNetworkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingLocalNetworkAction
        pendingLocalNetworkAction = null
        val permission = localNetworkPermission()
        if (!granted && permission != null && context.isPermissionPermanentlyDenied(permission)) {
            // The dialog will never come back, so the start would fail at discovery with
            // nothing to explain it: say where the switch is and open it, rather than
            // burning the start on a permission the user cannot grant from here.
            Toast.makeText(
                context,
                context.getString(R.string.permissions_open_settings_hint),
                Toast.LENGTH_LONG
            ).show()
            context.openAppSettings()
            return@rememberLauncherForActivityResult
        }
        // Otherwise carry on either way: a denial means discovery fails, which the failure
        // card then explains, rather than the tap appearing to do nothing.
        action?.invoke()
    }

    fun startWithLocalNetworkPermission(
        @ShizukuSettings.StartMethod method: Int,
        action: () -> Unit
    ) {
        if (context.needsLocalNetworkPermissionFor(method)) {
            pendingLocalNetworkAction = action
            localNetworkPermission()?.let { localNetworkLauncher.launch(it) }
        } else {
            action()
        }
    }

    /**
     * Reads everything the home screen shows, so no row keeps a value from an earlier
     * state. Called on resume, on every state change (a start or stop finishing) and
     * once at first composition.
     */
    suspend fun refresh() {
        ShizukuStateMachine.update()
        running = ShizukuStateMachine.isRunning()
        batteryIgnored = SettingsHelper.isIgnoringBatteryOptimizations(context)
        developerOptionsOn = context.isDeveloperOptionsEnabled()
        // Not the watch being up, which is a different question with two other answers: it is up
        // for force dark as well, and up for a list that a mode has been switched off around.
        // Only hiding that is armed and live makes the warning under it true.
        hidingActive = HidingWatchService.isRunning() && Hiding.isActive()
        // Root can be gone since the method was chosen; the card would otherwise keep
        // promising a start the device can no longer run.
        startMethod = StartMethodGuard.resolve()
        // A start that is already running has nothing left to report; without this a
        // "starting" state from a path that finishes elsewhere would stick.
        if (running) {
            StartStatusReporter.clear()
        } else {
            // The other state with nothing left to report: a failure whose cause was dealt with
            // while the app was away. This screen refreshes when it comes back into view, which is
            // exactly when somebody has just been to Developer options or to the accessibility
            // list and done what the message asked for - so the message is asked about again, and
            // dropped when the thing it wanted is now true. Leaving it there asks for the same
            // step twice and hides the next one behind a Start press.
            val status = StartStatusReporter.status.value
            if (status is StartStatus.Failed &&
                !status.kind.stillInTheWay(
                    wifiConnected = EnvironmentUtils.isWifiConnected(),
                    wirelessDebuggingOn = EnvironmentUtils.isWirelessDebuggingEnabled(),
                    adbPort = EnvironmentUtils.getAdbTcpPort()
                )
            ) {
                // Written down because the message it drops is the one somebody was looking at: a
                // report that says "the step went away" or "the step never went away" is answered
                // by this line, and by nothing else.
                Diag.info(TAG, "dropping the start failure whose cause is gone: ${status.kind}")
                StartStatusReporter.clear()
            }
        }

        withContext(Dispatchers.IO) {
            // Reset rather than keep: the card must not show the uid of a server that is
            // gone.
            uid = if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
            // Shell.getShell() can block and triggers the root request.
            rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
            val (selinux, seccomp) = readDeviceStatus()
            selinuxRes = selinux
            seccompRes = seccomp
            // Read off the same pass as the rest of the state, so the card cannot describe a
            // service that was there when the screen appeared and not now.
            diagnostics = buildDiagnostics(
                context = context,
                running = running,
                uid = uid,
                method = startMethod,
                hidingActive = hidingActive,
                batteryIgnored = batteryIgnored,
                rooted = rooted
            )
        }
    }

    /**
     * Undoes our own "ADB without Developer options" setting: the flag goes back on, so the
     * screens the failed start wanted are reachable again.
     */
    fun turnDeveloperOptionsBackOn() {
        ShizukuSettings.setAdbWithoutDeveloperOptions(context, false)
        restoreDeveloperOptions(context)
        scope.launch { refresh() }
    }

    DisposableEffect(Unit) {
        val listener: (ShizukuStateMachine.State) -> Unit = {
            // Covers "after each start": refresh every row, not just the state flag.
            scope.launch { refresh() }
        }
        ShizukuStateMachine.addListener(listener)
        onDispose { ShizukuStateMachine.removeListener(listener) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { refresh() }

        // Asked again every time the screen comes back: what clears this is the platform reading
        // the declaration, or a reboot, and a warning that outlives its cause is one nobody reads.
        permissionOwner = readPermissionOwnership(context)
    }

    LaunchedEffect(Unit) {
        refresh()

        // Asked once when the screen first opens, and again on every resume: a reinstall
        // commonly leaves the platform answering with a package that owns Shizuku's permission
        // and is not installed any more, and this is what tells the user whose it is.
        permissionOwner = readPermissionOwnership(context)

        updateAvailable = runCatching {
            UpdateHelper.isCheckForUpdatesEnabled() && UpdateHelper.isNewUpdateAvailable()
        }.getOrDefault(false)
        if (updateAvailable) {
            runCatching { UpdateHelper.updateLastPromptedVersion() }
        }    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            // A warning about where Android says Shizuku's permission lives, and nothing more:
            // every part of the app works with it wrong, but other apps look Shizuku up by that
            // permission, so finding out what they find is worth saying - once, and out of the way.
            val ownership = permissionOwner
            if (ownership !is PermissionOwnership.Ours && ownership != permissionWarningDismissed) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    if (ownership is PermissionOwnership.Other)
                                        R.string.home_dialog_duplicate_app_detected_title
                                    else R.string.home_dialog_reboot_required_title
                                ),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (ownership is PermissionOwnership.Other) {
                                    stringResource(
                                        R.string.home_permission_owned_by,
                                        ownership.packageName
                                    )
                                } else {
                                    stringResource(R.string.home_permission_not_registered)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            PillButtonQuiet(onClick = { permissionWarningDismissed = ownership }) {
                                Text(stringResource(R.string.action_dismiss))
                            }
                        }
                    }
                }
            }

            if (updateAvailable) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.snackbar_update_available),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            PillButton(onClick = {
                                scope.launch {
                                    runCatching { UpdateHelper.update() }
                                    updateAvailable = false
                                }
                            }) { Text(stringResource(R.string.snackbar_action_update)) }
                        }
                    }
                }
            }

            (startStatus as? StartStatus.Failed)?.let { failed ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        // Message on top, actions stacked full-width underneath: in a row
                        // they sat shoulder-to-shoulder and read as one control.
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                // The failures that name a command to run mark it with <b>,
                                // which used to arrive here as the tags themselves.
                                text = "${stringResource(R.string.start_failed)}: " +
                                    failed.message.stripHtmlTags(),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            // Two of these failures are answered by a screen that lives
                            // under Developer options, which our own setting can hide so
                            // those cards say why, and offer the one tap that puts it back
                            // instead of a button that opens nothing.
                            val needsDeveloperOptions =
                                failed.kind == StartFailureKind.SETTINGS ||
                                    failed.kind == StartFailureKind.PAIRING
                            val developerOptionsHidden =
                                needsDeveloperOptions && !developerOptionsOn

                            if (developerOptionsHidden) {
                                Text(
                                    text = stringResource(R.string.start_failed_developer_options_off),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (developerOptionsHidden) {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { turnDeveloperOptionsBackOn() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) {
                                        Text(stringResource(R.string.action_turn_on_developer_options))
                                    }
                                } else if (failed.kind == StartFailureKind.WIFI) {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    SettingsPage.InternetPanel.buildIntent(context)
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) { Text(stringResource(R.string.action_connect_wifi)) }
                                } else if (failed.kind == StartFailureKind.PORT) {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            // Opens the port and starts over it; if that
                                            // needs Wi-Fi, the card comes back offering
                                            // Connect instead.
                                            // Opening the port borrows the wireless
                                            // connection, so it discovers the port too.
                                            startWithLocalNetworkPermission(ShizukuSettings.StartMethod.USB) {
                                                scope.launch { openAdbPortAndStart(context) }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) { Text(stringResource(R.string.action_open_adb_port)) }
                                } else if (failed.kind == StartFailureKind.SETTINGS) {
                                    // The same thing the adb command would buy, done by hand:
                                    // switching wireless debugging on in Developer options.
                                    // Shizuku can't do it without WRITE_SECURE_SETTINGS, but
                                    // the user can, and not everyone has adb to hand.
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            runCatching {
                                                SettingsPage.Developer.HighlightWirelessDebugging
                                                    .launch(context)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) { Text(stringResource(R.string.action_open_wireless_debugging)) }
                                } else if (failed.kind == StartFailureKind.PAIRING) {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                // Pair without typing: the code is read out
                                                // of the system dialog. The manual flow
                                                // stays one tap away in that dialog.
                                                runCatching { context.showAccessibilityDialog() }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) { Text(stringResource(R.string.action_pair)) }
                                }

                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { StartStatusReporter.clear() },
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) { Text(stringResource(R.string.action_dismiss)) }
                            }
                        }
                    }
                }
            }

            if (!batteryIgnored) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.home_battery_warning),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                            ) {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        SettingsHelper.requestIgnoreBatteryOptimizationsPrivileged(context) {
                                            batteryIgnored = SettingsHelper.isIgnoringBatteryOptimizations(context)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) { Text(stringResource(R.string.snackbar_action_fix)) }
                            }
                        }
                    }
                }
            }

            // Said here rather than only on the lists, because this is the screen somebody
            // looks at when Shizuku is not running and they want to know why. While hiding is
            // live, a server that dies on its own, and comes back on its own, is the expected
            // behaviour - and an app that hides the connection it runs on cannot avoid it.
            if (hidingActive) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        // The summary alone: the sentence already says what is happening and why,
                        // and a heading over one line of text read as a label for itself.
                        Text(
                            text = stringResource(R.string.home_hiding_active_note),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                StatusCard(
                    running = running,
                    uid = uid,
                    startMethodLabelRes = startMethodLabelRes(startMethod)
                )
            }

            item {
                // The actions sit outside the status card and stay on screen in the
                // same place, so the buttons don't move around as the state changes.
                ServerActionButtons(
                    running = running,
                    starting = startStatus is StartStatus.Starting,
                    // Uses whichever method is set in Settings; never guesses from the
                    // last one that happened to work.
                    onStart = {
                        startWithLocalNetworkPermission(ShizukuSettings.getStartMethod()) {
                            ShizukuReceiverStarter.start(context, userInitiated = true)
                        }
                    },
                    // One tap, no confirmation: stopping is a normal action and the
                    // dialog only slowed it down.
                    onStop = {
                        ShizukuSettings.setManuallyStopped(true)
                        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                        runCatching { Shizuku.exit() }
                    },
                    // A bounce: forceStart replaces the running server instead of
                    // being ignored as "already running".
                    onRestart = {
                        startWithLocalNetworkPermission(ShizukuSettings.getStartMethod()) {
                            ShizukuReceiverStarter.start(
                                context,
                                forceStart = true,
                                userInitiated = true
                            )
                        }
                    }
                )
            }
            item {
                // The ways to start, as cards rather than rows: each one is a decision with
                // a reason attached, so it gets an icon to be recognised by and a body that
                // says the trade. The start options are disabled while Shizuku is running,
                // because starting again does nothing and Restart is how you relaunch it.
                //
                // Folded away by default behind one card. The button above already starts with
                // whichever method is set, so these are the ones you go looking for rarely, and
                // four cards of explanation for them is most of the home screen when what
                // somebody came to look at is whether Shizuku is running.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExpressiveCard(
                        icon = Icons.Rounded.MoreHoriz,
                        title = stringResource(R.string.home_other_methods),
                        onClick = { otherMethodsOpen = !otherMethodsOpen },
                        trailing = {
                            val turn by animateFloatAsState(
                                targetValue = if (otherMethodsOpen) 180f else 0f,
                                label = "otherMethodsChevron"
                            )
                            Icon(
                                Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(turn)
                            )
                        }
                    )

                    if (otherMethodsOpen) {
                        ExpressiveCard(
                            icon = Icons.Rounded.Wifi,
                            title = stringResource(R.string.home_wireless_adb_title),
                            // A start with the experiment on can spend two minutes asking before it
                            // either starts or gives up, and a card that only spins for that long
                            // reads as stuck.
                            body = if (startStatus is StartStatus.Starting &&
                                ShizukuSettings.getForceWirelessDebugging()
                            ) {
                                stringResource(R.string.home_wireless_adb_starting_without_wifi)
                            } else {
                                stringResource(R.string.home_wireless_adb_summary)
                            },
                            enabled = !running,
                            onClick = {
                                startWithLocalNetworkPermission(ShizukuSettings.StartMethod.WIRELESS) {
                                    ShizukuReceiverStarter.start(
                                        context,
                                        userInitiated = true,
                                        startMethod = ShizukuSettings.StartMethod.WIRELESS
                                    )
                                }
                            }
                        )
                        ExpressiveCard(
                            icon = Icons.Rounded.Usb,
                            title = stringResource(R.string.home_usb_adb_title),
                            // A USB start needs the classic port, and with no network and no port
                            // there is nothing it can do. It says so here rather than sliding over
                            // to wireless: starting without a network is a method of its own, and
                            // the card that does it is the one above this.
                            body = if (!EnvironmentUtils.isWifiConnected() &&
                                EnvironmentUtils.getAdbTcpPort() <= 0
                            ) {
                                // The string marks the method's name with <b>, which Compose
                                // does not parse: it was drawing the tags.
                                stringResource(R.string.home_usb_adb_needs_network).stripHtmlTags()
                            } else {
                                stringResource(R.string.home_usb_adb_summary).stripHtmlTags()
                            },
                            enabled = !running,
                            onClick = {
                                ShizukuReceiverStarter.start(
                                    context,
                                    userInitiated = true,
                                    startMethod = ShizukuSettings.StartMethod.USB
                                )
                            }
                        )
                        if (rooted) {
                            val rootDescription = stringResource(
                                R.string.home_root_description,
                                "<b><a href=\"${Helps.SUI.get()}\">Sui</a></b>",
                                "Sui"
                            ).stripHtmlTags()

                            // Always says what it does: start over root. It used to flip to
                            // "Restart" once a root server was running, which just duplicated the
                            // Restart button above (and hid the fact that this card starts over
                            // root whatever the start method is set to).
                            ExpressiveCard(
                                // A hash, which is what a root shell is: the same mark the shell's
                                // own prompt uses.
                                icon = Icons.Rounded.Numbers,
                                title = stringResource(R.string.home_root_title),
                                body = rootDescription,
                                enabled = !running,
                                onClick = {
                                    context.startActivity(
                                        Intent(context, StarterActivity::class.java)
                                            .putExtra(StarterActivity.EXTRA_IS_ROOT, true)
                                    )
                                }
                            )
                        }
                        ExpressiveCard(
                            icon = Icons.Rounded.AdminPanelSettings,
                            title = stringResource(R.string.home_system_title),
                            body = stringResource(R.string.home_system_summary),
                            enabled = !running,
                            onClick = {
                                ShizukuReceiverStarter.start(
                                    context,
                                    userInitiated = true,
                                    startMethod = ShizukuSettings.StartMethod.SYSTEM
                                )
                            }
                        )

                        // Starting from a computer is the same kind of decision as the rest, so it
                        // is a card as well: as a row underneath them it read as something else.
                        ExpressiveCard(
                            icon = Icons.Rounded.Computer,
                            title = stringResource(R.string.intents_adb_command),
                            // The command itself is long enough to swamp a card; it lives in the
                            // dialog this opens, where it can be copied.
                            body = stringResource(R.string.home_adb_command_summary),
                            enabled = !running,
                            onClick = { showAdbCommand = true }
                        )
                    }
                }
            }
            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    // Same rows KernelSU's manager shows, so the device is described the
                    // same way in both apps.
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.home_device_manager_version)) },
                            supportingContent = { Text(BuildConfig.VERSION_NAME) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.home_device_android_version)) },
                            supportingContent = { Text(androidVersion()) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.home_device_kernel_version)) },
                            supportingContent = { Text(kernelVersion()) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.home_device_model)) },
                            supportingContent = { Text(deviceModel()) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.home_device_fingerprint)) },
                            supportingContent = { Text(Build.FINGERPRINT ?: "-") }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.home_device_selinux)) },
                            supportingContent = { Text(selinuxRes?.let { stringResource(it) } ?: "-") }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.home_device_seccomp)) },
                            supportingContent = { Text(seccompRes?.let { stringResource(it) } ?: "-") }
                        )
                    }
                }
            }

            if (diagnostics.isNotEmpty()) {
                item {
                    // Last of the state, after the device rows: the status card is the
                    // glance, this is the whole answer, and it is the one place that puts it
                    // on the clipboard. Whoever needs it has already scrolled this far.
                    DiagnosticsCard(
                        text = diagnostics,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.diagnostics_title),
                                    diagnostics
                                )
                            )
                            Toast.makeText(
                                context,
                                R.string.diagnostics_copied,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }

            item {
                // The other half of the About row in Settings, and at the end of the page
                // rather than the top: nobody opens this app to be asked, and the person who
                // has scrolled past the device details is the one who has got something out
                // of it. Same page, same way of opening it.
                ExpressiveCard(
                    icon = Icons.Rounded.Coffee,
                    title = stringResource(R.string.settings_support_coffee),
                    body = stringResource(R.string.home_support_body),
                    onClick = {
                        CustomTabsHelper.launchUrlOrCopy(
                            context,
                            context.getString(R.string.buymeacoffee_url)
                        )
                    }
                )
            }
        }
    }

    if (showAdbCommand) {
        AlertDialog(
            onDismissRequest = { showAdbCommand = false },
            title = { Text(stringResource(R.string.intents_adb_command)) },
            text = { Text(Starter.adbCommand, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                PillButton(onClick = {
                    if (ClipboardUtils.put(context, Starter.adbCommand)) {
                        Toast.makeText(context, context.getString(R.string.toast_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                    }
                    showAdbCommand = false
                }) { Text(stringResource(R.string.intents_copy)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { showAdbCommand = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    uid: Int,
    @StringRes startMethodLabelRes: Int
) {
    val context = LocalContext.current
    // "Stopped" is a normal state, not an error a red container made the
    // primary action clash. Use a neutral surface instead.
    val containerColor = if (running) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (running) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // On the pure black theme the stopped card is the same colour as the page, so it
    // gets the same hairline outline as the other cards. The running card uses a
    // tinted container that the page can't swallow.
    val outlined = LocalAmoledTheme.current && !running

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (outlined) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.large
                    )
                } else {
                    Modifier
                }
            ),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large
    ) {
        // The icon reads as a status badge for the title beside it, with the facts in a
        // row underneath the two of them.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Icon(
                if (running) Icons.Rounded.CheckCircle else Icons.Rounded.StopCircle,
                contentDescription = null,
                // Nudged down to sit on the title's line rather than above it.
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (running) R.string.status_running_short else R.string.status_stopped_short
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                // Everything about the server at a glance: how it is running now, what
                // the Start button below will do next, and what it is running as. The same
                // facts are in the diagnostics card at the end of the page, where they are
                // one block with everything else in it - this is the one that is read, that
                // one is the one that is pasted.
                StatusFacts(
                    StatusFactEntry(
                        R.string.home_status_started_with,
                        if (running) startedWithLabel(context, uid) else stringResource(R.string.status_value_none)
                    ),
                    StatusFactEntry(
                        R.string.home_status_started_default,
                        stringResource(startMethodLabelRes)
                    ),
                    // The wire, as opposed to the method above it: a wireless start can ride
                    // the classic port (and a system start uses no adb at all).
                    StatusFactEntry(
                        R.string.home_status_transport_label,
                        if (running) transportLabel(context, uid) else stringResource(R.string.status_value_none)
                    ),
                    // The number is the fact; what the uid is called goes underneath it,
                    // because "2000 (shell)" in a quarter of the width lost its own name.
                    StatusFactEntry(
                        R.string.uid_label,
                        if (uid < 0) stringResource(R.string.status_value_none) else uid.toString(),
                        uidName(context, uid)
                    )
                )
            }

        }
    }
}

/**
 * KernelSU's fallback for the device name: manufacturer, brand when it differs, model.
 * Their per-manufacturer "marketing name" properties (Samsung, Xiaomi, OPPO…) could be
 * layered on top later; on a Nothing phone this is the name KSU shows too.
 */
/** What this screen's own decisions are filed under in the on-device log. */
private const val TAG = "StartStatus"

private fun deviceModel(): String = buildString {
    // Capitalised because manufacturers report themselves in lowercase ("samsung"),
    // and without their marketing-name layer there is nothing else to show.
    append(Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
    if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) {
        append(' ').append(Build.BRAND)
    }
    append(' ').append(Build.MODEL)
}

private fun kernelVersion(): String = System.getProperty("os.version").orEmpty().ifEmpty { "-" }

/** The Android release beside its API level, which is the pair that names it: "16 (SDK 36)". */
private fun androidVersion(): String = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

/**
 * The kernel's SELinux state, worked out the way KernelSU's manager works it out:
 * ask `getenforce` as an ordinary app, and treat a refusal as the answer selinuxfs is
 * world-readable on disk but denied to app domains, and a permissive policy would have
 * allowed the read. That is what makes this work with no root, no server and no KSU.
 */
@StringRes
private fun readSelinuxStatus(): Int? {
    // With a server running, read the switch itself rather than infer it.
    val raw = runShellCommand("cat /sys/fs/selinux/enforce")
        ?: runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()

    when (raw) {
        "1" -> return R.string.selinux_enforcing
        "0" -> return R.string.selinux_permissive
    }

    val (stdout, stderr) = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", "getenforce").start()
        val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val err = process.errorStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        out to err
    }.getOrNull() ?: return null

    return when {
        stdout.equals("Enforcing", true) -> R.string.selinux_enforcing
        stdout.equals("Permissive", true) -> R.string.selinux_permissive
        stdout.equals("Disabled", true) -> R.string.selinux_disabled
        // Refused: only an enforcing policy says no here.
        stderr.contains("Permission denied") -> R.string.selinux_enforcing
        else -> null
    }
}

/** Seccomp mode for this process, from our own /proc entry. */
@StringRes
private fun readSeccompStatus(): Int? {
    val mode = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Seccomp:") }
            ?.substringAfter(':')
            ?.trim()
    }.getOrNull()

    return when (mode) {
        "0" -> R.string.seccomp_disabled
        "1" -> R.string.seccomp_strict
        "2" -> R.string.seccomp_filter
        else -> null
    }
}

/** Reads what the device card shows, off the main thread. */
private fun readDeviceStatus(): Pair<Int?, Int?> = readSelinuxStatus() to readSeccompStatus()

/**
 * The facts inside the status card, spread across it with a hairline rule between each
 * pair. Each takes an equal share of the width so the rules land in even gaps, and long
 * values ellipsise inside their share rather than pushing the next one along.
 */
private data class StatusFactEntry(
    @StringRes val label: Int,
    val value: String,
    /** A quieter line under the value, for what the value is called rather than what it is. */
    val detail: String? = null
)

@Composable
private fun StatusFacts(vararg facts: StatusFactEntry) {
    Row(
        // IntrinsicSize.Min is what gives the rules a height to fill: without it the
        // divider measures against the row's (unbounded) constraint and disappears.
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(IntrinsicSize.Min)
    ) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    thickness = 1.dp,
                    color = LocalContentColor.current.copy(alpha = 0.25f)
                )
            }
            StatusFact(fact, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * One fact: its label above its value. The label is set back so the eye reads the value
 * first, and both line up with the title above them.
 */
@Composable
private fun StatusFact(fact: StatusFactEntry, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            stringResource(fact.label),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            fact.value,
            // One step up from the label and no more: four facts share the width, so a
            // bigger value just eats its own ellipsis. Two lines rather than one, because
            // the method names are longer than a quarter of the width and "Start without
            // Wi-Fi" clipped to "Start wi…" says less than it costs to wrap.
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        fact.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Restart's blue see the note where it is used. */
private val RestartBlue = Color(0xFF2563EB)

/**
 * Start, Stop and Restart below the status card, all always visible. An action that
 * doesn't apply right now is disabled rather than hidden, so the row never shifts.
 * A start in flight shows as progress inside Start, where the eye already is.
 */
@Composable
private fun ServerActionButtons(
    running: Boolean,
    starting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            modifier = Modifier.weight(1f),
            enabled = !running && !starting,
            onClick = onStart
        ) {
            if (starting) {
                // The morphing loader rather than a ring: it is the shape Material 3
                // gives an action that is running, and it is legible at button size.
                LoadingIndicator(
                    modifier = Modifier.size(22.dp),
                    // The button is disabled while starting, so pick a colour that
                    // still reads against the disabled container.
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(stringResource(R.string.action_start))
            }
        }

        // Red: stopping is the destructive one, and the theme's error role stays red
        // whatever the seed colour is.
        Button(
            modifier = Modifier.weight(1f),
            enabled = running,
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Text(stringResource(R.string.action_stop))
        }

        // Blue on purpose: Material 3 has no blue role and the palette is seeded from
        // the wallpaper or the brand colour, so a role here would come out indigo or
        // teal depending on the theme.
        Button(
            modifier = Modifier.weight(1f),
            enabled = running,
            onClick = onRestart,
            colors = ButtonDefaults.buttonColors(
                containerColor = RestartBlue,
                contentColor = Color.White
            )
        ) {
            Text(stringResource(R.string.action_restart))
        }
    }
}

/**
 * What Android answers about Shizuku's own permission.
 *
 * Both look-ups are wrapped because "not there" is one of the answers and the platform says it by
 * throwing. The group is asked as well as the permission: a reinstall can leave either of them
 * unprocessed, and the two together are what tells the two states apart.
 */
private fun readPermissionOwnership(context: Context): PermissionOwnership {
    val manager = context.packageManager
    val owner = runCatching {
        manager.getPermissionInfo(Manifest.permission.API_V23, 0).packageName
    }.getOrNull()
    val groupResolved = runCatching {
        manager.getPermissionGroupInfo(Manifest.permission_group.API, 0)
    }.isSuccess

    return permissionOwnership(context.packageName, owner, groupResolved)
}


