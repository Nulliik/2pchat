package com.example.twopchat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatInputBarIdentityPausedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun changedIdentityReplacesEverySendingControlWithSecurityPause() {
        var reviewed = false
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    showAttachments = false,
                    replyingToMessage = null,
                    editingMessage = null,
                    isSelectMode = false,
                    selectedCount = 0,
                    isBlocked = false,
                    isIdentityPaused = true,
                    isRecordingVoice = false,
                    recordingElapsedMs = 0,
                    inputText = "message which must not be sent",
                    peerName = "foxy",
                    appLanguage = "Русский",
                    primaryColor = Color(0xFF448AFF),
                    surfaceColor = Color.White,
                    surfaceVariant = Color.LightGray,
                    onSurfaceColor = Color.Black,
                    onSurfaceVariant = Color.DarkGray,
                    onAttachmentClick = {},
                    onDismissReply = {},
                    onDismissEditing = {},
                    onCancelSelection = {},
                    onDeleteSelected = {},
                    onUnblock = {},
                    onReviewIdentity = { reviewed = true },
                    onToggleAttachments = {},
                    onInputTextChange = {},
                    onActionClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Отправка приостановлена").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithContentDescription("Send message").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Проверить").performClick()
        composeRule.runOnIdle { assertTrue(reviewed) }
    }
}
