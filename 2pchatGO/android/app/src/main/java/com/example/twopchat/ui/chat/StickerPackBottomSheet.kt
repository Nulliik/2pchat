package com.example.twopchat.ui.chat

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.media.*
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class StickerPackRequestError {
    NONE,
    PEER_OFFLINE,
    TIMEOUT,
    NOT_FOUND,
    NETWORK_ERROR,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPackBottomSheet(
    packId: String,
    fallbackEmoji: String,
    canRequestFromPeer: Boolean,
    requestInProgress: Boolean,
    previewRevision: Int,
    appLanguage: String,
    primaryColor: Color,
    requestError: StickerPackRequestError = StickerPackRequestError.NONE,
    onDismiss: () -> Unit,
    onRequestPack: () -> Unit,
    onStickerSelected: (BuiltinSticker) -> Unit,
) {
    val context = LocalContext.current
    var pack by remember(context, packId) {
        mutableStateOf(
            StickerSupport.builtinPacks.firstOrNull { it.id == packId },
        )
    }
    var packLoading by remember(context, packId) {
        mutableStateOf(pack == null)
    }
    var isInstalled by remember(context, packId) {
        mutableStateOf(pack != null)
    }
    var requestAttempted by remember(context, packId) { mutableStateOf(false) }
    var installInProgress by remember(context, packId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(context, packId, requestInProgress, previewRevision) {
        StickerSupport.builtinPacks.firstOrNull { it.id == packId }?.let {
            pack = it
            isInstalled = true
            packLoading = false
            return@LaunchedEffect
        }
        packLoading = true
        val resolved = withContext(Dispatchers.IO) {
            val installed = StickerSupport.findPack(context, packId)
            (installed ?: StickerSupport.findPeerPackPreview(context, packId)) to
                (installed != null)
        }
        pack = resolved.first
        isInstalled = resolved.second
        packLoading = false
    }
    LaunchedEffect(packId, packLoading, pack, canRequestFromPeer) {
        if (!packLoading && pack == null && canRequestFromPeer && !requestAttempted) {
            requestAttempted = true
            onRequestPack()
        }
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
                if (packLoading || requestInProgress || installInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = primaryColor,
                        strokeWidth = 2.dp,
                    )
                } else if (pack == null && canRequestFromPeer) {
                    Button(
                        onClick = {
                            requestAttempted = true
                            onRequestPack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    ) {
                        Text(
                            if (appLanguage == "Русский") "Повторить" else "Retry",
                        )
                    }
                } else if (pack != null && !isInstalled) {
                    Button(
                        onClick = {
                            installInProgress = true
                            scope.launch {
                                val installed = withContext(Dispatchers.IO) {
                                    StickerSupport.installPeerPackPreview(context, packId)
                                }
                                installInProgress = false
                                if (installed != null) {
                                    pack = installed
                                    isInstalled = true
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") {
                                            "Стикерпак добавлен"
                                        } else {
                                            "Sticker pack added"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") {
                                            "Не удалось добавить стикерпак"
                                        } else {
                                            "Could not add sticker pack"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    ) {
                        Text(if (appLanguage == "Русский") "Добавить" else "Add")
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
            val currentPack = pack
            if (currentPack != null) {
                Text(
                    text = if (appLanguage == "Русский") {
                        "${currentPack.stickers.size} стикеров"
                    } else {
                        "${currentPack.stickers.size} stickers"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(currentPack.stickers, key = { it.stickerId }) { sticker ->
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
                            AnimatedStickerImage(
                                filePath = sticker.localFilePath,
                                fallbackEmoji = sticker.emoji,
                                contentDescription = sticker.emoji.ifBlank { "Sticker" },
                                targetSizePx = 136,
                                modifier = Modifier.size(68.dp),
                            )
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
                    if (requestError != StickerPackRequestError.NONE) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text("⚠️", fontSize = 32.sp)
                            Spacer(Modifier.height(8.dp))
                            val errorMessage = when (requestError) {
                                StickerPackRequestError.PEER_OFFLINE -> if (appLanguage == "Русский") {
                                    "Собеседник находится не в сети"
                                } else {
                                    "Peer is currently offline"
                                }
                                StickerPackRequestError.TIMEOUT -> if (appLanguage == "Русский") {
                                    "Таймаут ожидания P2P ответа"
                                } else {
                                    "P2P request timed out"
                                }
                                StickerPackRequestError.NOT_FOUND -> if (appLanguage == "Русский") {
                                    "Стикерпак не найден у собеседника"
                                } else {
                                    "Sticker pack not found on peer"
                                }
                                StickerPackRequestError.NETWORK_ERROR -> if (appLanguage == "Русский") {
                                    "Ошибка передачи данных"
                                } else {
                                    "Data transfer failed"
                                }
                                StickerPackRequestError.NONE -> if (appLanguage == "Русский") {
                                    "Не удалось загрузить стикерпак"
                                } else {
                                    "Could not load sticker pack"
                                }
                            }
                            Text(
                                text = errorMessage,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        val displayEmoji = if (fallbackEmoji.startsWith("2psticker_") || fallbackEmoji.contains(".webp") || fallbackEmoji.startsWith("attachment-")) {
                            "🎭"
                        } else {
                            fallbackEmoji.ifBlank { "🎭" }
                        }
                        Text(displayEmoji, fontSize = 76.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
