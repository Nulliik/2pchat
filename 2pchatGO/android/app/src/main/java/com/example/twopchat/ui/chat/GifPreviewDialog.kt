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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.twopchat.media.StoredGif
import kotlinx.coroutines.delay

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

@Composable
internal fun GifPreviewDialog(
    gif: StoredGif?,
    appLanguage: String,
    primaryColor: Color,
    initialShowActions: Boolean = false,
    onActionsRevealed: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSendGif: (StoredGif) -> Unit,
) {
    if (gif == null) return

    var showActions by remember(gif.id) { mutableStateOf(initialShowActions) }

    LaunchedEffect(gif.id, initialShowActions) {
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
        val context = LocalContext.current
        val density = LocalDensity.current
        val screenWidth = remember(density) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager?.currentWindowMetrics?.bounds
            } else null
            val px = bounds?.width() ?: context.resources.displayMetrics.widthPixels
            with(density) { px.toDp() }
        }
        val screenHeight = remember(density) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager?.currentWindowMetrics?.bounds
            } else null
            val px = bounds?.height() ?: context.resources.displayMetrics.heightPixels
            with(density) { px.toDp() + 160.dp }
        }

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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Main GIF Preview Box (Clean floating card, Telegram style)
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedGifImage(
                        filePath = gif.filePath,
                        targetMaxDimensionPx = 640,
                        contentScale = GifContentScale.FIT,
                        contentDescription = "GIF preview",
                        modifier = Modifier.fillMaxSize(),
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
                                    tr = "Kapat"
                                ),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Button(
                            onClick = {
                                onDismiss()
                                onSendGif(gif)
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
                                        tr = "Gönder"
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
