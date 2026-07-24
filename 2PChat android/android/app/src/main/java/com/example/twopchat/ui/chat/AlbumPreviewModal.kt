package com.example.twopchat.ui.chat

import android.graphics.BitmapFactory
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
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPreviewModal(
    files: List<File>,
    appLanguage: String,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
    onSurfaceColor: Color = MaterialTheme.colorScheme.onSurface,
    onDismiss: () -> Unit,
    onSendAlbum: (caption: String) -> Unit,
) {
    var captionText by remember { mutableStateOf("") }
    var selectedPreviewIndex by remember { mutableIntStateOf(0) }

    val mainPreviewBitmap = remember(files, selectedPreviewIndex) {
        val targetFile = files.getOrNull(selectedPreviewIndex) ?: files.firstOrNull()
        if (targetFile != null && targetFile.exists()) {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(targetFile.absolutePath, options)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    val isRu = appLanguage == "Русский"

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
                    text = if (isRu) "Альбом (${files.size})" else "Album (${files.size})",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(modifier = Modifier.size(48.dp))
            }

            // Main Preview Image Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (mainPreviewBitmap != null) {
                    Image(
                        bitmap = mainPreviewBitmap.asImageBitmap(),
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
                itemsIndexed(files) { idx, file ->
                    val isSelected = idx == selectedPreviewIndex
                    val thumbBitmap = remember(file) {
                        if (file.exists()) {
                            try {
                                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                                BitmapFactory.decodeFile(file.absolutePath, options)
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                    }

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
                        if (thumbBitmap != null) {
                            Image(
                                bitmap = thumbBitmap.asImageBitmap(),
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
                                text = if (isRu) "Добавить подпись..." else "Add a caption...",
                                color = onSurfaceColor.copy(alpha = 0.5f)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
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
                        onClick = { onSendAlbum(captionText.trim()) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(primaryColor, CircleShape)
                    ) {
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
