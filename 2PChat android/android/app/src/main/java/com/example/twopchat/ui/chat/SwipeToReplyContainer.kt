package com.example.twopchat.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.twopchat.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipeToReplyContainer(
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val deadzonePx = with(density) { 18.dp.toPx() }
    val thresholdPx = with(density) { 75.dp.toPx() }
    val maxLimitPx = with(density) { 110.dp.toPx() }

    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    var totalDragPx by remember { mutableFloatStateOf(0f) }

    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember(context) { com.example.twopchat.P2PPreferences.prefs(context) }
    var hasTriggeredHapticForSwipe by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxWidth().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = {
                    totalDragPx = 0f
                    hasTriggeredHapticForSwipe = false
                },
                onDragEnd = {
                    hasTriggeredHapticForSwipe = false
                    if (offsetX.value < -thresholdPx) onReply()
                    coroutineScope.launch {
                        offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                    }
                    totalDragPx = 0f
                },
                onDragCancel = {
                    hasTriggeredHapticForSwipe = false
                    coroutineScope.launch {
                        offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                    }
                    totalDragPx = 0f
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    totalDragPx += dragAmount
                    val effectiveDrag = if (totalDragPx < -deadzonePx) {
                        (totalDragPx + deadzonePx) * 0.45f
                    } else {
                        0f
                    }
                    val newOffset = effectiveDrag.coerceIn(-maxLimitPx, 0f)
                    coroutineScope.launch { offsetX.snapTo(newOffset) }

                    val crossed = newOffset <= -thresholdPx
                    if (crossed && !hasTriggeredHapticForSwipe) {
                        if (sharedPrefs.getBoolean("settings_haptic_feedback", true)) {
                            try {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            } catch (_: Exception) {}
                        }
                        hasTriggeredHapticForSwipe = true
                    } else if (!crossed && hasTriggeredHapticForSwipe) {
                        hasTriggeredHapticForSwipe = false
                    }
                },
            )
        },
    ) {
        if (offsetX.value < 0f) {
            val progress = (-offsetX.value / thresholdPx).coerceIn(0f, 1f)
            val iconAlpha = progress
            val iconScale = (progress * 0.4f + 0.6f).coerceIn(0.6f, 1f)
            Box(
                modifier = Modifier.fillMaxSize().padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_reply),
                    contentDescription = "Reply",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha),
                    modifier = Modifier.size(24.dp).graphicsLayer(scaleX = iconScale, scaleY = iconScale),
                )
            }
        }
        Box(
            modifier = Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }.fillMaxWidth(),
        ) { content() }
    }
}
