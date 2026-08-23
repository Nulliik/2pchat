package com.example.twopchat.ui.chat

import android.os.Debug
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.SparseIntArray
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.core.app.FrameMetricsAggregator
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import com.example.twopchat.media.GifStorageManager
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression guard for the real personal-chat timeline with enough media rows to
 * expose synchronous decoding, unbounded composition, scroll crashes, and obvious
 * frame or memory regressions. Budgets are deliberately broad enough for shared CI
 * emulators; precise release benchmarking belongs in a dedicated Macrobenchmark app.
 */
@LargeTest
class LongMediaChatPerformanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var mediaDirectory: File
    private lateinit var messages: List<Message>

    @Before
    fun setUp() {
        AttachmentImageCache.clear()
        mediaDirectory = File(context.cacheDir, "long_media_chat_performance").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val gifPaths = List(MEDIA_FILE_VARIANTS) { index ->
            File(mediaDirectory, "animated_$index.gif").apply {
                writeBytes(Base64.decode(ANIMATED_GIF_BASE64, Base64.DEFAULT))
            }.absolutePath
        }
        val videoPaths = List(MEDIA_FILE_VARIANTS) { index ->
            File(mediaDirectory, "video_$index.mp4").apply {
                writeBytes(Base64.decode(SHORT_MP4_BASE64, Base64.DEFAULT))
            }.absolutePath
        }
        messages = buildLongMediaTimeline(gifPaths, videoPaths)
    }

    @After
    fun tearDown() {
        AttachmentImageCache.clear()
        mediaDirectory.deleteRecursively()
    }

    @Test
    fun longVideoAndGifTimeline_loadsAndScrollsWithinRegressionBudgets() {
        lateinit var listState: LazyListState
        val frameMetricsAggregator = FrameMetricsAggregator(
            FrameMetricsAggregator.TOTAL_DURATION,
        )

        val initialStartedAt = SystemClock.elapsedRealtime()
        composeRule.setContent {
            MaterialTheme {
                listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = messages.lastIndex,
                )
                ChatMessageList(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(CHAT_LIST_TAG),
                    messages = messages,
                    selectedMessages = remember { mutableStateListOf<Message>() },
                    isHistoryLoading = false,
                    isSearchMode = false,
                    searchQuery = "",
                    isSelectMode = false,
                    isTyping = false,
                    peerName = "performance-peer",
                    myAvatarBitmap = null,
                    appLanguage = "English",
                    arrivalAnimationTracker = MessageArrivalAnimationTracker(),
                    showScrollDownButton = false,
                    newMessagesBelowCount = 0,
                    onScrollToBottom = {},
                    listState = listState,
                    primaryColor = Color(0xFF00A878),
                    surfaceColor = Color(0xFF171A1F),
                    onSurfaceColor = Color.White,
                    onSurfaceVariant = Color(0xFFB5BBC5),
                    onReply = {},
                    onShowOptions = {},
                    onOpenImages = { _, _ -> },
                    onOpenVideo = {},
                    onOpenStickerPack = {},
                    onCancelFileTransfer = {},
                )
            }
        }
        composeRule.waitForIdle()
        val initialRenderMs = SystemClock.elapsedRealtime() - initialStartedAt

        composeRule.runOnIdle {
            assertEquals(MESSAGE_COUNT, listState.layoutInfo.totalItemsCount)
            assertTrue(
                "Timeline did not compose its newest message; visible=" +
                    listState.layoutInfo.visibleItemsInfo.map { it.index },
                listState.layoutInfo.visibleItemsInfo.any { it.index == MESSAGE_COUNT - 1 },
            )
        }
        assertTrue(
            "Initial long-chat render took ${initialRenderMs}ms",
            initialRenderMs <= INITIAL_RENDER_BUDGET_MS,
        )

        // Warm one screen before taking the memory baseline so normal decoder and
        // Compose initialization is not mistaken for a leak.
        jumpTo(listState, MESSAGE_COUNT / 2)
        val baselinePssKb = currentPssKb()
        frameMetricsAggregator.add(composeRule.activity)

        val scrollStartedAt = SystemClock.elapsedRealtime()
        repeat(SCROLL_ROUNDS) {
            SCROLL_CHECKPOINTS.forEach { index -> jumpTo(listState, index) }
            SCROLL_CHECKPOINTS.asReversed().forEach { index -> jumpTo(listState, index) }
        }

        // Exercise the actual pointer-driven nested-scroll path in addition to
        // deterministic long-distance jumps.
        jumpTo(listState, MESSAGE_COUNT / 2)
        repeat(TOUCH_SWIPES_PER_DIRECTION) {
            composeRule.onRoot().performTouchInput { swipeUp(durationMillis = 180) }
            composeRule.waitForIdle()
        }
        repeat(TOUCH_SWIPES_PER_DIRECTION) {
            composeRule.onRoot().performTouchInput { swipeDown(durationMillis = 180) }
            composeRule.waitForIdle()
        }
        val scrollDurationMs = SystemClock.elapsedRealtime() - scrollStartedAt

        jumpTo(listState, 0)
        composeRule.runOnIdle {
            assertEquals("Timeline could not return to its oldest message", 0, listState.firstVisibleItemIndex)
        }
        jumpTo(listState, MESSAGE_COUNT - 1)
        composeRule.runOnIdle {
            assertTrue(
                "Timeline could not return to its newest message; visible=" +
                    listState.layoutInfo.visibleItemsInfo.map { it.index },
                listState.layoutInfo.visibleItemsInfo.any { it.index == MESSAGE_COUNT - 1 },
            )
        }

        val finalPssKb = currentPssKb()
        val memoryGrowthKb = (finalPssKb - baselinePssKb).coerceAtLeast(0)
        val frameMetrics = frameMetricsAggregator.remove(composeRule.activity)
        val totalDurations = frameMetrics?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
        val summary = summarizeFrames(totalDurations)
        Log.i(
            LOG_TAG,
            "initialRenderMs=$initialRenderMs scrollDurationMs=$scrollDurationMs " +
                "frames=$summary memoryGrowthKb=$memoryGrowthKb",
        )

        assertTrue(
            "Scroll scenario took ${scrollDurationMs}ms",
            scrollDurationMs <= SCROLL_SEQUENCE_BUDGET_MS,
        )
        assertTrue("No rendered frames were captured", summary.totalFrames > 0)
        assertEquals(
            "Frozen frames detected: $summary",
            0,
            summary.frozenFrames,
        )
        assertTrue(
            "Too many frames exceeded ${SLOW_FRAME_MS}ms: $summary",
            summary.slowFrameRatio <= MAX_SLOW_FRAME_RATIO,
        )
        assertTrue(
            "PSS grew by ${memoryGrowthKb / 1024}MB during repeated scrolling",
            memoryGrowthKb <= MAX_MEMORY_GROWTH_KB,
        )
    }

    private fun jumpTo(listState: LazyListState, index: Int) {
        composeRule.runOnIdle {
            listState.requestScrollToItem(index)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            assertTrue(
                "Requested message $index was not composed; first visible=" +
                    listState.firstVisibleItemIndex,
                visible,
            )
        }
    }

    private fun buildLongMediaTimeline(
        gifPaths: List<String>,
        videoPaths: List<String>,
    ): List<Message> {
        val startTimeMs = System.currentTimeMillis() - MESSAGE_COUNT * 60_000L
        return List(MESSAGE_COUNT) { index ->
            val common = Message(
                id = "performance-message-$index",
                text = "Media message $index",
                isMe = index % 2 == 0,
                timestamp = "",
                sentAtEpochMs = startTimeMs + index * 60_000L,
                status = if (index % 2 == 0) "sent" else null,
            )
            when (index % 3) {
                0 -> common.copy(
                    text = "GIF $index",
                    attachmentType = GifStorageManager.ATTACHMENT_TYPE,
                    attachmentUri = gifPaths[index % gifPaths.size],
                    attachmentName = "animated_$index.gif",
                )
                1 -> common.copy(
                    text = "Video $index",
                    attachmentType = "VIDEO",
                    attachmentUri = videoPaths[index % videoPaths.size],
                    attachmentName = "video_$index.mp4",
                )
                else -> common
            }
        }
    }

    private fun currentPssKb(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

    private fun summarizeFrames(histogram: SparseIntArray?): FrameSummary {
        if (histogram == null) return FrameSummary()
        var total = 0
        var slow = 0
        var frozen = 0
        for (index in 0 until histogram.size()) {
            val durationMs = histogram.keyAt(index)
            val count = histogram.valueAt(index)
            total += count
            if (durationMs > SLOW_FRAME_MS) slow += count
            if (durationMs > FROZEN_FRAME_MS) frozen += count
        }
        return FrameSummary(
            totalFrames = total,
            slowFrames = slow,
            frozenFrames = frozen,
        )
    }

    private data class FrameSummary(
        val totalFrames: Int = 0,
        val slowFrames: Int = 0,
        val frozenFrames: Int = 0,
    ) {
        val slowFrameRatio: Double
            get() = if (totalFrames == 0) 1.0 else slowFrames.toDouble() / totalFrames
    }

    private companion object {
        const val CHAT_LIST_TAG = "long_media_chat_list"
        const val LOG_TAG = "LongMediaChatPerf"
        const val MESSAGE_COUNT = 600
        const val MEDIA_FILE_VARIANTS = 12
        const val INITIAL_RENDER_BUDGET_MS = 10_000L
        const val SCROLL_SEQUENCE_BUDGET_MS = 30_000L
        const val MAX_MEMORY_GROWTH_KB = 128 * 1024
        const val SLOW_FRAME_MS = 100
        const val FROZEN_FRAME_MS = 700
        const val MAX_SLOW_FRAME_RATIO = 0.35
        const val SCROLL_ROUNDS = 2
        const val TOUCH_SWIPES_PER_DIRECTION = 4
        val SCROLL_CHECKPOINTS = listOf(0, 100, 200, 300, 400, 500, 599)

        const val ANIMATED_GIF_BASE64 =
            "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQBCgABACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7"

        const val SHORT_MP4_BASE64 =
            "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAM3bW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAaEAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAmJ0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAaEAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAKAAAABaAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAGhAAAAAAABAAAAAAHabWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAwAAAAFABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABhW1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAUVzdGJsAAAAuXN0c2QAAAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAKAAWgBIAAAASAAAAAAAAAABFUxhdmM2Mi4xMS4xMDAgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAGGdCwAraCjfkwEQAAAMABAAAAwBgPEiagAEABGjOD8gAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAA1UwAAAAAAAAAYc3R0cwAAAAAAAAABAAAABQAABAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxzdHNjAAAAAAAAAAEAAAABAAAABQAAAAEAAAAoc3RzegAAAAAAAAAAAAAABQAAAp8AAAAKAAAACgAAAAoAAAAKAAAAFHN0Y28AAAAAAAAAAQAAA2cAAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYyLjMuMTAwAAAACGZyZWUAAALPbWRhdAAAAlYGBf//UtxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjUgcjMyMjIgYjM1NjA1YSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJlZj0xIGRlYmxvY2s9MDotMzotMyBhbmFseXNlPTA6MCBtZT1kaWEgc3VibWU9MCBwc3k9MSBwc3lfcmQ9Mi4wMDowLjcwIG1peGVkX3JlZj0wIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MCA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0wIHRocmVhZHM9MyBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBmZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAga2V5aW50X21pbj0xMiBzY2VuZWN1dD0wIGludHJhX3JlZnJlc2g9MCByYz1jcmYgbWJ0cmVlPTAgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MACAAAAAQWWIhDoRigACEDHAAECiOAAID8nJycnJycnJyddddddddddddddddddddddddddddddddddddddddddddddddddeAAAABkGaIDqB7AAAAAZBmkA+gewAAAAGQZpgPoHsAAAABkGagD6B7A=="
    }
}
