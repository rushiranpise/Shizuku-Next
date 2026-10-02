package moe.shizuku.manager.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.theme.LocalAmoledTheme

/**
 * A Material 3 list rendered as a single rounded container with dividers between
 * rows (KernelSU-style "segmented list").
 */
@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    content: @Composable SegmentedColumnScope.() -> Unit
) {
    // On the pure black theme the card and the page are the same colour, so the card
    // shape disappears and the grouping is lost. A hairline outline in the same tone
    // as the row dividers brings it back without lighting the page up.
    val outlined = LocalAmoledTheme.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (outlined) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.large
                    )
                } else {
                    Modifier
                }
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        val scope = SegmentedColumnScope()
        Column { scope.content() }
    }
}

/**
 * One rounded card around a single row, for lists where every entry is its own card 
 * the app list, where a flat run of rows reads as one undifferentiated block.
 *
 * Same surface and outline as [SegmentedColumn], so the two kinds of list sit together
 * without looking like they came from different apps.
 */
@Composable
fun SegmentedCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (LocalAmoledTheme.current) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.large
                    )
                } else {
                    Modifier
                }
            ),
        shape = MaterialTheme.shapes.large,
        color = color
    ) {
        content()
    }
}

class SegmentedColumnScope {
    private var count = 0

    @Composable
    fun item(content: @Composable () -> Unit) {
        if (count > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        count++
        content()
    }
}

@Composable
fun SegmentedListItem(
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    // Centres every slot on the row rather than letting Material place them. Material aligns
    // the leading and trailing slots with the headline as soon as the supporting text wraps,
    // which leaves an icon and a switch beside the first line of a two or three line row
    // instead of beside the row. Centring them means owning the layout, so the typography
    // Material would have supplied is provided below instead.
    centerSlots: Boolean = false,
    switchState: Boolean? = null,
    switchEnabled: Boolean = true,
    onSwitchChange: ((Boolean) -> Unit)? = null
) {
    val dimmed = if (enabled) modifier else modifier.alpha(0.45f)

    // A row that carries a switch toggles from anywhere on it, which is what a settings row
    // is, and the switch becomes decoration: hidden from accessibility, so the row is read
    // once with its state rather than as a row plus a second control saying the same thing.
    val toggle = if (switchState != null && onSwitchChange != null) {
        { onSwitchChange(!switchState) }
    } else null
    val rowClick = onClick ?: toggle
    val stateText = if (switchState != null) {
        stringResource(if (switchState) R.string.state_on else R.string.state_off)
    } else null

    val clickable = when {
        rowClick != null && stateText != null -> dimmed
            .clickable(enabled = enabled, onClick = rowClick)
            .semantics { stateDescription = stateText }

        rowClick != null -> dimmed.clickable(enabled = enabled, onClick = rowClick)
        else -> dimmed
    }

    val resolvedTrailing: (@Composable () -> Unit)? = if (switchState != null) {
        {
            Box(modifier = Modifier.clearAndSetSemantics {}) {
                ExpressiveSwitch(
                    checked = switchState,
                    enabled = switchEnabled,
                    onCheckedChange = null
                )
            }
        }
    } else trailingContent

    if (!centerSlots) {
        ListItem(
            modifier = clickable,
            headlineContent = headlineContent,
            supportingContent = supportingContent,
            leadingContent = leadingContent,
            trailingContent = resolvedTrailing,
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
        return
    }

    Row(
        modifier = clickable
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge,
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) { headlineContent() }

            if (supportingContent != null) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) { supportingContent() }
            }
        }
        if (resolvedTrailing != null) {
            Spacer(modifier = Modifier.width(16.dp))
            resolvedTrailing()
        }
    }
}
