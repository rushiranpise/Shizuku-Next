package moe.shizuku.manager.ui.screen

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import moe.shizuku.manager.ui.component.AppAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand

/**
 * One action the shell can take on the device's power state.
 *
 * Every command here is one the shell is allowed to issue, which is why this can exist without
 * root: `svc power` goes through PowerManager with the shell's REBOOT permission, and `input`
 * injects the sleep key the same way a keyboard would.
 */
private enum class PowerAction(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val command: String,
    @StringRes val confirmRes: Int? = null,
    /** Only root can send this one; the row is not shown where it cannot work. */
    val rootOnly: Boolean = false
) {
    REBOOT(
        R.string.power_reboot,
        R.string.power_reboot_summary,
        "svc power reboot",
        R.string.power_reboot_confirm
    ),

    RECOVERY(
        R.string.power_recovery,
        R.string.power_recovery_summary,
        "svc power reboot recovery",
        R.string.power_recovery_confirm
    ),

    BOOTLOADER(
        R.string.power_bootloader,
        R.string.power_bootloader_summary,
        "svc power reboot bootloader",
        R.string.power_bootloader_confirm
    ),

    POWER_OFF(
        R.string.power_off,
        R.string.power_off_summary,
        "svc power shutdown",
        R.string.power_off_confirm
    ),

    /**
     * Restarts the framework, not the kernel: every app and the system UI come back, the phone
     * does not. Root only, because `ctl.restart` is a property of init's own and the adb shell
     * identity is not allowed to write it.
     */
    SOFT_REBOOT(
        R.string.power_soft_reboot,
        R.string.power_soft_reboot_summary,
        "setprop ctl.restart zygote",
        R.string.power_soft_reboot_confirm,
        rootOnly = true
    ),

    /**
     * Kills the process behind the status bar, navigation bar and notification shade; the
     * platform starts it again on its own, so this is the least destructive way out of an
     * interface that has stopped drawing. Root only, for the same reason the soft reboot is:
     * one app cannot kill another app's process, and SystemUI is not this app.
     */
    RESTART_SYSTEM_UI(
        R.string.power_restart_system_ui,
        R.string.power_restart_system_ui_summary,
        "pkill -f com.android.systemui",
        rootOnly = true
    ),

    /** No confirmation: sleeping the display is undone by the power button. */
    SCREEN_OFF(
        R.string.power_screen_off,
        R.string.power_screen_off_summary,
        "input keyevent 223"
    )
}

/**
 * The power menu, for the things the device's own panel does not offer.
 *
 * A reboot is destructive in the way that matters here: everything unsaved is gone, and a
 * reboot to the bootloader leaves a phone that will not start until it is connected to a
 * computer. Those ask before they run. Turning the screen off does not, because the power
 * button is right there.
 *
 * Nothing is read back. A reboot ends the shell that asked for it, and `input` prints nothing,
 * so the only confirmation either can offer is the device doing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(ShizukuStateMachine.isRunning()) }
    var rooted by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<PowerAction?>(null) }

    // Asked of the binder rather than of the last known state: the screen can be opened after
    // the server went away and nothing noticed.
    fun refresh() {
        scope.launch {
            // The binder first, because it is the quick one and it decides most of the rows.
            // The root probe can spend seconds waiting on `su` where there is no root, and it
            // must not hold the rest of the screen behind it.
            running = withContext(Dispatchers.IO) {
                ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING
            }
            // Asked without asking: neither check raises a prompt, so opening a power menu
            // cannot put a root dialog over the phone. `isAppGrantedRoot` answers only for a
            // grant this process has already used, so the su binary Magisk, KernelSU and
            // APatch all install is the second half: it says the phone is rooted even before
            // this app has needed root, and the prompt, if one is due, comes when the row is
            // tapped rather than when the screen is opened.
            rooted = withContext(Dispatchers.IO) {
                runCatching {
                    Shell.isAppGrantedRoot() == true || File("/system/bin/su").exists()
                }.getOrDefault(false)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    fun send(action: PowerAction) {
        if (action.rootOnly) {
            if (!rooted) {
                Toast.makeText(context, R.string.power_needs_root, Toast.LENGTH_SHORT).show()
                return
            }
        } else if (!ShizukuStateMachine.isRunning()) {
            Toast.makeText(context, R.string.power_needs_shizuku, Toast.LENGTH_SHORT).show()
            return
        }

        Diag.info(AppConstants.TAG, "power: ${action.name}")
        scope.launch {
            val sent = withContext(Dispatchers.IO) {
                // No result to read: the reboot ends the shell that sent it, and the sleep key
                // has nothing to say. The device doing it is the confirmation.
                if (action.rootOnly) {
                    // Root's own shell rather than Shizuku's: `ctl.restart` belongs to init, and
                    // the shell identity Shizuku runs as is refused it. The one answer worth
                    // reading is whether root itself was refused, which is what a denial looks
                    // like here.
                    runCatching { Shell.cmd(action.command).exec() }
                        .onFailure { Diag.warn(AppConstants.TAG, "${action.name} failed", it) }
                        .getOrNull()
                        ?.isSuccess == true
                } else {
                    runShellCommand(action.command)
                    true
                }
            }
            // A successful soft reboot takes the app down before this is drawn, so this only
            // ever appears when the command did not run at all.
            if (!sent && action.rootOnly) {
                Toast.makeText(
                    context,
                    context.getString(R.string.power_failed, context.getString(action.titleRes)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    confirming?.let { action ->
        val message = action.confirmRes ?: return@let
        AppAlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.power_confirm_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        send(action)
                    }
                ) {
                    Text(stringResource(R.string.power_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_power)) },
            windowInsets = watchTopAppBarInsets(),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
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
                    top = 4.dp,
                    end = 16.dp,
                    bottom = bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(
                            when {
                                running -> R.string.power_description
                                rooted -> R.string.power_needs_shizuku_rooted
                                else -> R.string.power_needs_shizuku
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (running || rooted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                item {
                    SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                        PowerAction.entries.forEach { action ->
                            // A row that cannot work is not offered: the soft reboot is the one
                            // that needs root instead of Shizuku, and on a phone without root
                            // there is nothing to say about it.
                            if (action.rootOnly && !rooted) return@forEach
                            item {
                                SegmentedListItem(
                                    headlineContent = { Text(stringResource(action.titleRes)) },
                                    supportingContent = {
                                        Text(stringResource(action.summaryRes))
                                    },
                                    // A row that cannot act is shown as one: what the rest of
                                    // this screen needs is Shizuku being up, and a tap that does
                                    // nothing is worse than a row that says why.
                                    enabled = if (action.rootOnly) rooted else running,
                                    onClick = {
                                        if (action.confirmRes != null) confirming = action
                                        else send(action)
                                    }
                                )
                            }
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
