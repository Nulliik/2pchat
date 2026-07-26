package com.example.twopchat.ui.chat

import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.example.twopchat.StoredGif
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GifLibraryBottomSheetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val gifFiles = mutableListOf<File>()

    @Before
    fun createGifs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repeat(12) { index ->
            File(context.cacheDir, "library-preview-$index.gif").also { file ->
                file.writeBytes(Base64.decode(ANIMATED_GIF_BASE64, Base64.DEFAULT))
                gifFiles += file
            }
        }
    }

    @After
    fun deleteGifs() {
        gifFiles.forEach(File::delete)
    }

    @Test
    fun onlyBoundedVisiblePreviewsAnimate() {
        val gifs = gifFiles.mapIndexed { index, file ->
            StoredGif(
                id = "preview-$index",
                filePath = file.absolutePath,
                previewPath = file.absolutePath,
                sizeBytes = file.length(),
                addedAtEpochMs = index.toLong(),
            )
        }
        composeRule.setContent {
            MaterialTheme {
                GifLibraryBottomSheet(
                    gifs = gifs,
                    isLoading = false,
                    appLanguage = "English",
                    primaryColor = Color.Cyan,
                    onDismiss = {},
                    onImport = {},
                    onGifSelected = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("Animated GIF preview")
                .fetchSemanticsNodes().isNotEmpty()
        }
        val animatedCount = composeRule
            .onAllNodesWithContentDescription("Animated GIF preview")
            .fetchSemanticsNodes()
            .size

        assertTrue(animatedCount in 1..6)
        assertTrue(animatedCount < gifs.size)
    }

    private companion object {
        const val ANIMATED_GIF_BASE64 =
            "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQBCgABACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7"
    }
}
