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
    val sharedPrefs = com.example.twopchat.P2PPreferences.prefs(context)
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
        useAmoled = false,
        onAmoledChanged = {},
        appLanguage = "English",
        onLanguageChanged = {},
        onIconChanged = {},
        onDeleteAccount = {}
      )
    }
  }

  @Test
  fun navigationBar_exists() {
    composeTestRule.onNodeWithText("Chats").assertExists()
    composeTestRule.onNodeWithText("Search").assertExists()
    composeTestRule.onNodeWithText("Settings").assertExists()
  }

  @Test
  fun savedMessages_exists() {
    composeTestRule.onNodeWithText("Saved Messages").assertExists()
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
