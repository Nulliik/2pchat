package com.example.twopchat.ui.chat

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.twopchat.media.StickerSupport
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AnimatedStickerImageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun decodesAndDisplaysAnimatedWebPSticker() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "2psticker_test--animated.webp")
        file.writeBytes(Base64.decode(ANIMATED_WEBP_BASE64, Base64.DEFAULT))

        try {
            assertTrue(StickerSupport.validateWebP(file)?.animated == true)
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            assertTrue(drawable is AnimatedImageDrawable)

            composeRule.setContent {
                MaterialTheme {
                    AnimatedStickerImage(
                        filePath = file.absolutePath,
                        fallbackEmoji = "🎭",
                        contentDescription = "Animated sticker",
                        targetSizePx = 128,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            composeRule.onNodeWithContentDescription("Animated sticker").assertIsDisplayed()
        } finally {
            file.delete()
        }
    }

    @Test
    fun sameAnimatedStickerCanBackMultipleVisibleViews() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "2psticker_test--shared.webp")
        file.writeBytes(Base64.decode(ANIMATED_WEBP_BASE64, Base64.DEFAULT))

        try {
            composeRule.setContent {
                MaterialTheme {
                    Row {
                        repeat(2) {
                            AnimatedStickerImage(
                                filePath = file.absolutePath,
                                fallbackEmoji = "🎭",
                                contentDescription = "Repeated animated sticker",
                                targetSizePx = 128,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription("Repeated animated sticker")
                .assertCountEquals(2)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val ANIMATED_WEBP_BASE64 =
            "UklGRoQAAABXRUJQVlA4WAoAAAASAAAADwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMQBxDR/wIGIqL/AQA="
    }
}
