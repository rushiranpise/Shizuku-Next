package moe.shizuku.manager.ui.component

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The end of a drag: the wall keeps going under its own momentum, is stretched against the edge it
 * runs into, and springs back. Ported from Duck Detector (Apache-2.0, Eltavine).
 */

/**
 * A velocity handed over when the last finger of a pinch lifts.
 *
 * It is only honoured if the fingers really did leave together - if the hand paused first, carrying
 * that stale velocity into a fling would send the wall flying after a finger was held still.
 */
internal data class WallVelocityHandoff(val velocity: Offset, val uptimeMillis: Long) {
    fun velocityAtRelease(releaseTimeMillis: Long): Offset? =
        velocity.takeIf { releaseTimeMillis - uptimeMillis in 0L..40L }
}

/**
 * Decays the wall's momentum and settles it back inside its limits.
 *
 * The two axes are animated independently so that hitting one edge still lets the wall keep sliding
 * along the other, which is what a rolled edge does.
 */
internal suspend fun settleContributorWall(
    initialOffset: Offset,
    initialVelocity: Offset,
    limit: Offset,
    viewport: Size,
    decay: DecayAnimationSpec<Float>,
    onFrame: (Offset) -> Unit
) = coroutineScope {
    var current = initialOffset
    launch {
        settleAxis(initialOffset.x, initialVelocity.x, limit.x, viewport.width, decay) {
            current = current.copy(x = it)
            onFrame(current)
        }
    }
    launch {
        settleAxis(initialOffset.y, initialVelocity.y, limit.y, viewport.height, decay) {
            current = current.copy(y = it)
            onFrame(current)
        }
    }
}

private suspend fun settleAxis(
    initial: Float,
    velocity: Float,
    bound: Float,
    extent: Float,
    decay: DecayAnimationSpec<Float>,
    onFrame: (Float) -> Unit
) {
    val rebound = spring<Float>(dampingRatio = 0.85f, stiffness = 220f)

    // Already past the edge - released mid-stretch. Nothing to fling; just come back.
    if (abs(initial) > bound) {
        animate(initial, initial.coerceIn(-bound, bound), animationSpec = rebound) { value, _ ->
            onFrame(value)
        }
        return
    }

    if (velocity == 0f || bound == 0f) return

    val motion = AnimationState(initialValue = initial, initialVelocity = velocity)
    motion.animateDecay(decay) {
        onFrame(resistWallAxis(value, bound, extent))
        if (abs(value) > bound) cancelAnimation()
    }

    if (abs(motion.value) > bound) {
        // Keep the speed it had on contact, so the fling turns into the rebound instead of stopping
        // dead at the edge.
        motion.animateTo(
            targetValue = motion.value.coerceIn(-bound, bound),
            animationSpec = rebound,
            sequentialAnimation = true
        ) {
            onFrame(resistWallAxis(value, bound, extent))
        }
    }
}
