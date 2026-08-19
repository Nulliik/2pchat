package com.example.twopchat.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize

object MotionTokens {
    // Easing Curves (Emil Kowalski / Material 3 standards)
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val DecelerateEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val AccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)

    // Standard Durations
    const val DurationFastMs = 150
    const val DurationNormalMs = 220
    const val DurationSlowMs = 350

    // Unified Spring Specs
    val ResponsiveSpring = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow
    )

    val ResponsiveIntSizeSpring = spring<IntSize>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow
    )

    val SubtlePressSpring = spring<Float>(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMedium
    )

    val BouncySpring = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = Spring.StiffnessLow
    )

    val FastTween = tween<Float>(DurationFastMs, easing = DecelerateEasing)
    val FastIntSizeTween = tween<IntSize>(DurationFastMs, easing = DecelerateEasing)
    val NormalTween = tween<Float>(DurationNormalMs, easing = EmphasizedEasing)
}
