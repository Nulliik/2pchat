package com.example.twopchat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import android.util.LruCache
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import java.io.FileInputStream
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import androidx.core.content.edit
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.Localizations
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.PythonBridge
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.SecureStorage
import com.example.twopchat.R
import com.example.twopchat.VoiceMessageSupport
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.theme.StealthBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.abs
object AttachmentImageCache {
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun clear() = cache.evictAll()
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

internal fun sampledImageCacheKey(filePath: String, targetWidth: Int, targetHeight: Int): String =
    "sample:$filePath:${targetWidth}x$targetHeight"

@Composable
fun rememberSampledImage(filePath: String?, targetWidth: Int = 400, targetHeight: Int = 400): Bitmap? {
    if (filePath == null) return null
    val cacheKey = sampledImageCacheKey(filePath, targetWidth, targetHeight)
    val cached = AttachmentImageCache.get(cacheKey)
    var bitmapState by remember(cacheKey) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(cacheKey) {
        if (bitmapState != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(filePath, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                    options.inJustDecodeBounds = false
                    val decoded = BitmapFactory.decodeFile(filePath, options)
                    if (decoded != null) {
                        AttachmentImageCache.put(cacheKey, decoded)
                        withContext(Dispatchers.Main) {
                            bitmapState = decoded
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return bitmapState
}

@Composable
fun rememberVideoThumbnail(filePath: String?): Bitmap? {
    if (filePath == null) return null
    val cacheKey = "thumb_$filePath"
    val cached = AttachmentImageCache.get(cacheKey)
    var bitmapState by remember(filePath) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(filePath) {
        if (bitmapState != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(filePath)
                    val frame = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    if (frame != null) {
                        AttachmentImageCache.put(cacheKey, frame)
                        withContext(Dispatchers.Main) {
                            bitmapState = frame
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return bitmapState
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullscreenImageViewer(
    imagePaths: List<String>,
    initialIndex: Int,
    appLanguage: String,
    bitmapOverrides: Map<String, Bitmap> = emptyMap(),
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imagePaths.size }
    )
    var zoomedPage by remember { mutableIntStateOf(-1) }

    LaunchedEffect(pagerState.currentPage) {
        // Zoom belongs to a page, never to the pager. Re-enable swiping as soon
        // as the selected page changes, even during a fast gesture.
        zoomedPage = -1
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch(Dispatchers.IO) {
                val currentPath = imagePaths[pagerState.currentPage]
                val uri = saveImageToPublicGallery(context, currentPath)
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        Toast.makeText(context, if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, if (appLanguage == "Русский") "Разрешение на запись отклонено" else "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = zoomedPage != pagerState.currentPage
        ) { page ->
            val imagePath = imagePaths[page]
            var scale by remember(page) { mutableStateOf(1f) }
            var offset by remember(page) { mutableStateOf(Offset.Zero) }

            val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, 5f)
                if (scale > 1f) {
                    offset += offsetChange
                } else {
                    offset = Offset.Zero
                }
                if (page == pagerState.currentPage) {
                    zoomedPage = if (scale > 1f) page else -1
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { onClose() },
                contentAlignment = Alignment.Center
            ) {
                val overriddenBitmap = bitmapOverrides[imagePath]
                val sampledBitmap = rememberSampledImage(
                    filePath = imagePath.takeIf { overriddenBitmap == null },
                    targetWidth = 2048,
                    targetHeight = 2048,
                )
                val bitmap = overriddenBitmap ?: sampledBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Fullscreen Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .transformable(
                                state = transformState,
                                // At 1x the pager owns one-finger horizontal drags. Pinch still
                                // starts zoom, and once zoomed the image owns panning. Keep the
                                // gesture detector outside the scaled graphics layer so pointer
                                // deltas stay in screen pixels at every zoom level.
                                canPan = { scale > 1f },
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onClose() },
                                    onDoubleTap = {
                                        if (scale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                            zoomedPage = -1
                                        } else {
                                            scale = 3f
                                            zoomedPage = page
                                        }
                                    }
                                )
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = if (scale > 1f) offset.x else 0f,
                                translationY = if (scale > 1f) offset.y else 0f
                            )
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // Close Button
        IconButton(
            onClick = { onClose() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_back_arrow),
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Page Indicator
        if (imagePaths.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${imagePaths.size}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            )
        }

        // Bitmap overrides are decrypted in-memory avatars. Do not materialize
        // them as plaintext files or expose a misleading download action.
        if (bitmapOverrides[imagePaths[pagerState.currentPage]] == null) {
            IconButton(
                onClick = {
                    val currentPath = imagePaths[pagerState.currentPage]
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        scope.launch(Dispatchers.IO) {
                            val uri = saveImageToPublicGallery(context, currentPath)
                            withContext(Dispatchers.Main) {
                                if (uri != null) {
                                    Toast.makeText(context, if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_download),
                    contentDescription = "Download",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

fun getVerificationEmojis(localFingerprint: String, peerFingerprint: String): List<String> {
    val emojiList = listOf(
        "🦄", "🦊", "🚀", "💎", "🍕", "🎈", "🚗", "🥝", "🎸", "🌟",
        "🦁", "🐼", "🐻", "🐨", "🐙", "🦋", "🍄", "🍉", "🍓", "🍍",
        "🥞", "🍔", "🍿", "🍩", "🍪", "🛹", "🚲", "⛵", "🛸", "🌈",
        "☀️", "⚡", "🔥", "🔮", "🛡️", "🔑", "📦", "🎨", "🎭", "🎮"
    )
    val hash = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val identityPair = listOf(localFingerprint, peerFingerprint).sorted().joinToString("|")
        digest.digest(identityPair.toByteArray(Charsets.UTF_8))
    } catch (e: java.lang.Exception) {
        (localFingerprint + peerFingerprint).toByteArray(Charsets.UTF_8)
    }
    val result = mutableListOf<String>()
    for (i in 0 until 4) {
        val byteVal = if (i < hash.size) hash[i].toInt() and 0xFF else 0
        val index = byteVal % emojiList.size
        result.add(emojiList[index])
    }
    return result
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun FullscreenVideoPlayer(
    videoPath: String,
    appLanguage: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch(Dispatchers.IO) {
                val uri = saveVideoToPublicGallery(context, videoPath)
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        Toast.makeText(context, if (appLanguage == "Русский") "Видео сохранено в Галерею" else "Video saved to Gallery", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить видео" else "Failed to save video", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, if (appLanguage == "Русский") "Разрешение на запись отклонено" else "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val exoPlayer = remember(videoPath) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(Uri.fromFile(java.io.File(videoPath)))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        IconButton(
            onClick = { onClose() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_back_arrow),
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    scope.launch(Dispatchers.IO) {
                        val uri = saveVideoToPublicGallery(context, videoPath)
                        withContext(Dispatchers.Main) {
                            if (uri != null) {
                                Toast.makeText(context, if (appLanguage == "Русский") "Видео сохранено в Галерею" else "Video saved to Gallery", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить видео" else "Failed to save video", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_download),
                contentDescription = "Download",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

fun saveImageToPublicGallery(context: android.content.Context, filePath: String): Uri? {
    val srcFile = File(filePath)
    if (!srcFile.exists()) return null

    val extension = srcFile.extension.lowercase()
    val mimeType = when (extension) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/x-ms-bmp"
        else -> "image/jpeg"
    }
    val fileName = "2pchat_${System.currentTimeMillis()}.${if (extension.isNotEmpty()) extension else "jpg"}"

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "2PChat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                resolver.openOutputStream(imageUri).use { outputStream ->
                    if (outputStream != null) {
                        FileInputStream(srcFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
                return imageUri
            }
        } else {
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "2PChat"
            )
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, fileName)
            FileOutputStream(targetFile).use { outputStream ->
                FileInputStream(srcFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            return Uri.fromFile(targetFile)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun saveVideoToPublicGallery(context: android.content.Context, filePath: String): Uri? {
    val srcFile = File(filePath)
    if (!srcFile.exists()) return null

    val extension = srcFile.extension.lowercase()
    val mimeType = when (extension) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }
    val fileName = "2pchat_${System.currentTimeMillis()}.${if (extension.isNotEmpty()) extension else "mp4"}"

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "2PChat")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (videoUri != null) {
                resolver.openOutputStream(videoUri).use { outputStream ->
                    if (outputStream != null) {
                        FileInputStream(srcFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, contentValues, null, null)
                return videoUri
            }
        } else {
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "2PChat"
            )
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, fileName)
            FileOutputStream(targetFile).use { outputStream ->
                FileInputStream(srcFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            return Uri.fromFile(targetFile)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
