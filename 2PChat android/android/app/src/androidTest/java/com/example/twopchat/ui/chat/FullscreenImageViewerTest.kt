package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class FullscreenImageViewerTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val imageFiles = mutableListOf<File>()

    @Before
    fun createImages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repeat(2) { index ->
            val file = File(context.cacheDir, "viewer-test-$index.png")
            file.outputStream().use { output ->
                Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index == 0) android.graphics.Color.RED else android.graphics.Color.BLUE)
                    compress(Bitmap.CompressFormat.PNG, 100, output)
                    recycle()
                }
            }
            imageFiles += file
        }
    }

    @After
    fun deleteImages() {
        imageFiles.forEach(File::delete)
    }

    @Test
    fun oneFingerSwipeChangesPageAtDefaultZoom() {
        composeTestRule.setContent {
            FullscreenImageViewer(
                imagePaths = imageFiles.map(File::getAbsolutePath),
                initialIndex = 0,
                appLanguage = "English",
                onClose = {},
            )
        }
        composeTestRule.onNodeWithText("1 / 2").assertExists()

        composeTestRule.onNodeWithContentDescription("Fullscreen Image")
            .performTouchInput { swipeLeft() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("2 / 2").assertExists()
    }

    @Test
    fun inMemoryAvatarBitmapOpensWithoutPlaintextFile() {
        val avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
        }
        val avatarKey = "avatar:Alice"

        composeTestRule.setContent {
            FullscreenImageViewer(
                imagePaths = listOf(avatarKey),
                initialIndex = 0,
                appLanguage = "English",
                bitmapOverrides = mapOf(avatarKey to avatar),
                onClose = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Fullscreen Image").assertExists()
        composeTestRule.onNodeWithContentDescription("Download").assertDoesNotExist()
    }
}
