package moe.shizuku.manager.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem
import rikka.core.util.ClipboardUtils

private const val PACKAGE = "moe.shizuku.privileged.api"

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
                        CommandRow(
                            label = stringResource(R.string.intents_start),
                            command = broadcast("$PACKAGE.START", token.takeIf { requireToken }),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CommandRow(
                            label = stringResource(R.string.intents_stop),
                            command = broadcast("$PACKAGE.STOP", token.takeIf { requireToken }),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CommandRow(
                            label = stringResource(R.string.intents_watchdog_on),
                            command = broadcast("$PACKAGE.WATCHDOG_ON", null),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CommandRow(
                            label = stringResource(R.string.intents_watchdog_off),
                            command = broadcast("$PACKAGE.WATCHDOG_OFF", null),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                    item {
                        CommandRow(
                            label = stringResource(R.string.intents_watchdog_toggle),
                            command = broadcast("$PACKAGE.WATCHDOG_TOGGLE", null),
                            onCopy = { copy("Copied", it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(label: String, command: String, onCopy: (String) -> Unit) {
    SegmentedListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(command, fontFamily = FontFamily.Monospace) },
        trailingContent = {
            PillButtonQuiet(onClick = { onCopy(command) }) {
                Text(stringResource(R.string.intents_copy))
            }
        }
    )
}

private fun broadcast(action: String, token: String?): String =
    if (token != null) {
        "adb shell am broadcast -a $action --es auth $token"
    } else {
        "adb shell am broadcast -a $action"
    }
