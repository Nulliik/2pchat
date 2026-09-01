package com.example.twopchat.ui.chat

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.twopchat.R
import com.example.twopchat.data.Localizations
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPreviewModal(
    files: List<File>,
    appLanguage: String,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
    onSurfaceColor: Color = MaterialTheme.colorScheme.onSurface,
    onDismiss: () -> Unit,
    onSendAlbum: (finalFiles: List<File>, caption: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var captionText by remember { mutableStateOf("") }
    var sendOriginalQuality by remember { mutableStateOf(false) }
    var showQualityTooltip by remember { mutableStateOf(false) }
    var qualityTooltipJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isCompressing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentFiles = remember(files) { mutableStateListOf<File>().apply { addAll(files) } }
    var selectedPreviewIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentFiles.size) {
        if (currentFiles.isEmpty()) {
            onDismiss()
        } else if (selectedPreviewIndex >= currentFiles.size) {
            selectedPreviewIndex = currentFiles.lastIndex.coerceAtLeast(0)
        }
    }


    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .systemBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back_arrow),
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            ru = "Альбом (${currentFiles.size})",
                            en = "Album (${currentFiles.size})",
                            de = "Album (${currentFiles.size})",
                            es = "Álbum (${currentFiles.size})",
                            fr = "Album (${currentFiles.size})",
                            pt = "Álbum (${currentFiles.size})"
                        ),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AnimatedQualityToggle(
                            isHd = sendOriginalQuality,
                            onToggle = {
                                sendOriginalQuality = !sendOriginalQuality
                                showQualityTooltip = true
                                qualityTooltipJob?.cancel()
                                qualityTooltipJob = scope.launch {
                                    delay(1800)
                                    showQualityTooltip = false
                                }
                            },
                            activeColor = primaryColor,
                            inactiveColor = Color.White
                        )
                        IconButton(
                            onClick = {
                                if (currentFiles.isNotEmpty()) {
                                    currentFiles.removeAt(selectedPreviewIndex)
                                    if (currentFiles.isEmpty()) {
                                        onDismiss()
                                    } else if (selectedPreviewIndex >= currentFiles.size) {
                                        selectedPreviewIndex = currentFiles.lastIndex
                                    }
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete),
                                contentDescription = "Delete Item",
                                tint = Color(0xFFFF5252)
                            )
                        }
                    }
                }

                // Quality Tooltip Popup
                AnimatedVisibility(
                    visible = showQualityTooltip,
                    enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { -it / 2 },
                    exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .zIndex(20f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        QualityTooltipBubble(
                            isHd = sendOriginalQuality,
                            appLanguage = appLanguage,
                            arrowAtTop = true,
                            modifier = Modifier.padding(end = 56.dp)
                        )
                    }
                }

                // Main Preview Image Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val targetFile = currentFiles.getOrNull(selectedPreviewIndex) ?: currentFiles.firstOrNull()
                    if (targetFile != null && targetFile.exists()) {
                        AsyncImage(
                            model = targetFile,
                            contentDescription = "Album preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .background(Color.White.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_attach_paperclip),
                                contentDescription = "Media",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    // Move left/right reorder overlay buttons
                    if (currentFiles.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = Localizations.tr(
                                    appLanguage,
                                    ru = "◀ Влево",
                                    en = "◀ Left",
                                    de = "◀ Links",
                                    es = "◀ Izquierda",
                                    fr = "◀ Gauche",
                                    pt = "◀ Esquerda"
                                ),
                                color = if (selectedPreviewIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(enabled = selectedPreviewIndex > 0) {
                                    val item = currentFiles.removeAt(selectedPreviewIndex)
                                    selectedPreviewIndex -= 1
                                    currentFiles.add(selectedPreviewIndex, item)
                                }
                            )
                            Text(
                                text = Localizations.tr(
                                    appLanguage,
                                    ru = "Вправо ▶",
                                    en = "Right ▶",
                                    de = "Rechts ▶",
                                    es = "Derecha ▶",
                                    fr = "Droite ▶",
                                    pt = "Direita ▶"
                                ),
                                color = if (selectedPreviewIndex < currentFiles.lastIndex) Color.White else Color.White.copy(alpha = 0.3f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(enabled = selectedPreviewIndex < currentFiles.lastIndex) {
                                    val item = currentFiles.removeAt(selectedPreviewIndex)
                                    selectedPreviewIndex += 1
                                    currentFiles.add(selectedPreviewIndex, item)
                                }
                            )
                        }
                    }
                }

                // Thumbnails List
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(currentFiles.toList()) { idx, file ->
                        val isSelected = idx == selectedPreviewIndex

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isSelected) 2.5.dp else 0.dp,
                                    color = if (isSelected) primaryColor else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedPreviewIndex = idx }
                        ) {
                            if (file.exists()) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = "Thumb $idx",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.White.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Caption Input and Send Button Footer
                Surface(
                    color = surfaceColor,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = captionText,
                            onValueChange = { captionText = it },
                            placeholder = {
                                Text(
                                    text = Localizations.tr(
                                        appLanguage,
                                        ru = "Добавить подпись...",
                                        en = "Add a caption...",
                                        de = "Beschriftung hinzufügen...",
                                        es = "Añadir subtítulo...",
                                        fr = "Ajouter une légende...",
                                        pt = "Adicionar legenda..."
                                    ),
                                    color = onSurfaceColor.copy(alpha = 0.5f)
                                )
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                                context = context,
                                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                            ),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.2f),
                                focusedContainerColor = surfaceColor,
                                unfocusedContainerColor = surfaceColor,
                                focusedTextColor = onSurfaceColor,
                                unfocusedTextColor = onSurfaceColor,
                            )
                        )

                        IconButton(
                            onClick = {
                                if (currentFiles.isNotEmpty()) {
                                    if (sendOriginalQuality) {
                                        onSendAlbum(currentFiles.toList(), captionText.trim())
                                    } else {
                                        isCompressing = true
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val attachmentsDir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
                                            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                                            val processedFiles = currentFiles.mapIndexed { idx, file ->
                                                try {
                                                    val ext = file.extension.lowercase()
                                                    if (ext in listOf("jpg", "jpeg", "png", "webp")) {
                                                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                                                        if (bmp != null) {
                                                            val maxDim = 1920
                                                            var scaledBmp = bmp
                                                            if (bmp.width > maxDim || bmp.height > maxDim) {
                                                                val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                                                                val newW = if (ratio > 1f) maxDim else (maxDim * ratio).toInt()
                                                                val newH = if (ratio > 1f) (maxDim / ratio).toInt() else maxDim
                                                                scaledBmp = android.graphics.Bitmap.createScaledBitmap(bmp, newW, newH, true)
                                                            }
                                                            val destFile = File(attachmentsDir, "album_${timeStamp}_${idx}_${java.util.UUID.randomUUID().toString().take(6)}.jpg")
                                                            java.io.FileOutputStream(destFile).use { out ->
                                                                scaledBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                                                            }
                                                            destFile
                                                        } else file
                                                    } else file
                                                } catch (_: Exception) {
                                                    file
                                                }
                                            }
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                isCompressing = false
                                                onSendAlbum(processedFiles, captionText.trim())
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isCompressing,
                            modifier = Modifier
                                .size(48.dp)
                                .background(primaryColor, CircleShape)
                        ) {
                            if (isCompressing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_send_airplane),
                                    contentDescription = "Send Album",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
