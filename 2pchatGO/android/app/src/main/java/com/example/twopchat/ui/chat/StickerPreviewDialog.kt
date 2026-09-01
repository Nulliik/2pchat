package com.example.twopchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.twopchat.R
import com.example.twopchat.data.Localizations
import com.example.twopchat.media.BuiltinSticker
import kotlinx.coroutines.delay

@Composable
internal fun StickerPreviewDialog(
    sticker: BuiltinSticker?,
    appLanguage: String,
    primaryColor: Color,
    initialShowActions: Boolean = false,
    onActionsRevealed: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSendSticker: (BuiltinSticker) -> Unit,
) {
    if (sticker == null) return

    val cleanEmoji = remember(sticker.emoji) {
        val raw = sticker.emoji.trim()
        val withoutMask = raw.replace("🎭", "").trim()
        if (withoutMask.isNotEmpty()) withoutMask else ""
    }

    var showActions by remember(sticker.stickerId) { mutableStateOf(initialShowActions) }

    LaunchedEffect(sticker.stickerId, initialShowActions) {
        if (!initialShowActions) {
            showActions = false
            delay(1500L)
            showActions = true
            onActionsRevealed?.invoke()
        } else {
            showActions = true
        }
    }

    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                return IntOffset(0, 0)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val screenHeight = configuration.screenHeightDp.dp

        Box(
            modifier = Modifier
                .size(screenWidth, screenHeight)
                .background(Color.Black.copy(alpha = 0.55f))
                .then(
                    if (showActions) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp, start = 24.dp, end = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Emoji badge above sticker (like Telegram Screenshot 1)
                if (cleanEmoji.isNotBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF262628),
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            text = cleanEmoji,
                            fontSize = 32.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                // Main Sticker Preview (Clean floating sticker, Telegram style!)
                Box(
                    modifier = Modifier.size(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedStickerImage(
                        filePath = sticker.localFilePath,
                        fallbackEmoji = cleanEmoji.ifBlank { sticker.emoji },
                        contentDescription = cleanEmoji.ifBlank { "Sticker" },
                        targetSizePx = 480,
                        modifier = Modifier.size(230.dp),
                    )
                }

                // Action Buttons: Close & Send (Appear after 1.5s hold or kept visible)
                AnimatedVisibility(
                    visible = showActions,
                    enter = fadeIn(tween(250)) + expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)),
                    exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(0.88f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF262628).copy(alpha = 0.9f),
                                contentColor = Color.White,
                            ),
                            border = null,
                        ) {
                            Text(
                                text = Localizations.tr(
                                    appLanguage,
                                    ru = "Закрыть",
                                    en = "Close",
                                    de = "Schließen",
                                    es = "Cerrar",
                                    fr = "Fermer",
                                    pt = "Fechar",
                                ),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Button(
                            onClick = {
                                onDismiss()
                                onSendSticker(sticker)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryColor,
                                contentColor = Color.White,
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_send_airplane),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White,
                                )
                                Text(
                                    text = Localizations.tr(
                                        appLanguage,
                                        ru = "Отправить",
                                        en = "Send",
                                        de = "Senden",
                                        es = "Enviar",
                                        fr = "Envoyer",
                                        pt = "Enviar",
                                    ),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
