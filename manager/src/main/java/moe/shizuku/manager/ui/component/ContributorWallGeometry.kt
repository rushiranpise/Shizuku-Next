package moe.shizuku.manager.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * The wall's layout and edge behaviour are ported from Duck Detector (Apache-2.0, Eltavine), which
 * is the same licence this project uses. Ported rather than reinvented on purpose: the honeycomb
 * packing, the way a face is pulled inwards and shrunk near the border, and the feathered edge are
 * what make the wall read as a sphere of faces rather than as a grid of icons.
 */

internal data class ContributorWallGeometry(
    val centers: List<Offset>,
    val contentSize: Size
) {
    /** How much the content can be scaled before it stops fitting; never magnified past 1. */
    fun fitScale(viewport: Size): Float =
        min(viewport.width / contentSize.width, viewport.height / contentSize.height)
            .coerceAtMost(1f)

    /** How far the wall may be dragged before an edge would come inside the viewport. */
    fun panLimit(viewport: Size, scale: Float): Offset = Offset(
        max(0f, (contentSize.width * scale - viewport.width) / 2f),
        max(0f, (contentSize.height * scale - viewport.height) / 2f)
    )
}

/**
 * Places [count] faces on a hex grid, nearest the middle first, so a wall that is not full stays
 * balanced rather than growing off to one side.
 *
 * Positions are returned centred on the origin, so the canvas only has to translate them.
 */
internal fun contributorWallGeometry(count: Int, avatarSize: Float, gap: Float): ContributorWallGeometry {
    require(count >= 0 && avatarSize > 0f && gap >= 0f)
    if (count == 0) return ContributorWallGeometry(emptyList(), Size(avatarSize, avatarSize))

    var radius = 0
    while (1 + 3 * radius * (radius + 1) < count) radius++
    val spacing = avatarSize + gap

    // Only half the rings are generated, then mirrored. Taking both halves together keeps each ring
    // populated on both sides at once, which is what stops a partial ring from loading one edge.
    val pairs = buildList {
        for (r in -radius..radius) {
            for (q in -radius..radius) {
                if (abs(q + r) <= radius && (r > 0 || r == 0 && q > 0)) {
                    val point = Offset((q + r / 2f) * spacing, r * spacing * sqrt(3f) / 2f)
                    add((q * q + q * r + r * r) to point)
                }
            }
        }
    }.sortedWith(compareBy<Pair<Int, Offset>> { it.first }.thenBy { atan2(it.second.y, it.second.x) })

    val centers = (listOf(Offset.Zero) + pairs.flatMap { listOf(it.second, -it.second) }).take(count)

    val minX = centers.minOf { it.x }
    val maxX = centers.maxOf { it.x }
    val minY = centers.minOf { it.y }
    val maxY = centers.maxOf { it.y }
    val center = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)

    return ContributorWallGeometry(
        centers = centers.map { it - center },
        contentSize = Size(maxX - minX + avatarSize, maxY - minY + avatarSize)
    )
}

/** The offset that keeps [offset] inside [limit], used when the viewport changes size. */
internal fun constrainWallOffset(offset: Offset, limit: Offset): Offset = Offset(
    if (limit.x == 0f) 0f else offset.x.coerceIn(-limit.x, limit.x),
    if (limit.y == 0f) 0f else offset.y.coerceIn(-limit.y, limit.y)
)

/**
 * Keeps a pinch anchored: the point under the fingers stays under the fingers.
 */
internal fun zoomAround(offset: Offset, centroid: Offset, pan: Offset, ratio: Float): Offset =
    (offset - centroid) * ratio + centroid + pan

/**
 * Lets the wall go a little past its limit, with the movement falling off as it goes, so a drag
 * against the edge feels like a stretched spring rather than a wall.
 */
internal fun resistWallOffset(offset: Offset, limit: Offset, viewport: Size): Offset = Offset(
    resistWallAxis(offset.x, limit.x, viewport.width),
    resistWallAxis(offset.y, limit.y, viewport.height)
)

internal fun resistWallAxis(value: Float, bound: Float, extent: Float): Float {
    val overflow = abs(value) - bound
    if (overflow <= 0f) return value
    val travel = extent * 0.18f
    return sign(value) * (bound + travel * overflow / (travel + overflow))
}

/**
 * The inverse of [resistWallAxis], for the moment a gesture starts: the stretch is undone so the
 * fingers keep hold of the same spot instead of the wall jumping.
 */
internal fun unresistWallOffset(offset: Offset, limit: Offset, viewport: Size): Offset {
    fun restore(value: Float, bound: Float, extent: Float): Float {
        val overflow = abs(value) - bound
        if (overflow <= 0f) return value
        val travel = extent * 0.18f
        val visibleOverflow = overflow.coerceAtMost(travel * 0.999f)
        return sign(value) * (bound + travel * visibleOverflow / (travel - visibleOverflow))
    }
    return Offset(
        restore(offset.x, limit.x, viewport.width),
        restore(offset.y, limit.y, viewport.height)
    )
}

internal data class WallBubbleTransform(val position: Offset, val scale: Float)

/**
 * Bends a face towards the middle of the viewport as it nears the border, and shrinks it on the
 * same curve, so the wall reads as curving away instead of being cropped.
 *
 * The middle three tenths of the viewport are left alone; only the outer band is bent.
 */
internal fun wallBubbleTransform(position: Offset, viewport: Size): WallBubbleTransform {
    val distance = max(abs(position.x) / (viewport.width / 2), abs(position.y) / (viewport.height / 2))
    if (distance <= 0.3f) return WallBubbleTransform(position, 1f)
    val edgeDistance = distance - 0.3f
    val curve = tanh(edgeDistance / 0.42f)
    val compressedDistance = 0.3f + 0.35f * edgeDistance + 0.65f * 0.42f * curve
    // Position and scale share the one curve, so faces stay evenly spaced as they shrink.
    val scale = 0.35f + 0.65f * (1f - curve * curve)
    return WallBubbleTransform(position * (compressedDistance / distance), scale)
}

/**
 * Feathered border, so the wall fades out rather than ending in a row of half faces.
 *
 * Wider than the band this is ported from: faces scale with the zoom, so at the wall's opening
 * zoom a band sized for thumbnail faces leaves the ones at the border looking sliced.
 */
internal fun Modifier.wallEdgeFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val fade = 56.dp.toPx().coerceAtMost(size.minDimension / 3)
            fun stops(fraction: Float) = arrayOf(
                0f to Color.Transparent,
                fraction * 0.4f to Color.Black.copy(alpha = 0.35f),
                fraction to Color.Black,
                1f - fraction to Color.Black,
                1f - fraction * 0.4f to Color.Black.copy(alpha = 0.35f),
                1f to Color.Transparent
            )
            val horizontal = Brush.horizontalGradient(*stops(fade / size.width))
            val vertical = Brush.verticalGradient(*stops(fade / size.height))
            onDrawWithContent {
                drawContent()
                drawRect(horizontal, blendMode = BlendMode.DstIn)
                drawRect(vertical, blendMode = BlendMode.DstIn)
            }
        }
