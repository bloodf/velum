package dev.velum.app.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import dev.velum.app.platform.HapticKind
import dev.velum.app.platform.LocalHaptics
import dev.velum.app.platform.reduceMotionPreferred

/**
 * Motion tokens translated from Emil Kowalski / Appllama mobile craft for Compose.
 * Durations stay under 300ms for UI chrome; ease-out on enter/exit; never ease-in.
 */
object Motion {
    /** Strong ease-out (cubic-bezier(0.23, 1, 0.32, 1)). */
    val EaseOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** Strong ease-in-out for on-screen morphs (cubic-bezier(0.77, 0, 0.175, 1)). */
    val EaseInOut: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    const val PressMs: Int = 120
    const val ToggleMs: Int = 180
    const val OverlayMs: Int = 220
    const val SheetMs: Int = 280
    const val NavMs: Int = 180

    /** Entrance scale floor — never scale(0). */
    const val EnterScaleFrom: Float = 0.96f
    const val PressScale: Float = 0.97f

    @Composable
    fun reduceMotion(): Boolean = reduceMotionPreferred()

    @Composable
    fun <T> tweenOut(durationMs: Int = ToggleMs): TweenSpec<T> =
        if (reduceMotion()) {
            tween(0)
        } else {
            tween(durationMs, easing = EaseOut)
        }

    @Composable
    fun <T> tweenMorph(durationMs: Int = ToggleMs): TweenSpec<T> =
        if (reduceMotion()) {
            tween(0)
        } else {
            tween(durationMs, easing = EaseInOut)
        }

    @Composable
    fun <T> settleSpring(): androidx.compose.animation.core.SpringSpec<T> =
        if (reduceMotion()) {
            spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy)
        } else {
            spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
        }
}

/**
 * Press-in scale feedback (100–150ms). No ripple — Plaud chrome stays flat.
 * Fires a light haptic on press-in when [haptic] is true.
 */
@Composable
fun Modifier.pressableScale(
    enabled: Boolean = true,
    haptic: Boolean = false,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = Motion.reduceMotion()
    val haptics = LocalHaptics.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.PressScale else 1f,
        animationSpec = if (reduce) SnapSpec() else tween(Motion.PressMs, easing = Motion.EaseOut),
        label = "pressScale",
    )
    // clickable before graphicsLayer so a contentDescription applied on the
    // same chain stays on the clickable a11y node (#106). TabIcon uses plain
    // clickable instead — graphicsLayer after clickable still split a11y under R8.
    return this
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = {
                if (haptic) haptics.perform(HapticKind.Light)
                onClick()
            },
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}
