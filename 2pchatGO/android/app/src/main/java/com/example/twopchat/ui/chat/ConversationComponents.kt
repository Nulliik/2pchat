package com.example.twopchat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import com.example.twopchat.theme.MotionTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.R
import com.example.twopchat.media.*
import com.example.twopchat.theme.MintGreen
import com.example.twopchat.theme.StealthBlack

@Composable
fun ConversationSearchHeader(
    query: String,
    placeholder: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .statusBarsPadding()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                painterResource(R.drawable.ic_back_arrow),
                contentDescription = "Close search",
                tint = onSurfaceColor,
                modifier = Modifier.size(20.dp),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(placeholder, color = onSurfaceVariant, fontSize = 14.sp) },
            singleLine = true,
            keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                context = context,
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primaryColor,
                unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.2f),
                cursorColor = primaryColor,
                focusedTextColor = onSurfaceColor,
                unfocusedTextColor = onSurfaceColor,
            ),
            textStyle = TextStyle(fontSize = 14.sp),
            shape = RoundedCornerShape(24.dp),
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Text("×", fontSize = 22.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ConversationMessagePreviewBar(
    visible: Boolean,
    title: String,
    text: String,
    primaryColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = MotionTokens.ResponsiveIntSizeSpring) + fadeIn(animationSpec = MotionTokens.FastTween),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = MotionTokens.FastIntSizeTween) + fadeOut(animationSpec = MotionTokens.FastTween),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(onSurfaceColor.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(36.dp).background(primaryColor, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                Text(
                    text,
                    fontSize = 11.sp,
                    color = onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Text("×", fontSize = 18.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ConversationReplyQuote(
    author: String,
    text: String,
    accentColor: Color,
    titleColor: Color,
    textColor: Color,
    backgroundColor: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(accentColor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(author, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Text(
                text,
                fontSize = 11.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ConversationComposerRow(
    attachmentsOpen: Boolean,
    isRecordingVoice: Boolean,
    recordingElapsedMs: Int,
    recordingAmplitudes: List<Float> = emptyList(),
    isEditing: Boolean,
    inputText: String,
    placeholder: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    attachEnabled: Boolean = true,
    inputEnabled: Boolean = true,
    actionEnabled: Boolean = true,
    voiceActionEnabled: Boolean = actionEnabled,
    actionLoading: Boolean = false,
    onToggleAttachments: () -> Unit,
    onOpenStickerPicker: () -> Unit,
    onInputTextChange: (String) -> Unit,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    inputTestTag: String? = null,
    actionTestTag: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleAttachments()
            },
            enabled = attachEnabled || isRecordingVoice,
            modifier = Modifier
                .size(42.dp)
                .background(primaryColor.copy(alpha = 0.12f), CircleShape),
        ) {
            if (attachmentsOpen || isRecordingVoice) {
                Text("×", fontSize = 22.sp, color = primaryColor, fontWeight = FontWeight.Bold)
            } else {
                Icon(
                    painterResource(R.drawable.ic_attach_paperclip),
                    "Attach",
                    tint = if (attachEnabled) primaryColor else onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val isDark = surfaceColor.luminance() < 0.5f
        val inputBackground = if (isDark) {
            Color(0xFF14161A).copy(alpha = 0.9f)
        } else {
            Color(0xFFEFEFEF).copy(alpha = 0.9f)
        }
        if (isRecordingVoice) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(inputBackground, RoundedCornerShape(23.dp))
                    .border(0.5.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(23.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    VoiceMessageSupport.formatDuration(recordingElapsedMs),
                    color = Color.Red,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.width(10.dp))
                RecordingLiveWaveform(
                    recordingElapsedMs = recordingElapsedMs,
                    recordingAmplitudes = recordingAmplitudes,
                    activeColor = primaryColor,
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                )
            }
        } else {
            TextField(
                value = inputText,
                onValueChange = onInputTextChange,
                enabled = inputEnabled,
                placeholder = { Text(placeholder, color = onSurfaceVariant.copy(alpha = 0.5f), fontSize = 14.sp) },
                keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                    context = context,
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = inputBackground,
                    unfocusedContainerColor = inputBackground,
                    disabledContainerColor = inputBackground.copy(alpha = 0.6f),
                    focusedTextColor = onSurfaceColor,
                    unfocusedTextColor = onSurfaceColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(23.dp),
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .then(inputTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                    .border(
                        0.5.dp,
                        if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f),
                        RoundedCornerShape(23.dp),
                    ),
            )
        }
        Spacer(Modifier.width(10.dp))
        if (!isRecordingVoice) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenStickerPicker()
                },
                enabled = attachEnabled,
                modifier = Modifier
                    .size(42.dp)
                    .semantics { contentDescription = "Открыть стикеры" }
                    .background(primaryColor.copy(alpha = 0.12f), CircleShape),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_sticker_smile),
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onActionClick()
            },
            enabled = (
                if (isRecordingVoice || inputText.isBlank()) voiceActionEnabled else actionEnabled
                ) && !actionLoading,
            modifier = Modifier
                .size(42.dp)
                .then(actionTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                .background(primaryColor, CircleShape),
        ) {
            if (actionLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                val actionIconRes = when {
                    isRecordingVoice -> R.drawable.ic_voice_stop
                    isEditing -> R.drawable.ic_check
                    inputText.isBlank() -> R.drawable.ic_voice_mic
                    else -> R.drawable.ic_send_airplane
                }
                AnimatedContent(
                    targetState = actionIconRes,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.75f, animationSpec = MotionTokens.ResponsiveSpring) + fadeIn(animationSpec = MotionTokens.FastTween))
                            .togetherWith(scaleOut(targetScale = 0.75f, animationSpec = MotionTokens.FastTween) + fadeOut(animationSpec = MotionTokens.FastTween))
                    },
                    label = "composer_action_icon"
                ) { iconRes ->
                    Icon(
                        painterResource(iconRes),
                        contentDescription = "Send message",
                        tint = if (primaryColor == MintGreen) StealthBlack else Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ConversationPinnedMessageBar(
    visible: Boolean,
    title: String,
    preview: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
    pinnedCount: Int = 1,
    currentIndex: Int = 1,
    onOpenSheet: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surfaceColor.copy(alpha = 0.95f))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(34.dp).background(primaryColor, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_pin),
                        "Pinned",
                        tint = primaryColor,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    val titleText = if (pinnedCount > 1) {
                        "$title ($currentIndex/$pinnedCount)"
                    } else title
                    Text(titleText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                }
                Text(
                    preview,
                    fontSize = 12.sp,
                    color = onSurfaceColor.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onOpenSheet != null) {
                IconButton(onClick = onOpenSheet, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_pin),
                        contentDescription = "All Pinned Messages",
                        tint = primaryColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onUnpin, modifier = Modifier.size(28.dp)) {
                Text("×", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecordingLiveWaveform(
    recordingElapsedMs: Int,
    recordingAmplitudes: List<Float>,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val barCount = 24
    val displayAmplitudes = remember(recordingAmplitudes, recordingElapsedMs) {
        if (recordingAmplitudes.isNotEmpty()) {
            val list = recordingAmplitudes.takeLast(barCount)
            if (list.size < barCount) {
                List(barCount - list.size) { 0.08f } + list
            } else list
        } else {
            List(barCount) { idx ->
                val phase = (recordingElapsedMs / 50 + idx * 15) % 360
                val norm = Math.abs(Math.sin(Math.toRadians(phase.toDouble()))).toFloat()
                0.08f + (norm * 0.92f)
            }
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayAmplitudes.forEach { heightRatio ->
            val minHeight = 3.dp
            val maxHeight = 22.dp
            val barHeight = minHeight + ((maxHeight - minHeight) * heightRatio)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(activeColor)
            )
        }
    }
}
