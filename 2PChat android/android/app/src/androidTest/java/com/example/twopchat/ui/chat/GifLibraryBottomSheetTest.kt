package com.example.twopchat.ui.chat

import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

        assertTrue(animatedCount in 1..4)
        assertTrue(animatedCount < gifs.size)
    }

    @Test
    fun savedGifsUseEqualSquareGridCells() {
        val gifs = gifFiles.mapIndexed { index, file ->
            StoredGif(
                id = "grid-$index",
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

        val cells = composeRule
            .onAllNodesWithContentDescription("Saved GIF tile")
            .fetchSemanticsNodes()
        assertTrue(cells.size >= 3)

        val firstWidth = cells.first().boundsInRoot.width
        cells.take(6).forEach { cell ->
            assertTrue(kotlin.math.abs(cell.boundsInRoot.width - cell.boundsInRoot.height) < 1f)
            assertTrue(kotlin.math.abs(cell.boundsInRoot.width - firstWidth) < 1f)
        }
    }

    @Test
    fun longPressShowsLargeGifPreviewUntilRelease() {
        val gifs = gifFiles.take(3).mapIndexed { index, file ->
            StoredGif(
                id = "hold-$index",
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

        val firstTile = composeRule
            .onAllNodesWithContentDescription("Saved GIF tile")
            .onFirst()
        firstTile.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(1_000)
        firstTile.performTouchInput {
            advanceEventTime(1_000)
            moveBy(Offset(1f, 0f))
        }
        composeRule.onNodeWithContentDescription("GIF hold preview").assertExists()
        composeRule.onNodeWithText("1 / 3").assertExists()

        firstTile.performTouchInput {
            moveBy(Offset(240f, 0f))
            advanceEventTime(16)
        }
        composeRule.onNodeWithText("2 / 3").assertExists()

        firstTile.performTouchInput { up() }
    }

    private companion object {
        const val ANIMATED_GIF_BASE64 =
            "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQBCgABACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7"
    }
}
