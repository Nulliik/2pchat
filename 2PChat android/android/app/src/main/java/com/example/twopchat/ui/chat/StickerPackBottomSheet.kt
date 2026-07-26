package com.example.twopchat.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.BuiltinSticker
import com.example.twopchat.StickerSupport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPackBottomSheet(
    packId: String,
    fallbackEmoji: String,
    canRequestFromPeer: Boolean,
    requestInProgress: Boolean,
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onRequestPack: () -> Unit,
    onStickerSelected: (BuiltinSticker) -> Unit,
) {
    val context = LocalContext.current
    val pack = remember(context, packId, requestInProgress) {
        StickerSupport.findPack(context, packId)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = pack?.title ?: if (appLanguage == "Русский") {
                            "Стикерпак собеседника"
                        } else {
                            "Peer sticker pack"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = pack?.author ?: packId,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pack == null && canRequestFromPeer) {
                    Button(
                        onClick = onRequestPack,
                        enabled = !requestInProgress,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    ) {
                        Text(
                            if (requestInProgress) {
                                if (appLanguage == "Русский") "Загрузка…" else "Loading…"
                            } else {
                                if (appLanguage == "Русский") "Добавить" else "Add"
                            },
                        )
                    }
                } else if (pack != null) {
                    Text(
                        text = if (appLanguage == "Русский") "В коллекции" else "In collection",
                        color = primaryColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (pack != null) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pack.stickers, key = { it.stickerId }) { sticker ->
                        val bitmap = rememberSampledImage(
                            sticker.localFilePath,
                            targetWidth = 128,
                            targetHeight = 128,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(78.dp)
                                .background(
                                    if (sticker.localFilePath == null) {
                                        Color(sticker.backgroundColor).copy(alpha = 0.75f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    },
                                    RoundedCornerShape(20.dp),
                                )
                                .clickable {
                                    onStickerSelected(sticker)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = sticker.emoji,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(68.dp),
                                )
                            } else {
                                Text(sticker.emoji.ifBlank { "🎭" }, fontSize = 40.sp)
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(24.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(fallbackEmoji.ifBlank { "🎭" }, fontSize = 76.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
