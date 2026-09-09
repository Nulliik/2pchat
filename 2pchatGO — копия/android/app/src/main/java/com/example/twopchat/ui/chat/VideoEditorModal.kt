package com.example.twopchat.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.twopchat.data.Localizations
import com.example.twopchat.media.VideoTranscoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoEditorModal(
    videoPath: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    onSendVideo: (videoPath: String, caption: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var captionText by remember { mutableStateOf("") }
    var selectedQuality by remember { mutableStateOf(VideoTranscoder.VideoQuality.HD_720P) }

    var isCompressing by remember { mutableStateOf(false) }
    var compressionProgress by remember { mutableIntStateOf(0) }
    var compressionJob by remember { mutableStateOf<Job?>(null) }

    val inputFile = remember(videoPath) { File(videoPath) }
    val originalSizeBytes = remember(inputFile) { if (inputFile.isFile) inputFile.length() else 0L }

    val exoPlayer = remember(videoPath) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
            compressionJob?.cancel()
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isCompressing) {
                exoPlayer.stop()
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isCompressing,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .systemBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (!isCompressing) {
                                exoPlayer.stop()
                                onDismiss()
                            }
                        },
                        enabled = !isCompressing,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = Localizations.tr(
                                appLanguage,
                                ru = "Отменить",
                                en = "Cancel",
                                de = "Abbrechen",
                                es = "Cancelar",
                                fr = "Annuler",
                                pt = "Cancelar",
                                tr = "İptal"
                            ),
                            tint = onSurfaceColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = Localizations.tr(
                                appLanguage,
                                ru = "Видеозапись",
                                en = "Video",
                                de = "Video",
                                es = "Vídeo",
                                fr = "Vidéo",
                                pt = "Vídeo",
                                tr = "Video"
                            ),
                            color = onSurfaceColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (originalSizeBytes > 0L) {
                            Text(
                                text = formatFileSize(originalSizeBytes),
                                color = onSurfaceVariant.copy(alpha = 0.75f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Video Preview Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Quality Selector Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoTranscoder.VideoQuality.values().forEach { quality ->
                        val isSelected = selectedQuality == quality
                        val chipBg = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.08f)
                        val chipTextColor = if (isSelected) Color.White else onSurfaceColor

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = chipBg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !isCompressing) { selectedQuality = quality }
                        ) {
                            Text(
                                text = quality.displayName(appLanguage),
                                color = chipTextColor,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Caption Input Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = captionText,
                        onValueChange = { captionText = it },
                        enabled = !isCompressing,
                        placeholder = {
                            Text(
                                text = Localizations.tr(
                                    appLanguage,
                                    ru = "Добавить подпись...",
                                    en = "Add a caption...",
                                    de = "Beschriftung hinzufügen...",
                                    es = "Añadir subtítulo...",
                                    fr = "Ajouter une légende...",
                                    pt = "Adicionar legenda...",
                                    tr = "Açıklama ekle..."
                                ),
                                color = onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = onSurfaceVariant.copy(alpha = 0.3f),
                            focusedContainerColor = surfaceColor,
                            unfocusedContainerColor = surfaceColor,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor
                        ),
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        maxLines = 4
                    )

                    FloatingActionButton(
                        onClick = {
                            if (isCompressing) return@FloatingActionButton
                            exoPlayer.pause()

                            if (selectedQuality == VideoTranscoder.VideoQuality.ORIGINAL) {
                                onSendVideo(videoPath, captionText)
                            } else {
                                isCompressing = true
                                compressionProgress = 0
                                compressionJob = coroutineScope.launch {
                                    val result = VideoTranscoder.compressVideo(
                                        context = context,
                                        inputPath = videoPath,
                                        quality = selectedQuality,
                                        onProgress = { progress ->
                                            compressionProgress = progress
                                        }
                                    )
                                    isCompressing = false
                                    result.onSuccess { compressedFile ->
                                        onSendVideo(compressedFile.absolutePath, captionText)
                                    }.onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            Localizations.tr(
                                                appLanguage,
                                                ru = "Сжатие не удалось, отправка оригинала",
                                                en = "Compression failed, sending original",
                                                de = "Komprimierung fehlgeschlagen, Original wird gesendet",
                                                es = "Falló la compresión, enviando original",
                                                fr = "Échec de la compression, envoi de l'original",
                                                pt = "Falha na compressão, enviando original",
                                                tr = "Sıkıştırma başarısız oldu, orijinal gönderiliyor"
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onSendVideo(videoPath, captionText)
                                    }
                                }
                            }
                        },
                        containerColor = primaryColor,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Compression Progress Dialog Overlay
            if (isCompressing) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = surfaceColor,
                            modifier = Modifier
                                .padding(32.dp)
                                .widthIn(max = 320.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = Localizations.tr(
                                        appLanguage,
                                        ru = "Сжатие видео...",
                                        en = "Compressing video...",
                                        de = "Video wird komprimiert...",
                                        es = "Comprimiendo vídeo...",
                                        fr = "Compression de la vidéo...",
                                        pt = "Comprimindo vídeo...",
                                        tr = "Video sıkıştırılıyor..."
                                    ),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                LinearProgressIndicator(
                                    progress = { compressionProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = primaryColor,
                                    trackColor = onSurfaceVariant.copy(alpha = 0.2f),
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "$compressionProgress%",
                                    fontSize = 13.sp,
                                    color = onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                TextButton(
                                    onClick = {
                                        compressionJob?.cancel()
                                        isCompressing = false
                                        compressionProgress = 0
                                    }
                                ) {
                                    Text(
                                        text = Localizations.tr(
                                            appLanguage,
                                            ru = "Отмена",
                                            en = "Cancel",
                                            de = "Abbrechen",
                                            es = "Cancelar",
                                            fr = "Annuler",
                                            pt = "Cancelar",
                                            tr = "İptal"
                                        ),
                                        color = primaryColor,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

