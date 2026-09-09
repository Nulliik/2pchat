package com.example.twopchat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

val LocalScrollInProgress = compositionLocalOf { false }

/**
 * Animated media should consume decoder and render-thread time only while the
 * containing screen is in the foreground and the list is not actively scrolling.
 */
@Composable
internal fun isAnimatedMediaActive(): Boolean {
    if (LocalScrollInProgress.current) return false
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    return lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
}
