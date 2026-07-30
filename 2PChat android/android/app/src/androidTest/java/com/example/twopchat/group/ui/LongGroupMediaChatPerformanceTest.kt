package com.example.twopchat.group.ui

import android.os.Debug
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.SparseIntArray
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.core.app.FrameMetricsAggregator
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression guard for the real group-chat screen with a long, media-heavy
 * timeline. It covers group-specific author/role rendering as well as GIF and
 * video attachment cards, long-distance jumps, touch scrolling, frame times,
 * and process-memory stability.
 *
 * The limits intentionally tolerate shared CI emulators. Precise release
 * measurements belong in a profileable Macrobenchmark target.
 */
@LargeTest
class LongGroupMediaChatPerformanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var mediaDirectory: File
    private lateinit var messages: List<GroupTimelineMessage>
    private lateinit var listState: LazyListState

    @Before
    fun setUp() {
        mediaDirectory = File(context.cacheDir, "long_group_media_chat_performance").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val gifBytes = Base64.decode(ANIMATED_GIF_BASE64, Base64.DEFAULT)
        val videoBytes = Base64.decode(SHORT_MP4_BASE64, Base64.DEFAULT)
        val gifPaths = List(MEDIA_FILE_VARIANTS) { index ->
            File(mediaDirectory, "group_animated_$index.gif").apply {
                writeBytes(gifBytes)
            }.absolutePath
        }
        val videoPaths = List(MEDIA_FILE_VARIANTS) { index ->
            File(mediaDirectory, "group_video_$index.mp4").apply {
                writeBytes(videoBytes)
            }.absolutePath
        }
        messages = buildLongGroupTimeline(gifPaths, videoPaths)
    }

    @After
    fun tearDown() {
        mediaDirectory.deleteRecursively()
    }

    @Test
    fun longGroupVideoAndGifTimeline_loadsAndScrollsWithinRegressionBudgets() {
        val frameMetricsAggregator = FrameMetricsAggregator(
            FrameMetricsAggregator.TOTAL_DURATION,
        )

        val initialStartedAt = SystemClock.elapsedRealtime()
        composeRule.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                GroupChatScreen(
                    state = GroupChatUiState(
                        groupId = "performance-group",
                        title = "Media performance group",
                        memberCount = AUTHORS.size,
                        syncStatus = GroupSyncStatus.LIVE,
                        messages = messages,
                        hasMoreBefore = false,
                        composerEnabled = true,
                    ),
                    controller = object : GroupUiController {},
                    listState = listState,
                )
            }
        }
        composeRule.waitForIdle()
        val initialRenderMs = SystemClock.elapsedRealtime() - initialStartedAt

        // GroupChatScreen owns its list state; verify the real timeline itself
        // is mounted before exercising deterministic positions below.
        groupList().assertExists()
        assertTrue(
            "Initial long group-chat render took ${initialRenderMs}ms",
            initialRenderMs <= INITIAL_RENDER_BUDGET_MS,
        )

        // Warm one screen before taking the baseline so normal decoder and
        // Compose initialization is not counted as retained-memory growth.
        jumpTo(MESSAGE_COUNT / 2)
        val baselinePssKb = currentPssKb()

        val scrollStartedAt = SystemClock.elapsedRealtime()
        repeat(SCROLL_ROUNDS) {
            SCROLL_CHECKPOINTS.forEach(::jumpTo)
            SCROLL_CHECKPOINTS.asReversed().forEach(::jumpTo)
        }

        // Exercise pointer-driven scrolling in addition to semantics-based
        // deterministic jumps across the entire timeline.
        jumpTo(MESSAGE_COUNT / 2)
        // Long-distance semantics jumps validate loading and recycling speed,
        // but are not user-driven frames. Capture frame smoothness only for
        // the real touch-scroll path to avoid classifying deliberate jumps as
        // frozen animation frames.
        frameMetricsAggregator.add(composeRule.activity)
        repeat(TOUCH_SWIPES_PER_DIRECTION) {
            groupList().performTouchInput { swipeUp(durationMillis = 180) }
            composeRule.waitForIdle()
        }
        repeat(TOUCH_SWIPES_PER_DIRECTION) {
            groupList().performTouchInput { swipeDown(durationMillis = 180) }
            composeRule.waitForIdle()
        }
        val scrollDurationMs = SystemClock.elapsedRealtime() - scrollStartedAt

        jumpTo(0)
        attachmentNode(0).assertExists()
        jumpTo(MESSAGE_COUNT - 3)
        attachmentNode(MESSAGE_COUNT - 3).assertExists()

        val finalPssKb = currentPssKb()
        val memoryGrowthKb = (finalPssKb - baselinePssKb).coerceAtLeast(0)
        val frameMetrics = frameMetricsAggregator.remove(composeRule.activity)
        val summary = summarizeFrames(
            frameMetrics?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX),
        )
        Log.i(
            LOG_TAG,
            "initialRenderMs=$initialRenderMs scrollDurationMs=$scrollDurationMs " +
                "frames=$summary memoryGrowthKb=$memoryGrowthKb",
        )

        assertTrue(
            "Group scroll scenario took ${scrollDurationMs}ms",
            scrollDurationMs <= SCROLL_SEQUENCE_BUDGET_MS,
        )
        assertTrue("No rendered group-chat frames were captured", summary.totalFrames > 0)
        assertTrue(
            "Too many frozen frames detected: $summary",
            summary.frozenFrames <= MAX_FROZEN_FRAMES,
        )
        assertTrue(
            "Too many frames exceeded ${SLOW_FRAME_MS}ms: $summary",
            summary.slowFrameRatio <= MAX_SLOW_FRAME_RATIO,
        )
        assertTrue(
            "PSS grew by ${memoryGrowthKb / 1024}MB during repeated group scrolling",
            memoryGrowthKb <= MAX_MEMORY_GROWTH_KB,
        )
    }

    private fun groupList() = composeRule.onNodeWithTag(GROUP_LIST_TAG)

    private fun attachmentNode(messageIndex: Int) = composeRule.onNodeWithTag(
        attachmentTag(messageIndex),
        useUnmergedTree = true,
    )

    private fun jumpTo(messageIndex: Int) {
        // The first LazyColumn item is the "start of history" pagination row.
        val lazyListIndex = messageIndex + PAGINATION_ITEM_COUNT
        composeRule.runOnIdle {
            listState.requestScrollToItem(lazyListIndex)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(
                "Requested group message $messageIndex was not composed; first visible=" +
                    listState.firstVisibleItemIndex,
                listState.layoutInfo.visibleItemsInfo.any { it.index == lazyListIndex },
            )
        }
        attachmentNode(messageIndex).assertExists()
    }

    private fun attachmentTag(index: Int) = "attachment_group-performance-$index"

    private fun buildLongGroupTimeline(
        gifPaths: List<String>,
        videoPaths: List<String>,
    ): List<GroupTimelineMessage> = List(MESSAGE_COUNT) { index ->
        val author = AUTHORS[index % AUTHORS.size]
        val role = when (index % 11) {
            0 -> GroupRole.ADMIN
            1 -> GroupRole.MODERATOR
            else -> GroupRole.MEMBER
        }
        val attachment = when (index % 3) {
            0 -> GroupAttachmentUi(
                attachmentId = "group-gif-$index",
                fileName = "group_animated_$index.gif",
                mimeType = "image/gif",
                sizeLabel = "1 KB",
                availableBlocks = 1,
                totalBlocks = 1,
                isDownloaded = true,
                localPath = gifPaths[index % gifPaths.size],
            )
            1 -> GroupAttachmentUi(
                attachmentId = "group-video-$index",
                fileName = "group_video_$index.mp4",
                mimeType = "video/mp4",
                sizeLabel = "4 KB",
                availableBlocks = 1,
                totalBlocks = 1,
                isDownloaded = true,
                localPath = videoPaths[index % videoPaths.size],
            )
            else -> null
        }
        GroupTimelineMessage(
            messageId = "group-performance-$index",
            authorId = "member-${index % AUTHORS.size}",
            authorName = author,
            authorRole = role,
            text = when (index % 3) {
                0 -> "Group GIF $index"
                1 -> "Group video $index"
                else -> "Group media message $index"
            },
            timestampLabel = "%02d:%02d".format((index / 60) % 24, index % 60),
            isMine = index % 5 == 0,
            attachment = attachment,
            deliveryStatus = GroupDeliveryStatus.READ,
            canReply = false,
            canReact = false,
        )
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
        return FrameSummary(total, slow, frozen)
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
        const val GROUP_LIST_TAG = "group_message_list"
        const val LOG_TAG = "LongGroupChatPerf"
        const val MESSAGE_COUNT = 600
        const val MEDIA_FILE_VARIANTS = 12
        const val PAGINATION_ITEM_COUNT = 1
        const val INITIAL_RENDER_BUDGET_MS = 30_000L
        const val SCROLL_SEQUENCE_BUDGET_MS = 30_000L
        const val MAX_MEMORY_GROWTH_KB = 128 * 1024
        const val SLOW_FRAME_MS = 100
        const val FROZEN_FRAME_MS = 700
        const val MAX_FROZEN_FRAMES = 6
        const val MAX_SLOW_FRAME_RATIO = 0.35
        const val SCROLL_ROUNDS = 2
        const val TOUCH_SWIPES_PER_DIRECTION = 4
        val SCROLL_CHECKPOINTS = listOf(0, 100, 201, 300, 400, 501, 597)
        val AUTHORS = listOf("Alice", "Bob", "Carol", "Dmitry", "Eve", "Fatima")

        const val ANIMATED_GIF_BASE64 =
            "R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQBCgABACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7"

        const val SHORT_MP4_BASE64 =
            "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAM3bW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAaEAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAmJ0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAaEAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAKAAAABaAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAGhAAAAAAABAAAAAAHabWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAwAAAAFABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABhW1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAUVzdGJsAAAAuXN0c2QAAAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAKAAWgBIAAAASAAAAAAAAAABFUxhdmM2Mi4xMS4xMDAgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAGGdCwAraCjfkwEQAAAMABAAAAwBgPEiagAEABGjOD8gAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAA1UwAAAAAAAAAYc3R0cwAAAAAAAAABAAAABQAABAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxzdHNjAAAAAAAAAAEAAAABAAAABQAAAAEAAAAoc3RzegAAAAAAAAAAAAAABQAAAp8AAAAKAAAACgAAAAoAAAAKAAAAFHN0Y28AAAAAAAAAAQAAA2cAAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYyLjMuMTAwAAAACGZyZWUAAALPbWRhdAAAAlYGBf//UtxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjUgcjMyMjIgYjM1NjA1YSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJlZj0xIGRlYmxvY2s9MDotMzotMyBhbmFseXNlPTA6MCBtZT1kaWEgc3VibWU9MCBwc3k9MSBwc3lfcmQ9Mi4wMDowLjcwIG1peGVkX3JlZj0wIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MCA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0wIHRocmVhZHM9MyBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBmZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAga2V5aW50X21pbj0xMiBzY2VuZWN1dD0wIGludHJhX3JlZnJlc2g9MCByYz1jcmYgbWJ0cmVlPTAgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MACAAAAAQWWIhDoRigACEDHAAECiOAAID8nJycnJycnJyddddddddddddddddddddddddddddddddddddddddddddddddddeAAAABkGaIDqB7AAAAAZBmkA+gewAAAAGQZpgPoHsAAAABkGagD6B7A=="
    }
}
