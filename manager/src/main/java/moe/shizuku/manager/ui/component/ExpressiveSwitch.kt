package moe.shizuku.manager.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The switch this app uses everywhere.
 *
 * It is Material 3's own switch carrying a thumb icon, which is the expressive treatment of
 * it: the state reads on its own without reading the row it belongs to, and the theme's
 * expressive motion scheme animates it. One component rather than a `thumbContent` at every
 * call site, so that no screen can end up with a switch that looks different from the rest,
 * and so that changing the treatment is one edit rather than fifteen.
 *
 * The parameters are named exactly as Material 3's are, which is what makes it a drop-in
 * replacement for the call sites it was introduced for.
 */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors()
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Remove,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize)
            )
        }
    )
}
