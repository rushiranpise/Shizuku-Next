package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.Hiding
import moe.shizuku.manager.manage.HidingGrants
import moe.shizuku.manager.manage.Signal
import moe.shizuku.manager.service.HidingWatchService
import moe.shizuku.manager.ui.Detail
import moe.shizuku.manager.ui.component.ExpressiveSwitch
import moe.shizuku.manager.ui.component.FeaturePlateIcon
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard

/**
 * One mode, and the three things worth knowing about it from here.
 *
 * What is not here is the state of the hiding itself. Whether a setting is being lied about right
 * now is a question about the app in the foreground, answered and un-answered as apps come and go,
 * and a row that said "hidden" from the last read would be wrong within the second. The one state
 * this screen is the right place for is the mode's own switch, which nothing else moves.
 */
private data class ModeRow(
    val signal: Signal,
    val enabled: Boolean,
    val listed: Int,
    /**
     * Whether applying this mode would take the shell that applies it down with it, which is what
     * hiding USB or wireless debugging does while Shizuku is running over that same wire.
     */
    val carriesSession: Boolean
)

/**
 * All of hiding on one screen: the modes, whether each is on, and what each covers.
 *
 * Six of these were six tiles in the grid and six screens behind them, each a list with a switch
 * per app over a different setting. That is the right screen for working one mode out, and the
 * wrong one for the question a person actually arrives with - what is hiding, and is it on - which
 * took six screens to answer and could not be answered at all while the settings were being
 * flipped. So the modes are listed here with their own switches, and each row is still the way
 * into its list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HidingScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenDetail: (Detail) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<ModeRow>>(emptyList()) }
    var restartOnOpen by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    // Read whenever this screen is come back to. The switches here are not the only place a mode
    // is turned off - its own list has one in its header - so coming back is exactly when the two
    // are about to be compared.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    LaunchedEffect(refresh) {
        restartOnOpen = Hiding.isRestartOnOpenEnabled()
        rows = withContext(Dispatchers.IO) {
            // The first screen most people reach hiding from, so the two grants it needs are asked
            // for here and not left to a settings page somebody has to go and find. Nothing is
            // shown for it: there is no decision in it.
            HidingGrants.ensureQuietly()

            Signal.entries.map { signal ->
                ModeRow(
                    signal = signal,
                    enabled = Hiding.isSignalEnabled(signal),
                    listed = Hiding.appsFor(signal).size,
                    carriesSession = Hiding.carriesSession(signal)
                )
            }
        }
    }

    /**
     * Flips one mode, then reads it back.
     *
     * The switch moves with the tap and the read is what settles it, the same trade the lists make:
     * a mode switched on for a phone whose shell is not up hides nothing, and the read is what puts
     * the switch back where it belongs rather than showing what did not happen.
     */
    fun setEnabled(signal: Signal, enabled: Boolean) {
        rows = rows.map { row -> if (row.signal == signal) row.copy(enabled = enabled) else row }

        scope.launch {
            withContext(Dispatchers.IO) {
                Hiding.setSignalEnabled(signal, enabled)
                // The watch starts and stops with the modes, so it is told to look again: turning
                // the last one off leaves it nothing to do.
                HidingWatchService.refresh(context)
            }
            refresh++
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_hiding)) },
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
            items(rows, key = { it.signal.name }) { row ->
                // The icon and the route both come from the feature the signal belongs to, which
                // is where the two are decided; a signal with no feature has no list to open.
                val feature = AppToggleFeature.entries.firstOrNull { it.signal == row.signal }

                SegmentedCard {
                    ListItem(
                        modifier = Modifier.clickable(enabled = feature != null) {
                            feature?.let { Detail.of(it) }?.let(onOpenDetail)
                        },
                        leadingContent = {
                            feature?.let { toggle -> FeaturePlateIcon(toggle.icon) }
                        },
                        headlineContent = { Text(stringResource(row.signal.labelRes)) },
                        supportingContent = {
                            Column {
                                // The mode's own answer while it is off, and what it covers while
                                // it is on. The count is of the apps that name it, which is what
                                // the list behind the row is made of.
                                Text(
                                    text = if (row.enabled) {
                                        stringResource(R.string.hiding_hidden_count, row.listed)
                                    } else {
                                        stringResource(R.string.hiding_gate_disabled)
                                    }
                                )

                                // Said here as well as on the list, because a switch that turns a
                                // mode on and appears to do nothing is the one thing this screen
                                // must not do: over this wire there is nothing it can do.
                                if (row.carriesSession) {
                                    Text(
                                        text = stringResource(
                                            if (row.signal == Signal.WIRELESS_DEBUGGING) {
                                                R.string.hiding_carries_session_wireless
                                            } else {
                                                R.string.hiding_carries_session_usb
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            ExpressiveSwitch(
                                checked = row.enabled,
                                onCheckedChange = { setEnabled(row.signal, it) }
                            )
                        }
                    )
                }
            }

                item {
                    SegmentedCard {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.hiding_restart_on_open)) },
                            supportingContent = {
                                Text(stringResource(R.string.hiding_restart_on_open_summary))
                            },
                            trailingContent = {
                                ExpressiveSwitch(
                                    checked = restartOnOpen,
                                    onCheckedChange = { checked ->
                                        restartOnOpen = checked
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                Hiding.setRestartOnOpen(checked)
                                            }
                                            refresh++
                                        }
                                    }
                                )
                            }
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
