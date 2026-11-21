package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.theme.LocalWatch

/**
 * The insets an app bar needs on a round watch.
 *
 * A watch hands the app a square window and shows a circle of it. An app bar puts its back
 * arrow and its actions at the two ends of a row near the top, and both ends are outside that
 * circle: the title lost its first letters and the rightmost action could not be tapped at all.
 *
 * Applied as the bar's own window insets rather than as padding around the bar, so the bar's
 * surface still reaches the screen edges and only what it draws moves into the circle. The
 * inset is only on the app bar; the page below it keeps its width.
 */
@Composable
fun watchTopAppBarInsets(): WindowInsets =
    if (LocalWatch.current) {
        // 56dp at the sides clears the curve at the height the row of icons sits at, and 24dp
        // at the top moves that row down to where the circle is wide enough for both ends.
        WindowInsets(56.dp, 24.dp, 56.dp, 0.dp)
    } else {
        WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
    }
