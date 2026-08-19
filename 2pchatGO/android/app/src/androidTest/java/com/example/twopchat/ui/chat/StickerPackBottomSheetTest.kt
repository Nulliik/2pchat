package com.example.twopchat.ui.chat

import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.twopchat.StickerSupport
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StickerPackBottomSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun requestsUnknownPeerPackWhenPreviewIsOpened() {
        val requests = AtomicInteger()
        val packId = "missing_peer_pack_${System.nanoTime()}"

        composeRule.setContent {
            MaterialTheme {
                StickerPackBottomSheet(
                    packId = packId,
                    fallbackEmoji = "🎭",
                    canRequestFromPeer = true,
                    requestInProgress = false,
                    previewRevision = 0,
                    appLanguage = "English",
                    primaryColor = Color(0xFF6750A4),
                    onDismiss = {},
                    onRequestPack = { requests.incrementAndGet() },
                    onStickerSelected = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            requests.get() == 1
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(1, requests.get())
    }

    @Test
    fun displaysDetailedErrorWhenPeerIsOffline() {
        val packId = "missing_peer_pack_${System.nanoTime()}"

        composeRule.setContent {
            MaterialTheme {
                StickerPackBottomSheet(
                    packId = packId,
                    fallbackEmoji = "🎭",
                    canRequestFromPeer = true,
                    requestInProgress = false,
                    previewRevision = 0,
                    appLanguage = "English",
                    primaryColor = Color(0xFF6750A4),
                    requestError = StickerPackRequestError.PEER_OFFLINE,
                    onDismiss = {},
                    onRequestPack = {},
                    onStickerSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Peer is currently offline").assertIsDisplayed()
    }

    @Test
    fun displaysEveryStickerFromCachedPeerPackBeforeAddingIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packId = "peer_sheet_${System.nanoTime()}"
        val packDirectory = File(StickerSupport.peerPackPreviewDirectory(context), packId)
            .apply { mkdirs() }
        val stickerBytes = Base64.decode(ANIMATED_WEBP_BASE64, Base64.DEFAULT)
        val stickerFiles = listOf(
            "2psticker_${packId}--hello.webp",
            "2psticker_${packId}--party.webp",
        )
        stickerFiles.forEach { File(packDirectory, it).writeBytes(stickerBytes) }
        File(packDirectory, "pack.json").writeText(
            JSONObject().apply {
                put("format", 1)
                put("pack_id", packId)
                put("title", "Alice Pack")
                put("author", "Alice")
                put(
                    "stickers",
                    JSONArray().apply {
                        stickerFiles.forEachIndexed { index, fileName ->
                            put(
                                JSONObject().apply {
                                    put("sticker_id", if (index == 0) "hello" else "party")
                                    put("emoji", if (index == 0) "👋" else "🥳")
                                    put("file", fileName)
                                },
                            )
                        }
                    },
                )
            }.toString(),
            Charsets.UTF_8,
        )

        try {
            composeRule.setContent {
                MaterialTheme {
                    StickerPackBottomSheet(
                        packId = packId,
                        fallbackEmoji = "🎭",
                        canRequestFromPeer = true,
                        requestInProgress = false,
                        previewRevision = 0,
                        appLanguage = "English",
                        primaryColor = Color(0xFF6750A4),
                        onDismiss = {},
                        onRequestPack = {},
                        onStickerSelected = {},
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("2 stickers")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithText("Alice Pack").assertIsDisplayed()
            composeRule.onNodeWithText("2 stickers").assertIsDisplayed()
            composeRule.onNodeWithText("Add").assertIsDisplayed()
        } finally {
            StickerSupport.clearPeerPackPreview(context, packId)
        }
    }

    private companion object {
        const val ANIMATED_WEBP_BASE64 =
            "UklGRoQAAABXRUJQVlA4WAoAAAASAAAADwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMQBxDR/wIGIqL/AQA="
    }
}
