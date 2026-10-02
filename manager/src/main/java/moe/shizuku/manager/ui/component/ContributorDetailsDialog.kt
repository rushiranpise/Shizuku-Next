package moe.shizuku.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.CustomTabsHelper

/**
 * What a face says when it is chosen: who this is, what they worked on, and where to find them.
 *
 * Laid out like the wall's sibling project does it - a large photo, the name, the handle, a line of
 * prose and then the areas as chips - so the two read as the same idea rather than as two designs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContributorDetailsDialog(
    contributor: Contributor,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val profileUrl = contributor.profileUrl

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // One face, on screen, drawn large: the only place a picture is worth its full
                // size rather than a step off the wall's own scale.
                ContributorAvatar(
                    contributor = contributor,
                    onScreen = true,
                    targetPx = 512,
                    modifier = Modifier.size(104.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = contributor.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    contributor.login
                        ?.takeIf { !it.equals(contributor.name, ignoreCase = true) }
                        ?.let { login ->
                            Text(
                                text = "@$login",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    Text(
                        text = pluralStringResource(
                            R.plurals.contributor_commits,
                            contributor.commits,
                            contributor.commits
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = contributorSummary(contributor),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (contributor.areas.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        contributor.areas.forEach { ContributionChip(it) }
                    }
                }
            }
        },
        confirmButton = {
            if (profileUrl != null) {
                PillButton(onClick = { CustomTabsHelper.launchUrlOrCopy(context, profileUrl) }) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.contributor_view_github),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else {
                PillButton(onClick = onDismiss) {
                    Text(stringResource(R.string.contributor_details_close))
                }
            }
        },
        dismissButton = if (profileUrl != null) {
            {
                PillButtonQuiet(onClick = onDismiss) {
                    Text(stringResource(R.string.contributor_details_close))
                }
            }
        } else null
    )
}

@Composable
private fun ContributionChip(area: ContributionArea) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = area.icon,
                contentDescription = null,
                tint = area.tint,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(area.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
