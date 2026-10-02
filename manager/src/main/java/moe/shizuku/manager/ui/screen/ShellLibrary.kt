package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.R
import moe.shizuku.manager.shell.LibraryCommand
import moe.shizuku.manager.shell.ShellCommands

/**
 * The library, as a sheet: a search field over every command the shell knows, each with what it
 * does underneath it.
 *
 * The rows are the command and its description, and nothing else a filter row by tag would be
 * a second way to do what the search field already does, and the tags are what the search
 * matches, so typing "battery" finds them whether or not the word is in the command.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySheet(
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (LibraryCommand) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val matches = ShellCommands.search(query)
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.shell_library_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true
            )

            if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.shell_library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(matches, key = { it.command }) { entry ->
                        ListItem(
                            modifier = Modifier.clickable { onPick(entry) },
                            headlineContent = {
                                Text(
                                    entry.command,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            supportingContent = {
                                Text(entry.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Asks for whatever the chosen command has left as `<placeholders>`.
 *
 * One field per variable, in the order they appear, and the ones that name an app get a button
 * that opens the app list because the value of `<package>` is a package name nobody types, and
 * the shell already knows every one on the device. What comes back is a finished command put in
 * the input, not a run: the point of filling it in is to read it first.
 */
@Composable
internal fun VariablesDialog(
    command: String,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    onPickPackage: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val variables = ShellCommands.variablesOf(command)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_variables_title)) },
        text = {
            Column {
                Text(
                    ShellCommands.filled(command, values),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                variables.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = values[name].orEmpty(),
                            onValueChange = { onValueChange(name, it) },
                            modifier = Modifier.weight(1f),
                            label = { Text(name) },
                            singleLine = true
                        )
                        if (ShellCommands.isPackageVariable(name)) {
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(onClick = { onPickPackage(name) }) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.shell_variables_pick_app)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            PillButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
