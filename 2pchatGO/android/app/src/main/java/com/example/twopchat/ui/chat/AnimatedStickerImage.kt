package com.example.twopchat.ui.chat

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.penfeizhou.animation.webp.WebPDrawable
import com.example.twopchat.media.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val stickerDecodeSlots = Semaphore(3)

/**
 * Decodes animated WEBP stickers only while their lazy-grid/message item is composed.
 * Android 9+ uses AnimatedImageDrawable; Android 7–8 use the compatibility decoder.
 *
 * Drawable instances must never be shared between ImageViews: Drawable owns a
 * single callback, so reusing a cached animated drawable makes the newest view
 * steal invalidation callbacks from older stickers and eventually leaves them
 * frozen or blank.
 */
@Composable
internal fun AnimatedStickerImage(
    filePath: String?,
    fallbackEmoji: String,
    contentDescription: String,
    targetSizePx: Int,
    modifier: Modifier = Modifier,
    isAnimationEnabled: Boolean = true,
) {
    val shouldAnimate = isAnimationEnabled && isAnimatedMediaActive()
    val isScrolling = LocalScrollInProgress.current

    // Fast static first-frame for zero-latency placeholder & scroll rendering
    var staticBitmap by remember(filePath) {
        mutableStateOf(filePath?.let { StickerThumbnailCache.get(it) })
    }

    LaunchedEffect(filePath, targetSizePx) {
        if (filePath != null && staticBitmap == null) {
            withContext(Dispatchers.IO) {
                val bmp = StickerThumbnailCache.loadFirstFrame(filePath, targetSizePx)
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        staticBitmap = bmp
                    }
                }
            }
        }
    }

    val drawable by produceState<Drawable?>(
        initialValue = null,
        filePath,
        targetSizePx,
        isScrolling,
    ) {
        if (filePath == null) {
            value = null
            return@produceState
        }
        // Defer heavy multi-frame animated decoding while rapid scrolling is active
        if (isScrolling) {
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            stickerDecodeSlots.withPermit {
                val file = File(filePath)
                val info = StickerSupport.validateWebP(file)
                if (info == null) {
                    null
                } else {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) {
                                decoder,
                                imageInfo,
                                _,
                            ->
                                val width = imageInfo.size.width.coerceAtLeast(1)
                                val height = imageInfo.size.height.coerceAtLeast(1)
                                val scale = minOf(
                                    targetSizePx.coerceAtLeast(1).toFloat() / width,
                                    targetSizePx.coerceAtLeast(1).toFloat() / height,
                                    1f,
                                )
                                decoder.setTargetSize(
                                    (width * scale).toInt().coerceAtLeast(1),
                                    (height * scale).toInt().coerceAtLeast(1),
                                )
                            }
                        } else if (info.animated) {
                            WebPDrawable.fromFile(file.absolutePath)
                        } else {
                            @Suppress("DEPRECATION")
                            Drawable.createFromPath(file.absolutePath)
                        }
                    }.getOrNull()
                }
            }
        }
    }
    DisposableEffect(drawable, shouldAnimate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setInfinitePlatformRepeat(drawable)
        }
        if (shouldAnimate) {
            (drawable as? Animatable)?.start()
        } else {
            (drawable as? Animatable)?.stop()
        }
        onDispose {
            (drawable as? Animatable)?.stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (drawable as? AnimatedImageDrawable)?.clearAnimationCallbacks()
            }
        }
    }

    Box(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = staticBitmap
        when {
            drawable != null && shouldAnimate -> AndroidView(
                factory = { context ->
                    android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { imageView ->
                    if (imageView.drawable !== drawable) imageView.setImageDrawable(drawable)
                },
                onReset = { imageView ->
                    imageView.setImageDrawable(null)
                },
                onRelease = { imageView ->
                    imageView.setImageDrawable(null)
                },
                modifier = Modifier.fillMaxSize(),
            )
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            else -> {
                val cleanEmoji = if (
                    fallbackEmoji.isBlank() ||
                    fallbackEmoji.equals("Sticker", ignoreCase = true) ||
                    fallbackEmoji.equals("Стикер", ignoreCase = true)
                ) "🎭" else fallbackEmoji
                Text(
                    text = cleanEmoji,
                    fontSize = (targetSizePx.coerceIn(48, 144) * 0.32f).sp,
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun setInfinitePlatformRepeat(drawable: Drawable?) {
    (drawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
}
