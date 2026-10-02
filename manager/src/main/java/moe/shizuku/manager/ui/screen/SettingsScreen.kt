package moe.shizuku.manager.ui.screen

import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.ChoiceRow
import moe.shizuku.manager.ui.component.Contributor
import moe.shizuku.manager.ui.component.ContributorCredits
import moe.shizuku.manager.ui.component.ContributorDetailsDialog
import moe.shizuku.manager.ui.component.ContributorWall
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import moe.shizuku.manager.ui.component.SegmentedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.settings.BugReportDialogActivity
import moe.shizuku.manager.ui.Detail
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.theme.ThemeState
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.ui.component.stripHtmlTags
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.start.StartMethodGuard
import moe.shizuku.manager.start.hasWriteSecureSettings
import moe.shizuku.manager.start.AdbPortPersistence
import moe.shizuku.manager.start.applyAdbWithoutDeveloperOptions
import moe.shizuku.manager.start.restoreDeveloperOptions
import moe.shizuku.manager.start.startMethodLabelRes
import moe.shizuku.manager.utils.AppLocale
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UpdateHelper
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.manager.ShizukuLocales

/** The agent a system uid start goes through, checked for when it is not running yet. */
private const val FOTA_AGENT_PACKAGE = "com.sdet.fotaagent"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(bottomPadding: Dp, onOpenDetail: (Detail) -> Unit) {
    val context = LocalContext.current

    var startOnBoot by remember { mutableStateOf(ShizukuSettings.getStartOnBoot(context)) }
    var watchdog by remember { mutableStateOf(ShizukuSettings.getWatchdog()) }
    var autoDisableUsb by remember { mutableStateOf(ShizukuSettings.getAutoDisableUsbDebugging()) }
    var autoDisableWireless by remember { mutableStateOf(ShizukuSettings.getAutoDisableWirelessDebugging()) }
    var startMethod by remember { mutableStateOf(ShizukuSettings.getStartMethod()) }
    var waitForWifi by remember { mutableStateOf(ShizukuSettings.getWaitForWifi()) }
    var forceWireless by remember { mutableStateOf(ShizukuSettings.getForceWirelessDebugging()) }
    var persistAdbPort by remember { mutableStateOf(ShizukuSettings.getPersistAdbPort()) }
    var tcpMode by remember { mutableStateOf(ShizukuSettings.getTcpMode()) }
    var tcpPort by remember { mutableStateOf(ShizukuSettings.getTcpPort().toString()) }
    var systemStartMethod by remember { mutableStateOf(ShizukuSettings.getSystemStartMethod()) }
    var updateMode by remember { mutableStateOf(ShizukuSettings.getUpdateMode()) }
    var nightMode by remember { mutableStateOf(ShizukuSettings.getNightMode()) }
    var themeDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(ShizukuSettings.getLanguageTag()) }
    var useSystemColor by remember {
        mutableStateOf(ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.Keys.KEY_USE_SYSTEM_COLOR, true))
    }
    var blackNight by remember {
        mutableStateOf(ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.Keys.KEY_BLACK_NIGHT_THEME, false))
    }

    var legacyPairing by remember { mutableStateOf(ShizukuSettings.getLegacyPairing()) }
    var adbWithoutDeveloperOptions by remember {
        mutableStateOf(ShizukuSettings.getAdbWithoutDeveloperOptions())
    }
    var adbWithoutDeveloperOptionsPrompt by remember { mutableStateOf(false) }

    /**
     * Whether the persistent ADB port can be written here at all.
     *
     * The property belongs to adbd, so root or a server running as the system uid is the
     * only thing allowed to write it, and on a shell-only server the platform refuses it
     * outright. Offering a switch that can only fail is worse than not offering it, so the
     * row appears when one of those is running, or when the device has what it takes to
     * reach one: root as the chosen start method, or the agent a system start goes through.
     *
     * Nothing here prompts for root the way checking for a root shell would, and the row
     * stays visible while the setting is on, so it can always be turned off again.
     */
    val canPersistAdbPort = remember {
        val running = ShizukuSettings.getRunningStartMethod()
        val agent = runCatching {
            context.packageManager.getPackageInfo(FOTA_AGENT_PACKAGE, 0)
        }.isSuccess

        startMethod == ShizukuSettings.StartMethod.ROOT ||
            running == ShizukuSettings.StartMethod.ROOT ||
            running == ShizukuSettings.StartMethod.SYSTEM ||
            agent
    }

    /**
     * Applies the setting and keeps the switch honest: if the writes were refused (no
     * WRITE_SECURE_SETTINGS yet) the setting is put back rather than left showing on for
     * something that did not happen.
     */
    fun setAdbWithoutDeveloperOptions(enable: Boolean) {
        val applied = if (enable) {
            applyAdbWithoutDeveloperOptions(context)
        } else {
            // The way back is a write as well, and the permission can be gone since the
            // setting was switched on - a reinstall under a new key loses it - so this is
            // the one place where being refused would otherwise leave Developer options
            // hidden and the switch claiming they are not. A refusal keeps the setting as
            // it was, which is what the device is actually in.
            restoreDeveloperOptions(context)
        }
        ShizukuSettings.setAdbWithoutDeveloperOptions(context, if (applied) enable else !enable)
        adbWithoutDeveloperOptions = ShizukuSettings.getAdbWithoutDeveloperOptions()
        if (!applied) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_adb_without_developer_options_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    var closeTcpDialog by remember { mutableStateOf(false) }
    var restartAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var restartWifiNote by remember { mutableStateOf(false) }
    var batteryPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()

    var startMethodDialog by remember { mutableStateOf(false) }
    var tcpPortDialog by remember { mutableStateOf(false) }
    var systemStartDialog by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf(false) }
    var selectedContributor by remember { mutableStateOf<Contributor?>(null) }
    val contributors = rememberContributors()

    // Whether the experiment can write the setting it is made of, which is what decides if it
    // is offered at all. It arrives without this screen being told - a running Shizuku grants
    // itself the permission, and `pm grant` from a computer does the same - so it is read
    // again every time the screen is come back to rather than once when it is built.
    var writeSecureSettings by remember { mutableStateOf(context.hasWriteSecureSettings()) }

    // The language can be changed without this screen: from Android 13 the system's own per-app
    // language screen sets it too, and the framework rebuilds the activities rather than the
    // process, so the row would go on naming the language that used to be chosen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        AppLocale.reconcile(context)
        language = ShizukuSettings.getLanguageTag()
        writeSecureSettings = context.hasWriteSecureSettings()
    }

    // Root can be gone since the method was chosen (an OTA, root switched off in the
    // manager), and a stored Root would then never start anything. The resolver rewrites
    // the setting; Root is also left out of the list below, so it can't be picked again.
    // Both probes spawn a shell, hence off the main thread.
    var rootAvailable by remember { mutableStateOf(false) }
    var rootMethodDropped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            StartMethodGuard.isAvailable(ShizukuSettings.StartMethod.ROOT) to
                StartMethodGuard.resolve()
        }.let { (available, resolved) ->
            rootAvailable = available
            // Read after resolving: the drop may have happened on the home screen
            // already, and it is still worth explaining here.
            rootMethodDropped = StartMethodGuard.droppedRoot
            startMethod = resolved
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                SettingsSectionHeader(R.string.settings_section_startup)
            }
            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.PowerSettingsNew) },
                            headlineContent = { Text(stringResource(R.string.settings_start_on_boot)) },
                            switchState = startOnBoot,
                            onSwitchChange =
                                { checked ->
                                    if (checked && needsBatteryPrompt(context)) {
                                        batteryPrompt = {
                                            ShizukuSettings.setStartOnBoot(context, true)
                                            startOnBoot = ShizukuSettings.getStartOnBoot(context)
                                        }
                                        startOnBoot = false
                                    } else {
                                        ShizukuSettings.setStartOnBoot(context, checked)
                                        startOnBoot = ShizukuSettings.getStartOnBoot(context)
                                    }
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.MonitorHeart) },
                            headlineContent = { Text(stringResource(R.string.settings_watchdog)) },
                            supportingContent = { Text(stringResource(R.string.settings_watchdog_summary)) },
                            switchState = watchdog,
                            onSwitchChange =
                                { checked ->
                                    if (checked && needsBatteryPrompt(context)) {
                                        batteryPrompt = {
                                            ShizukuSettings.setWatchdog(context, true)
                                            watchdog = true
                                        }
                                    } else {
                                        ShizukuSettings.setWatchdog(context, checked)
                                        watchdog = checked
                                    }
                                }
                        )
                    }
                    item {
                        // A switch whose whole effect is one write to the settings provider,
                        // so it is offered only while that write can be made - see
                        // writeSecureSettings above. On stays on offer, because a device that
                        // is already in the state has to be able to leave it.
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Usb) },
                            headlineContent = { Text(stringResource(R.string.settings_auto_disable_usb_debugging)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (writeSecureSettings)
                                            R.string.settings_auto_disable_usb_debugging_summary
                                        else R.string.settings_needs_write_secure_settings
                                    )
                                )
                            },
                            switchState = autoDisableUsb,
                            switchEnabled = autoDisableUsb || writeSecureSettings,
                            onSwitchChange =
                                {
                                    ShizukuSettings.getPreferences().edit()
                                        .putBoolean(ShizukuSettings.Keys.KEY_AUTO_DISABLE_USB_DEBUGGING, it).apply()
                                    autoDisableUsb = it
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Wifi) },
                            headlineContent = { Text(stringResource(R.string.settings_auto_disable_wireless_debugging)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        when {
                                            forceWireless ->
                                                R.string.settings_unavailable_while_forcing_wireless

                                            !writeSecureSettings ->
                                                R.string.settings_needs_write_secure_settings

                                            else -> R.string.settings_auto_disable_wireless_debugging_summary
                                        }
                                    )
                                )
                            },
                            // Turning wireless debugging off when Shizuku stops is the one
                            // thing the experiment cannot survive, so this row is not offered
                            // while it is on rather than flipped and then flipped back.
                            switchState = autoDisableWireless && !forceWireless,
                            switchEnabled =
                                !forceWireless && (autoDisableWireless || writeSecureSettings),
                            onSwitchChange =
                                {
                                    ShizukuSettings.getPreferences().edit()
                                        .putBoolean(ShizukuSettings.Keys.KEY_AUTO_DISABLE_WIRELESS_DEBUGGING, it).apply()
                                    autoDisableWireless = it
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.RocketLaunch) },
                            headlineContent = { Text(stringResource(R.string.settings_start_method)) },
                            supportingContent = {
                                // Say why the row no longer reads Root, rather than changing
                                // the setting behind the user's back without a word.
                                Text(
                                    stringResource(startMethodLabelRes(startMethod)) +
                                        if (rootMethodDropped) {
                                            "\n" + stringResource(R.string.settings_start_method_root_unavailable)
                                        } else {
                                            ""
                                        }
                                )
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { startMethodDialog = true }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.DeveloperMode) },
                            headlineContent = { Text(stringResource(R.string.settings_adb_without_developer_options)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (writeSecureSettings)
                                            R.string.settings_adb_without_developer_options_summary
                                        else R.string.settings_needs_write_secure_settings
                                    )
                                )
                            },
                            switchState = adbWithoutDeveloperOptions,
                            switchEnabled = adbWithoutDeveloperOptions || writeSecureSettings,
                            onSwitchChange =
                                { checked ->
                                    // Hiding Developer options is not something to do to
                                    // someone on a stray tap, so it asks first, with what
                                    // it costs spelled out. Putting it back is the safe
                                    // direction and needs no ceremony.
                                    if (checked) {
                                        adbWithoutDeveloperOptionsPrompt = true
                                    } else {
                                        setAdbWithoutDeveloperOptions(false)
                                    }
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.NetworkCheck) },
                            headlineContent = { Text(stringResource(R.string.settings_wait_for_wifi)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (forceWireless) R.string.settings_unavailable_while_forcing_wireless
                                        else R.string.settings_wait_for_wifi_summary
                                    )
                                )
                            },
                            // Waiting for a network is what the experiment starts without,
                            // so asking for both at once is not a state worth offering.
                            switchState = waitForWifi && !forceWireless,
                            switchEnabled = !forceWireless,
                            onSwitchChange =
                                {
                                    ShizukuSettings.setWaitForWifi(it)
                                    waitForWifi = it
                                }
                        )
                    }
                    item {
                        // The experiment is a write to the settings provider, and a fresh
                        // install has no WRITE_SECURE_SETTINGS to make it with: switching it
                        // on there asks for a start that cannot happen, from a screen whose
                        // own warning is then the only way out. So it is offered only once
                        // the permission is there, which a running Shizuku grants itself - and
                        // a switch that is already on stays operable, because a device that
                        // reached that state has to be able to leave it.
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.WifiTethering) },
                            headlineContent = { Text(stringResource(R.string.settings_force_wireless_debugging)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (writeSecureSettings)
                                            R.string.settings_force_wireless_debugging_summary
                                        else R.string.settings_needs_write_secure_settings
                                    )
                                )
                            },
                            switchState = forceWireless,
                            // On stays on offer so it can be turned back off: the state it
                            // describes is the one that cannot start anything.
                            switchEnabled = forceWireless || writeSecureSettings,
                            onSwitchChange =
                                { checked ->
                                    // The two settings this one cannot work with are turned
                                    // off along with it, and both are rows in this same
                                    // list, so nothing moves out of sight: waiting for a
                                    // network is the thing the attempt is trying to start
                                    // without, and turning wireless debugging off when
                                    // Shizuku stops would undo it on the way out.
                                    if (checked) {
                                        ShizukuSettings.setWaitForWifi(false)
                                        waitForWifi = false
                                        ShizukuSettings.getPreferences().edit()
                                            .putBoolean(
                                                ShizukuSettings.Keys.KEY_AUTO_DISABLE_WIRELESS_DEBUGGING,
                                                false
                                            ).apply()
                                        autoDisableWireless = false
                                    }
                                    ShizukuSettings.setForceWirelessDebugging(checked)
                                    forceWireless = checked

                                    // It is a start method as well as a setting, so switching
                                    // it on makes that the default and switching it off takes
                                    // the default back to plain wireless debugging. The two
                                    // cannot then disagree about how a start should work, and
                                    // nothing is left pointing at a method that is no longer
                                    // offered.
                                    if (checked) {
                                        ShizukuSettings.setStartMethod(
                                            ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK
                                        )
                                        startMethod =
                                            ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK
                                    } else if (startMethod ==
                                        ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK
                                    ) {
                                        ShizukuSettings.setStartMethod(
                                            ShizukuSettings.StartMethod.WIRELESS
                                        )
                                        startMethod = ShizukuSettings.StartMethod.WIRELESS
                                    }
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.AdminPanelSettings) },
                            headlineContent = { Text(stringResource(R.string.settings_system_start_method)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (systemStartMethod == ShizukuSettings.SYSTEM_START_EXPLOIT)
                                            R.string.settings_system_start_method_exploit
                                        else R.string.settings_system_start_method_custom
                                    )
                                )
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { systemStartDialog = true }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(R.string.settings_section_wireless)
            }
            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Link) },
                            headlineContent = { Text(stringResource(R.string.settings_tcp_mode)) },
                            supportingContent = { Text(stringResource(R.string.settings_tcp_mode_summary)) },
                            switchState = tcpMode,
                            onSwitchChange =
                                { checked ->
                                    when {
                                        !checked && EnvironmentUtils.getAdbTcpPort() > 0 ->
                                            closeTcpDialog = true

                                        ShizukuStateMachine.isRunning() &&
                                            needsRestart(ShizukuSettings.Keys.KEY_TCP_MODE, checked) -> {
                                            restartAction = {
                                                ShizukuSettings.setTcpMode(checked)
                                                tcpMode = checked
                                            }
                                            restartWifiNote = true
                                        }

                                        else -> {
                                            ShizukuSettings.setTcpMode(checked)
                                            tcpMode = checked
                                        }
                                    }
                                }
                        )
                    }
                    if (persistAdbPort || canPersistAdbPort) item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.PushPin) },
                            headlineContent = { Text(stringResource(R.string.settings_persist_adb_port)) },
                            supportingContent = {
                                Text(stringResource(R.string.settings_persist_adb_port_summary))
                            },
                            switchState = persistAdbPort,
                            onSwitchChange =
                                { checked ->
                                    // The write is the platform's to allow or refuse, so the
                                    // switch follows what actually happened rather than
                                    // what was asked for.
                                    scope.launch {
                                        val outcome = AdbPortPersistence.apply(checked)
                                        ShizukuSettings.setPersistAdbPort(
                                            outcome == AdbPortPersistence.Outcome.APPLIED
                                        )
                                        persistAdbPort = ShizukuSettings.getPersistAdbPort()
                                        val message = when (outcome) {
                                            AdbPortPersistence.Outcome.APPLIED ->
                                                R.string.settings_persist_adb_port_ok

                                            AdbPortPersistence.Outcome.REFUSED ->
                                                R.string.settings_persist_adb_port_refused

                                            AdbPortPersistence.Outcome.UNAVAILABLE ->
                                                R.string.settings_persist_adb_port_unavailable
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Numbers) },
                            headlineContent = { Text(stringResource(R.string.settings_tcp_port)) },
                            supportingContent = { Text(tcpPort) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { tcpPortDialog = true }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Devices) },
                            headlineContent = { Text(stringResource(R.string.settings_legacy_pairing)) },
                            supportingContent = { Text(stringResource(R.string.settings_legacy_pairing_summary)) },
                            switchState = legacyPairing,
                            onSwitchChange =
                                {
                                    ShizukuSettings.getPreferences().edit()
                                        .putBoolean(ShizukuSettings.Keys.KEY_LEGACY_PAIRING, it).apply()
                                    legacyPairing = it
                                }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(R.string.settings_section_tools)
            }
            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    // Battery optimisation moved onto the permissions page: it answers the
                    // same question as the rest of that page (may this app do its job?)
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.VerifiedUser) },
                            headlineContent = { Text(stringResource(R.string.settings_permissions)) },
                            supportingContent = { Text(stringResource(R.string.settings_permissions_summary)) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { onOpenDetail(Detail.PERMISSIONS) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.VisibilityOff) },
                            headlineContent = { Text(stringResource(R.string.tools_stealth)) },
                            supportingContent = { Text(stringResource(R.string.stealth_description)) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { onOpenDetail(Detail.STEALTH) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Terminal) },
                            headlineContent = { Text(stringResource(R.string.tools_terminal)) },
                            supportingContent = { Text(stringResource(R.string.tools_terminal_summary)) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { onOpenDetail(Detail.TERMINAL) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Bolt) },
                            headlineContent = { Text(stringResource(R.string.intents_title)) },
                            supportingContent = { Text(stringResource(R.string.intents_description)) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { onOpenDetail(Detail.INTENTS) }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(R.string.settings_section_appearance)
            }
            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.DarkMode) },
                            headlineContent = { Text(stringResource(R.string.settings_theme)) },
                            supportingContent = {
                                Text(
                                    when (nightMode) {
                                        AppCompatDelegate.MODE_NIGHT_NO -> stringResource(R.string.settings_theme_light)
                                        AppCompatDelegate.MODE_NIGHT_YES -> stringResource(R.string.settings_theme_dark)
                                        else -> stringResource(R.string.settings_theme_system)
                                    }
                                )
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { themeDialog = true }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Palette) },
                            headlineContent = { Text(stringResource(R.string.settings_use_system_color)) },
                            supportingContent = {
                                Text(stringResource(R.string.settings_use_system_color_summary))
                            },
                            switchState = useSystemColor,
                            onSwitchChange =
                                {
                                    ShizukuSettings.getPreferences().edit()
                                        .putBoolean(ShizukuSettings.Keys.KEY_USE_SYSTEM_COLOR, it).apply()
                                    useSystemColor = it
                                    ThemeState.refresh()
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Contrast) },
                            headlineContent = { Text(stringResource(R.string.settings_black_night_theme)) },
                            supportingContent = { Text(stringResource(R.string.settings_black_night_theme_summary)) },
                            switchState = blackNight,
                            onSwitchChange =
                                {
                                    ShizukuSettings.getPreferences().edit()
                                        .putBoolean(ShizukuSettings.Keys.KEY_BLACK_NIGHT_THEME, it).apply()
                                    blackNight = it
                                    ThemeState.refresh()
                                }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Translate) },
                            headlineContent = { Text(stringResource(R.string.settings_language)) },
                            // The row says which language is in force, because the choice is now
                            // made here and there is nowhere else to look it up.
                            supportingContent = {
                                Text(
                                    if (language == AppLocale.SYSTEM) stringResource(R.string.settings_language_system)
                                    else AppLocale.label(language)
                                )
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { languageDialog = true }
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader(R.string.settings_section_about)
            }
            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Info) },
                            headlineContent = { Text(stringResource(R.string.app_name)) },
                            supportingContent = { Text(BuildConfig.VERSION_NAME) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Code) },
                            headlineContent = { Text(stringResource(R.string.about_package)) },
                            supportingContent = { Text(context.packageName) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.SystemUpdate) },
                            headlineContent = { Text(stringResource(R.string.check_for_updates)) },
                            supportingContent = {
                                Text(
                                    when (updateMode) {
                                        ShizukuSettings.UpdateMode.OFF -> stringResource(R.string.off)
                                        ShizukuSettings.UpdateMode.BETA -> stringResource(R.string.settings_update_beta)
                                        else -> stringResource(R.string.settings_update_stable)
                                    }
                                )
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { updateDialog = true }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.MenuBook) },
                            headlineContent = { Text(stringResource(R.string.settings_help)) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = {
                                CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.help_url))
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.BugReport) },
                            headlineContent = { Text(stringResource(R.string.settings_report_bug)) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = {
                                context.startActivity(Intent(context, BugReportDialogActivity::class.java))
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            leadingContent = { SettingsIcon(Icons.Outlined.Coffee) },
                            headlineContent = {
                                Text(stringResource(R.string.settings_support_coffee))
                            },
                            // Says what the tap does, because the row is a link out of the app
                            // rather than another screen of it.
                            supportingContent = {
                                Text(stringResource(R.string.settings_support_coffee_note))
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
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
            // Nothing to credit is better than an empty card: a build with no snapshot at all - an
            // asset that failed to make it in - simply has no credits section.
            if (contributors.isNotEmpty()) {
                // The heading and the card are separate items so the list's own spacing sits
                // between them; inside one item the badge came to rest on the card's edge.
                item {
                    SettingsSectionHeader(
                        titleRes = R.string.settings_contributors,
                        badge = contributors.size.toString()
                    )
                }
                item {
                    SegmentedCard {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            ContributorWall(
                                contributors = contributors,
                                onSelect = { selectedContributor = it }
                            )
                            Text(
                                text = stringResource(R.string.settings_contributors_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    selectedContributor?.let { contributor ->
        ContributorDetailsDialog(
            contributor = contributor,
            onDismiss = { selectedContributor = null }
        )
    }

    restartAction?.let { action ->
        AlertDialog(
            onDismissRequest = { restartAction = null },
            title = { Text(stringResource(R.string.settings_restart_dialog_title)) },
            text = {
                // Two paragraphs, the second in bold. The string carries <br><br><b> for the
                // View UI's sake, and Compose does not parse markup: it drew the tags and the
                // sentence arrived glued to the one above it. The emphasis is the point of the
                // second string, so it is put on by hand instead of being stripped, and the
                // break between them is this column's.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_restart_dialog_message))
                    if (restartWifiNote) {
                        Text(
                            stringResource(R.string.settings_restart_dialog_message_wifi_required)
                                .stripHtmlTags(),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                PillButton(onClick = {
                    action()
                    restartAction = null
                    ShizukuReceiverStarter.start(context, forceStart = true, userInitiated = true)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { restartAction = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (closeTcpDialog) {
        AlertDialog(
            onDismissRequest = { closeTcpDialog = false },
            title = { Text(stringResource(android.R.string.dialog_alert_title)) },
            text = { Text(stringResource(R.string.settings_tcp_mode_dialog_close_port)) },
            confirmButton = {
                PillButton(onClick = {
                    closeTcpDialog = false
                    scope.launch {
                        val port = EnvironmentUtils.getAdbTcpPort()
                        if (port > 0) AdbStarter.stopTcp(context, port)
                        ShizukuSettings.setTcpMode(false)
                        tcpMode = false
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { closeTcpDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (adbWithoutDeveloperOptionsPrompt) {
        AlertDialog(
            onDismissRequest = { adbWithoutDeveloperOptionsPrompt = false },
            title = {
                Text(stringResource(R.string.settings_adb_without_developer_options_confirm_title))
            },
            text = {
                Text(stringResource(R.string.settings_adb_without_developer_options_confirm_message))
            },
            confirmButton = {
                PillButton(onClick = {
                    adbWithoutDeveloperOptionsPrompt = false
                    setAdbWithoutDeveloperOptions(true)
                }) { Text(stringResource(R.string.settings_adb_without_developer_options_confirm)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { adbWithoutDeveloperOptionsPrompt = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    batteryPrompt?.let { action ->
        AlertDialog(
            onDismissRequest = { batteryPrompt = null },
            title = { Text(stringResource(R.string.tools_battery)) },
            text = { Text(stringResource(R.string.snackbar_battery_optimization_settings)) },
            confirmButton = {
                PillButton(onClick = {
                    SettingsHelper.requestIgnoreBatteryOptimizationsPrivileged(context)
                    action()
                    batteryPrompt = null
                }) { Text(stringResource(R.string.snackbar_action_fix)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { batteryPrompt = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (themeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = listOf(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString() to stringResource(R.string.settings_theme_system),
                AppCompatDelegate.MODE_NIGHT_NO.toString() to stringResource(R.string.settings_theme_light),
                AppCompatDelegate.MODE_NIGHT_YES.toString() to stringResource(R.string.settings_theme_dark),
            ),
            selected = nightMode.toString(),
            onDismiss = { themeDialog = false },
            onSelect = {
                val value = it.toIntOrNull() ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ShizukuSettings.getPreferences().edit()
                    .putInt(ShizukuSettings.Keys.KEY_NIGHT_MODE, value).apply()
                AppCompatDelegate.setDefaultNightMode(value)
                nightMode = value
                ThemeState.refresh()
                themeDialog = false
            }
        )
    }

    if (languageDialog) {
        val systemLabel = stringResource(R.string.settings_language_system)
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = ShizukuLocales.LOCALES.map { tag ->
                tag to if (tag == AppLocale.SYSTEM) systemLabel else AppLocale.label(tag)
            },
            selected = language,
            onDismiss = { languageDialog = false },
            onSelect = { tag ->
                languageDialog = false
                language = tag
                AppLocale.select(context, tag)
                // Android 13+ rebuilds the activities itself when the per-app locale changes.
                // Below that nothing does, so the context wrapped in attachBaseContext would go
                // on being the old language until the next launch.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    AppLocale.activityOf(context)?.recreate()
                }
            }
        )
    }

    if (tcpPortDialog) {
        var draft by remember { mutableStateOf(tcpPort) }
        AlertDialog(
            onDismissRequest = { tcpPortDialog = false },
            title = { Text(stringResource(R.string.settings_tcp_port)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { s -> draft = s.filter { it.isDigit() }.take(5) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )
            },
            confirmButton = {
                PillButton(onClick = {
                    val value = draft.toIntOrNull()?.takeIf { it in 1..65535 }
                    tcpPortDialog = false
                    if (value != null) {
                        if (ShizukuStateMachine.isRunning() &&
                            needsRestart(ShizukuSettings.Keys.KEY_TCP_PORT, value)
                        ) {
                            restartAction = {
                                ShizukuSettings.setTcpPort(value)
                                tcpPort = value.toString()
                            }
                            restartWifiNote = false
                        } else {
                            ShizukuSettings.setTcpPort(value)
                            tcpPort = value.toString()
                        }
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { tcpPortDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    if (startMethodDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_start_method),
            options = buildList {
                add(ShizukuSettings.StartMethod.WIRELESS.toString() to stringResource(R.string.start_method_wireless))
                add(ShizukuSettings.StartMethod.USB.toString() to stringResource(R.string.start_method_usb))
                add(ShizukuSettings.StartMethod.SYSTEM.toString() to stringResource(R.string.start_method_system))
                // Offered only where it can work the same rule the home screen's root
                // row follows.
                if (rootAvailable) {
                    add(ShizukuSettings.StartMethod.ROOT.toString() to stringResource(R.string.start_method_root))
                }
                // Offered only while the experiment behind it is on, which is what it is: the
                // wireless start with that fight switched on. Turning the experiment off takes
                // the default back to wireless debugging, so this cannot be chosen and then
                // left meaning something else.
                if (forceWireless) {
                    add(
                        ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK.toString() to
                            stringResource(R.string.start_method_wireless_no_network)
                    )
                }
            },
            selected = startMethod.toString(),
            onDismiss = { startMethodDialog = false },
            onSelect = {
                val value = it.toIntOrNull() ?: ShizukuSettings.StartMethod.WIRELESS
                ShizukuSettings.setStartMethod(value)
                startMethod = value
                startMethodDialog = false
            }
        )
    }

    if (systemStartDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_system_start_method),
            options = listOf(
                ShizukuSettings.SYSTEM_START_EXPLOIT to stringResource(R.string.settings_system_start_method_exploit),
                ShizukuSettings.SYSTEM_START_CUSTOM to stringResource(R.string.settings_system_start_method_custom),
            ),
            selected = systemStartMethod,
            onDismiss = { systemStartDialog = false },
            onSelect = {
                ShizukuSettings.setSystemStartMethod(it)
                systemStartMethod = it
                systemStartDialog = false
            }
        )
    }

    if (updateDialog) {
        ChoiceDialog(
            title = stringResource(R.string.check_for_updates),
            options = listOf(
                ShizukuSettings.UpdateMode.OFF.toString() to stringResource(R.string.off),
                ShizukuSettings.UpdateMode.STABLE.toString() to stringResource(R.string.settings_update_stable),
                ShizukuSettings.UpdateMode.BETA.toString() to stringResource(R.string.settings_update_beta),
            ),
            selected = updateMode.toString(),
            onDismiss = { updateDialog = false },
            onSelect = {
                val value = it.toIntOrNull() ?: ShizukuSettings.UpdateMode.STABLE
                ShizukuSettings.getPreferences().edit()
                    .putInt(ShizukuSettings.Keys.KEY_UPDATE_MODE, value).apply()
                updateMode = value
                updateDialog = false
            },
            // Picking a mode only says when to look; this is the "look now" that
            // the About screen used to offer.
            confirmButton = {
                PillButton(onClick = {
                    updateDialog = false
                    scope.launch { runCatching { UpdateHelper.checkAndInstallUpdates() } }
                }) { Text(stringResource(R.string.check_for_updates_now)) }
            }
        )
    }
}

private fun needsRestart(setting: String, newValue: Any? = null): Boolean {
    val currentPort = EnvironmentUtils.getAdbTcpPort()
    return when (setting) {
        ShizukuSettings.Keys.KEY_TCP_MODE ->
            (currentPort > 0) != (newValue as? Boolean ?: ShizukuSettings.getTcpMode())

        ShizukuSettings.Keys.KEY_TCP_PORT ->
            currentPort > 0 && currentPort != (newValue as? Int ?: ShizukuSettings.getTcpPort())

        else -> false
    }
}

/** Section title above a settings card, so the list reads as deliberate groups. */
@Composable
private fun SettingsSectionHeader(@StringRes titleRes: Int, badge: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        // How many people the wall is holding, beside the title, so the number is read rather than
        // counted off the faces.
        badge?.let {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * The icon every settings row leads with.
 *
 * The word already says what the row is, so the icon is not there to be read: it is an
 * outline to run an eye down, which is what makes a long list of switches scannable. Tinted
 * as secondary content for the same reason, so the titles stay the first thing seen.
 */
@Composable
private fun SettingsIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp)
    )
}

/**
 * The credits to draw, and the fetch that keeps them current.
 *
 * The shipped snapshot is read synchronously, so the wall is never empty on the frame it first
 * appears; the published copy is fetched behind it - at most once a day - and the wall redraws if
 * it arrives. A failed fetch leaves the wall exactly as it was, which is why nothing here reports
 * an error: a credits screen is not worth interrupting anyone over.
 */
@Composable
private fun rememberContributors(): List<Contributor> {
    val context = LocalContext.current
    var contributors by remember(context) {
        mutableStateOf(ContributorCredits.initial(context))
    }
    LaunchedEffect(context) {
        ContributorCredits.refresh(context)?.let { contributors = it }
    }
    return contributors
}

private fun needsBatteryPrompt(context: android.content.Context): Boolean =
    !EnvironmentUtils.isTelevision() && !SettingsHelper.isIgnoringBatteryOptimizations(context)

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    confirmButton: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // The row is the radio and the tint, defined once for the whole app: a tick glued
            // to the front of a label was doing a radio button's job badly here - it shifted the
            // text along, said nothing to a screen reader, and left the choice looking like a row
            // of buttons.
            // The list is as long as the setting is: three themes do not need this, but the
            // language dialog has one entry per translation, and a plain Column silently cuts
            // the rest off with no way to reach them.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { (value, label) ->
                    ChoiceRow(
                        selected = value == selected,
                        label = label,
                        onClick = { onSelect(value) }
                    )
                }
            }
        },
        confirmButton = { confirmButton?.invoke() },
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
