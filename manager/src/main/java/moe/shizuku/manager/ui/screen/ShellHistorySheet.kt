package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.R
import moe.shizuku.manager.shell.ShellHistory

/**
 * What has been run, newest first.
 *
 * Deliberately the same shape as the bookmarks sheet: a command is filled in by tapping it
 * and run by the button beside it, because filling is the safe half of the two and the one
 * a long command is usually wanted for. Clearing is in the header rather than per row, since
 * the list is a record of what happened rather than a collection being curated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellHistorySheet(
    entries: List<ShellHistory.Entry>,
    onFill: (String) -> Unit,
    onRun: (String) -> Unit,
    onForget: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.shell_history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (entries.isNotEmpty()) {
                PillButtonQuiet(onClick = onClear) {
                    Text(stringResource(R.string.shell_history_clear))
                }
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.shell_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )
            return@ModalBottomSheet
        }

        LazyColumn(
            modifier = Modifier.heightIn(max = 460.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(entries, key = { it.command }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFill(entry.command) }
                        .padding(start = 24.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.command,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRun(entry.command) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.shell_history_run),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { onForget(entry.command) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.shell_history_forget),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
