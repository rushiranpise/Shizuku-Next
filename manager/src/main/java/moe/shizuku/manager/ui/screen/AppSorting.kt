package moe.shizuku.manager.ui.screen

import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.shizuku.manager.R

/**
 * The orders every app list offers, so the same words mean the same order wherever a list of
 * apps is read: the Apps tab, the app-ops list, and the lists under Labs.
 */
enum class SortOrder {
    /** Most recently installed first: when the app first appeared on the device. */
    RECENTLY_INSTALLED,

    /** Most recently updated first: when the APK last changed. */
    RECENTLY_UPDATED,

    ALPHABETICAL
}

/**
 * The apps in the chosen order.
 *
 * The labels are resolved once per app rather than inside the comparator: a comparator is asked
 * for its keys about n log n times, and these lists re-sort on every keystroke of their search
 * field, so a lookup per comparison was the same few labels fetched over and over.
 */
fun List<PackageInfo>.sortedForDisplay(
    order: SortOrder,
    label: (PackageInfo) -> String
): List<PackageInfo> {
    val labelled = map { it to label(it).lowercase() }
    return when (order) {
        SortOrder.RECENTLY_INSTALLED -> labelled.sortedByDescending { it.first.firstInstallTime }
        SortOrder.RECENTLY_UPDATED -> labelled.sortedByDescending { it.first.lastUpdateTime }
        SortOrder.ALPHABETICAL -> labelled.sortedBy { it.second }
    }.map { it.first }
}

/**
 * The sort control the app lists share.
 *
 * The three orders are the same everywhere. [keepInPlace] is for the lists that act on the apps
 * they show, which puts those apps at the top by default because that is what somebody opens the
 * list to see. Left unchecked, an app keeps the place the chosen order gives it instead, which is
 * what makes a long list easy to scan for one name.
 *
 * Null [keepInPlace] leaves the option out: the Apps tab and the app-ops list do not have a state
 * of their own to put first.
 */
@Composable
fun AppSortMenu(
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
    keepInPlace: Boolean? = null,
    onKeepInPlaceChange: (Boolean) -> Unit = {}
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.Sort,
                contentDescription = stringResource(R.string.app_management_sort)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_management_sort_last_added)) },
                onClick = {
                    onSortOrderChange(SortOrder.RECENTLY_INSTALLED)
                    open = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_management_sort_updated)) },
                onClick = {
                    onSortOrderChange(SortOrder.RECENTLY_UPDATED)
                    open = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_management_sort_alphabetical)) },
                onClick = {
                    onSortOrderChange(SortOrder.ALPHABETICAL)
                    open = false
                }
            )
            if (keepInPlace != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.apps_sort_keep_in_place)) },
                    leadingIcon = {
                        if (keepInPlace) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    // Stays open after the tap, so the tick that just changed is seen where it
                    // changed. The other items close the menu because they are the whole answer.
                    onClick = { onKeepInPlaceChange(!keepInPlace) }
                )
            }
        }
    }
}
