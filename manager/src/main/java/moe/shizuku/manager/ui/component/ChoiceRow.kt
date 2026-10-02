package moe.shizuku.manager.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One row of a single-choice list: a radio, what the choice is, and - when it is the one in force -
 * the tinted container that says so.
 *
 * All three parts are on purpose. A radio on its own is a small mark to find on a long list, and it
 * says the state to a screen reader without saying it at a glance; the tint is what the eye finds
 * first. A tick used to stand in for the radio on some of these lists, which shifted every label
 * along by its own width and read as a set rather than as a choice.
 *
 * Every dialog in the app that offers a set of mutually exclusive options draws them with this row,
 * so picking a default start method, a theme, an app op or the app hiding is run as all look like
 * the same question being asked.
 *
 * [leading] is for the lists whose options are apps rather than words, where the icon is what makes
 * a row findable; it sits after the radio so the radios line up down the list whichever way the
 * icons fall.
 */
@Composable
fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Clipped before the click and painted after it, so the ripple is inside the rounded
            // container rather than a rectangle behind it.
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = content
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = content
                )
            }
        }
    }
}
