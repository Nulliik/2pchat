package com.example.twopchat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import com.example.twopchat.ui.onboarding.OnboardingScreen
import com.example.twopchat.ui.main.MainScreen
import com.example.twopchat.ui.chat.ChatScreen

@Composable
fun MainNavigation(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    onIconChanged: (String) -> Unit
) {
  val context = LocalContext.current
  val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE) }
  var isOnboardingCompleted by remember { mutableStateOf(sharedPrefs.getBoolean("onboarding_completed", false)) }

  if (!isOnboardingCompleted) {
    OnboardingScreen(
      appLanguage = appLanguage,
      onComplete = {
        sharedPrefs.edit().putBoolean("onboarding_completed", true).apply()
        isOnboardingCompleted = true
        // The relay is started before onboarding, when no username exists yet.
        // Announce immediately now that the profile is complete instead of
        // leaving tracker diagnostics empty until the periodic loop wakes up.
        P2PMessageRelay.refreshAnnouncement(context)
      },
      modifier = Modifier.fillMaxSize()
    )
  } else {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<Main> {
            MainScreen(
              onItemClick = { navKey -> backStack.add(navKey) },
              isDarkTheme = isDarkTheme,
              onThemeChanged = onThemeChanged,
              useCerulean = useCerulean,
              onAccentChanged = onAccentChanged,
              appLanguage = appLanguage,
              onLanguageChanged = onLanguageChanged,
              onIconChanged = onIconChanged,
              onDeleteAccount = {
                  sharedPrefs.edit().clear().apply()
                  try {
                      context.filesDir.deleteRecursively()
                  } catch (e: Exception) {
                      android.util.Log.e("Navigation", "Failed to clear identity files", e)
                  }
                  isOnboardingCompleted = false
              },
              modifier = Modifier.fillMaxSize()
            )
          }
          entry<Chat> { chatKey ->
            ChatScreen(
              peerName = chatKey.peerName,
              isActive = backStack.lastOrNull() == chatKey,
              appLanguage = appLanguage,
              onBack = { backStack.removeLastOrNull() },
              modifier = Modifier.fillMaxSize()
            )
          }
        },
    )
  }
}
