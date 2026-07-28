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
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.twopchat.ui.onboarding.OnboardingScreen
import com.example.twopchat.ui.main.MainScreen
import com.example.twopchat.ui.chat.ChatScreen
import com.example.twopchat.group.runtime.AndroidGroupUiController
import com.example.twopchat.group.runtime.GroupChatCoordinator
import com.example.twopchat.group.ui.CreateGroupScreen
import com.example.twopchat.group.ui.GroupChatScreen as P2PGroupChatScreen
import com.example.twopchat.group.ui.GroupInfoScreen
import com.example.twopchat.group.ui.PendingGroupInvitesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainNavigation(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    useAmoled: Boolean,
    onAmoledChanged: (Boolean) -> Unit,
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    onIconChanged: (String) -> Unit
) {
  val context = LocalContext.current
  val sharedPrefs = remember { P2PPreferences.prefs(context) }
  val coroutineScope = rememberCoroutineScope()
  var isOnboardingCompleted by remember { mutableStateOf(sharedPrefs.getBoolean("onboarding_completed", false)) }
  var accountDeletionInProgress by remember { mutableStateOf(false) }

  if (!isOnboardingCompleted) {
    OnboardingScreen(
      appLanguage = appLanguage,
      onComplete = {
        sharedPrefs.edit().putBoolean("onboarding_completed", true).apply()
        isOnboardingCompleted = true
        // Account deletion stops the service completely. Recreate it only
        // after onboarding persisted the new name and generated a new key.
        ContextCompat.startForegroundService(
          context,
          Intent(context, P2PRelayService::class.java).apply {
            action = P2PRelayService.ACTION_RESTART
          },
        )
      },
      modifier = Modifier.fillMaxSize()
    )
  } else {
    val backStack = rememberNavBackStack(Main)
    val groupController = remember(backStack) {
      AndroidGroupUiController(
        onBackNavigation = { backStack.removeLastOrNull() },
        onOpenGroupNavigation = { groupId -> backStack.add(GroupConversation(groupId)) },
        onOpenGroupInfoNavigation = { groupId -> backStack.add(GroupInfo(groupId)) },
      )
    }

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
              useAmoled = useAmoled,
              onAmoledChanged = onAmoledChanged,
              appLanguage = appLanguage,
              onLanguageChanged = onLanguageChanged,
              onIconChanged = onIconChanged,
              onDeleteAccount = {
                  if (!accountDeletionInProgress) {
                      accountDeletionInProgress = true
                      coroutineScope.launch {
                          val deleted = withContext(Dispatchers.IO) {
                              AccountLifecycle.deleteAccount(context)
                          }
                          accountDeletionInProgress = false
                          if (deleted) {
                              isOnboardingCompleted = false
                          } else {
                              Toast.makeText(
                                  context,
                                  "Account deletion failed: secure sessions are still active",
                                  Toast.LENGTH_LONG,
                              ).show()
                          }
                      }
                  }
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
          entry<CreateGroup> {
            val state by GroupChatCoordinator.createState.collectAsState()
            LaunchedEffect(Unit) { GroupChatCoordinator.refreshContacts() }
            CreateGroupScreen(
              state = state,
              controller = groupController,
              modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
          }
          entry<GroupInvites> {
            val state by GroupChatCoordinator.pendingInvites.collectAsState()
            PendingGroupInvitesScreen(
              state = state,
              controller = groupController,
              modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
          }
          entry<GroupConversation> { groupKey ->
            val state by GroupChatCoordinator.chatState(groupKey.groupId).collectAsState()
            P2PGroupChatScreen(
              state = state,
              controller = groupController,
              modifier = Modifier.fillMaxSize(),
            )
          }
          entry<GroupInfo> { groupKey ->
            val state by GroupChatCoordinator.infoState(groupKey.groupId).collectAsState()
            GroupInfoScreen(
              state = state,
              controller = groupController,
              modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
          }
        },
    )
  }
}
