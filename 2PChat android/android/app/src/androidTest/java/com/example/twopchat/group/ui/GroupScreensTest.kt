package com.example.twopchat.group.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class GroupScreensTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun memberReadOnlyState_hidesComposerActions() {
    composeTestRule.setContent {
      MaterialTheme {
        GroupChatScreen(
          state = GroupChatUiState(
            groupId = "group-1",
            title = "Research group",
            memberCount = 240,
            syncStatus = GroupSyncStatus.OFFLINE,
            composerEnabled = false,
            readOnlyReason = "Only administrators can send messages"
          ),
          controller = object : GroupUiController {}
        )
      }
    }

    composeTestRule.onNodeWithTag("read_only_composer").assertExists()
    composeTestRule.onNodeWithText("Only administrators can send messages").assertExists()
    composeTestRule.onNodeWithTag("group_send_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("group_sync_status", useUnmergedTree = true).assertExists()
  }

  @Test
  fun mediaRestrictedState_disablesVoiceAction() {
    composeTestRule.setContent {
      MaterialTheme {
        GroupChatScreen(
          state = GroupChatUiState(
            groupId = "group-media-restricted",
            title = "Text only",
            memberCount = 2,
            composerEnabled = true,
            textComposerEnabled = true,
            mediaComposerEnabled = false,
          ),
          controller = object : GroupUiController {}
        )
      }
    }

    composeTestRule.onNodeWithTag("group_send_button").assertIsNotEnabled()
  }

  @Test
  fun administratorCanToggleGroupPostingPolicy() {
    var submitted: Boolean? = null
    val controller = object : GroupUiController {
      override fun setAdminOnlyPosting(groupId: String, enabled: Boolean) {
        assertEquals("group-policy", groupId)
        submitted = enabled
      }
    }

    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "group-policy",
              title = "Announcements",
              memberCount = 3,
              adminOnlyPosting = false,
            ),
            currentUserRole = GroupRole.ADMIN,
            management = GroupManagementPermissions(canEditMetadata = true),
          ),
          controller = controller,
        )
      }
    }

    composeTestRule.onNodeWithTag("admin_only_posting_switch")
      .performScrollTo()
      .performClick()
    composeTestRule.runOnIdle { assertEquals(true, submitted) }
  }

  @Test
  fun mediaTab_composesOnlyVisibleRows() {
    val mediaMessages = List(90) { index ->
      GroupTimelineMessage(
        messageId = "media-$index",
        authorId = "member",
        authorName = "Member",
        text = "",
        timestampLabel = "",
        attachment = GroupAttachmentUi(
          attachmentId = "attachment-$index",
          fileName = "missing-$index.gif",
          mimeType = "image/gif",
          sizeLabel = "1 KB",
          availableBlocks = 1,
          totalBlocks = 1,
          localPath = "/missing/media-$index.gif",
        ),
      )
    }

    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "large-media-group",
              title = "Media",
              memberCount = 1,
            ),
            currentUserRole = GroupRole.MEMBER,
            timelineMessages = mediaMessages,
          ),
          controller = object : GroupUiController {},
        )
      }
    }

    composeTestRule.onNodeWithText("Медиа").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("group_media_media-0_attachment-0").assertExists()
    composeTestRule.onNodeWithTag("group_media_media-89_attachment-89").assertDoesNotExist()
  }

  @Test
  fun moderatorMessageControls_areShownOnlyWhenGranted() {
    composeTestRule.setContent {
      MaterialTheme {
        GroupChatScreen(
          state = GroupChatUiState(
            groupId = "group-2",
            title = "Community",
            memberCount = 10_000,
            messages = listOf(
              GroupTimelineMessage(
                messageId = "message-1",
                authorId = "member-1",
                authorName = "Alex",
                text = "Announcement draft",
                timestampLabel = "10:42",
                canReply = true,
                canReact = true,
                canPin = true,
                canDelete = true,
                canEdit = false
              )
            )
          ),
          controller = object : GroupUiController {}
        )
      }
    }

    composeTestRule.onNodeWithTag("pin_message-1").assertExists()
    composeTestRule.onNodeWithTag("delete_message-1").assertExists()
    composeTestRule.onNodeWithTag("reply_message-1").assertExists()
    composeTestRule.onNodeWithTag("edit_message-1").assertDoesNotExist()
  }

  @Test
  fun ownerRoleManagement_showsRoleAndTransferActions() {
    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "group-3",
              title = "Project",
              memberCount = 2
            ),
            currentUserRole = GroupRole.OWNER,
            management = GroupManagementPermissions(
              canManageRoles = true,
              canRestrictMembers = true,
              canRemoveMembers = true,
              canTransferOwnership = true,
              canViewAdminLog = true
            ),
            members = listOf(
              GroupMember(
                memberId = "owner",
                displayName = "Owner",
                role = GroupRole.OWNER,
                isCurrentUser = true
              ),
              GroupMember(
                memberId = "bob",
                displayName = "Bob",
                role = GroupRole.MEMBER,
                canChangeRole = true,
                canRestrict = true,
                canRemove = true,
                canTransferOwnership = true
              )
            )
          ),
          controller = object : GroupUiController {}
        )
      }
    }

    composeTestRule.onNodeWithTag("group_info_list")
      .performScrollToNode(hasTestTag("make_admin_bob"))
    composeTestRule.onNodeWithTag("make_admin_bob").assertExists()
    composeTestRule.onNodeWithTag("make_moderator_bob").assertExists()
    composeTestRule.onNodeWithTag("restrict_bob").assertExists()
    composeTestRule.onNodeWithTag("remove_bob").assertExists()
    composeTestRule.onNodeWithTag("transfer_bob").assertExists()
    composeTestRule.onNodeWithTag("make_admin_owner").assertDoesNotExist()
  }

  @Test
  fun restrictionDialogSubmitsSendLinksPermission() {
    var submitted: GroupMemberPermissions? = null
    val controller = object : GroupUiController {
      override fun setMemberRestrictions(
        groupId: String,
        memberId: String,
        permissions: GroupMemberPermissions
      ) {
        submitted = permissions
      }
    }
    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "links-group",
              title = "Links",
              memberCount = 2
            ),
            currentUserRole = GroupRole.MODERATOR,
            management = GroupManagementPermissions(canRestrictMembers = true),
            members = listOf(
              GroupMember(
                memberId = "bob",
                displayName = "Bob",
                permissions = GroupMemberPermissions(canSendLinks = true),
                canRestrict = true
              )
            )
          ),
          controller = controller
        )
      }
    }

    composeTestRule.onNodeWithTag("group_info_list")
      .performScrollToNode(hasTestTag("restrict_bob"))
    composeTestRule.onNodeWithTag("member_bob").performClick()
    composeTestRule.onNodeWithText("Ограничить права").performClick()
    composeTestRule.onNodeWithText("Отправка ссылок", useUnmergedTree = true)
      .performScrollTo()
      .performClick()
    composeTestRule.onNodeWithTag("apply_member_restrictions").performClick()

    composeTestRule.runOnIdle {
      assertFalse(checkNotNull(submitted).canSendLinks)
    }
  }

  @Test
  fun createGroup_allowsOwnerOnlyGroup() {
    var submittedTitle: String? = null
    var submittedContacts: Set<String> = emptySet()
    val controller = object : GroupUiController {
      override fun createGroup(title: String, description: String, contactIds: Set<String>) {
        submittedTitle = title
        submittedContacts = contactIds
      }
    }

    composeTestRule.setContent {
      MaterialTheme {
        CreateGroupScreen(
          state = CreateGroupUiState(
            knownContacts = listOf(
              GroupContactSummary("alice", "Alice", isOnline = true),
              GroupContactSummary("bob", "Bob")
            )
          ),
          controller = controller
        )
      }
    }

    composeTestRule.onNodeWithTag("create_group_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("group_title_input").performTextInput("Distributed systems")
    composeTestRule.onNodeWithTag("create_group_button").assertIsEnabled().performClick()

    composeTestRule.runOnIdle {
      assertEquals("Distributed systems", submittedTitle)
      assertEquals(emptySet<String>(), submittedContacts)
    }
  }

  @Test
  fun editMetadata_submitsTrimmedValuesToController() {
    var submittedGroupId: String? = null
    var submittedTitle: String? = null
    var submittedDescription: String? = null
    val controller = object : GroupUiController {
      override fun updateGroupInfo(groupId: String, title: String, description: String) {
        submittedGroupId = groupId
        submittedTitle = title
        submittedDescription = description
      }
    }

    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "editable-group",
              title = "Old title",
              description = "Existing description",
              memberCount = 1
            ),
            currentUserRole = GroupRole.OWNER,
            management = GroupManagementPermissions(canEditMetadata = true)
          ),
          controller = controller
        )
      }
    }

    composeTestRule.onNodeWithTag("edit_group_info").performClick()
    composeTestRule.onNodeWithTag("edit_group_title").performTextClearance()
    composeTestRule.onNodeWithTag("edit_group_title").performTextInput("  New title  ")
    composeTestRule.onNodeWithText("Сохранить").assertIsEnabled().performClick()

    composeTestRule.runOnIdle {
      assertEquals("editable-group", submittedGroupId)
      assertEquals("New title", submittedTitle)
      assertEquals("Existing description", submittedDescription)
    }
  }

  @Test
  fun inviteMembers_submitsOnlySelectedContacts() {
    var submittedGroupId: String? = null
    var submittedContacts: Set<String> = emptySet()
    val controller = object : GroupUiController {
      override fun inviteMembers(groupId: String, contactIds: Set<String>) {
        submittedGroupId = groupId
        submittedContacts = contactIds
      }
    }

    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "invite-group",
              title = "Community",
              memberCount = 3
            ),
            currentUserRole = GroupRole.ADMIN,
            inviteCandidates = listOf(
              GroupContactSummary("alice", "Alice"),
              GroupContactSummary("bob", "Bob")
            ),
            management = GroupManagementPermissions(canInviteMembers = true)
          ),
          controller = controller
        )
      }
    }

    composeTestRule.onNodeWithTag("group_info_list")
      .performScrollToNode(hasTestTag("invite_group_members"))
    composeTestRule.onNodeWithTag("invite_group_members").performClick()
    composeTestRule.onAllNodes(isToggleable()).onFirst().performClick()
    composeTestRule.onNodeWithText("Пригласить").assertIsEnabled().performClick()

    composeTestRule.runOnIdle {
      assertEquals("invite-group", submittedGroupId)
      assertEquals(setOf("alice"), submittedContacts)
    }
  }

  @Test
  fun banMember_requiresConfirmationAndCallsController() {
    var bannedGroupId: String? = null
    var bannedMemberId: String? = null
    val controller = object : GroupUiController {
      override fun banMember(groupId: String, memberId: String) {
        bannedGroupId = groupId
        bannedMemberId = memberId
      }
    }

    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "moderated-group",
              title = "Moderated",
              memberCount = 2
            ),
            currentUserRole = GroupRole.MODERATOR,
            members = listOf(
              GroupMember(
                memberId = "bob",
                displayName = "Bob",
                canBan = true
              )
            ),
            management = GroupManagementPermissions(canBanMembers = true)
          ),
          controller = controller
        )
      }
    }

    composeTestRule.onNodeWithTag("group_info_list")
      .performScrollToNode(hasTestTag("ban_bob"))
    composeTestRule.onNodeWithTag("member_bob").performClick()
    composeTestRule.onNodeWithText("Заблокировать").performClick()
    composeTestRule.onNodeWithTag("confirm_ban_member", useUnmergedTree = true).performClick()

    composeTestRule.runOnIdle {
      assertEquals("moderated-group", bannedGroupId)
      assertEquals("bob", bannedMemberId)
    }
  }

  @Test
  fun removePermission_doesNotImplicitlyExposeBan() {
    composeTestRule.setContent {
      MaterialTheme {
        GroupInfoScreen(
          state = GroupInfoUiState(
            metadata = GroupMetadata(
              groupId = "remove-only-group",
              title = "Remove only",
              memberCount = 2
            ),
            currentUserRole = GroupRole.ADMIN,
            members = listOf(
              GroupMember(
                memberId = "bob",
                displayName = "Bob",
                canRemove = true,
                canBan = false
              )
            ),
            management = GroupManagementPermissions(
              canRemoveMembers = true,
              canBanMembers = false
            )
          ),
          controller = object : GroupUiController {}
        )
      }
    }

    composeTestRule.onNodeWithTag("remove_bob").assertExists()
    composeTestRule.onNodeWithTag("ban_bob").assertDoesNotExist()
  }

  @Test
  fun readDeliveryStatus_isRenderedForMessage() {
    composeTestRule.setContent {
      MaterialTheme {
        GroupChatScreen(
          state = GroupChatUiState(
            groupId = "receipts-group",
            title = "Receipts",
            memberCount = 2,
            messages = listOf(
              GroupTimelineMessage(
                messageId = "read-message",
                authorId = "me",
                authorName = "Me",
                text = "Seen by the recipient",
                timestampLabel = "12:30",
                isMine = true,
                deliveryStatus = GroupDeliveryStatus.READ
              )
            )
          ),
          controller = object : GroupUiController {}
        )
      }
    }

    composeTestRule.onNodeWithTag("delivery_read-message", useUnmergedTree = true)
      .assertTextEquals("✔✔")
  }

  @Test
  fun replicatedAttachment_showsProgressAndRequestsDownload() {
    var requestedMessage: String? = null
    val controller = object : GroupUiController {
      override fun downloadAttachment(groupId: String, messageId: String) {
        requestedMessage = "$groupId:$messageId"
      }
    }
    composeTestRule.setContent {
      MaterialTheme {
        GroupChatScreen(
          state = GroupChatUiState(
            groupId = "group-files",
            title = "Files",
            memberCount = 3,
            messages = listOf(
              GroupTimelineMessage(
                messageId = "media-1",
                authorId = "alice",
                authorName = "Alice",
                text = "report.pdf",
                timestampLabel = "12:00",
                attachment = GroupAttachmentUi(
                  attachmentId = "attachment-1",
                  fileName = "report.pdf",
                  mimeType = "application/pdf",
                  sizeLabel = "2.0 MiB",
                  availableBlocks = 2,
                  totalBlocks = 4,
                ),
              ),
            ),
          ),
          controller = controller,
        )
      }
    }

    composeTestRule.onNodeWithTag("attachment_media-1", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText("2.0 MiB · 2/4 бл.").assertExists()
    composeTestRule.onNodeWithTag("download_media-1").performClick()
    composeTestRule.runOnIdle {
      assertEquals("group-files:media-1", requestedMessage)
    }
  }
}
