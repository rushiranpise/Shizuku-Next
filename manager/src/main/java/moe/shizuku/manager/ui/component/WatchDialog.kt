package moe.shizuku.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.shizuku.manager.R

/**
 * A selection dialog that gives its list the whole watch screen instead of the few pixels an
 * AlertDialog leaves for text. The caller supplies the rows; the panel supplies the title, the
 * scrolling area and the action row.
 *
 * The title and the actions stay put: the old panel scrolled them with the rows, so a long list
 * took its own Cancel button off the screen. The title is centred because the top of a round
 * display is too narrow for a left-aligned heading.
 */
@Composable
fun WatchDialogPanel(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val watchHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(watchHeight),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (scrollable) {
                                Modifier.verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            }
                        )
                ) {
                    content()
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    confirmButton?.invoke()
                    if (dismissButton != null) {
                        dismissButton()
                    } else {
                        PillButtonQuiet(onClick = onDismiss) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}
