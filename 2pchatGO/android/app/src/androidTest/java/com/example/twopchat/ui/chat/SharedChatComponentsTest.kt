package com.example.twopchat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SharedChatComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewActionsStayHiddenUntilRevealedAndDismissBeforeSending() {
        val visible = mutableStateOf(false)
        val events = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                Column {
                    MediaPreviewActions(
                        showActions = visible.value,
                        appLanguage = "Русский",
                        primaryColor = Color.Blue,
                        onDismiss = { events += "dismiss" },
                        onSend = { events += "send" },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Отправить").assertDoesNotExist()
        composeRule.runOnIdle { visible.value = true }
        composeRule.onNodeWithText("Закрыть").performClick()
        composeRule.runOnIdle { assertEquals(listOf("dismiss"), events) }
        composeRule.onNodeWithText("Отправить").performClick()
        composeRule.runOnIdle { assertEquals(listOf("dismiss", "dismiss", "send"), events) }
    }

    @Test
    fun attachmentFailureUpdatesStatusAndOnlyOffersRetryWhenAllowed() {
        val cancelled = mutableStateOf(true)
        val failed = mutableStateOf(true)
        val canRetry = mutableStateOf(false)
        var retries = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(280.dp)) {
                    AttachmentFailureOverlay(
                        isCancelled = cancelled.value,
                        hasFailed = failed.value,
                        canRetry = canRetry.value,
                        appLanguage = "Русский",
                        backgroundColor = Color.Black.copy(alpha = 0.45f),
                        textColor = Color.White.copy(alpha = 0.9f),
                        onRetry = { retries++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Передача отменена").assertIsDisplayed()
        composeRule.onNodeWithText("Возобновить").assertDoesNotExist()
        composeRule.runOnIdle {
            cancelled.value = false
            canRetry.value = true
        }
        composeRule.onNodeWithText("Ошибка передачи").assertIsDisplayed()
        composeRule.onNodeWithText("Возобновить").performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            failed.value = false
            canRetry.value = false
        }
        composeRule.onNodeWithText("Файл удалён").assertIsDisplayed()
        composeRule.onNodeWithText("Возобновить").assertDoesNotExist()
    }
}
