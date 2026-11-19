package moe.shizuku.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.shizuku.manager.ui.theme.LocalWatch

/**
 * The app's alert dialog: Material's on a phone, a round-safe panel on a watch.
 *
 * A watch is 300dp wide at most, and Material's dialog is measured for a phone: the title at
 * headline size, a body that trusts the screen width, and buttons that can fall off the bottom.
 * The watch panel is inset far enough that its corners sit inside the circle, keeps the title
 * and the buttons on screen, and scrolls only the body.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    if (!LocalWatch.current) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties
        )
        return
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // A Dialog window measures to its content, so the height is explicit. The panel is the
        // screen, with its content inset rather than squeezed by a small panel: a watch has no
        // room for a title, a body and two buttons inside a phone-sized dialog.
        val watchHeight = (LocalConfiguration.current.screenHeightDp - 16).dp
        Surface(
            modifier = Modifier.fillMaxWidth().height(watchHeight),
            color = containerColor,
            tonalElevation = tonalElevation
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                if (icon != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                            icon()
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (title != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                                title()
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    CompositionLocalProvider(LocalContentColor provides textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            text?.invoke()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        8.dp,
                        Alignment.CenterHorizontally
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    confirmButton()
                    dismissButton?.invoke()
                }
            }
        }
    }
}
