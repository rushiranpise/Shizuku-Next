package moe.shizuku.manager.ui.component

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R

/**
 * The pictures the two app screens share: a filter chip that carries its own count, the
 * count badge inside it, app icons, the state chip and the centred block the empty states
 * are built from. They live here rather than in either screen because Apps and Manage are
 * the same list about different questions, and they should not drift apart.
 */

/** The label, or the package name when there is no application record to read one from. */
fun appLabel(pm: PackageManager, pi: PackageInfo): String =
    runCatching { pi.applicationInfo?.loadLabel(pm)?.toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: pi.packageName

/**
 * One filter, sized to its share of the row rather than to its label, with its label and how
 * many apps it holds centred together. A stock chip sizes to its text, which left the four
 * ragged on the left and hid the counts somewhere else entirely.
 */
@Composable
fun AppFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    // A row of chips that shares the width evenly wants each chip to fill its slot; a row
    // that scrolls, because there are more filters than fit, wants them to hug their text.
    fill: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        border = BorderStroke(
            1.dp,
            if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = if (fill) {
                Modifier.fillMaxSize().padding(horizontal = 6.dp)
            } else {
                Modifier.padding(horizontal = 12.dp)
            },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            // Only the filter in force carries its number: the four labels together read
            // as one row of names, and the count for the one you picked is the count you
            // were asking about. It also leaves the label the room it needs to stay whole.
            if (selected) {
                Spacer(modifier = Modifier.width(6.dp))
                CountBadge(count, selected)
            }
        }
    }
}

/**
 * Just the number, in a small rounded chip the same shape family as the filter it sits
 * in, rather than a circle, so a selected filter reads as one object. Enough to read at a
 * glance, not enough to shout.
 */
@Composable
fun CountBadge(count: Int, selected: Boolean) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(MaterialTheme.shapes.small)
            // Background before padding, so the tint covers the whole chip and not just
            // the space the digits take up inside it.
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * A short label in a small rounded chip, the same shape family as the filter chips and the
 * count inside them.
 *
 * A row's state belongs here rather than at the end of the line under the name: that line is
 * the package name, which is long, and a suffix bolted onto it is the first thing a narrow
 * screen cuts off leaving a stray separator and no state at all.
 */
/** How much a chip wants to be noticed. */
enum class ChipEmphasis {
    /** A line that says something else entirely, in the theme's neutral. */
    NONE,

    /** A state worth seeing disabled, suspended without shouting it. */
    SOFT,

    /** Something is wrong: the app is not installed any more. */
    WARN,

    /** Came with the system. */
    SYSTEM,

    /** Installed by somebody. */
    USER,

    /** No launcher entry, so nothing on the home screen opens it. */
    HIDDEN
}

/*
 * Three colours the scheme does not have. It has an error red, which means "something is wrong"
 * rather than "this came with the device", and no green or brown at all - and these three chips
 * are facts about an app, not the app being unhappy, so borrowing the error role for one of them
 * would say the wrong thing.
 *
 * Alpha in the literal rather than a colour faded at the call site, so the tint sits over whatever
 * is behind the chip - a card, a sheet, a pressed row - instead of over its own layer. Five
 * parts in ten of the colour and a near-white label, because the first attempt used a low alpha
 * with the same hue as the text: brown at a quarter over a dark card came out as rgb(75,73,75),
 * which is grey, and the label on it could not be read either.
 */
/** The room a chip leaves around its label, on each side. */
private val ChipPadding = 8.dp

/**
 * The labels an app row's chip can carry - everything [appStatusChips] and the app detail screen
 * put in one.
 *
 * "Removed, data kept" is deliberately not here. It is a sentence rather than a label, and
 * measuring it would stretch every chip in the list to fit the row of an app that is already
 * gone; left out, that one chip is simply the wider one.
 */
val AppStatusLabels = listOf(
    R.string.manage_status_system,
    R.string.manage_status_user,
    R.string.manage_status_suspended,
    R.string.manage_status_disabled,
    R.string.manage_status_hidden
)

/** The labels an activity row's chip can carry, which is a different set and a different width. */
val ActivityStatusLabels = listOf(
    R.string.activities_launcher,
    R.string.activities_exported,
    R.string.activities_not_exported
)

/**
 * The width a group of status chips is given, measured from the longest label in it.
 *
 * A chip that sizes to its own word makes a column of them ragged: "User" above "Disabled" in
 * the same list are two widths, and the row under them starts somewhere else again. Measuring the
 * group they belong to lines them up without anybody having to know which label is the longest -
 * and the group rather than the whole app, because the activity rows' longest is "Not exported"
 * and sizing the app rows' chips for a word they can never show would cost the app name that
 * space on every row.
 *
 * Measured, rather than a number of dp worked out from the English: the labels are translated,
 * and the longest of them is a different word in every language.
 */
@Composable
fun statusChipMinWidth(labels: List<Int>): Dp {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current

    return remember(measurer, style, density) {
        val widest = labels.maxOf { id ->
            measurer.measure(AnnotatedString(context.getString(id)), style).size.width
        }

        with(density) { widest.toDp() } + ChipPadding * 2
    }
}

private val ChipRedSurface = Color(0x4DFF5252)
private val ChipRedLabel = Color(0xFFFFDAD6)
private val ChipGreenSurface = Color(0x4D2E7D32)
private val ChipGreenLabel = Color(0xFFD7F5D9)
private val ChipBrownSurface = Color(0x4DD7A87E)
private val ChipBrownLabel = Color(0xFFF2DCC8)

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    emphasis: ChipEmphasis = ChipEmphasis.NONE,
    /**
     * The width to make this chip at least, from [statusChipMinWidth] for the group it is in.
     *
     * Left null by the chips that are not part of such a group - a count on its own, say - which
     * then size to what they hold.
     */
    minWidth: Dp? = null
) {
    val container = when (emphasis) {
        ChipEmphasis.NONE -> MaterialTheme.colorScheme.surfaceContainerHighest
        ChipEmphasis.SOFT -> MaterialTheme.colorScheme.secondaryContainer
        ChipEmphasis.WARN -> MaterialTheme.colorScheme.errorContainer
        ChipEmphasis.SYSTEM -> ChipRedSurface
        ChipEmphasis.USER -> ChipGreenSurface
        ChipEmphasis.HIDDEN -> ChipBrownSurface
    }
    val content = when (emphasis) {
        ChipEmphasis.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        ChipEmphasis.SOFT -> MaterialTheme.colorScheme.onSecondaryContainer
        ChipEmphasis.WARN -> MaterialTheme.colorScheme.onErrorContainer
        ChipEmphasis.SYSTEM -> ChipRedLabel
        ChipEmphasis.USER -> ChipGreenLabel
        ChipEmphasis.HIDDEN -> ChipBrownLabel
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        // Centred in the width the chip was given rather than left where its own text ends, which
        // is the other half of making a column of them line up.
        textAlign = TextAlign.Center,
        color = content,
        modifier = modifier
            .then(if (minWidth != null) Modifier.widthIn(min = minWidth) else Modifier)
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = ChipPadding, vertical = 3.dp)
    )
}

/**
 * What a row says about the app beside its name.
 *
 * Two at most: what kind of app it is - system or user, which is a fact about the app that
 * nothing else in the list shows - and one thing worth knowing about its state, most actionable
 * first: gone from this user, suspended, disabled, or with no launcher entry. Always at least
 * the kind, so a row that says nothing else still says what it is.
 */
fun appStatusChips(
    pi: PackageInfo,
    hidden: Boolean,
    removed: Boolean = false
): List<Pair<Int, ChipEmphasis>> {
    val ai = pi.applicationInfo
    if (removed || ai == null) {
        return listOf(R.string.manage_status_removed to ChipEmphasis.WARN)
    }

    val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
    val chips = mutableListOf(
        (if (system) R.string.manage_status_system else R.string.manage_status_user) to
            if (system) ChipEmphasis.SYSTEM else ChipEmphasis.USER
    )
    val flags = ai.flags
    when {
        flags and ApplicationInfo.FLAG_SUSPENDED != 0 ->
            chips += R.string.manage_status_suspended to ChipEmphasis.SOFT

        !ai.enabled -> chips += R.string.manage_status_disabled to ChipEmphasis.SOFT

        hidden -> chips += R.string.manage_status_hidden to ChipEmphasis.HIDDEN
    }
    return chips
}

/**
 * The chips of [appStatusChips], laid out for a row's trailing slot.
 *
 * Stacked, not side by side. Two chips in a row take the width the name needs, and a name
 * squeezed into what is left wraps one letter per line - which is what "3 Button Navigation
 * Bar" did next to System and No launcher icon. A taller row is the better trade, and one chip
 * is a column of one.
 */
@Composable
fun AppStatusChips(
    pi: PackageInfo,
    hidden: Boolean,
    removed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val chips = appStatusChips(pi, hidden, removed)

    // One width for the whole group, so the column of chips lines up down the list rather than
    // each chip being as wide as its own word.
    val chipWidth = statusChipMinWidth(AppStatusLabels)

    // Three slots, always: an empty one, the kind chip, and then the status chip or another empty
    // one. That is what puts the kind chip on the row's centre line - the block is centred as a
    // whole, so a two-slot block would leave its kind chip half a chip high and a one-slot block
    // half a chip low, and the chips would not line up from one row to the next. With three, the
    // kind chip is always in the middle slot, whatever hangs under it.
    //
    // The cost is height: three slots are taller than a name and a package, so a row grows to
    // fit. That is the trade for the chips being in line down the whole list.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(ChipGap)
    ) {
        EmptyChip(chipWidth)
        chips.forEach { (label, emphasis) ->
            StatusChip(stringResource(label), emphasis = emphasis, minWidth = chipWidth)
        }
        if (chips.size < 2) EmptyChip(chipWidth)
    }
}

/** The gap between two chips in a row's trailing stack. */
private val ChipGap = 4.dp

/**
 * The space a chip would take, with nothing in it.
 *
 * Given the group's width rather than the nothing it holds, so the invisible slot lines up with
 * the visible ones - it is what keeps the kind chip on the row's centre line from one row to the
 * next, and a slot narrower than its neighbours would do the opposite.
 */
@Composable
private fun EmptyChip(minWidth: Dp) {
    StatusChip(
        text = "",
        emphasis = ChipEmphasis.NONE,
        minWidth = minWidth,
        modifier = Modifier
            .alpha(0f)
            .clearAndSetSemantics { }
    )
}

/** Centred content for the states that aren't a list. */
@Composable
fun CenteredMessage(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
fun AppIcon(pi: PackageInfo) {
    val context = LocalContext.current
    val icon = remember(pi.packageName) {
        runCatching { pi.applicationInfo!!.loadIcon(context.packageManager) }.getOrNull()
    }
    AppIcon(icon)
}

/**
 * The icon for a package that has no [PackageInfo] to hand an app that is only listed by
 * name, or one whose application record is gone because it is no longer installed.
 */
@Composable
fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    AppIcon(icon)
}

@Composable
private fun AppIcon(drawable: android.graphics.drawable.Drawable?) {
    val bitmap by produceState<ImageBitmap?>(null, drawable) {
        value = withContext(Dispatchers.IO) {
            runCatching { drawable?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Spacer(modifier = Modifier.size(40.dp))
    }
}
