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
    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val threshold = 120f
    val limit = 200f
    Box(
        modifier = modifier.fillMaxWidth().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = {},
                onDragEnd = {
                    if (abs(offsetX.value) > threshold) onReply()
                    coroutineScope.launch {
                        offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                    }
                },
                onDragCancel = {
                    coroutineScope.launch {
                        offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                    }
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    val newOffset = (offsetX.value + dragAmount).coerceIn(-limit, limit)
                    coroutineScope.launch { offsetX.snapTo(newOffset) }
                },
            )
        },
    ) {
        if (offsetX.value != 0f) {
            val alignment = if (offsetX.value > 0) Alignment.CenterStart else Alignment.CenterEnd
            val iconAlpha = (abs(offsetX.value) / threshold).coerceIn(0f, 1f)
            val iconScale = (abs(offsetX.value) / threshold).coerceIn(0.5f, 1f)
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentAlignment = alignment,
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
