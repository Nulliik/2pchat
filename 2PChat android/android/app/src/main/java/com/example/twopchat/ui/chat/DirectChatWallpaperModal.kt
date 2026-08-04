package com.example.twopchat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.twopchat.R
import com.example.twopchat.theme.MintGreen
import com.example.twopchat.theme.StealthBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectChatWallpaperModal(
    peerName: String,
    currentWallpaperPath: String?,
    currentDimming: Int,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    onApply: (selectedBitmap: Bitmap?, dimming: Int, isBlur: Boolean, isMotion: Boolean, applyToPeer: Boolean) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var dimming by remember { mutableFloatStateOf(currentDimming.toFloat().coerceIn(0f, 80f)) }
    var isBlur by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<WallpaperPreset?>(null) }

    val animatedBlurRadius by animateDpAsState(
        targetValue = if (isBlur) 12.dp else 0.dp,
        animationSpec = tween(300),
        label = "blurAnimation"
    )
    val animatedDimmingAlpha by animateFloatAsState(
        targetValue = dimming / 100f,
        animationSpec = tween(150),
        label = "dimmingAnimation"
    )

    // Gesture Transform States (Scale & Offset for panning/zooming)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            selectedPreset = null
            // Reset transform gestures when picking a new image
            scale = 1f
            offset = Offset.Zero
        }
    }

    fun clampOffset(currentOffset: Offset, currentScale: Float, size: IntSize): Offset {
        if (size.width <= 0 || size.height <= 0) return currentOffset
        val maxX = (size.width * (currentScale - 1f)) / 2f
        val maxY = (size.height * (currentScale - 1f)) / 2f
        return Offset(
            currentOffset.x.coerceIn(-maxX, maxX),
            currentOffset.y.coerceIn(-maxY, maxY)
        )
    }

    LaunchedEffect(selectedUri, currentWallpaperPath) {
        if (selectedPreset != null) return@LaunchedEffect
        previewBitmap = withContext(Dispatchers.IO) {
            try {
                if (selectedUri != null) {
                    decodeSampledBitmapFromUri(context, selectedUri!!)
                } else if (!currentWallpaperPath.isNullOrBlank()) {
                    decodeSampledBitmapFromFile(currentWallpaperPath)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun prepareFinalBitmap(): Bitmap? {
        val src = previewBitmap ?: return null
        return if (scale != 1f || offset != Offset.Zero) {
            transformBitmap(src, containerSize, scale, offset)
        } else {
            src
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .onSizeChanged { containerSize = it }
        ) {
            // Live Preview Background with Pinch-to-Zoom & Pan Gesture Support
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap!!.asImageBitmap(),
                    contentDescription = "Wallpaper Preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                // Clamp offset so the image cannot be dragged beyond its scaled bounds
                                offset = clampOffset(offset + pan, newScale, containerSize)
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                        .then(if (animatedBlurRadius > 0.dp) Modifier.blur(animatedBlurRadius) else Modifier)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E293B))
                )
            }

            // Dimming Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = animatedDimmingAlpha))
            )

            // Top Section Column: Top Header Bar + Compact Dimming Slider Row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back_arrow),
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Обои" else "Wallpaper",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_attach_gallery),
                            contentDescription = "Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Compact Dimming Slider Bar (Relocated to Top)
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Затемнение" else "Dimming",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(10.dp))
                        Slider(
                            value = dimming,
                            onValueChange = { dimming = it },
                            valueRange = 0f..80f,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${dimming.toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Center Content: Telegram-style Message Preview Bubbles
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Info notice pill
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "$peerName также сможет установить эти обои" else "$peerName will also be able to set this wallpaper",
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                // Sample Incoming Bubble
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        color = Color(0xFF1E2732),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (appLanguage == "Русский") "Посмотри, как тебе такой вариант оформления?" else "Look, how do you like this design?",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "04:25",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }

                // Sample Outgoing Bubble
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        color = primaryColor,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (appLanguage == "Русский") "Выглядит отлично, оставляем!" else "Looks great, let's keep it!",
                                color = if (primaryColor == MintGreen) StealthBlack else Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "04:25 ✓✓",
                                color = (if (primaryColor == MintGreen) StealthBlack else Color.White).copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }

            // Bottom Controls Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Presets Gallery Selector Row
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Готовые темы" else "Preset Wallpapers",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(WallpaperPreset.entries, key = { it.name }) { preset ->
                            val isSelected = selectedPreset == preset && previewBitmap != null
                            Box(
                                modifier = Modifier
                                    .size(width = 72.dp, height = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        brush = Brush.linearGradient(preset.colors),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) primaryColor else Color.White.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedPreset = preset
                                        scale = 1f
                                        offset = Offset.Zero
                                        val targetW = containerSize.width.coerceAtLeast(720)
                                        val targetH = containerSize.height.coerceAtLeast(1280)
                                        previewBitmap = createPresetBitmap(preset, targetW, targetH)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") preset.titleRu else preset.titleEn,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Centered "Размытие" (Blur) Toggle Button
                Surface(
                    color = if (isBlur) primaryColor.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, if (isBlur) primaryColor else Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isBlur = !isBlur
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isBlur,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = primaryColor),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (appLanguage == "Русский") "Размытие" else "Blur",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Pick / Change Image Button with Vector Icon
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        imagePickerLauncher.launch("image/*")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.16f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_attach_gallery),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Выбрать из галереи" else "Pick from gallery",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Action Buttons Row (Apply for me & Apply for me and Peer)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Apply for me only
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val finalBmp = prepareFinalBitmap()
                            onApply(finalBmp, dimming.toInt(), isBlur, false, false)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.18f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Применить у меня" else "Apply for me",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    // Apply for me AND peer
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val finalBmp = prepareFinalBitmap()
                            onApply(finalBmp, dimming.toInt(), isBlur, false, true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Применить у меня и $peerName" else "Apply for me and $peerName",
                            color = if (primaryColor == MintGreen) StealthBlack else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

private fun transformBitmap(
    source: Bitmap,
    containerSize: IntSize,
    scale: Float,
    offset: Offset
): Bitmap {
    if (containerSize.width <= 0 || containerSize.height <= 0) return source
    val output = Bitmap.createBitmap(containerSize.width, containerSize.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    val scaleX = containerSize.width.toFloat() / source.width.toFloat()
    val scaleY = containerSize.height.toFloat() / source.height.toFloat()
    val baseScale = maxOf(scaleX, scaleY)

    val baseWidth = source.width * baseScale
    val baseHeight = source.height * baseScale
    val baseTranslateX = (containerSize.width - baseWidth) / 2f
    val baseTranslateY = (containerSize.height - baseHeight) / 2f

    val matrix = Matrix().apply {
        postScale(baseScale, baseScale)
        postTranslate(baseTranslateX, baseTranslateY)
        postScale(scale, scale, containerSize.width / 2f, containerSize.height / 2f)
        postTranslate(offset.x, offset.y)
    }

    val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    canvas.drawBitmap(source, matrix, paint)
    return output
}

enum class WallpaperPreset(
    val titleRu: String,
    val titleEn: String,
    val colors: List<Color>
) {
    NEBULA("Небула", "Nebula", listOf(Color(0xFF0F172A), Color(0xFF3B0764), Color(0xFF1E1B4B))),
    EMERALD("Изумруд", "Emerald", listOf(Color(0xFF064E3B), Color(0xFF022C22), Color(0xFF065F46))),
    SUNSET("Закат", "Sunset", listOf(Color(0xFF4C1D95), Color(0xFF831843), Color(0xFF9A3412))),
    CYBER("Кибер", "Cyber", listOf(Color(0xFF0F172A), Color(0xFF0891B2), Color(0xFF155E75))),
    MINIMAL("Тёмный", "Minimal", listOf(Color(0xFF1E293B), Color(0xFF0F172A), Color(0xFF334155)))
}

private fun createPresetBitmap(preset: WallpaperPreset, width: Int = 720, height: Int = 1280): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val colorsInt = preset.colors.map { 
        android.graphics.Color.argb(
            (it.alpha * 255).toInt(), 
            (it.red * 255).toInt(), 
            (it.green * 255).toInt(), 
            (it.blue * 255).toInt()
        ) 
    }.toIntArray()
    val shader = android.graphics.LinearGradient(
        0f, 0f, width.toFloat(), height.toFloat(),
        colorsInt, null, android.graphics.Shader.TileMode.CLAMP
    )
    val paint = Paint().apply {
        isAntiAlias = true
        this.shader = shader
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    return bitmap
}

private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDim: Int = 1920): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        var sampleSize = 1
        val w = options.outWidth
        val h = options.outHeight
        if (w > maxDim || h > maxDim) {
            val halfW = w / 2
            val halfH = h / 2
            while ((halfW / sampleSize) >= maxDim && (halfH / sampleSize) >= maxDim) {
                sampleSize *= 2
            }
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    } catch (e: Exception) {
        null
    }
}

private fun decodeSampledBitmapFromFile(path: String, maxDim: Int = 1920): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        var sampleSize = 1
        val w = options.outWidth
        val h = options.outHeight
        if (w > maxDim || h > maxDim) {
            val halfW = w / 2
            val halfH = h / 2
            while ((halfW / sampleSize) >= maxDim && (halfH / sampleSize) >= maxDim) {
                sampleSize *= 2
            }
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(path, decodeOptions)
    } catch (e: Exception) {
        null
    }
}
