package moe.shizuku.manager.ui.component

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ui.theme.LocalWatch

/**
 * Watches the display setting, so turning the button off removes one that is already on screen.
 */
@Composable
private fun rememberShowScrollToTop(): Boolean {
    val preferences = ShizukuSettings.getPreferences()
    var show by remember {
        mutableStateOf(
            preferences.getBoolean(ShizukuSettings.Keys.KEY_SHOW_SCROLL_TO_TOP, true)
        )
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ShizukuSettings.Keys.KEY_SHOW_SCROLL_TO_TOP) {
                show = preferences.getBoolean(key, true)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return show
}

/**
 * The way back to the top of a list, once it has been scrolled past the first screen.
 *
 * Shown only while there is something above: a button that is always there asks for a look every
 * time a list is opened, and the first item is already in view then. Fades and scales in so the
 * appearance is not a jump.
 *
 * [bottomPadding] is the room the screen gave its list for whatever floats over it - the tab bar,
 * usually - and the button is put just above that, so it never covers the bar and the bar never
 * covers it. A screen with a button of its own at the bottom passes its height as [extraBottom]
 * and the two stack.
 *
 * Place it inside a Box, at `Alignment.BottomEnd`.
 */
@Composable
fun ScrollToTopButton(
    listState: LazyListState,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    extraBottom: Dp = 0.dp
) {
    // A watch already has the raised tab pill across the bottom. A second floating button there
    // landed on top of the last row it was supposed to help with.
    if (LocalWatch.current) return
    if (!rememberShowScrollToTop()) return
    val scrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = scrolled,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        SmallFloatingActionButton(
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(end = 16.dp, bottom = bottomPadding + 16.dp + extraBottom)
        ) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = stringResource(R.string.scroll_to_top)
            )
        }
    }
}

/**
 * The same button for a screen built on `verticalScroll` rather than a lazy list: a settings form
 * or a long dialog, where there is no item to scroll to and only an offset.
 */
@Composable
fun ScrollToTopButton(
    scrollState: ScrollState,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    extraBottom: Dp = 0.dp
) {
    if (LocalWatch.current) return
    if (!rememberShowScrollToTop()) return
    val scrolled by remember { derivedStateOf { scrollState.value > 0 } }
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = scrolled,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        SmallFloatingActionButton(
            onClick = { scope.launch { scrollState.animateScrollTo(0) } },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(end = 16.dp, bottom = bottomPadding + 16.dp + extraBottom)
        ) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = stringResource(R.string.scroll_to_top)
            )
        }
    }
}
