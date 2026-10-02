package moe.shizuku.manager.ui.component

import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The wall itself. The face size, the gap and the interaction are ported from Duck Detector
 * (Apache-2.0, Eltavine): the way a wall of faces reads depends on all of them together.
 *
 * What is not theirs is where it opens. Their wall is thirty faces and a fixed 1.5x, and at their
 * size a fixed zoom is the same as a sensible one. This wall is a hundred and forty faces, so the
 * same number would draw every one of them as a dot, and fitting all of them instead is worse: a
 * honeycomb of specks is not a wall of people. So the opening zoom is derived - enough of it that
 * the faces in the middle are a readable size - and the rest of the wall is reached by dragging
 * and pinching, which is what the interaction was always for.
 */

/** Zoom is a multiplier on the fit, and this is the value that means "not chosen yet". */
private const val UnsetZoom = 0f

/**
 * How much of a face to draw on opening.
 *
 * One would draw a face at the size it was laid out, which is readable; just under that leaves a
 * little of the surrounding wall visible at the edges.
 */
private const val OpeningFaceFraction = 0.85f

private const val MinWallZoom = 1f
private const val MaxWallZoom = 5f

/** Faces near the border are bent inwards at all, but this keeps them from being sliced. */
private const val WallFitMargin = 0.94f

private val AvatarSize = 56.dp
private val AvatarGap = 6.dp

/** How often the wall works out which faces are on screen. See the loop below for why. */
private const val VISIBILITY_POLL_MS = 150L

private val WallOffsetSaver = listSaver<Offset, Float>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) }
)

/**
 * The wall of faces: drag to move it, pinch to zoom, tap one for details.
 *
 * Every face is a focusable, clickable control as well, which is what lets the same wall be used
 * with a remote on a television rather than only by touch.
 */
@Composable
internal fun ContributorWall(
    contributors: List<Contributor>,
    onSelect: (Contributor) -> Unit,
    modifier: Modifier = Modifier
) {
    var userZoom by rememberSaveable { mutableFloatStateOf(UnsetZoom) }
    var offset by rememberSaveable(stateSaver = WallOffsetSaver) { mutableStateOf(Offset.Zero) }
    var motionJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val density = LocalDensity.current

    val geometry = remember(contributors.size, density) {
        with(density) { contributorWallGeometry(contributors.size, AvatarSize.toPx(), AvatarGap.toPx()) }
    }
    val detailsLabel = stringResource(R.string.contributor_view_github)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val canvasHeight = maxWidth.coerceAtMost(380.dp)
        val viewport = with(density) { Size(maxWidth.toPx(), canvasHeight.toPx()) }
        val inset = with(density) { 8.dp.toPx() }
        val available = Size(viewport.width - inset * 2, viewport.height - inset * 2)
        val fitScale = geometry.fitScale(available) * WallFitMargin
        val center = Offset(viewport.width / 2, viewport.height / 2)

        // How big a face comes out at depends on how many there are, so the zoom that opens on a
        // readable patch of wall is derived from the fit rather than fixed. It is not stored, so a
        // rotation or a change in the number of contributors re-derives it until the user takes
        // over by pinching.
        val openingZoom = (OpeningFaceFraction / fitScale)
            .coerceIn(MinWallZoom, MaxWallZoom)

        // Read through the state instead of capturing the value: the gesture and the visibility
        // loop below both outlive several recompositions, and a captured zoom would leave them
        // holding a pinch and decoding faces against the zoom this wall happened to open at.
        fun zoom() = if (userZoom == UnsetZoom) openingZoom else userZoom

        // Which faces are on screen decides whose picture is worth fetching, and asking that
        // question per frame would cost more than the fetching it saves - the answer only changes
        // when the wall is moved. It is also bucketed to powers of two, so a pinch re-decodes a
        // face four times at most instead of on every frame of the gesture.
        var visible by remember(geometry, viewport) {
            mutableStateOf(geometry.centers.indices.toSet())
        }
        var avatarPx by remember(geometry, viewport) { mutableIntStateOf(64) }

        LaunchedEffect(geometry, viewport) {
            while (true) {
                val scale = fitScale * zoom()
                val reach = with(density) { AvatarSize.toPx() } * scale
                val onScreen = HashSet<Int>()
                geometry.centers.forEachIndexed { index, spot ->
                    val position = spot * scale + offset
                    if (abs(position.x) <= viewport.width / 2 + reach &&
                        abs(position.y) <= viewport.height / 2 + reach
                    ) {
                        onScreen += index
                    }
                }
                if (onScreen != visible) visible = onScreen

                val wanted = with(density) { AvatarSize.toPx() * scale }.roundToInt()
                val bucketed = avatarBucket(wanted)
                if (bucketed != avatarPx) avatarPx = bucketed

                delay(VISIBILITY_POLL_MS)
            }
        }

        // A saved offset can belong to a different viewport - a rotation, or a resized window - and
        // has to be brought back inside before it is drawn.
        LaunchedEffect(geometry, viewport) {
            motionJob?.cancel()
            offset = constrainWallOffset(offset, geometry.panLimit(available, fitScale * zoom()))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .clip(MaterialTheme.shapes.large)
                .wallEdgeFade()
                .pointerInput(geometry, viewport) {
                    val maximumVelocity = 6000.dp.toPx().let { Velocity(it, it) }
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        // Touching a wall that is still coasting stops it, which is the only way to
                        // catch it: a fling can outlast the eye.
                        val stoppingMotion = motionJob?.isActive == true
                        motionJob?.cancel()
                        if (stoppingMotion) down.consume()

                        val tracker = VelocityTracker()
                        var trackedPosition = Offset.Zero
                        var pointerCount = 1
                        tracker.addPointerInputChange(down)
                        var rawOffset = unresistWallOffset(
                            offset,
                            geometry.panLimit(available, fitScale * zoom()),
                            viewport
                        )
                        var velocityHandoff: WallVelocityHandoff? = null
                        var releaseTimeMillis = down.uptimeMillis
                        var drag = Offset.Zero
                        var ownsGesture = stoppingMotion

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pan = event.calculatePan()
                            val pressedCount = event.changes.count { it.pressed }
                            val previousEventTimeMillis = releaseTimeMillis
                            releaseTimeMillis = event.changes.first().uptimeMillis

                            // Lifting one finger of a pinch: keep the speed it had, but only for
                            // the next event or two.
                            if (pressedCount > 0 && pressedCount != pointerCount) {
                                velocityHandoff = if (pointerCount > 1 && pressedCount == 1) {
                                    val velocity = tracker.calculateVelocity(maximumVelocity)
                                    WallVelocityHandoff(
                                        Offset(velocity.x, velocity.y),
                                        previousEventTimeMillis
                                    )
                                } else null
                                tracker.resetTracking()
                                trackedPosition = Offset.Zero
                            }
                            if (pressedCount == 1 && pan != Offset.Zero) velocityHandoff = null

                            if (pressedCount == 1 || pressedCount == 0 && pointerCount == 1) {
                                val pointer = event.changes.firstOrNull { it.pressed }
                                    ?: event.changes.first { it.previousPressed }
                                tracker.addPointerInputChange(pointer)
                            } else if (pressedCount > 1) {
                                // Two fingers: track their midpoint rather than a single finger.
                                trackedPosition += pan
                                tracker.addPosition(event.changes.first().uptimeMillis, trackedPosition)
                            }
                            pointerCount = pressedCount
                            drag += pan
                            if (pressedCount >= 2 || drag.getDistance() > viewConfiguration.touchSlop) {
                                ownsGesture = true
                            }
                            if (ownsGesture) {
                                val current = zoom()
                                val nextZoom = (current * event.calculateZoom())
                                    .coerceIn(MinWallZoom, MaxWallZoom)
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (centroid != Offset.Unspecified) {
                                    rawOffset = zoomAround(rawOffset, centroid - center, pan, nextZoom / current)
                                }
                                userZoom = nextZoom
                                offset = resistWallOffset(
                                    rawOffset,
                                    geometry.panLimit(available, fitScale * nextZoom),
                                    viewport
                                )
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        if (ownsGesture) {
                            val velocity = tracker.calculateVelocity(maximumVelocity)
                            val releasedVelocity = velocityHandoff?.velocityAtRelease(releaseTimeMillis)
                                ?: Offset(velocity.x, velocity.y)
                            // Below a flick's worth of speed, treat the release as holding still.
                            val initialVelocity = releasedVelocity.let {
                                if (it.getDistance() < 50.dp.toPx()) Offset.Zero else it
                            }
                            motionJob = scope.launch {
                                settleContributorWall(
                                    initialOffset = offset,
                                    initialVelocity = initialVelocity,
                                    limit = geometry.panLimit(available, fitScale * zoom()),
                                    viewport = viewport,
                                    decay = decay
                                ) { offset = it }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            contributors.forEachIndexed { index, contributor ->
                key(index) {
                    ContributorAvatar(
                        contributor = contributor,
                        onScreen = index in visible,
                        targetPx = avatarPx,
                        modifier = Modifier
                            .size(AvatarSize)
                            .graphicsLayer {
                                val scale = fitScale * zoom()
                                val bubble = wallBubbleTransform(
                                    geometry.centers[index] * scale + offset,
                                    viewport
                                )
                                scaleX = scale * bubble.scale
                                scaleY = scaleX
                                translationX = bubble.position.x
                                translationY = bubble.position.y
                            }
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = detailsLabel,
                                onClick = { onSelect(contributor) }
                            )
                            .semantics { contentDescription = contributor.name }
                    )
                }
            }
        }
    }
}

/** The size a face is drawn at, rounded up to a step, so a pinch does not re-decode per frame. */
internal fun avatarBucket(targetPx: Int): Int {
    var bucket = 32
    while (bucket < targetPx && bucket < 512) bucket *= 2
    return bucket
}

/**
 * One face: the person's picture in a ringed circle, or their initial where there is no picture.
 *
 * A face that is off screen draws its initial and loads nothing: it is not visible, and a wall of
 * well over a hundred faces cannot hold everybody's photograph at once.
 */
@Composable
internal fun ContributorAvatar(
    contributor: Contributor,
    onScreen: Boolean,
    targetPx: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        contributor.avatarUrl,
        onScreen,
        targetPx
    ) {
        value = if (onScreen) {
            ContributorAvatars.load(context, contributor, targetPx)
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        val picture = bitmap
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = contributor.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
