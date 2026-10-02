package moe.shizuku.manager.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.theme.LocalAmoledTheme

/*
 * The two buttons this app puts a word in.
 *
 * A control whose whole content is a word is a pill here rather than a bare label. Material reads
 * such a label as tertiary next to a filled button, which is wrong for the way this app uses them:
 * a dialog's two choices are peers, and the pair below says which one is being asked for by filling
 * it and leaving the other on the surface. The shape is what makes them read as one row of the same
 * thing, so both are pills and neither is a link.
 *
 * They are sized for a dialog footer and a snackbar rather than for a page: a row of them has to fit
 * beside a title, and the default padding of a filled button is generous enough to push the third
 * one off the edge.
 */

/** The light pill: the action the screen is asking for. */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PillPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        content = content
    )
}

/**
 * The dark pill: every other action, and the same shape with the surface under it.
 *
 * On the pure black theme there is no lighter surface to put under it, so the pill is drawn with a
 * hairline outline instead - the same answer [SegmentedColumn] gives a card, which would otherwise
 * vanish into the page. The outline is handed to the button rather than laid over it, so that it
 * traces the pill: a border on the outside of a button draws around the box the button reserves for
 * the finger, which is a 48dp square of empty space, and the quiet pill came out visibly taller than
 * the filled one it stands beside.
 */
@Composable
fun PillButtonQuiet(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        colors = colors,
        border = if (LocalAmoledTheme.current) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        contentPadding = PillPadding,
        content = content
    )
}

private val PillPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
