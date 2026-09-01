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
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.R
import com.example.twopchat.media.*
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
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
import java.util.concurrent.ConcurrentHashMap
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
    private val decodeSlots = Semaphore(6)
    private val decodeLocks = ConcurrentHashMap<String, Mutex>()

    fun get(key: String): Bitmap? = cache.get(key)
    fun clear() = cache.evictAll()
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    suspend fun getOrLoad(key: String, loader: () -> Bitmap?): Bitmap? {
        get(key)?.let { return it }
        val lock = decodeLocks.computeIfAbsent(key) { Mutex() }
        return try {
            lock.withLock {
                get(key) ?: decodeSlots.withPermit {
                    loader()?.also { put(key, it) }
                }
            }
        } finally {
            decodeLocks.remove(key, lock)
        }
    }
}

internal fun sampledImageCacheKey(filePath: String, targetWidth: Int, targetHeight: Int): String {
    val clean = filePath.removePrefix("file://")
    return "sample:$clean:${targetWidth}x$targetHeight"
}

fun resolveAttachmentFile(context: android.content.Context, filePath: String?): java.io.File? {
    if (filePath.isNullOrBlank()) return null
    val cleanPath = filePath.removePrefix("file://")
    val direct = java.io.File(cleanPath)
    if (direct.exists() && direct.length() > 0L) return direct

    val attachDirFile = java.io.File(java.io.File(context.filesDir, "attachments"), cleanPath)
    if (attachDirFile.exists() && attachDirFile.length() > 0L) return attachDirFile

    val filesDirFile = java.io.File(context.filesDir, cleanPath)
    if (filesDirFile.exists() && filesDirFile.length() > 0L) return filesDirFile

    val byName = java.io.File(context.filesDir, direct.name)
    if (byName.exists() && byName.length() > 0L) return byName

    return null
}

private fun extractVideoThumbnail(
    retriever: android.media.MediaMetadataRetriever,
    targetWidth: Int = 400,
    targetHeight: Int = 400,
): Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        retriever.getScaledFrameAtTime(
            0,
            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            targetWidth,
            targetHeight,
        )
    } else {
        val full = retriever.getFrameAtTime(
            0,
            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        if (full.width > targetWidth || full.height > targetHeight) {
            val scale = minOf(targetWidth.toFloat() / full.width, targetHeight.toFloat() / full.height)
            val dstW = (full.width * scale).toInt().coerceAtLeast(1)
            val dstH = (full.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(full, dstW, dstH, true)
            if (scaled != full) full.recycle()
            scaled
        } else {
            full
        }
    }
}

@Composable
fun rememberSampledImage(filePath: String?, targetWidth: Int = 400, targetHeight: Int = 400): Bitmap? {
    if (filePath.isNullOrBlank()) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = sampledImageCacheKey(filePath, targetWidth, targetHeight)
    val cached = AttachmentImageCache.get(cacheKey)
    var bitmapState by remember(cacheKey) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(cacheKey) {
        if (bitmapState != null) return@LaunchedEffect
        bitmapState = withContext(Dispatchers.IO) {
            try {
                val targetFile = resolveAttachmentFile(context, filePath)
                val cleanPath = filePath.removePrefix("file://")

                if (targetFile != null) {
                    AttachmentImageCache.getOrLoad(cacheKey) {
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(targetFile.absolutePath, options)
                        options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                        options.inJustDecodeBounds = false
                        options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        BitmapFactory.decodeFile(targetFile.absolutePath, options)
                    }
                } else if (cleanPath.startsWith("content://")) {
                    context.contentResolver.openInputStream(android.net.Uri.parse(cleanPath))?.use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 2
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    return bitmapState
}

@Composable
fun rememberVideoThumbnail(filePath: String?, targetWidth: Int = 400, targetHeight: Int = 400): Bitmap? {
    if (filePath.isNullOrBlank()) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val cleanPath = filePath.removePrefix("file://")
    val cacheKey = "thumb_${cleanPath}_${targetWidth}x$targetHeight"
    val cached = AttachmentImageCache.get(cacheKey)
    var bitmapState by remember(cacheKey) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(cacheKey) {
        if (bitmapState != null) return@LaunchedEffect
        bitmapState = withContext(Dispatchers.IO) {
            try {
                val targetFile = resolveAttachmentFile(context, filePath)
                if (targetFile != null && targetFile.exists()) {
                    AttachmentImageCache.getOrLoad(cacheKey) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(targetFile.absolutePath)
                            extractVideoThumbnail(retriever, targetWidth, targetHeight)
                        } catch (_: Exception) {
                            null
                        } finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                    }
                } else if (cleanPath.startsWith("content://")) {
                    AttachmentImageCache.getOrLoad(cacheKey) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, android.net.Uri.parse(cleanPath))
                            extractVideoThumbnail(retriever, targetWidth, targetHeight)
                        } catch (_: Exception) {
                            null
                        } finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
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
        while (halfHeight / inSampleSize >= reqHeight || halfWidth / inSampleSize >= reqWidth) {
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
    onGoToMessage: ((String) -> Unit)? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val fullscreenGifTargetPx = remember(configuration, density) {
        with(density) {
            maxOf(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
                .roundToPx()
                .times(2)
                .coerceIn(720, 2_048)
        }
    }
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

    val dismissOffsetY = remember { Animatable(0f) }
    val bgAlpha = (1f - (kotlin.math.abs(dismissOffsetY.value) / 600f)).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = zoomedPage != pagerState.currentPage && kotlin.math.abs(dismissOffsetY.value) < 10f
        ) { page ->
            val imagePath = imagePaths[page]
            val isGif = remember(imagePath) { isGifMediaPath(imagePath) }
            var scale by remember(page) { mutableFloatStateOf(1f) }
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

            val isZoomed = scale > 1.05f
            val dragModifier = if (!isZoomed) {
                Modifier.pointerInput(page) {
                    detectDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(dismissOffsetY.value) > 160f) {
                                scope.launch {
                                    dismissOffsetY.animateTo(
                                        if (dismissOffsetY.value > 0) 1200f else -1200f,
                                        animationSpec = tween(180)
                                    )
                                    onClose()
                                }
                            } else {
                                scope.launch {
                                    dismissOffsetY.animateTo(
                                        0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                dismissOffsetY.animateTo(0f, animationSpec = spring())
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x) || kotlin.math.abs(dismissOffsetY.value) > 10f) {
                                change.consume()
                                scope.launch {
                                    dismissOffsetY.snapTo(dismissOffsetY.value + dragAmount.y)
                                }
                            }
                        }
                    )
                }
            } else {
                Modifier
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(dragModifier)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { onClose() },
                contentAlignment = Alignment.Center
            ) {
                val overriddenBitmap = bitmapOverrides[imagePath]
                val sampledBitmap = rememberSampledImage(
                    filePath = imagePath.takeIf { overriddenBitmap == null && !isGif },
                    targetWidth = 2048,
                    targetHeight = 2048,
                )
                val bitmap = overriddenBitmap ?: sampledBitmap
                val mediaModifier = Modifier
                    .fillMaxSize()
                    .transformable(
                        state = transformState,
                        // At 1x the pager owns one-finger horizontal drags. Pinch still
                        // starts zoom, and once zoomed the media owns panning.
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
                                    scale = 2.5f
                                    zoomedPage = page
                                }
                            },
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = if (scale > 1f) offset.x else 0f,
                        translationY = if (scale > 1f) offset.y else dismissOffsetY.value,
                    )
                if (isGif) {
                    AnimatedGifImage(
                        filePath = imagePath,
                        targetMaxDimensionPx = fullscreenGifTargetPx,
                        contentScale = GifContentScale.FIT,
                        contentDescription = "Fullscreen GIF",
                        modifier = mediaModifier,
                        isAnimationEnabled = page == pagerState.currentPage,
                    )
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Fullscreen Image",
                        modifier = mediaModifier,
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
                .alpha(bgAlpha)
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
                    .alpha(bgAlpha)
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

        if (onGoToMessage != null) {
            val hasDownload = bitmapOverrides[imagePaths[pagerState.currentPage]] == null
            IconButton(
                onClick = { onGoToMessage(imagePaths[pagerState.currentPage]) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = if (hasDownload) 64.dp else 16.dp)
                    .alpha(bgAlpha)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_eye),
                    contentDescription = "Go to message in chat",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

internal fun isGifMediaPath(path: String): Boolean =
    File(path).extension.equals("gif", ignoreCase = true)

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
    onGoToMessage: ((String) -> Unit)? = null,
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

        if (onGoToMessage != null) {
            IconButton(
                onClick = { onGoToMessage(videoPath) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_eye),
                    contentDescription = "Go to message in chat",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
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
