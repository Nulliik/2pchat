package com.example.twopchat

import androidx.compose.ui.MotionDurationScale
import androidx.compose.runtime.staticCompositionLocalOf

internal const val REDUCE_MOTION_SETTING = "settings_reduce_motion"
internal val LocalAppAnimationsEnabled = staticCompositionLocalOf { true }

internal class AppMotionDurationScale(
    animationsEnabled: Boolean = true,
    systemScaleFactor: Float = 1f,
) : MotionDurationScale {
    @Volatile
    var animationsEnabled: Boolean = animationsEnabled

    @Volatile
    var systemScaleFactor: Float = systemScaleFactor.coerceAtLeast(0f)
        set(value) {
            field = value.coerceAtLeast(0f)
        }

    override val scaleFactor: Float
        get() = if (animationsEnabled) systemScaleFactor else 0f
}
