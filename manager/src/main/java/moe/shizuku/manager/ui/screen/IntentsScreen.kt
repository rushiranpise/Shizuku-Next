package moe.shizuku.manager.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem
import rikka.core.util.ClipboardUtils

/**
 * The package as it is now, not as it was built.
 *
 * These are the commands the screen asks people to paste into their automation app, and a hidden
 * copy answers to its own name: showing the build-time one would hand out commands that address a
 * package which is not installed.
 */
private val PACKAGE: String get() = ShizukuApplication.appContext.packageName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(ShizukuSettings.getAuthToken()) }
    var requireToken by remember { mutableStateOf(ShizukuSettings.getRequireIntentToken()) }

    fun copy(label: String, text: String) {
        if (ClipboardUtils.put(context, text)) {
            Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.intents_title)) },
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
            item {
                Text(
                    text = stringResource(R.string.intents_description),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.intents_require_token)) },
                            supportingContent = {
                                Text(stringResource(R.string.intents_require_token_summary))
                            },
                            switchState = requireToken,
                            onSwitchChange = { checked ->
                                requireToken = checked
                                ShizukuSettings.setRequireIntentToken(checked)
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.intents_token)) },
                            supportingContent = {
                                Text(token, fontFamily = FontFamily.Monospace)
                            },
                            trailingContent = {
                                PillButtonQuiet(onClick = { copy("Copied", token) }) {
                                    Text(stringResource(R.string.intents_copy))
                                }
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.intents_regenerate)) },
                            trailingContent = {
                                PillButtonQuiet(onClick = {
                                    token = ShizukuSettings.generateAuthToken()
                                }) { Text(stringResource(R.string.intents_regenerate)) }
                            }
                        )
                    }
                    // The one broadcast this app sends rather than takes, and the reason it is
                    // here at all: an automation that reacts to Shizuku stopping has to match this
                    // action, and nothing in the app said so (reported while answering issue #80).
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.intents_status_broadcast)) },
                            supportingContent = {
                                Column {
                                    Text("$PACKAGE.SHIZUKU_CHANGED", fontFamily = FontFamily.Monospace)
                                    Text(stringResource(R.string.intents_status_broadcast_extra))
                                }
                            },
                            trailingContent = {
                                PillButtonQuiet(onClick = { copy("Copied", "$PACKAGE.SHIZUKU_CHANGED") }) {
                                    Text(stringResource(R.string.intents_copy))
                                }
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.intents_adb_command)) },
                            supportingContent = {
                                Text(Starter.adbCommand, fontFamily = FontFamily.Monospace)
                            },
                            trailingContent = {
                                PillButtonQuiet(onClick = { copy("Copied", Starter.adbCommand) }) {
                                    Text(stringResource(R.string.intents_copy))
                                }
                            }
                        )
                    }
                }
            }

            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        CopyRow(
                            label = stringResource(R.string.intents_start),
                            value = broadcast("$PACKAGE.START", token.takeIf { requireToken }),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CopyRow(
                            label = stringResource(R.string.intents_stop),
                            value = broadcast("$PACKAGE.STOP", token.takeIf { requireToken }),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CopyRow(
                            label = stringResource(R.string.intents_watchdog_on),
                            value = broadcast("$PACKAGE.WATCHDOG_ON", null),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CopyRow(
                            label = stringResource(R.string.intents_watchdog_off),
                            value = broadcast("$PACKAGE.WATCHDOG_OFF", null),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CopyRow(
                            label = stringResource(R.string.intents_watchdog_toggle),
                            value = broadcast("$PACKAGE.WATCHDOG_TOGGLE", null),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                }
            }

            // The same five broadcasts as values rather than as commands: an automation app asks
            // for an action and a package separately, and until now the screen only offered the
            // whole `am` command, which meant retyping both by hand from a line that cannot be
            // split reliably on a phone (issue #121).
            item {
                Text(
                    text = stringResource(R.string.intents_values),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        CopyRow(
                            label = stringResource(R.string.intents_package),
                            value = PACKAGE,
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    actions.forEach { (label, action) ->
                        item {
                            CopyRow(
                                label = stringResource(
                                    R.string.intents_action_for,
                                    stringResource(label)
                                ),
                                value = "$PACKAGE.$action",
                                onCopy = { copy("Copied", it) }
                            )
                        }
                    }
                }
            }
            }

            ScrollToTopButton(
                listState = listState,
                bottomPadding = 16.dp,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit) {
    SegmentedListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value, fontFamily = FontFamily.Monospace) },
        trailingContent = {
            PillButtonQuiet(onClick = { onCopy(value) }) {
                Text(stringResource(R.string.intents_copy))
            }
        }
    )
}

/**
 * The five broadcasts, as the action suffixes an automation app wants.
 *
 * Kept beside the commands rather than derived from them: a command is a line to paste, and
 * the action inside it is one field of somebody else's form.
 */
private val actions = listOf(
    R.string.intents_start to "START",
    R.string.intents_stop to "STOP",
    R.string.intents_watchdog_on to "WATCHDOG_ON",
    R.string.intents_watchdog_off to "WATCHDOG_OFF",
    R.string.intents_watchdog_toggle to "WATCHDOG_TOGGLE"
)

/**
 * The command to paste, with the package named.
 *
 * `am broadcast` without `-p` delivers the action to every app that declares it, and the
 * broadcast is only addressed to this one: a hidden copy of the app answers to its own name, and
 * pointing at a package that is not installed is the same as not pointing at anything at all.
 */
private fun broadcast(action: String, token: String?): String = buildString {
    append("adb shell am broadcast -a ").append(action)
    append(" -p ").append(PACKAGE)
    if (token != null) {
        append(" --es auth ").append(token)
    }
}
