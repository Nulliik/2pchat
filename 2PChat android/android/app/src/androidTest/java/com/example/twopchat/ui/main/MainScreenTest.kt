package com.example.twopchat.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.twopchat.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val sharedPrefs = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
    sharedPrefs.edit()
      .clear()
      .putStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen"))
      .commit()

    composeTestRule.setContent {
      MainScreen(
        onItemClick = {},
        isDarkTheme = true,
        onThemeChanged = {},
        useCerulean = false,
        onAccentChanged = {},
        appLanguage = "English",
        onLanguageChanged = {},
        onIconChanged = {},
        onDeleteAccount = {}
      )
    }
  }

  @Test
  fun appHeader_exists() {
    composeTestRule.onNodeWithText("2PChat").assertExists()
  }

  @Test
  fun activeHandshakes_exists() {
    composeTestRule.onNodeWithText("Active Handshakes").assertExists()
  }

  @Test
  fun mockPeers_exist() {
    composeTestRule.onNodeWithText("Eleanor Vance").assertExists()
    composeTestRule.onNodeWithText("Liam O'Connor").assertExists()
  }

  @Test
  fun unknownRoute_isShownAsDetecting() {
    composeTestRule.onAllNodesWithText("DETECTING...", substring = false).assertCountEquals(3)
  }
}
