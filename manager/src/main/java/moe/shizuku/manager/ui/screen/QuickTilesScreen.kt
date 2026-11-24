package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.service.QuickTiles
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.SegmentedListItem

/**
 * Which of the app's quick settings tiles the system's tile picker offers.
 *
 * The panel has always listed every tile this app declares. That is right for a phone where the
 * shade is a shortcut to the same switches the app already has, and too much for one where the
 * picker is a wall of rows a user never asked for. Each row is one tile service, on by default,
 * and switching it off disables the component the system lists.
 *
 * A tile that is already in the shade leaves with the component: the panel has nothing left to
 * bind. Turning it back on makes it available in the picker again, but adding it back to the shade
 * is the panel's own edit, which is where the user put it in the first place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTilesScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    var tiles by remember { mutableStateOf<List<QuickTiles.Tile>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        tiles = withContext(Dispatchers.IO) { QuickTiles.all(context) }
    }

    /** Moves the row with the tap, then writes the component state that is the real record. */
    fun setEnabled(tile: QuickTiles.Tile, enabled: Boolean) {
        tiles = tiles?.map {
            if (it.component == tile.component) it.copy(enabled = enabled) else it
        }
        scope.launch {
            withContext(Dispatchers.IO) { QuickTiles.setEnabled(context, tile, enabled) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.labs_quick_tiles)) },
            windowInsets = watchTopAppBarInsets(),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        val list = tiles
        if (list == null) {
            CenteredMessage { LoadingIndicator() }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = bottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.quick_tiles_summary),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(list, key = { it.component.flattenToString() }) { tile ->
                        SegmentedCard {
                            SegmentedListItem(
                                centerSlots = true,
                                leadingContent = {
                                    if (tile.iconRes != 0) {
                                        Icon(
                                            painterResource(tile.iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }
                                },
                                headlineContent = { Text(tile.label) },
                                switchState = tile.enabled,
                                onSwitchChange = { setEnabled(tile, it) }
                            )
                        }
                    }
                }

                ScrollToTopButton(
                    listState = listState,
                    bottomPadding = bottomPadding,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
    }
}
