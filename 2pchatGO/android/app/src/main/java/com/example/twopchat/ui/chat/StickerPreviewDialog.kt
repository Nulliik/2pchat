package com.example.twopchat.ui.chat

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.twopchat.R
import com.example.twopchat.data.Localizations
import com.example.twopchat.media.BuiltinSticker

@Composable
internal fun StickerPreviewDialog(
    sticker: BuiltinSticker?,
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onSendSticker: (BuiltinSticker) -> Unit,
) {
    if (sticker == null) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Emoji badge above sticker (Telegram style)
                if (sticker.emoji.isNotBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF262628),
                        shadowElevation = 6.dp,
                    ) {
                        Text(
                            text = sticker.emoji,
                            fontSize = 28.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                // Main Sticker Large Preview Card
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (sticker.localFilePath == null) {
                        Color(sticker.backgroundColor).copy(alpha = 0.9f)
                    } else {
                        Color(0xFF1E1E20)
                    },
                    shadowElevation = 14.dp,
                    modifier = Modifier.size(200.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedStickerImage(
                            filePath = sticker.localFilePath,
                            fallbackEmoji = sticker.emoji,
                            contentDescription = sticker.emoji.ifBlank { "Sticker" },
                            targetSizePx = 384,
                            modifier = Modifier.size(160.dp),
                        )
                    }
                }

                // Action Buttons: Close & Send
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF262628),
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
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
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
